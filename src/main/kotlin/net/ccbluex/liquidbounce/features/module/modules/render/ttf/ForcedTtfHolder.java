package net.ccbluex.liquidbounce.injection.mixins.minecraft.client;

import net.ccbluex.liquidbounce.features.module.modules.render.ttf.ForcedTtfHolder;
import net.ccbluex.liquidbounce.features.module.modules.render.ttf.TtfDiskProviderFactory;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 字体加载完成后，把磁盘 TTF 做成的 GlyphProvider 插到 default（及 uniform）字体前面。
 * 不写资源包、不改 default.json。
 *
 * mixins.json:
 *   "minecraft.client.MixinFontManagerForceTtf"
 */
@Mixin(FontManager.class)
public abstract class MixinFontManagerForceTtf {

    /** 常见方法名：apply / reload / loadAll / updateFonts — require=0 多挂几个 */
    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private void liquidbounce$injectTtfAfterApply(CallbackInfo ci) {
        tryInject();
    }

    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private void liquidbounce$injectTtfAfterReload(CallbackInfo ci) {
        tryInject();
    }

    private void tryInject() {
        if (!ForcedTtfHolder.enabled || ForcedTtfHolder.ttfFile == null) return;
        try {
            Object provider = TtfDiskProviderFactory.createProvider(
                ForcedTtfHolder.ttfFile,
                ForcedTtfHolder.size,
                ForcedTtfHolder.oversample,
                ForcedTtfHolder.shiftX,
                ForcedTtfHolder.shiftY,
                ForcedTtfHolder.skip
            );
            if (provider == null) return;

            FontManager self = (FontManager) (Object) this;
            injectIntoFontSets(self, provider);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void injectIntoFontSets(FontManager manager, Object glyphProvider) {
        // FontManager 内通常有 Map<ResourceLocation, FontSet> 或类似
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
                boolean isDefault = id.contains("default") || id.endsWith("/default") || id.equals("minecraft:default");
                boolean isUniform = id.contains("uniform");
                if (!isDefault && !(ForcedTtfHolder.alsoUniform && isUniform)) continue;

                Object fontSet = e.getValue();
                if (fontSet == null) continue;
                prependProvider(fontSet, glyphProvider);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void prependProvider(Object fontSet, Object glyphProvider) {
        // FontSet 可能持有 List<GlyphProvider> 或 providers 数组
        for (Field f : fontSet.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try {
                val = f.get(fontSet);
            } catch (Throwable t) {
                continue;
            }
            if (val instanceof List<?> list) {
                // 检查元素类型是否像 GlyphProvider
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (first != null && !isGlyphProviderLike(first) && !isGlyphProviderLike(glyphProvider)) {
                        continue;
                    }
                }
                try {
                    List raw = (List) list;
                    raw.removeIf(p -> p != null && p.getClass() == glyphProvider.getClass()
                        && p.toString().contains("TrueType"));
                    raw.add(0, glyphProvider);
                    return;
                } catch (Throwable ignored) {}
            }
            if (val instanceof Object[] arr) {
                // 较少见
            }
        }

        // 尝试方法 addGlyphProvider / setProviders
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
                    List<Object> list = new ArrayList<>();
                    list.add(glyphProvider);
                    m.invoke(fontSet, list);
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isGlyphProviderLike(Object o) {
        String n = o.getClass().getName();
        return n.contains("GlyphProvider") || n.contains("TrueType") || n.contains("Bitmap") || n.contains("Provider");
    }
}
