// ModuleGlobalTtfFont - 磁盘 TTF 运行时注入（不重载资源包）
// 依赖: utils/ttf/ForcedTtf.kt
// Mixin 可选: 仅在原版自己重载字体后补注一次
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.ttf.ForcedTtf
import java.io.File

object ModuleGlobalTtfFont : ClientModule(
    "GlobalTtfFont",
    ModuleCategories.RENDER,
    aliases = listOf("TTF", "CustomFont", "全局字体", "TrueTypeFont", "ForceTTF"),
) {

    private val fontsFolderName by text("Fonts Folder", "LiquidBounce/fonts")
    private val fontFileName by text("Font File", "custom.ttf")
    private val autoPickFirst by boolean("Auto Pick First TTF", true)

    private val fontSize by float("Size", 11f, 6f..24f)
    private val oversample by float("Oversample", 4f, 1f..16f)
    private val shiftX by float("Shift X", 0f, -4f..4f)
    private val shiftY by float("Shift Y", 0.5f, -4f..4f)
    private val skipChars by text("Skip Chars", "")
    private val alsoUniform by boolean("Also Uniform", true)

    private val applyOnEnable by boolean("Apply On Enable", true)
    private val reapply by boolean("Reapply Now", false)
    private val chatNotify by boolean("Chat Notify", true)

    private var lastReapply = false
    private var pending = false
    private var pendingTicks = 0
    private var retryLeft = 0

    private fun runDir(): File = try {
        val m = mc.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && (it.name == "getGameDirectory" || it.name == "getRunDirectory")
        }
        (m?.invoke(mc) as? File)
            ?: runCatching { mc.javaClass.getField("gameDirectory").get(mc) as File }.getOrNull()
            ?: File(".")
    } catch (_: Throwable) {
        File(".")
    }

    private fun fontsDir(): File = File(runDir(), fontsFolderName).apply { mkdirs() }

    private fun resolveTtf(): File? {
        val dir = fontsDir()
        val named = File(dir, fontFileName)
        if (named.isFile && named.extension.equals("ttf", true)) return named
        if (autoPickFirst) {
            dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("ttf", true) }?.let { return it }
        }
        return null
    }

    private fun notify(msg: String) {
        if (chatNotify) runCatching { chat(msg) }
    }

    private fun pushHolder(ttf: File) {
        ForcedTtf.enabled = true
        ForcedTtf.ttfFile = ttf
        ForcedTtf.size = fontSize
        ForcedTtf.oversample = oversample
        ForcedTtf.shiftX = shiftX
        ForcedTtf.shiftY = shiftY
        ForcedTtf.skip = skipChars
        ForcedTtf.alsoUniform = alsoUniform
    }

    /** 直接注入，不调用 F3+T / reloadResourcePacks */
    fun applyFont(): Boolean {
        val ttf = resolveTtf()
        if (ttf == null) {
            notify("§c未找到 TTF，放到: §e${fontsDir().absolutePath}")
            return false
        }
        pushHolder(ttf)
        notify("§f字体: §a${ttf.name} §7(${ttf.length()} bytes)")

        val n = try {
            ForcedTtf.injectIntoMinecraft(mc)
        } catch (t: Throwable) {
            t.printStackTrace()
            notify("§c注入异常: ${t.message}")
            return false
        }

        if (n > 0) {
            notify("§a已注入 §f$n §a个 FontSet（无资源重载）")
            retryLeft = 0
            return true
        }

        // FontManager 可能尚未就绪，稍后重试
        retryLeft = 40
        notify("§e暂未注入成功，将自动重试…")
        return false
    }

    override fun onEnabled() {
        lastReapply = reapply
        if (applyOnEnable) {
            pending = true
            pendingTicks = 0
        }
    }

    override fun onDisabled() {
        ForcedTtf.clear()
        pending = false
        retryLeft = 0
        notify("§7已关闭强制 TTF（新字形不再使用自定义字体）")
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pending) {
            pendingTicks++
            if (pendingTicks >= 5) {
                pending = false
                applyFont()
            }
        }
        if (retryLeft > 0 && ForcedTtf.enabled) {
            retryLeft--
            if (retryLeft % 10 == 0) {
                val n = runCatching { ForcedTtf.injectIntoMinecraft(mc) }.getOrDefault(0)
                if (n > 0) {
                    notify("§a重试成功，注入 §f$n §a个 FontSet")
                    retryLeft = 0
                }
            }
        }
        if (reapply && !lastReapply) applyFont()
        lastReapply = reapply
    }
}
