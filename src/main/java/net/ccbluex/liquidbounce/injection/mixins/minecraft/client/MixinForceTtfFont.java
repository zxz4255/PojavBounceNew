/*
 * 强制启用磁盘生成的 LiquidBounce-TTF 资源包，使全游戏文字走 TTF。
 * 配合 ModuleGlobalTtfFont 写出的 resourcepacks/LiquidBounce-TTF 使用。
 *
 * 放入: src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/client/MixinForceTtfFont.java
 * 并在 liquidbounce.mixins.json 的 client 列表中注册本类。
 */
package net.ccbluex.liquidbounce.injection.mixins.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在客户端资源包仓库 reload 后，把 id 含 LiquidBounce-TTF 的 pack 插到选中列表末尾（最高优先级）。
 * 这样无需玩家手动进资源包菜单；Module 负责写出 pack 文件即可。
 */
@Mixin(PackRepository.class)
public abstract class MixinForceTtfFont {

    private static final String PACK_HINT = "LiquidBounce-TTF";

    /**
     * 兼容不同映射的方法名：reload / 无参重载可用。
     * 若你这边 Yarn/Mojmap 方法名不同，改 method 即可。
     */
    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private void liquidbounce$forceTtfPack(CallbackInfo ci) {
        forceSelect();
    }

    /** 部分版本用 rebuild / reloadAvailable — 多挂几个入口，require=0 不匹配则跳过 */
    @Inject(method = "rebuildSelected", at = @At("RETURN"), require = 0)
    private void liquidbounce$forceTtfPackRebuild(CallbackInfo ci) {
        forceSelect();
    }

    private void forceSelect() {
        try {
            PackRepository self = (PackRepository) (Object) this;
            Collection<Pack> available = self.getAvailablePacks();
            if (available == null || available.isEmpty()) {
                return;
            }

            Pack target = null;
            for (Pack pack : available) {
                String id = pack.getId();
                if (id != null && id.contains(PACK_HINT)) {
                    target = pack;
                    break;
                }
            }
            if (target == null) {
                return;
            }

            // 已选中则保证在最后（覆盖 default）
            List<Pack> selected = new ArrayList<>(self.getSelectedPacks());
            selected.removeIf(p -> p.getId() != null && p.getId().contains(PACK_HINT));
            selected.add(target);

            // setSelected 接受 Collection<String> id 或 Pack —— 优先 id 列表
            try {
                List<String> ids = new ArrayList<>();
                for (Pack p : selected) {
                    ids.add(p.getId());
                }
                self.setSelected(ids);
            } catch (Throwable t) {
                // 旧签名：setSelected(Collection<Pack>)
                try {
                    java.lang.reflect.Method m = PackRepository.class.getMethod("setSelected", Collection.class);
                    m.invoke(self, selected);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // 静默失败，避免拖垮客户端
        }
    }
}
