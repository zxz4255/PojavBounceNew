/*
 * ============================================================================
 *  ModuleSolsticeWatermark —— 还原 Watermark.cpp / Watermark.hpp
 *
 *  适用: LiquidBounce Nextgen 0.39 · 原生 Overlay · 无 Web
 *
 *  原版要点 (Style::Solstice):
 *   - 文字 "solstice"，字号约 45（GUI 下按比例缩放）
 *   - 逐字符 ColorUtils::getThemedColor(i * 100)
 *   - 可选 Glow：字符中心 ShadowCircle 近似多层圆
 *   - 阴影字：偏移 +3.25，颜色 *0.25
 *   - 启用/关闭：pos lerp 从 (-200,-200) → 目标，anim = lerp(anim, enabled?1:0, dt*10)
 *   - Style SevenDays 依赖嵌入贴图，原生无法加载同一资源 → 仅做 Solstice 文字风格
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ModuleSolsticeWatermark : ClientModule(
    "SolsticeWatermark",
    ModuleCategories.RENDER,
    aliases = listOf("Watermark", "SolsticeWM"),
) {
    init { enabled = true }

    private enum class Style(override val tag: String) : Tagged {
        SOLSTICE("Solstice"),
        /** 原版 7 Days 需嵌入贴图；此处用同文字样式降级 */
        SEVEN_DAYS("7 Days"),
    }

    private val style by enumChoice("Style", Style.SOLSTICE)
    private val glow by boolean("Glow", true)
    private val bold by boolean("Bold", true) // 近似：略微加粗绘制（叠字）

    private val text by text("Text", "solstice")
    private val fontSize by float("Font Size", 22f, 10f..48f) // 原版 45 对应高分辨率；GUI 默认偏小
    private val posX by float("Pos X", 20f, 0f..800f)
    private val posY by float("Pos Y", 20f, 0f..600f)
    private val shadowOffset by float("Shadow Offset", 2.5f, 0f..8f)
    private val glowRadius by float("Glow Radius", 10f, 2f..40f)
    private val glowStrength by float("Glow Strength", 0.75f, 0f..1f)
    private val animSpeed by float("Anim Speed", 10f, 1f..30f)

    private val themeA by color("Theme A", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeB by color("Theme B", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeC by color("Theme C", Color4b(255, 255, 255, 128))
    private val themeSeconds by float("Theme Cycle Sec", 3f, 0.5f..12f)
    private val rainbow by boolean("Rainbow", false)
    private val rainbowSpeed by float("Rainbow Speed", 3f, 0.5f..10f)

    private var anim = 0f
    private var lastFrameNs = 0L

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun getThemedColor(index: Float, ms: Long = 0L): Color4b {
        if (rainbow) {
            val hue = ((System.currentTimeMillis() + index.toLong()) % (rainbowSpeed * 1000).toLong()) /
                (rainbowSpeed * 1000f)
            return Color4b.ofHSB(hue, 0.85f, 1f)
        }
        val colors = listOf(themeA, themeB, themeC)
        val time = 10000f / themeSeconds.coerceAtLeast(0.01f)
        val now = if (ms == 0L) System.currentTimeMillis() else ms
        val angle = ((now + index.toLong()) % time.toLong()).toFloat()
        val segT = time / colors.size
        val seg = (angle / segT).toInt() % colors.size
        val t = (angle / segT - (angle / segT).toInt()).coerceIn(0f, 1f)
        val s = colors[seg]
        val e = colors[(seg + 1) % colors.size]
        return Color4b(
            lerp(s.r.toFloat(), e.r.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.g.toFloat(), e.g.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.b.toFloat(), e.b.toFloat(), t).toInt().coerceIn(0, 255),
            255,
        )
    }

    /** 近似 AddShadowCircle：多层圆角方块 */
    private fun drawGlow(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        cx: Float, cy: Float, radius: Float, color: Color4b, alpha: Float,
    ) {
        val layers = 4
        for (i in 1..layers) {
            val t = i / layers.toFloat()
            val r = radius * (0.4f + 0.6f * t)
            val a = (alpha * glowStrength * (1f - t) * 0.55f * 255).toInt().coerceIn(0, 90)
            if (a < 2) continue
            ctx.drawRoundedRect(
                cx - r, cy - r, cx + r, cy + r,
                r,
                color.alpha(a),
            )
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs != 0L) {
            ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        } else 0.016f
        lastFrameNs = nowNs

        // anim = lerp(anim, enabled ? 1 : 0, dt * 10)
        anim = lerp(anim, if (enabled) 1f else 0f, (dt * animSpeed).coerceIn(0f, 1f))
        anim = anim.coerceIn(0f, 1f)
        if (anim < 0.01f) return@handler

        val label = if (style == Style.SEVEN_DAYS) text.ifBlank { "7 days" } else text.ifBlank { "solstice" }
        val scale = fontSize / 9f

        // 原版：renderPosition lerp 从 (-200,-200)
        val targetX = posX
        val targetY = posY
        var drawX = lerp(-200f, targetX, anim)
        var drawY = lerp(-200f, targetY, anim)

        val font = mc.font
        val aMul = anim

        for (i in label.indices) {
            val ch = label[i].toString()
            val charW = font.width(ch) * scale
            val charH = font.lineHeight * scale
            val color = getThemedColor(i * 100f)

            val cx = drawX + charW / 2f
            val cy = drawY + charH / 2f

            if (glow) {
                drawGlow(ctx, cx, cy, max(glowRadius, fontSize / 3f), color, aMul)
            }

            // 阴影字 (offset + color*0.25)
            val shadowCol = Color4b(
                (color.r * 0.25f).toInt().coerceIn(0, 255),
                (color.g * 0.25f).toInt().coerceIn(0, 255),
                (color.b * 0.25f).toInt().coerceIn(0, 255),
                (0.925f * aMul * 255).toInt().coerceIn(0, 255),
            )
            ctx.pose().withPush {
                translate(drawX + shadowOffset, drawY + shadowOffset)
                scale(scale, scale)
                ctx.text(font, ch, 0, 0, shadowCol.argb, false)
            }

            // 主字
            val mainCol = color.alpha((255 * aMul).toInt())
            ctx.pose().withPush {
                translate(drawX, drawY)
                scale(scale, scale)
                ctx.text(font, ch, 0, 0, mainCol.argb, false)
                if (bold) {
                    // 轻微叠字近似粗体
                    ctx.text(font, ch, 1, 0, mainCol.argb, false)
                }
            }

            drawX += charW
        }
    }

    override fun onDisabled() {
        // 保持出场动画：不立刻清 anim，由 render 在 enabled=false 时收起
        lastFrameNs = 0L
    }
}
