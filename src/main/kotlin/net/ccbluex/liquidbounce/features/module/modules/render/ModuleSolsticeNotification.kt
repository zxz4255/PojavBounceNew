/*
 * ============================================================================
 *  ModuleSolsticeNotification —— 还原 Notifications.cpp / Notifications.hpp
 *
 *  适用: LiquidBounce Nextgen 0.39 · 原生 Overlay 渲染 · 无 Web
 *
 *  原版要点:
 *   - Style: Solaris
 *   - 右下角堆叠，x = lerp(屏外, 目标, currentDuration)
 *   - currentDuration = lerp(cur, timeUp?0:1, dt*5)
 *   - 进度条 percentDone，左侧主题色 / 可选渐变，右侧半透明黑
 *   - getThemedColor(y*2)；Warning 黄 / Error 红；alpha 0.7
 *   - AddShadowRect 近似为多层圆角描边
 *   - Show on toggle / join（模块开关时可选推送）
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.util.Mth
import kotlin.math.max
import kotlin.math.roundToInt

object ModuleSolsticeNotification : ClientModule(
    "SolsticeNotification",
    ModuleCategories.RENDER,
    aliases = listOf("Notifications", "SolsticeNotification"),
) {
    init { enabled = true }

    private enum class Style(override val tag: String) : Tagged { SOLARIS("Solaris") }

    private val style by enumChoice("Style", Style.SOLARIS)
    private val showOnToggle by boolean("Show On Toggle", true)
    private val showOnJoin by boolean("Show On Join", true)
    private val colorGradient by boolean("Color Gradient", false)
    private val limitNotifications by boolean("Limit Notifications", false)
    private val maxNotifications by int("Max Notifications", 6, 1..25)
    private val fontSize by float("Font Size", 11f, 8f..20f)
    private val rightMargin by float("Right Margin", 10f, 0f..40f)
    private val bottomMargin by float("Bottom Margin", 10f, 0f..40f)
    private val animSpeed by float("Anim Speed", 5f, 1f..15f)
    private val defaultDuration by float("Default Duration", 3f, 1f..15f)
    private val cornerRadius by float("Corner Radius", 5f, 0f..16f)
    private val shadowBlur by float("Shadow Blur", 50f, 1f..120f)
    private val shadowDensity by int("Shadow Density", 2, 1..8)

    private val themeA by color("Theme A", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeB by color("Theme B", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeC by color("Theme C", Color4b(255, 255, 255, 128))
    private val themeSeconds by float("Theme Cycle Sec", 3f, 0.5f..10f)

    enum class Type { INFO, WARNING, ERROR }

    class Notification(
        val message: String,
        val type: Type = Type.INFO,
        val duration: Float = 3f,
    ) {
        var timeShown = 0f
        var currentDuration = 0f
        var isTimeUp = false
    }

    private val notifications = ArrayList<Notification>()
    private var lastFrameNs = 0L

    fun add(message: String, type: Type = Type.INFO, duration: Float = defaultDuration) {
        notifications.add(Notification(message, type, duration))
    }

    fun info(msg: String, duration: Float = defaultDuration) = add(msg, Type.INFO, duration)
    fun warning(msg: String, duration: Float = defaultDuration) = add(msg, Type.WARNING, duration)
    fun error(msg: String, duration: Float = defaultDuration) = add(msg, Type.ERROR, duration)

    fun notifyToggle(moduleName: String, enabled: Boolean) {
        if (!showOnToggle) return
        add("$moduleName was ${if (enabled) "enabled" else "disabled"}", Type.INFO, defaultDuration)
    }

    fun notifyJoin(address: String) {
        if (!showOnJoin) return
        add("Connecting to $address...", Type.INFO, 6f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun getThemedColor(index: Float, ms: Long = 0L): Color4b {
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
            lerp(s.a.toFloat(), e.a.toFloat(), t).toInt().coerceIn(0, 255),
        )
    }

    private fun textW(s: String) = mc.font.width(s) * (fontSize / 9f)
    private fun textH() = mc.font.lineHeight * (fontSize / 9f)

    private fun drawScaledText(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        text: String, x: Float, y: Float, color: Color4b,
    ) {
        ctx.pose().withPush {
            translate(x, y)
            scale(fontSize / 9f, fontSize / 9f)
            ctx.text(mc.font, text, 0, 0, color.argb, false)
        }
    }

    private fun drawShadow(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float, color: Color4b,
    ) {
        val density = shadowDensity.coerceAtLeast(1)
        for (d in 0 until density) {
            val t = if (density > 1) d.toFloat() / (density - 1) else 0f
            val r = shadowBlur * (0.25f + 0.75f * t) * 0.08f
            val a = (0.35f * (1f - t * 0.5f) * 255).toInt().coerceIn(0, 80)
            ctx.drawRoundedRect(
                x1 - r, y1 - r, x2 + r, y2 + r,
                cornerRadius + r * 0.3f,
                Color4b.TRANSPARENT,
                color.alpha(a),
                1.5f + t * 2f,
            )
        }
    }

    private fun drawHGradient(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        c1: Color4b, c2: Color4b, radius: Float,
    ) {
        val w = x2 - x1
        if (w <= 1f) {
            ctx.drawRoundedRect(x1, y1, x2, y2, radius, c1)
            return
        }
        val segments = 10
        val segW = w / segments
        for (i in 0 until segments) {
            val t = i / segments.toFloat()
            val col = Color4b(
                lerp(c1.r.toFloat(), c2.r.toFloat(), t).toInt().coerceIn(0, 255),
                lerp(c1.g.toFloat(), c2.g.toFloat(), t).toInt().coerceIn(0, 255),
                lerp(c1.b.toFloat(), c2.b.toFloat(), t).toInt().coerceIn(0, 255),
                lerp(c1.a.toFloat(), c2.a.toFloat(), t).toInt().coerceIn(0, 255),
            )
            val sx = x1 + segW * i
            val ex = if (i == segments - 1) x2 else x1 + segW * (i + 1)
            if (i == 0 || i == segments - 1) ctx.drawRoundedRect(sx, y1, ex, y2, radius, col)
            else ctx.drawQuad(sx, y1, ex, y2, col)
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs != 0L) {
            ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        } else 0.016f
        lastFrameNs = nowNs

        notifications.removeAll { it.isTimeUp && it.timeShown > it.duration + 3f }

        val screenW = ctx.guiWidth().toFloat()
        val screenH = ctx.guiHeight().toFloat()
        var y = screenH - bottomMargin
        var shown = 0

        for (n in notifications) {
            if (limitNotifications && shown >= maxNotifications) break

            n.timeShown += dt
            n.isTimeUp = n.timeShown >= n.duration
            n.currentDuration = lerp(
                n.currentDuration,
                if (n.isTimeUp) 0f else 1f,
                dt * animSpeed,
            ).coerceIn(0f, 1f)
            if (n.currentDuration < 0.01f && n.isTimeUp) continue

            val percentDone = Mth.clamp(n.timeShown / n.duration.coerceAtLeast(0.01f), 0f, 1f)
            val tH = textH()
            val tW = textW(n.message)
            val boxW = max(200f * (fontSize / 11f), 50f + tW)
            val boxH = tH + 30f * (fontSize / 11f)

            val beginX = screenW - boxW - rightMargin
            val endX = screenW + boxW
            val x = lerp(endX, beginX, n.currentDuration)
            val boxTop = y - boxH
            val boxBottom = y - 10f

            var theme = getThemedColor(boxTop * 2f)
            when (n.type) {
                Type.WARNING -> theme = Color4b(255, 204, 0, 255)
                Type.ERROR -> theme = Color4b(255, 0, 0, 255)
                Type.INFO -> Unit
            }
            theme = theme.alpha((0.7f * 255).toInt())

            val progressW = (boxW * percentDone + 6f).coerceIn(0f, boxW)
            val progMaxX = (x + progressW).coerceIn(x, x + boxW)

            drawShadow(ctx, x, boxTop, progMaxX, boxBottom, theme)

            if (progressW > 1f) {
                if (!colorGradient) {
                    ctx.drawRoundedRect(x, boxTop, progMaxX, boxBottom, cornerRadius, theme)
                } else {
                    val rgb2 = getThemedColor(boxTop * 2f + ((x - progMaxX) * 1.2f))
                        .alpha((0.7f * 255).toInt())
                    drawHGradient(ctx, x, boxTop, progMaxX, boxBottom, theme, rgb2, cornerRadius)
                }
            }

            val darkStart = x + boxW * percentDone - 6f
            if (darkStart < x + boxW - 0.5f) {
                ctx.drawRoundedRect(
                    darkStart.coerceAtLeast(x), boxTop, x + boxW, boxBottom,
                    cornerRadius, Color4b(0, 0, 0, (0.7f * 255).toInt()),
                )
            }

            drawScaledText(ctx, n.message, x + 10f, boxTop + 10f * (fontSize / 11f), Color4b.WHITE)

            y = boxTop
            if (!n.isTimeUp) shown++
        }
    }

    override fun onDisabled() {
        notifications.clear()
        lastFrameNs = 0L
    }
}
