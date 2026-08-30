/*
 * ModuleMusicVisualizer —— 屏幕底部条形示波器（纯视觉，不依赖真实音频）
 * LiquidBounce Nextgen 0.39 · Overlay 原生渲染 · 无 Web
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
import net.ccbluex.liquidbounce.utils.client.mc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object ModuleMusicVisualizer : ClientModule(
    "MusicVisualizer",
    ModuleCategories.RENDER,
    aliases = listOf("AudioBars", "Spectrum", "Equalizer", "示波器"),
) {

    // ---------- 布局 ----------
    private val barCount by int("Bar Count", 64, 8..128)
    private val barWidth by float("Bar Width", 6f, 1f..24f)
    private val barGap by float("Bar Gap", 2f, 0f..12f)
    private val maxHeight by float("Max Height", 72f, 16f..240f)
    private val minHeight by float("Min Height", 3f, 0f..40f)
    private val bottomMargin by float("Bottom Margin", 28f, 0f..120f)
    private val sideMargin by float("Side Margin", 40f, 0f..200f)
    private val centerAlign by boolean("Center Align", true)
    private val mirror by boolean("Mirror", false)
    private val barRadius by float("Bar Radius", 2f, 0f..12f)

    // ---------- 动画 / 频谱 ----------
    private enum class WaveMode(override val tag: String) : Tagged {
        SMOOTH("Smooth"),
        PULSE("Pulse"),
        BASS("Bass Heavy"),
        CHAOTIC("Chaotic"),
        SINE("Sine Stack"),
        RANDOM("Random"),
    }

    private val waveMode by enumChoice("Wave Mode", WaveMode.SMOOTH)
    private val speed by float("Speed", 1.15f, 0.1f..4f)
    private val reactivity by float("Reactivity", 0.55f, 0.05f..1f)
    private val smoothness by float("Smoothness", 0.28f, 0.02f..0.9f)
    private val peakHold by boolean("Peak Hold", true)
    private val peakDecay by float("Peak Decay", 0.35f, 0.05f..2f)
    private val peakThickness by float("Peak Thickness", 2f, 1f..6f)
    private val noise by float("Noise", 0.12f, 0f..0.6f)

    // ---------- 颜色 ----------
    private enum class ColorMode(override val tag: String) : Tagged {
        SOLID("Solid"),
        GRADIENT("Gradient"),
        RAINBOW("Rainbow"),
        HEIGHT("Height Map"),
        PULSE_COLOR("Pulse Color"),
        RANDOM("Random"),
    }

    private val colorMode by enumChoice("Color Mode", ColorMode.GRADIENT)
    private val colorA by color("Color A", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val colorB by color("Color B", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val peakColor by color("Peak Color", Color4b(255, 255, 255, 220))
    private val rainbowSpeed by float("Rainbow Speed", 0.35f, 0.05f..2f)
    private val alpha by float("Alpha", 0.92f, 0.15f..1f)
    private val glow by boolean("Glow", true)
    private val glowStrength by float("Glow Strength", 0.45f, 0.05f..1.2f)
    private val glowLayers by int("Glow Layers", 4, 1..10)

    // ---------- 背景 ----------
    private val baseLine by boolean("Base Line", true)
    private val baseLineAlpha by float("Base Line Alpha", 0.25f, 0.05f..1f)
    private val hideInGui by boolean("Hide In GUI", true)

    // ---------- 状态 ----------
    private var bars = FloatArray(64)
    private var peaks = FloatArray(64)
    private var phases = FloatArray(64) { Random.nextFloat() * PI.toFloat() * 2f }
    private var lastNs = 0L
    private var time = 0.0

    override fun onEnabled() {
        resizeBuffers()
        lastNs = 0L
    }

    private fun resizeBuffers() {
        val n = barCount.coerceIn(8, 128)
        if (bars.size != n) {
            bars = FloatArray(n)
            peaks = FloatArray(n)
            phases = FloatArray(n) { Random.nextFloat() * PI.toFloat() * 2f }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun hsv(h: Float, s: Float, v: Float, a: Int): Color4b {
        val hh = ((h % 1f) + 1f) % 1f * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Color4b(
            (r * 255f).roundToInt().coerceIn(0, 255),
            (g * 255f).roundToInt().coerceIn(0, 255),
            (b * 255f).roundToInt().coerceIn(0, 255),
            a,
        )
    }

    /** 伪频谱：多正弦叠加 + 低频鼓点 + 噪声，仅视觉 */
    private fun targetLevel(i: Int, n: Int, t: Double): Float {
        val u = i / (n - 1f).coerceAtLeast(1f)
        val sp = speed.toDouble()
        val ph = phases[i].toDouble()

        return when (waveMode) {
            WaveMode.SMOOTH -> {
                val a = 0.55 + 0.35 * sin(t * 2.1 * sp + ph)
                val b = 0.25 * sin(t * 5.3 * sp + u * 8.0)
                val c = 0.15 * sin(t * 11.0 * sp + ph * 0.5)
                val envelope = 0.65 + 0.35 * sin(u * PI + t * 0.7 * sp)
                ((a + b + c) * envelope).toFloat()
            }
            WaveMode.PULSE -> {
                val beat = (sin(t * 3.2 * sp) * 0.5 + 0.5).pow(2.2)
                val mid = expFall(abs(u - 0.5f) * 2.2f)
                val shimmer = 0.2 * sin(t * 14.0 * sp + i)
                (0.2 + beat * 0.75 * mid + shimmer).toFloat()
            }
            WaveMode.BASS -> {
                val bass = (sin(t * 2.4 * sp) * 0.5 + 0.5).pow(1.6)
                val low = expFall(u * 2.8f)
                val high = 0.15 * sin(t * 9.0 * sp + ph) * u
                (bass * (0.35 + 0.65 * low) + high).toFloat()
            }
            WaveMode.CHAOTIC -> {
                val a = sin(t * 3.7 * sp + ph * 1.3)
                val b = cos(t * 6.1 * sp + u * 12.0)
                val c = sin(t * 1.1 * sp + i * 0.37)
                (0.45 + 0.35 * a + 0.2 * b * c).toFloat()
            }
            WaveMode.SINE -> {
                val layers = 0.4 * sin(t * 2.0 * sp + u * PI * 2) +
                    0.25 * sin(t * 4.5 * sp + u * PI * 4 + 0.4) +
                    0.15 * sin(t * 8.0 * sp + u * PI * 6)
                (0.35 + layers).toFloat()
            }
            WaveMode.RANDOM -> {
                // 每根柱独立伪随机游走 + 全局鼓点
                val beat = (sin(t * 2.8 * sp) * 0.5 + 0.5).pow(2.0)
                val walk = sin(t * (1.3 + i * 0.17) * sp + ph) * 0.35
                val jump = sin(t * (4.0 + (i % 7) * 0.9) * sp + ph * 2.1) * 0.25
                val spark = if (Random.nextFloat() < 0.04f * speed) Random.nextFloat() * 0.5f else 0f
                (0.25 + beat * 0.4 + walk + jump + spark).toFloat()
            }
        }.let { raw ->
            val nse = if (noise > 0.001f) {
                (Random.nextFloat() * 2f - 1f) * noise
            } else 0f
            (raw + nse).coerceIn(0.02f, 1.15f)
        }
    }

    private fun expFall(x: Float): Float = kotlin.math.exp(-(x * x)).toFloat()

    private fun barColor(i: Int, n: Int, level: Float, a: Int): Color4b {
        val u = i / (n - 1f).coerceAtLeast(1f)
        return when (colorMode) {
            ColorMode.SOLID -> colorA.alpha(a)
            ColorMode.GRADIENT -> {
                val s = smoothstep(u)
                Color4b(
                    lerp(colorA.r.toFloat(), colorB.r.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(colorA.g.toFloat(), colorB.g.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(colorA.b.toFloat(), colorB.b.toFloat(), s).toInt().coerceIn(0, 255),
                    a,
                )
            }
            ColorMode.RAINBOW -> hsv(u * 0.85f + (time * rainbowSpeed).toFloat(), 0.85f, 1f, a)
            ColorMode.HEIGHT -> {
                val s = smoothstep(level.coerceIn(0f, 1f))
                Color4b(
                    lerp(colorA.r.toFloat(), colorB.r.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(colorA.g.toFloat(), colorB.g.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(colorA.b.toFloat(), colorB.b.toFloat(), s).toInt().coerceIn(0, 255),
                    a,
                )
            }
            ColorMode.PULSE_COLOR -> {
                val pulse = (sin(time * 2.5 * speed + u * 3).toFloat() * 0.5f + 0.5f)
                val s = smoothstep(pulse)
                Color4b(
                    lerp(colorA.r.toFloat(), colorB.r.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(colorA.g.toFloat(), colorB.g.toFloat(), s).toInt().coerceIn(0, 255),
                    lerp(colorA.b.toFloat(), colorB.b.toFloat(), s).toInt().coerceIn(0, 255),
                    a,
                )
            }
            ColorMode.RANDOM -> {
                // 每柱独立色相缓慢漂移
                val h = (u * 0.4f + phases[i] * 0.05f + (time * 0.15).toFloat() + i * 0.03f)
                hsv(h, 0.75f, 1f, a)
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (hideInGui && runCatching { mc.gui.screen() != null }.getOrDefault(false)) return@handler

        resizeBuffers()
        val n = bars.size
        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        time += dt * speed

        val react = reactivity.coerceIn(0.05f, 1f)
        val smooth = smoothness.coerceIn(0.02f, 0.9f)
        // 低 smooth → 更快跟上；高 → 更粘滞
        val follow = (1f - smooth.pow(0.65f)) * (0.4f + react * 0.8f)

        for (i in 0 until n) {
            val target = targetLevel(i, n, time).coerceIn(0f, 1f)
            bars[i] = lerp(bars[i], target, (dt * 14f * follow).coerceIn(0.02f, 1f))
            if (peakHold) {
                if (bars[i] >= peaks[i]) {
                    peaks[i] = bars[i]
                } else {
                    peaks[i] = (peaks[i] - dt * peakDecay * (0.4f + peaks[i])).coerceAtLeast(bars[i])
                }
            } else {
                peaks[i] = bars[i]
            }
        }

        // 镜像时只算一半再对称（视觉更像音响）
        if (mirror && n >= 4) {
            val half = n / 2
            for (i in 0 until half) {
                val j = n - 1 - i
                val v = max(bars[i], bars[j])
                bars[i] = v
                bars[j] = v
                val pk = max(peaks[i], peaks[j])
                peaks[i] = pk
                peaks[j] = pk
            }
        }

        val ctx = event.context
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        val gap = barGap
        val bw = barWidth
        val totalW = n * bw + (n - 1) * gap
        val avail = (sw - sideMargin * 2f).coerceAtLeast(8f)
        val scaleX = if (totalW > avail) avail / totalW else 1f
        val drawBw = bw * scaleX
        val drawGap = gap * scaleX
        val drawTotal = n * drawBw + (n - 1) * drawGap
        val startX = if (centerAlign) {
            (sw - drawTotal) * 0.5f
        } else {
            sideMargin
        }
        val baseY = sh - bottomMargin
        val a = (255 * alpha).toInt().coerceIn(20, 255)
        val rad = barRadius.coerceAtMost(drawBw * 0.5f)

        if (baseLine) {
            val la = (a * baseLineAlpha).toInt().coerceIn(0, 255)
            ctx.drawQuad(
                startX - 4f, baseY, startX + drawTotal + 4f, baseY + 1.5f,
                Color4b(255, 255, 255, la),
            )
        }

        for (i in 0 until n) {
            val level = bars[i].coerceIn(0f, 1f)
            val h = minHeight + (maxHeight - minHeight) * level
            val x1 = startX + i * (drawBw + drawGap)
            val x2 = x1 + drawBw
            val y1 = baseY - h
            val y2 = baseY
            val col = barColor(i, n, level, a)

            if (glow && glowStrength > 0.02f) {
                val layers = glowLayers.coerceIn(1, 10)
                for (L in layers downTo 1) {
                    val u = L / layers.toFloat()
                    val expand = 2f + 5f * u * glowStrength
                    val ga = (col.a * (1f - u) * 0.35f * glowStrength).toInt().coerceIn(0, 120)
                    if (ga < 2) continue
                    val gc = Color4b(col.r, col.g, col.b, ga)
                    if (rad > 0.4f) {
                        ctx.drawRoundedRect(
                            x1 - expand * 0.3f, y1 - expand * 0.5f,
                            x2 + expand * 0.3f, y2 + expand * 0.2f,
                            rad + expand * 0.2f, gc,
                        )
                    } else {
                        ctx.drawQuad(
                            x1 - expand * 0.25f, y1 - expand * 0.4f,
                            x2 + expand * 0.25f, y2, gc,
                        )
                    }
                }
            }

            if (rad > 0.4f) {
                ctx.drawRoundedRect(x1, y1, x2, y2, rad, col)
            } else {
                ctx.drawQuad(x1, y1, x2, y2, col)
            }

            if (peakHold) {
                val ph = minHeight + (maxHeight - minHeight) * peaks[i].coerceIn(0f, 1f)
                val py = baseY - ph
                val pt = peakThickness.coerceAtLeast(1f)
                val pc = peakColor.alpha((peakColor.a * alpha).toInt().coerceIn(0, 255))
                ctx.drawQuad(x1, py - pt, x2, py, pc)
            }
        }
    }

    override fun onDisabled() {
        lastNs = 0L
        bars.fill(0f)
        peaks.fill(0f)
    }
}
