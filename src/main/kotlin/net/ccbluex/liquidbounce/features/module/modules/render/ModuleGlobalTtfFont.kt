/*
 * ModuleGlobalTtfFont —— 用磁盘 TTF 覆盖全游戏文字（聊天/物品栏/菜单/告示牌等）
 *
 * 原理：原版字体栈读 assets/minecraft/font/default.json。
 * 本模块在 .minecraft/resourcepacks/LiquidBounce-TTF 生成资源包：
 *   - assets/minecraft/font/default.json  （type: ttf）
 *   - assets/minecraft/font/uniform.json
 *   - assets/minecraft/font 下的 ttf 文件
 * 然后启用该资源包并重载，实现整个游戏文字都走 TTF。
 *
 * 字体放置：.minecraft/LiquidBounce/fonts/ 目录中的 .ttf 文件
 * 选定文件名在模块选项 Font File 中填写（或用 Auto Pick 选第一份）。
 *
 * LiquidBounce Nextgen / Minecraft 26.x · 不依赖 LB 自带 FontManager（那只服务 HUD）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ModuleGlobalTtfFont : ClientModule(
    "GlobalTtfFont",
    ModuleCategories.RENDER,
    aliases = listOf("TTF", "CustomFont", "全局字体", "TrueTypeFont"),
) {

    // ---------- 文件 ----------
    private val fontsFolderName by text("Fonts Folder", "LiquidBounce/fonts")
    private val fontFileName by text("Font File", "custom.ttf")
    private val autoPickFirst by boolean("Auto Pick First TTF", true)
    private val packName by text("Pack Name", "LiquidBounce-TTF")

    // ---------- TTF provider 参数（原版 TrueTypeGlyphProvider）----------
    private val fontSize by float("Size", 11f, 6f..24f)
    private val oversample by float("Oversample", 4f, 1f..16f)
    private val shiftX by float("Shift X", 0f, -4f..4f)
    private val shiftY by float("Shift Y", 0.5f, -4f..4f)
    private val skipChars by text("Skip Chars", "")

    // ---------- 行为 ----------
    private val alsoUniform by boolean("Override Uniform", true)
    private val applyOnEnable by boolean("Apply On Enable", true)
    private val reapply by boolean("Reapply Now", false)
    private val removeOnDisable by boolean("Remove Pack On Disable", false)
    private val chatNotify by boolean("Chat Notify", true)

    private var lastReapply = false
    private var applied = false

    private fun runDir(): File = try {
        val f = mc.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && (it.name == "getGameDirectory" || it.name == "getRunDirectory")
        }?.invoke(mc) as? File
        f ?: File((mc.javaClass.getField("gameDirectory").get(mc) as? File)?.path ?: ".")
    } catch (_: Throwable) {
        try {
            (mc.javaClass.getField("gameDirectory").get(mc) as? File) ?: File(".")
        } catch (_: Throwable) {
            File(".")
        }
    }

    private fun fontsDir(): File = File(runDir(), fontsFolderName).apply { mkdirs() }

    private fun resourcePacksDir(): File = File(runDir(), "resourcepacks").apply { mkdirs() }

    private fun packRoot(): File = File(resourcePacksDir(), packName)

    private fun resolveTtf(): File? {
        val dir = fontsDir()
        val named = File(dir, fontFileName)
        if (named.isFile && named.extension.equals("ttf", true)) return named
        if (autoPickFirst) {
            val first = dir.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("ttf", true)
            }
            if (first != null) return first
        }
        return if (named.isFile) named else null
    }

    private fun notify(msg: String) {
        if (chatNotify) chat("§7[GlobalTtf] §f$msg")
    }

    /** 原版 bitmap/ttf 字体定义 */
    private fun buildFontJson(ttfResourcePath: String): String {
        // ttf provider：file 指向 assets 内路径（minecraft:font/xxx.ttf）
        val skip = skipChars.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{\n")
            append("  \"providers\": [\n")
            append("    {\n")
            append("      \"type\": \"ttf\",\n")
            append("      \"file\": \"").append(ttfResourcePath).append("\",\n")
            append("      \"shift\": [").append(shiftX).append(", ").append(shiftY).append("],\n")
            append("      \"size\": ").append(fontSize).append(",\n")
            append("      \"oversample\": ").append(oversample).append(",\n")
            append("      \"skip\": \"").append(skip).append("\"\n")
            append("    }\n")
            append("  ]\n")
            append("}\n")
        }
    }

    private fun buildPackMcmeta(): String = """
        {
          "pack": {
            "pack_format": 64,
            "description": "LiquidBounce Global TTF Font"
          }
        }
    """.trimIndent() + "\n"

    /**
     * 写出资源包：
     * resourcepacks/<pack>/
     *   pack.mcmeta
     *   assets/minecraft/font/default.json
     *   assets/minecraft/font/uniform.json   (可选)
     *   assets/minecraft/font/<name>.ttf
     */
    private fun writeResourcePack(ttf: File): Boolean {
        return try {
            val root = packRoot()
            if (root.exists()) root.deleteRecursively()
            val fontDir = File(root, "assets/minecraft/font").apply { mkdirs() }

            // 复制 TTF（资源名用安全文件名）
            val safeName = ttf.nameWithoutExtension
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .ifBlank { "custom" } + ".ttf"
            val destTtf = File(fontDir, safeName)
            Files.copy(ttf.toPath(), destTtf.toPath(), StandardCopyOption.REPLACE_EXISTING)

            val rl = "minecraft:font/$safeName"
            val json = buildFontJson(rl)
            File(fontDir, "default.json").writeText(json)
            if (alsoUniform) {
                File(fontDir, "uniform.json").writeText(json)
            }
            File(root, "pack.mcmeta").writeText(buildPackMcmeta())
            true
        } catch (e: Exception) {
            notify("§c写资源包失败: ${e.message}")
            false
        }
    }

    /** 启用资源包并重载（反射兼容不同映射） */
    private fun enablePackAndReload() {
        val id = "file/$packName"
        try {
            val options = mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getOptions" || it.name == "options")
            }?.invoke(mc) ?: mc.javaClass.getField("options").get(mc)

            // options.resourcePacks: List<String>
            val packs: MutableList<String> = runCatching {
                val f = options.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (
                        it.name == "getResourcePacks" || it.name.equals("resourcePacks", true) ||
                            it.name == "getResourcePackRepository"
                    )
                }
                // 优先字段 resourcePacks
                val field = options.javaClass.declaredFields.firstOrNull {
                    it.name.equals("resourcePacks", true) || it.name == "resourcePacks"
                }
                if (field != null) {
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (field.get(options) as? MutableList<String>)
                        ?: (field.get(options) as? List<*>)?.map { it.toString() }?.toMutableList()
                } else null
            }.getOrNull() ?: run {
                // 再试 getResourcePacks
                val m = options.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.lowercase().contains("resourcepack") &&
                        !it.name.lowercase().contains("repo")
                }
                @Suppress("UNCHECKED_CAST")
                (m?.invoke(options) as? MutableList<String>)
                    ?: (m?.invoke(options) as? List<*>)?.map { x -> x.toString() }?.toMutableList()
            } ?: mutableListOf()

            // 确保本包在列表中（放最后优先）
            packs.removeAll { it.contains(packName) }
            packs.add(id)

            // 写回
            runCatching {
                val field = options.javaClass.declaredFields.firstOrNull {
                    it.name.equals("resourcePacks", true)
                }
                if (field != null) {
                    field.isAccessible = true
                    val cur = field.get(options)
                    when (cur) {
                        is MutableList<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val ml = cur as MutableList<Any>
                            ml.clear()
                            packs.forEach { ml.add(it) }
                        }
                        else -> field.set(options, packs)
                    }
                }
            }

            runCatching {
                options.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "save" || it.name == "saveOptions")
                }?.invoke(options)
            }

            // 重载资源
            val reloaded = runCatching {
                val m = mc.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (
                        it.name == "reloadResourcePacks" ||
                            it.name == "reloadResources" ||
                            it.name.contains("reload", true) && it.name.contains("Resource", true)
                    )
                }
                m?.invoke(mc)
                true
            }.getOrDefault(false)

            if (reloaded) {
                notify("§a已应用 TTF 资源包 §f$id")
                applied = true
            } else {
                notify("§e资源包已写入，请手动选资源包并 §fF3+T §e重载: §f$packName")
                applied = true
            }
        } catch (e: Exception) {
            notify("§e自动启用失败 (${e.message})，请在资源包菜单启用 §f$packName §e后按 F3+T")
            applied = true
        }
    }

    private fun disablePack() {
        try {
            val options = mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getOptions" || it.name == "options")
            }?.invoke(mc) ?: mc.javaClass.getField("options").get(mc)

            val field = options.javaClass.declaredFields.firstOrNull {
                it.name.equals("resourcePacks", true)
            } ?: return
            field.isAccessible = true
            val cur = field.get(options)
            if (cur is MutableList<*>) {
                cur.removeAll { it.toString().contains(packName) }
            }
            runCatching {
                options.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "save" || it.name == "saveOptions")
                }?.invoke(options)
            }
            runCatching {
                mc.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.contains("reload", true) &&
                        it.name.contains("Resource", true)
                }?.invoke(mc)
            }
            notify("已移除资源包 $packName")
        } catch (_: Throwable) {
        }
        applied = false
    }

    fun applyFont(): Boolean {
        val ttf = resolveTtf()
        if (ttf == null) {
            notify("§c未找到 TTF。请放到 §e${fontsDir().absolutePath}")
            return false
        }
        notify("使用字体: §a${ttf.name}")
        if (!writeResourcePack(ttf)) return false
        enablePackAndReload()
        return true
    }

    override fun onEnabled() {
        lastReapply = reapply
        if (applyOnEnable) {
            // 延后一拍，等客户端就绪
            pendingApply = true
        }
    }

    override fun onDisabled() {
        if (removeOnDisable && applied) {
            disablePack()
        }
        pendingApply = false
    }

    private var pendingApply = false
    private var pendingTicks = 0

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pendingApply) {
            pendingTicks++
            if (pendingTicks >= 5) {
                pendingApply = false
                pendingTicks = 0
                applyFont()
            }
        }
        if (reapply && !lastReapply) {
            applyFont()
        }
        lastReapply = reapply
    }
}
