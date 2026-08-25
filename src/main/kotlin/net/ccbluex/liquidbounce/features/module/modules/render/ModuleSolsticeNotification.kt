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
import net.ccbluex.liquidbounce.event.events.ServerConnectEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
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
    aliases = listOf("Notifications", "SolsticeNotif"),
) {
    init { enabled = true }

    private enum class Style(override val tag: String) : Tagged { SOLARIS("Solaris") }

    private val style by enumChoice("Style", Style.SOLARIS)
    private val showOnToggle by boolean("Show On Toggle", true)
    private val showOnJoin by boolean("Show On Join", true)
    private val colorGradient by boolean("Color Gradient", true)
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

    private val glow by boolean("Glow", true)
    private val glowRadius by float("Glow Radius", 14f, 2f..48f)
    private val glowStrength by float("Glow Strength", 0.65f, 0.05f..1.5f)
    private val glowLayers by int("Glow Layers", 12, 3..24)
    private val glowSoftness by float("Glow Softness", 1.35f, 0.5f..3f)
    private val glowSpread by float("Glow Spread", 1.0f, 0.3f..2.5f)
    private val glowInner by float("Glow Inner", 0.15f, 0f..0.8f)
    private enum class GlowColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        THEME("Theme"),
        TYPE("By Type"),
        GRADIENT("Gradient"),
    }

    private val glowColorMode by enumChoice("Glow Color Mode", GlowColorMode.CUSTOM)
    private val glowColor by color("Glow Color", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val glowColor2 by color("Glow Color 2", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val glowAlpha by float("Glow Alpha", 1f, 0.1f..1.5f)
    private val glowPulse by boolean("Glow Pulse", false)
    private val glowPulseSpeed by float("Glow Pulse Speed", 2.2f, 0.5f..8f)

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

    /** 监听模块开关 → 真正弹出通知（否则模块「无用」） */
    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { e ->
        if (!enabled || !showOnToggle) return@handler
        if (e.hidden) return@handler
        // 避免自己开关刷屏
        if (e.moduleName.equals(name, true) || e.moduleName.contains("Notification", true)) return@handler
        notifyToggle(e.moduleName, e.enabled)
    }

    /** 监听客户端统一通知事件 */
    @Suppress("unused")
    private val notifEventHandler = handler<NotificationEvent> { e ->
        if (!enabled) return@handler
        val type = when (e.severity) {
            NotificationEvent.Severity.ERROR -> Type.ERROR
            NotificationEvent.Severity.ENABLED, NotificationEvent.Severity.SUCCESS -> Type.INFO
            NotificationEvent.Severity.DISABLED -> Type.WARNING
            else -> Type.INFO
        }
        val msg = if (e.title.isNotBlank() && e.message.isNotBlank()) {
            "${e.title}: ${e.message}"
        } else e.title.ifBlank { e.message }
        if (msg.isNotBlank()) add(msg, type, defaultDuration)
    }

    /** 进服提示 */
    @Suppress("unused")
    private val connectHandler = handler<ServerConnectEvent> { e ->
        if (!enabled || !showOnJoin) return@handler
        val nice = try {
            e.address.toString()
        } catch (_: Throwable) {
            try { e.serverInfo.name } catch (_: Throwable) { "server" }
        }
        notifyJoin(nice.take(48))
    }

    /** 测试：开启模块时推一条，确认渲染链路正常 */
    override suspend fun enabledEffect() {
        add("Solstice Notification enabled", Type.INFO, 2.5f)
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

    /**
     * Natural outer glow: gaussian-like radial falloff, multi-layer rounded fills.
     * Tunable radius / strength / softness / spread / inner core / optional pulse.
     */
    private fun drawGlow(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        base: Color4b, base2: Color4b, alphaMul: Float, gradient: Boolean,
    ) {
        if (!glow) return
        val layers = glowLayers.coerceIn(3, 24)
        val maxR = (glowRadius * glowSpread).coerceAtLeast(1.5f)
        val strength = glowStrength.coerceIn(0.05f, 1.5f)
        val soft = glowSoftness.coerceIn(0.5f, 3f)
        val inner = glowInner.coerceIn(0f, 0.8f)
        val aScale = glowAlpha.coerceIn(0.1f, 1.5f)

        var pulse = 1f
        if (glowPulse) {
            val t = (System.currentTimeMillis() % 100000L) / 1000.0
            pulse = (0.82f + 0.18f * kotlin.math.sin(t * glowPulseSpeed).toFloat())
        }

        for (i in layers downTo 1) {
            val u = i / layers.toFloat()
            val r = maxR * u
            val gauss = kotlin.math.exp(-(u * u) * (2.8f / soft)).toFloat()
            val core = 1f + inner * (1f - u)
            val fall = (gauss * core).coerceIn(0f, 1.6f)
            val a = (fall * strength * pulse * aScale * 165f * alphaMul)
                .toInt()
                .coerceIn(0, 180)
            if (a < 2) continue

            // layer color: solid base, or lerp base→base2 by radius (outer uses base2)
            val src = if (gradient) {
                val s = u // outer = more base2
                Color4b(
                    lerp(base.r.toFloat(), base2.r.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(base.g.toFloat(), base2.g.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(base.b.toFloat(), base2.b.toFloat(), s).toInt().coerceIn(0, 255),
                    255,
                )
            } else base

            val desat = (u * 0.10f).coerceIn(0f, 0.2f)
            val rC = (src.r + (255 - src.r) * desat).toInt().coerceIn(0, 255)
            val gC = (src.g + (255 - src.g) * desat).toInt().coerceIn(0, 255)
            val bC = (src.b + (255 - src.b) * desat).toInt().coerceIn(0, 255)
            val col = Color4b(rC, gC, bC, a)

            val rad = (cornerRadius + r * 0.55f).coerceAtLeast(cornerRadius)
            ctx.drawRoundedRect(x1 - r, y1 - r, x2 + r, y2 + r, rad, col)
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
            // 主题渐变背景：无黑色阴影/黑底层
            val aMul = n.currentDuration
            val cLeft = theme.alpha((220 * aMul).toInt().coerceIn(0, 255))
            val cRight = getThemedColor(boxTop * 2f + boxW * 1.2f)
                .alpha((200 * aMul).toInt().coerceIn(0, 255))
            // 外围辉光颜色：Custom / Theme / By Type / Gradient
            val (g1, g2, gGrad) = when (glowColorMode) {
                GlowColorMode.CUSTOM -> Triple(glowColor, glowColor2, false)
                GlowColorMode.THEME -> Triple(theme, getThemedColor(boxTop * 2f + 40f), false)
                GlowColorMode.TYPE -> Triple(
                    when (n.type) {
                        Type.WARNING -> Color4b(255, 204, 0, 255)
                        Type.ERROR -> Color4b(255, 60, 60, 255)
                        Type.INFO -> glowColor
                    },
                    glowColor2,
                    false,
                )
                GlowColorMode.GRADIENT -> Triple(glowColor, glowColor2, true)
            }
            drawGlow(ctx, x, boxTop, x + boxW, boxBottom, g1, g2, aMul, gGrad)
            // 先铺一层不透明主题底，杜绝缝隙透出黑/游戏画面
            ctx.drawRoundedRect(x, boxTop, x + boxW, boxBottom, cornerRadius, cLeft)
            if (colorGradient) {
                // 分段渐变叠在主题底上，段间轻微重叠，无黑条
                val segs = 20
                val segW = boxW / segs
                for (i in 0 until segs) {
                    val t0 = i / (segs - 1).toFloat().coerceAtLeast(1f)
                    val s = t0 * t0 * (3f - 2f * t0)
                    val col = Color4b(
                        lerp(cLeft.r.toFloat(), cRight.r.toFloat(), s).toInt().coerceIn(0, 255),
                        lerp(cLeft.g.toFloat(), cRight.g.toFloat(), s).toInt().coerceIn(0, 255),
                        lerp(cLeft.b.toFloat(), cRight.b.toFloat(), s).toInt().coerceIn(0, 255),
                        (235 * aMul).toInt().coerceIn(0, 255),
                    )
                    val sx = x + segW * i - 0.5f
                    val ex = x + segW * (i + 1) + 0.5f
                    ctx.drawQuad(
                        sx.coerceAtLeast(x),
                        boxTop,
                        ex.coerceAtMost(x + boxW),
                        boxBottom,
                        col,
                    )
                }
            }

            // 底部细进度（剩余时间），主题色连续条
            val remain = (1f - percentDone).coerceIn(0f, 1f)
            val barH = (3f * (fontSize / 11f)).coerceIn(2f, 5f)
            val barY1 = boxBottom - barH - 1f
            val barY2 = boxBottom - 1f
            val barPad = 4f
            val barLeft = x + barPad
            val barRight = x + boxW - barPad
            val barW = (barRight - barLeft) * remain
            if (barW > 0.5f) {
                val barCol = Color4b(255, 255, 255, (200 * aMul).toInt().coerceIn(0, 255))
                ctx.drawQuad(barLeft, barY1, barLeft + barW, barY2, barCol)
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
