// 磁盘 TTF 状态 + 构造 TrueTypeGlyphProvider（纯 Kotlin，避免 Java 源路径问题）
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

    @JvmStatic
    fun clear() {
        enabled = false
        ttfFile = null
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
