package net.ccbluex.liquidbounce.features.module.modules.render.ttf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * 从磁盘 TTF 直接构造 {@code com.mojang.blaze3d.font.TrueTypeGlyphProvider}，不经过资源包。
 */
public final class TtfDiskProviderFactory {

    private TtfDiskProviderFactory() {}

    public static Object createProvider(File ttf, float size, float oversample, float shiftX, float shiftY, String skip) {
        if (ttf == null || !ttf.isFile()) return null;
        ByteBuffer fontMemory = null;
        try {
            byte[] bytes = Files.readAllBytes(ttf.toPath());
            if (bytes.length < 64) return null;

            fontMemory = MemoryUtil.memAlloc(bytes.length);
            fontMemory.put(bytes);
            fontMemory.flip();

            Object face = createFreeTypeFace(fontMemory);
            if (face == null) {
                MemoryUtil.memFree(fontMemory);
                return null;
            }

            Class<?> providerClz = Class.forName("com.mojang.blaze3d.font.TrueTypeGlyphProvider");
            String skipStr = skip != null ? skip : "";

            for (Constructor<?> c : providerClz.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != 7 || p[0] != ByteBuffer.class || p[6] != String.class) continue;
                if (p[2] != float.class) continue;
                c.setAccessible(true);
                return c.newInstance(fontMemory, face, size, oversample, shiftX, shiftY, skipStr);
            }
            MemoryUtil.memFree(fontMemory);
            return null;
        } catch (Throwable t) {
            if (fontMemory != null) {
                try { MemoryUtil.memFree(fontMemory); } catch (Throwable ignored) {}
            }
            t.printStackTrace();
            return null;
        }
    }

    private static Object createFreeTypeFace(ByteBuffer fontMemory) {
        try {
            Class<?> util = Class.forName("com.mojang.blaze3d.font.FreeTypeUtil");
            Object lock = getStatic(util, "LIBRARY_LOCK");
            Object library = invokeStaticNoArg(util, "getLibrary");
            if (library == null) library = getStatic(util, "library");

            final Object lib = library;
            final Object[] result = new Object[1];

            Runnable open = () -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var pb = stack.mallocPointer(1);
                    // FreeType.FT_New_Memory_Face(FT_Library, ByteBuffer, long, PointerBuffer)
                    Method newFace = null;
                    for (Method m : FreeType.class.getMethods()) {
                        if (m.getName().equals("FT_New_Memory_Face") && m.getParameterCount() >= 4) {
                            newFace = m;
                            break;
                        }
                    }
                    if (newFace == null) return;
                    Object err = newFace.invoke(null, lib, fontMemory, 0L, pb);
                    int code = (err instanceof Number) ? ((Number) err).intValue() : -1;
                    if (code != 0) {
                        // FreeTypeUtil.assertError optional
                        return;
                    }
                    long ptr = pb.get(0);
                    FT_Face face = FT_Face.create(ptr);
                    try {
                        FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE);
                    } catch (Throwable ignored) {}
                    result[0] = face;
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            };

            if (lock != null) {
                synchronized (lock) {
                    open.run();
                }
            } else {
                open.run();
            }
            return result[0];
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static Object getStatic(Class<?> clz, String name) {
        try {
            Field f = clz.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            for (Field f : clz.getDeclaredFields()) {
                if (f.getName().equalsIgnoreCase(name) || f.getName().contains(name)) {
                    try {
                        f.setAccessible(true);
                        return f.get(null);
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }
    }

    private static Object invokeStaticNoArg(Class<?> clz, String name) {
        try {
            Method m = clz.getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            for (Method m : clz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getName().equalsIgnoreCase(name)) {
                    try {
                        m.setAccessible(true);
                        return m.invoke(null);
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }
    }
}
