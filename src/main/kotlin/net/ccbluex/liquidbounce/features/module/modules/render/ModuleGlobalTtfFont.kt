/*
 * ModuleGlobalTtfFont —— 磁盘 TTF 强制注入（不走资源包）
 *
 * 依赖:
 *   - ForcedTtfHolder.java
 *   - TtfDiskProviderFactory.java
 *   - MixinFontManagerForceTtf.java  (mixins.json 注册 minecraft.client.MixinFontManagerForceTtf)
 *
 * 字体: .minecraft/LiquidBounce/fonts/*.ttf
 * 开启后写 Holder → 触发资源/字体重载 → Mixin 在 FontManager 加载完后插入 GlyphProvider
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.ttf.ForcedTtfHolder
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
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
        ForcedTtfHolder.enabled = true
        ForcedTtfHolder.ttfFile = ttf
        ForcedTtfHolder.size = fontSize
        ForcedTtfHolder.oversample = oversample
        ForcedTtfHolder.shiftX = shiftX
        ForcedTtfHolder.shiftY = shiftY
        ForcedTtfHolder.skip = skipChars
        ForcedTtfHolder.alsoUniform = alsoUniform
    }

    private fun clearHolder() {
        ForcedTtfHolder.enabled = false
        ForcedTtfHolder.ttfFile = null
    }

    /** 触发客户端资源重载 → FontManager 重建 → Mixin 注入 */
    private fun requestFontReload() {
        runCatching {
            val m = mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (
                    it.name == "reloadResourcePacks" ||
                        it.name == "reloadResources" ||
                        (it.name.contains("reload", true) && it.name.contains("Resource", true))
                )
            }
            m?.invoke(mc)
            notify("§a已请求重载字体（磁盘注入，无资源包）")
        }.onFailure {
            notify("§e请按 §fF3+T §e重载资源以应用 TTF")
        }
    }

    fun applyFont(): Boolean {
        val ttf = resolveTtf()
        if (ttf == null) {
            notify("§c未找到 TTF，放到: §e${fontsDir().absolutePath}")
            return false
        }
        pushHolder(ttf)
        notify("§f注入字体: §a${ttf.absolutePath}")
        requestFontReload()
        return true
    }

    override fun onEnabled() {
        lastReapply = reapply
        if (applyOnEnable) {
            pending = true
            pendingTicks = 0
        }
    }

    override fun onDisabled() {
        clearHolder()
        pending = false
        // 重载以恢复原版字体
        requestFontReload()
        notify("§7已关闭强制 TTF，重载后恢复原版字体")
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pending) {
            pendingTicks++
            if (pendingTicks >= 10) {
                pending = false
                applyFont()
            }
        }
        if (reapply && !lastReapply) applyFont()
        lastReapply = reapply
    }
}
