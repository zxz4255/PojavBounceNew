// 磁盘 TTF：状态 + 构造 TrueTypeGlyphProvider + 注入到已有 FontManager（不重载资源）
// 路径: src/main/kotlin/net/ccbluex/liquidbounce/utils/ttf/ForcedTtf.kt
package net.ccbluex.liquidbounce.utils.ttf

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FreeType
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

object ForcedTtf {
    @JvmField @Volatile
    var enabled: Boolean = false

    @JvmField @Volatile
    var ttfFile: File? = null

    @JvmField @Volatile
    var size: Float = 11f

    @JvmField @Volatile
    var oversample: Float = 4f

    @JvmField @Volatile
    var shiftX: Float = 0f

    @JvmField @Volatile
    var shiftY: Float = 0.5f

    @JvmField @Volatile
    var skip: String = ""

    @JvmField @Volatile
    var alsoUniform: Boolean = true

    /** 当前已注入的 provider，关闭时尝试从列表移除 */
    @JvmField @Volatile
    var lastProvider: Any? = null

    @JvmStatic
    fun clear() {
        enabled = false
        ttfFile = null
        lastProvider = null
    }

    @JvmStatic
    fun createProviderFromHolder(): Any? {
        if (!enabled) return null
        val file = ttfFile ?: return null
        return createProvider(file, size, oversample, shiftX, shiftY, skip)
    }

    @JvmStatic
    fun createProvider(
        ttf: File?,
        size: Float,
        oversample: Float,
        shiftX: Float,
        shiftY: Float,
        skip: String?,
    ): Any? {
        if (ttf == null || !ttf.isFile) return null
        var fontMemory: ByteBuffer? = null
        try {
            val bytes = Files.readAllBytes(ttf.toPath())
            if (bytes.size < 64) return null

            fontMemory = MemoryUtil.memAlloc(bytes.size)
            fontMemory!!.put(bytes)
            fontMemory.flip()

            val face = createFreeTypeFace(fontMemory) ?: run {
                MemoryUtil.memFree(fontMemory)
                return null
            }

            val providerClz = Class.forName("com.mojang.blaze3d.font.TrueTypeGlyphProvider")
            val skipStr = skip ?: ""

            for (c in providerClz.declaredConstructors) {
                val p = c.parameterTypes
                if (p.size != 7) continue
                if (p[0] != ByteBuffer::class.java) continue
                if (p[6] != String::class.java) continue
                if (p[2] != Float::class.javaPrimitiveType) continue
                c.isAccessible = true
                return c.newInstance(fontMemory, face, size, oversample, shiftX, shiftY, skipStr)
            }
            MemoryUtil.memFree(fontMemory)
            return null
        } catch (t: Throwable) {
            fontMemory?.let {
                try {
                    MemoryUtil.memFree(it)
                } catch (_: Throwable) {
                }
            }
            t.printStackTrace()
            return null
        }
    }

    /**
     * 在已加载的 FontManager 上注入 provider，不调用 reloadResources。
     * @return 注入成功的 FontSet 数量
     */
    @JvmStatic
    fun injectIntoMinecraft(mc: Any): Int {
        val provider = createProviderFromHolder() ?: return 0
        lastProvider = provider
        val fontManager = findFontManager(mc) ?: return 0
        return injectIntoFontSets(fontManager, provider)
    }

    @JvmStatic
    fun findFontManager(mc: Any): Any? {
        // mc.fontManager / getFontManager()
        try {
            for (n in listOf("getFontManager", "fontManager", "getFonts")) {
                val m = mc.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                }
                if (m != null) {
                    val r = m.invoke(mc)
                    if (r != null) return r
                }
            }
            for (f in mc.javaClass.declaredFields) {
                if (f.name.contains("font", true) && f.type.simpleName.contains("FontManager", true)) {
                    f.isAccessible = true
                    return f.get(mc)
                }
            }
            // 任意含 FontManager 的字段
            for (f in mc.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(mc) ?: continue
                if (v.javaClass.name.contains("FontManager")) return v
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        return null
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun injectIntoFontSets(manager: Any, glyphProvider: Any): Int {
        var count = 0
        for (f in manager.javaClass.declaredFields) {
            f.isAccessible = true
            val valMap = try {
                f.get(manager)
            } catch (_: Throwable) {
                continue
            }
            if (valMap !is Map<*, *> || valMap.isEmpty()) continue

            for ((key, fontSet) in valMap) {
                if (fontSet == null) continue
                val id = key?.toString() ?: ""
                val isDefault = id.contains("default", true)
                val isUniform = id.contains("uniform", true)
                if (!isDefault && !(alsoUniform && isUniform)) continue
                if (prependProvider(fontSet, glyphProvider)) count++
            }
        }
        return count
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun prependProvider(fontSet: Any, glyphProvider: Any): Boolean {
        // 1) List 字段
        for (f in fontSet.javaClass.declaredFields) {
            f.isAccessible = true
            val v = try {
                f.get(fontSet)
            } catch (_: Throwable) {
                continue
            }
            if (v is MutableList<*>) {
                if (v.isNotEmpty()) {
                    val first = v[0]
                    if (first != null && !isGlyphProviderLike(first) && !isGlyphProviderLike(glyphProvider)) {
                        continue
                    }
                }
                try {
                    val raw = v as MutableList<Any?>
                    raw.removeAll { it != null && it.javaClass == glyphProvider.javaClass }
                    raw.add(0, glyphProvider)
                    // 尝试清缓存，让新 provider 立刻生效
                    clearFontSetCaches(fontSet)
                    return true
                } catch (_: Throwable) {
                }
            }
            if (v is List<*>) {
                // 不可变则跳过
            }
        }

        // 2) 方法
        for (m in fontSet.javaClass.declaredMethods) {
            if (m.parameterCount != 1) continue
            val n = m.name.lowercase()
            if (!(n.contains("provider") || n.contains("glyph"))) continue
            try {
                m.isAccessible = true
                val pt = m.parameterTypes[0]
                if (pt.isAssignableFrom(glyphProvider.javaClass) || pt.name.contains("GlyphProvider")) {
                    m.invoke(fontSet, glyphProvider)
                    clearFontSetCaches(fontSet)
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun clearFontSetCaches(fontSet: Any) {
        // 清 glyph 缓存 Map，强制重新从 provider 取字形
        for (f in fontSet.javaClass.declaredFields) {
            try {
                f.isAccessible = true
                val v = f.get(fontSet) ?: continue
                when (v) {
                    is MutableMap<*, *> -> {
                        if (f.name.contains("glyph", true) || f.name.contains("cache", true) ||
                            f.name.contains("char", true) || f.name.contains("codepoint", true)
                        ) {
                            try {
                                v.clear()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    is MutableList<*> -> {
                        if (f.name.contains("cache", true)) {
                            try {
                                (v as MutableList<*>).clear()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        // 常见方法名
        for (n in listOf("reload", "clear", "reset", "invalidate", "rebuild")) {
            try {
                val m = fontSet.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                } ?: continue
                m.invoke(fontSet)
            } catch (_: Throwable) {
            }
        }
    }

    private fun isGlyphProviderLike(o: Any): Boolean {
        val n = o.javaClass.name
        return n.contains("GlyphProvider") || n.contains("TrueType") ||
            n.contains("Bitmap") || n.contains("Provider")
    }

    private fun createFreeTypeFace(fontMemory: ByteBuffer): Any? {
        return try {
            val util = Class.forName("com.mojang.blaze3d.font.FreeTypeUtil")
            val lock = getStatic(util, "LIBRARY_LOCK")
            var library = invokeStaticNoArg(util, "getLibrary")
            if (library == null) library = getStatic(util, "library")

            val result = arrayOfNulls<Any>(1)
            val open = Runnable {
                try {
                    MemoryStack.stackPush().use { stack ->
                        val pb = stack.mallocPointer(1)
                        var newFace: java.lang.reflect.Method? = null
                        for (m in FreeType::class.java.methods) {
                            if (m.name == "FT_New_Memory_Face" && m.parameterCount >= 4) {
                                newFace = m
                                break
                            }
                        }
                        if (newFace == null) return@Runnable
                        val err = newFace.invoke(null, library, fontMemory, 0L, pb)
                        val code = if (err is Number) err.toInt() else -1
                        if (code != 0) return@Runnable
                        val ptr = pb.get(0)
                        val face = FT_Face.create(ptr)
                        try {
                            FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE)
                        } catch (_: Throwable) {
                        }
                        result[0] = face
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }

            if (lock != null) {
                synchronized(lock) { open.run() }
            } else {
                open.run()
            }
            result[0]
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private fun getStatic(clz: Class<*>, name: String): Any? {
        try {
            val f = clz.getDeclaredField(name)
            f.isAccessible = true
            return f.get(null)
        } catch (_: Throwable) {
            for (f in clz.declaredFields) {
                if (f.name.equals(name, true) || f.name.contains(name)) {
                    try {
                        f.isAccessible = true
                        return f.get(null)
                    } catch (_: Throwable) {
                    }
                }
            }
            return null
        }
    }

    private fun invokeStaticNoArg(clz: Class<*>, name: String): Any? {
        try {
            val m = clz.getDeclaredMethod(name)
            m.isAccessible = true
            return m.invoke(null)
        } catch (_: Throwable) {
            for (m in clz.declaredMethods) {
                if (m.parameterCount == 0 && m.name.equals(name, true)) {
                    try {
                        m.isAccessible = true
                        return m.invoke(null)
                    } catch (_: Throwable) {
                    }
                }
            }
            return null
        }
    }
}
