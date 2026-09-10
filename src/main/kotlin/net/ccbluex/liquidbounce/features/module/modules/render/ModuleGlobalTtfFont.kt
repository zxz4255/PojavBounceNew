/*
 * ModuleGlobalTtfFont —— 磁盘 TTF 覆盖全游戏文字
 *
 * 原理：写出 folder 资源包并启用，覆盖 minecraft:default / uniform。
 * TTF 放在: .minecraft/LiquidBounce/fonts/ 下的 .ttf 文件
 *
 * 注意：ttf 的 file 字段是 assets/<ns>/font/ 下的路径，
 * 正确写法是 "minecraft:xxx.ttf"（不要写成 minecraft:font/xxx.ttf）。
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

    private val fontsFolderName by text("Fonts Folder", "LiquidBounce/fonts")
    private val fontFileName by text("Font File", "custom.ttf")
    private val autoPickFirst by boolean("Auto Pick First TTF", true)
    private val packName by text("Pack Name", "LiquidBounce-TTF")

    private val fontSize by float("Size", 11f, 6f..24f)
    private val oversample by float("Oversample", 4f, 1f..16f)
    private val shiftX by float("Shift X", 0f, -4f..4f)
    private val shiftY by float("Shift Y", 0.5f, -4f..4f)
    private val skipChars by text("Skip Chars", "")

    private val alsoUniform by boolean("Override Uniform", true)
    private val alsoAlt by boolean("Override Alt", false)
    private val applyOnEnable by boolean("Apply On Enable", true)
    private val reapply by boolean("Reapply Now", false)
    private val removeOnDisable by boolean("Remove Pack On Disable", false)
    private val chatNotify by boolean("Chat Notify", true)

    private var lastReapply = false
    private var applied = false
    private var pendingApply = false
    private var pendingTicks = 0
    private var lastPackPath: String = ""

    private fun runDir(): File {
        return try {
            val m = mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getGameDirectory" || it.name == "getRunDirectory")
            }
            (m?.invoke(mc) as? File)
                ?: (runCatching { mc.javaClass.getField("gameDirectory").get(mc) as File }.getOrNull())
                ?: File(".")
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
            dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("ttf", true) }?.let { return it }
        }
        // 也接受 otf（部分版本仍可读；1.20.5+ 官方更偏向 ttf）
        if (named.isFile && named.extension.equals("otf", true)) return named
        if (autoPickFirst) {
            dir.listFiles()?.firstOrNull {
                it.isFile && (it.extension.equals("ttf", true) || it.extension.equals("otf", true))
            }?.let { return it }
        }
        return null
    }

    private fun notify(msg: String) {
        if (chatNotify) {
            try {
                chat(msg)
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildFontJson(ttfResourceId: String): String {
        val skip = skipChars.replace("\\", "\\\\").replace("\"", "\\\"")
        // 官方 wiki：file 指向 assets/<namespace>/font/ 内的文件
        // 例：文件在 assets/minecraft/font/foo.ttf → "minecraft:foo.ttf"
        return """
        {
          "providers": [
            {
              "type": "ttf",
              "file": "$ttfResourceId",
              "shift": [$shiftX, $shiftY],
              "size": $fontSize,
              "oversample": $oversample,
              "skip": "$skip"
            }
          ]
        }
        """.trimIndent() + "\n"
    }

    private fun buildPackMcmeta(): String = """
        {
          "pack": {
            "pack_format": 64,
            "description": "LiquidBounce Global TTF"
          }
        }
    """.trimIndent() + "\n"

    private fun writeResourcePack(ttf: File): Boolean {
        return try {
            val root = packRoot()
            if (root.exists()) {
                root.deleteRecursively()
            }
            val fontDir = File(root, "assets/minecraft/font").apply { mkdirs() }

            val safeBase = ttf.nameWithoutExtension
                .lowercase()
                .replace(Regex("[^a-z0-9_\\-]"), "_")
                .ifBlank { "custom" }
            val safeName = "$safeBase.ttf"
            val destTtf = File(fontDir, safeName)
            Files.copy(ttf.toPath(), destTtf.toPath(), StandardCopyOption.REPLACE_EXISTING)

            // 关键：minecraft:foo.ttf  →  assets/minecraft/font/foo.ttf
            val resourceId = "minecraft:$safeName"
            val json = buildFontJson(resourceId)
            File(fontDir, "default.json").writeText(json)
            if (alsoUniform) File(fontDir, "uniform.json").writeText(json)
            if (alsoAlt) File(fontDir, "alt.json").writeText(json)
            File(root, "pack.mcmeta").writeText(buildPackMcmeta())

            lastPackPath = root.absolutePath
            if (!destTtf.isFile || destTtf.length() < 100L) {
                notify("§cTTF 复制失败或文件过小")
                return false
            }
            notify("§a资源包已写出: §f${root.absolutePath}")
            notify("§7TTF: §f$safeName §7(${destTtf.length()} bytes)  id=§f$resourceId")
            true
        } catch (e: Exception) {
            notify("§c写资源包失败: ${e.message}")
            false
        }
    }

    /** 通过 ResourcePackRepository 选中 folder 包 */
    private fun selectPackViaRepository(): Boolean {
        return try {
            val repo = runCatching {
                mc.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (
                        it.name == "getResourcePackRepository" ||
                            it.name == "getPackRepository" ||
                            it.name.equals("resourcePackRepository", true)
                    )
                }?.invoke(mc)
                    ?: mc.javaClass.fields.firstOrNull {
                        it.name.contains("resourcePack", true) || it.name.contains("packRepository", true)
                    }?.get(mc)
            }.getOrNull() ?: return false

            // reload available packs
            runCatching {
                repo.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals("reload", true)
                }?.invoke(repo)
            }

            // 收集可用 pack id
            val available: List<Any> = runCatching {
                val m = repo.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (
                        it.name == "getAvailablePacks" || it.name == "getAvailable" ||
                            it.name.contains("Available", true)
                    )
                }
                when (val v = m?.invoke(repo)) {
                    is Collection<*> -> v.filterNotNull()
                    is Array<*> -> v.filterNotNull()
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())

            // 找包含 packName 的 id
            val match = available.firstOrNull { pack ->
                val id = runCatching {
                    pack.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "getId" || it.name == "id" || it.name == "getName")
                    }?.invoke(pack)?.toString()
                }.getOrNull() ?: pack.toString()
                id.contains(packName, ignoreCase = true)
            }

            val packIdStr = if (match != null) {
                runCatching {
                    match.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "getId" || it.name == "id")
                    }?.invoke(match)?.toString()
                }.getOrNull() ?: "file/$packName"
            } else {
                "file/$packName"
            }

            // setSelected / 修改 selected
            var selectedOk = false
            runCatching {
                val selectedMethod = repo.javaClass.methods.firstOrNull {
                    it.name.equals("setSelected", true) || it.name.equals("setSelectedPacks", true)
                }
                if (selectedMethod != null && selectedMethod.parameterCount == 1) {
                    // 可能接受 Collection<String> 或 Collection<Pack>
                    val argType = selectedMethod.parameterTypes[0]
                    if (Collection::class.java.isAssignableFrom(argType) || Iterable::class.java.isAssignableFrom(argType)) {
                        // 尝试字符串 id 列表
                        val current = runCatching {
                            repo.javaClass.methods.firstOrNull {
                                it.parameterCount == 0 && it.name.contains("Selected", true)
                            }?.invoke(repo)
                        }.getOrNull()
                        val ids = mutableListOf<String>()
                        when (current) {
                            is Collection<*> -> current.mapNotNullTo(ids) { it?.toString() }
                            else -> {}
                        }
                        ids.removeAll { it.contains(packName, ignoreCase = true) }
                        ids.add(packIdStr)
                        try {
                            selectedMethod.invoke(repo, ids)
                            selectedOk = true
                        } catch (_: Throwable) {
                            // 尝试传 Pack 对象列表
                            if (match != null) {
                                val packList = mutableListOf<Any>()
                                if (current is Collection<*>) {
                                    current.filterNotNull().forEach { packList.add(it) }
                                    packList.removeAll { it.toString().contains(packName, ignoreCase = true) }
                                }
                                packList.add(match)
                                selectedMethod.invoke(repo, packList)
                                selectedOk = true
                            }
                        }
                    }
                }
            }

            // options.resourcePacks 同步
            runCatching {
                val options = mc.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "getOptions" || it.name == "options")
                }?.invoke(mc) ?: mc.javaClass.getField("options").get(mc)

                val field = options.javaClass.declaredFields.firstOrNull {
                    it.name.equals("resourcePacks", true)
                }
                if (field != null) {
                    field.isAccessible = true
                    val cur = field.get(options)
                    if (cur is MutableList<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val ml = cur as MutableList<Any>
                        ml.removeAll { it.toString().contains(packName, ignoreCase = true) }
                        ml.add(packIdStr)
                    }
                }
                // updateResourcePacks(repo)
                options.javaClass.methods.firstOrNull {
                    it.parameterCount == 1 && it.name.contains("updateResourcePack", true)
                }?.invoke(options, repo)

                options.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "save" || it.name == "saveOptions")
                }?.invoke(options)
            }

            selectedOk
        } catch (e: Exception) {
            notify("§eRepository 启用异常: ${e.message}")
            false
        }
    }

    private fun reloadResources(): Boolean {
        return try {
            val m = mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (
                    it.name == "reloadResourcePacks" ||
                        it.name == "reloadResources" ||
                        (it.name.contains("reload", true) && it.name.contains("Resource", true))
                )
            }
            if (m != null) {
                m.invoke(mc)
                true
            } else {
                // 尝试带 executor 的重载
                val m2 = mc.javaClass.methods.firstOrNull {
                    it.name.contains("reloadResource", true)
                }
                if (m2 != null) {
                    when (m2.parameterCount) {
                        0 -> m2.invoke(mc)
                        else -> return false
                    }
                    true
                } else false
            }
        } catch (e: Exception) {
            notify("§e重载失败: ${e.message}")
            false
        }
    }

    private fun enablePackAndReload() {
        val viaRepo = selectPackViaRepository()
        val reloaded = reloadResources()
        applied = true
        when {
            viaRepo && reloaded -> notify("§a已启用并重载资源包")
            reloaded -> notify("§a已重载资源。若无变化请到 §f资源包 §a里勾选 §f$packName")
            else -> notify(
                "§e请手动: 选项 → 资源包 → 启用 §f$packName §e→ 完成 → 或按 §fF3+T\n" +
                    "§7路径: $lastPackPath"
            )
        }
    }

    private fun disablePack() {
        try {
            val options = mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getOptions" || it.name == "options")
            }?.invoke(mc) ?: mc.javaClass.getField("options").get(mc)

            val field = options.javaClass.declaredFields.firstOrNull {
                it.name.equals("resourcePacks", true)
            }
            if (field != null) {
                field.isAccessible = true
                val cur = field.get(options)
                if (cur is MutableList<*>) {
                    cur.removeAll { it.toString().contains(packName, ignoreCase = true) }
                }
            }
            runCatching {
                options.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "save" || it.name == "saveOptions")
                }?.invoke(options)
            }
            reloadResources()
            notify("已尝试移除资源包 $packName")
        } catch (_: Throwable) {
        }
        applied = false
    }

    fun applyFont(): Boolean {
        val ttf = resolveTtf()
        if (ttf == null) {
            notify("§c未找到 TTF。放到: §e${fontsDir().absolutePath}")
            fontsDir().mkdirs()
            return false
        }
        if (!ttf.extension.equals("ttf", true) && !ttf.extension.equals("otf", true)) {
            notify("§c需要 .ttf 文件（1.20.5+ 不建议 otf）")
            return false
        }
        notify("§f字体文件: §a${ttf.absolutePath}")
        if (!writeResourcePack(ttf)) return false
        enablePackAndReload()
        return true
    }

    override fun onEnabled() {
        lastReapply = reapply
        if (applyOnEnable) {
            pendingApply = true
            pendingTicks = 0
        }
    }

    override fun onDisabled() {
        if (removeOnDisable && applied) disablePack()
        pendingApply = false
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pendingApply) {
            pendingTicks++
            if (pendingTicks >= 10) {
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
