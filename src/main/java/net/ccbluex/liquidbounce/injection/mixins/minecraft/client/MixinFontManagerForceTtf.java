package net.ccbluex.liquidbounce.injection.mixins.minecraft.client;

import net.ccbluex.liquidbounce.utils.ttf.ForcedTtf;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 仅在原版自己重载字体后补注一次。主路径由 Module 直接 injectIntoMinecraft，不主动 F3+T。
 * mixins.json: "minecraft.client.MixinFontManagerForceTtf"
 */
@Mixin(FontManager.class)
public abstract class MixinFontManagerForceTtf {

    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private void liquidbounce$injectTtfAfterReload(CallbackInfoReturnable<?> cir) {
        if (!ForcedTtf.enabled || ForcedTtf.ttfFile == null) return;
        try {
            Object provider = ForcedTtf.createProviderFromHolder();
            if (provider == null) return;
            ForcedTtf.injectIntoFontSets(this, provider);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
