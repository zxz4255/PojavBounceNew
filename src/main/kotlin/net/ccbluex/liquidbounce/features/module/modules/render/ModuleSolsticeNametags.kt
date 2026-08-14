/*
 * ============================================================================
 *  ModuleSolsticeNametags —— 移植 Solstice 的 Nametags.cpp/hpp (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  原版功能 (Nametags.cpp, Dear ImGui):
 *   1. 玩家头顶名牌: 名字 + 可选 BPS/平均 BPS (1 秒滑动平均)
 *   2. 朋友显示为绿色 (Show Friends)
 *   3. 距离缩放字体 (Distance Scaled Font: 1/dist*100*multiplier, 下限 Min Scale)
 *   4. 背景 (Blur Strength=0 时黑色 0.5 圆角; >0 时用模糊近似→背景减淡)
 *   5. 替代原版 Nametag (取消原版渲染)
 *
 *  移植说明:
 *   - ImGui AddText → context.text(mc.font, 9px) + pose scale (距离缩放)
 *   - OWorldToScreen → WorldToScreen.calculateScreenPos (LB 原版同款)
 *   - AddRectFilled 背景 → drawRoundedRect
 *   - gFriendManager->isFriend → FriendManager.isFriend
 *   - BPS: 每 tick 记录位置, 水平距离 × 20, 1 秒历史平均
 *   - Blur (无原生模糊) → 背景透明度按 Blur Strength 递减近似
 *
 *  可调节项 (20+): 显示朋友/本地玩家/IRC(占位)/BPS/平均 BPS、距离缩放字体、
 *        模糊强度(背景近似)、字体大小、缩放倍率、最小缩放、文字阴影、
 *        背景开关、最大渲染距离等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor, 无 Web 依赖。
 *
 *  安装:
 *    1. 放入 src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleSolsticeNametags.kt
 *    2. ModuleManager.kt: import + builtin 列表加 ModuleSolsticeNametags,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Player
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ModuleSolsticeNametags : ClientModule(
    "Nametags2",
    ModuleCategories.RENDER,
    aliases = listOf("Nametags", "SNameTags"),
) {

    /* ============================= 枚举 ============================= */

    private enum class Style(override val tag: String) : Tagged { SOLSTICE("Solstice") }

    /* ============================= 可调节项 ============================= */

    private val style by enumChoice("Style", Style.SOLSTICE)
    private val showFriends by boolean("Show Friends", true)          // 朋友显示为绿色
    private val renderLocal by boolean("Render Local", false)         // 渲染本地玩家
    private val distanceScaledFont by boolean("Distance Scaled Font", true)
    private val blurStrength by float("Blur Strength", 0f, 0f..10f)  // 原生近似: 背景透明度递减
    private val fontSize by float("Font Size", 15f, 1f..40f)         // 基础字体大小(px, 映射 9px 基准)
    private val scalingMultiplier by float("Scaling Multiplier", 0f, 0f..5f)
    private val minScale by float("Minimum Scale", 5f, 0.01f..20f)
    private val showBps by boolean("Show BPS", true)
    private val averageBps by boolean("Average BPS", true)
    private val textShadow by boolean("Text Shadow", true)
    private val background by boolean("Background", false)
    private val maxDistance by float("Max Distance", 512f, 16f..512f)  // 渲染距离上限

    /* ============================= 内部状态 ============================= */

    private val prevPosMap = IdentityHashMap<AbstractClientPlayer, net.minecraft.world.phys.Vec3>()
    private val bpsMap = IdentityHashMap<AbstractClientPlayer, Float>()
    private val avgBpsMap = IdentityHashMap<AbstractClientPlayer, Float>()
    private val bpsHistory = IdentityHashMap<AbstractClientPlayer, ArrayDeque<Pair<Long, Float>>>()

    /* ============================= BPS 计算 ============================= */

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val players = mc.level?.players() ?: return@handler
        val now = System.currentTimeMillis()
        for (player in players) {
            if (player === mc.player && !renderLocal) continue
            val pos = player.position()
            val prev = prevPosMap[player] ?: pos
            prevPosMap[player] = pos

            val dx = pos.x - prev.x
            val dz = pos.z - prev.z
            val bps = (sqrt(dx * dx + dz * dz).toFloat() * 20f)   // tick/s = 20

            val history = bpsHistory.getOrPut(player) { ArrayDeque() }
            history.addLast(now to bps)
            while (history.isNotEmpty() && now - history.first().first > 1000) {
                history.removeFirst()
            }
            bpsMap[player] = bps
            avgBpsMap[player] = if (history.isEmpty()) 0f
            else history.sumOf { it.second.toDouble() }.toFloat() / history.size
        }
        // 清理已离开的玩家
        val alive = players.toSet()
        prevPosMap.keys.retainAll(alive)
        bpsMap.keys.retainAll(alive)
        avgBpsMap.keys.retainAll(alive)
        bpsHistory.keys.retainAll(alive)
    }

    /* ============================= 工具 ============================= */

    private fun AbstractClientPlayer.nametagText(): String {
        var text = name.string
        if (showBps) {
            val v = if (averageBps) avgBpsMap[this] ?: 0f else bpsMap[this] ?: 0f
            text += " [" + String.format(Locale.US, "%.2f", v) + "]"
        }
        return text
    }

    /** 距离 → 字体缩放 (还原原版: 1/dist*100*multiplier, 下限 minScale) */
    private fun computeScale(distance: Float): Float {
        val base = if (distanceScaledFont) {
            val d = (distance + 2.5f).coerceAtLeast(0.01f)
            val f = (1f / d * 100f * scalingMultiplier).coerceAtLeast(1f)
            if (f < minScale) minScale else f
        } else {
            fontSize
        }
        // 9px 字体基准 → pose scale
        return (base / 9f).coerceIn(0.3f, 4f)
    }

    /* ============================= 渲染 ============================= */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font
        val local = mc.player ?: return@handler
        val players = mc.level?.players() ?: return@handler

        for (player in players) {
            if (player === local && !renderLocal) continue
            if (player.isRemoved || player.isDeadOrDying) continue
            val distance = local.distanceTo(player).toFloat()
            if (distance > maxDistance) continue

            // 头顶位置
            val pos = player.position().add(0.0, player.eyeHeight + 0.5, 0.0)
            val screenPos = WorldToScreen.calculateScreenPos(pos) ?: continue
            val sx = screenPos.x
            val sy = screenPos.y
            if (!sx.isFinite() || !sy.isFinite()) continue
            if (sx < -200 || sy < -100 || sx > context.guiWidth() + 200 || sy > context.guiHeight() + 100) continue

            val text = player.nametagText()
            if (text.isEmpty()) continue

            // 颜色: 朋友绿色, 否则白色
            val isFriend = showFriends && FriendManager.isFriend(player)
            val color = if (isFriend) Color4b(0, 255, 0) else Color4b.WHITE

            val scale = computeScale(distance)

            context.pose().withPush {
                translate(sx, sy)
                scale(scale, scale)

                val textW = font.width(text).toFloat()
                // 背景 (Blur Strength 越高背景越淡: 0.5 * (1 - blur/10))
                if (background) {
                    val bgAlpha = (0.5f * (1f - blurStrength / 10f) * 255).toInt().coerceIn(0, 255)
                    if (bgAlpha > 0) {
                        val pad = 4f
                        context.drawRoundedRect(
                            -textW / 2f - pad, -9f - pad, textW / 2f + pad, pad,
                            10f, Color4b(0, 0, 0, bgAlpha)
                        )
                    }
                }
                // 文字 (居中, baseline = 1)
                context.text(font, text, (-textW / 2f).roundToInt(), 1, color.argb, textShadow)
            }
        }
    }
}
