package net.ccbluex.liquidbounce.injection.mixins.minecraft.client;

import net.ccbluex.liquidbounce.utils.ttf.ForcedTtf;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * FontManager 加载完成后注入磁盘 TTF GlyphProvider。
 * 依赖 Kotlin: net.ccbluex.liquidbounce.utils.ttf.ForcedTtf
 * mixins.json: "minecraft.client.MixinFontManagerForceTtf"
 */
@Mixin(FontManager.class)
public abstract class MixinFontManagerForceTtf {

    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private void liquidbounce$injectTtfAfterApply(CallbackInfo ci) {
        tryInject();
    }

    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private void liquidbounce$injectTtfAfterReload(CallbackInfo ci) {
        tryInject();
    }

    private void tryInject() {
        if (!ForcedTtf.enabled || ForcedTtf.ttfFile == null) return;
        try {
            Object provider = ForcedTtf.createProviderFromHolder();
            if (provider == null) return;
            injectIntoFontSets((FontManager) (Object) this, provider);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void injectIntoFontSets(FontManager manager, Object glyphProvider) {
        for (Field f : manager.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try {
                val = f.get(manager);
            } catch (Throwable t) {
                continue;
            }
            if (!(val instanceof Map<?, ?> map) || map.isEmpty()) continue;

            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object key = e.getKey();
                String id = key != null ? key.toString() : "";
                boolean isDefault = id.contains("default");
                boolean isUniform = id.contains("uniform");
                if (!isDefault && !(ForcedTtf.alsoUniform && isUniform)) continue;

                Object fontSet = e.getValue();
                if (fontSet == null) continue;
                prependProvider(fontSet, glyphProvider);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void prependProvider(Object fontSet, Object glyphProvider) {
        for (Field f : fontSet.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try {
                val = f.get(fontSet);
            } catch (Throwable t) {
                continue;
            }
            if (val instanceof List<?> list) {
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (first != null && !isGlyphProviderLike(first) && !isGlyphProviderLike(glyphProvider)) {
                        continue;
                    }
                }
                try {
                    List raw = (List) list;
                    raw.removeIf(p -> p != null && p.getClass() == glyphProvider.getClass());
                    raw.add(0, glyphProvider);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }

        for (Method m : fontSet.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            String n = m.getName().toLowerCase();
            if (!(n.contains("provider") || n.contains("glyph"))) continue;
            try {
                m.setAccessible(true);
                Class<?> pt = m.getParameterTypes()[0];
                if (pt.isAssignableFrom(glyphProvider.getClass()) || pt.getName().contains("GlyphProvider")) {
                    m.invoke(fontSet, glyphProvider);
                    return;
                }
                if (Collection.class.isAssignableFrom(pt)) {
                    m.invoke(fontSet, List.of(glyphProvider));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isGlyphProviderLike(Object o) {
        String n = o.getClass().getName();
        return n.contains("GlyphProvider") || n.contains("TrueType") || n.contains("Bitmap") || n.contains("Provider");
    }
}
