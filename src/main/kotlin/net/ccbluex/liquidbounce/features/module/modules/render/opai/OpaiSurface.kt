/*
 * OpaiSurface / OpaiCanvas / OpaiIcons
 * —— Samsara OpaiSurface 契约、NanoVG 后端到 GuiGraphicsExtractor 的等价实现、24x24 图标路径。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/opai/OpaiSurface.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.opai

import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawLines
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.HorizontalAnchor
import net.ccbluex.liquidbounce.render.engine.font.VerticalAnchor
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface OpaiSurface {
    fun rect(x: Float, y: Float, width: Float, height: Float, color: Int)
    fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int)
    fun panel(x: Float, y: Float, width: Float, height: Float, header: Boolean, color: Int)
    fun text(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int)
    fun textWidth(text: String, size: Float): Float
    fun clip(x: Float, y: Float, width: Float, height: Float, content: () -> Unit)
    fun opacity(factor: Float, content: () -> Unit)
    fun icon(icon: OpaiIcons.Icon, x: Float, y: Float, size: Float, color: Int)
    fun scale(factor: Float, centerX: Float, centerY: Float, content: () -> Unit)
    fun ripple(
        x: Float, y: Float, width: Float, height: Float, corner: Float,
        centerX: Float, centerY: Float, radius: Float, color: Int,
    )

    companion object {
        fun mix(from: Int, to: Int, amount: Float): Int {
            var result = 0
            var shift = 0
            while (shift <= 24) {
                val a = (from ushr shift) and 255
                val b = (to ushr shift) and 255
                result = result or ((a + (b - a) * amount.coerceIn(0f, 1f)).roundToInt() shl shift)
                shift += 8
            }
            return result
        }

        fun alpha(color: Int, factor: Float): Int {
            val a = (((color ushr 24) and 255) * factor.coerceIn(0f, 1f)).roundToInt()
            return (a shl 24) or (color and 0xFFFFFF)
        }
    }
}

/**
 * GuiGraphicsExtractor 后端。与 NanoVG 版逐项对应：
 * - 绘制坐标、尺寸、颜色原样传入；
 * - opacity 等价 nvgGlobalAlpha（颜色 alpha 乘算，作用于其后全部绘制）；
 * - scale 等价 nvgTranslate/Scale/Translate 组合；
 * - 文本用 LiquidBounce FontRenderer，按 size / renderer.height 缩放，START + MIDDLE 锚点。
 */
class OpaiCanvas(
    private val gfx: GuiGraphicsExtractor,
    private val globalAlpha: Float,
) : OpaiSurface {

    private var localOpacity = 1f

    private fun effective(color: Int): Int = OpaiSurface.alpha(color, globalAlpha * localOpacity)

    override fun rect(x: Float, y: Float, width: Float, height: Float, color: Int) {
        if (width <= 0 || height <= 0) return
        gfx.drawRoundedRect(x, y, x + width, y + height, 0f, Color4b(effective(color)))
    }

    override fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) {
        if (width <= 0 || height <= 0) return
        gfx.drawRoundedRect(x, y, x + width, y + height, radius, Color4b(effective(color)))
    }

    /** header=true: 上圆角下直角；header=false: 上直角下圆角（对应 roundedRectVarying）。 */
    override fun panel(x: Float, y: Float, width: Float, height: Float, header: Boolean, color: Int) {
        if (width <= 0 || height <= 0) return
        val r = OpaiLayout.RADIUS
        val tint = Color4b(effective(color))
        gfx.drawRoundedRect(x, y, x + width, y + height, r, tint)
        if (height > r) {
            if (header) gfx.drawRoundedRect(x, y + height - r, x + width, y + height, 0f, tint)
            else gfx.drawRoundedRect(x, y, x + width, y + r, 0f, tint)
        }
    }

    private fun scaleFor(size: Float): Float = size / FontManager.FONT_RENDERER.height

    override fun textWidth(text: String, size: Float): Float {
        val renderer = FontManager.FONT_RENDERER
        return renderer.getStringWidth(text.asPlainText(), scaleFor(size), false) + OpaiStyle.TEXT_WEIGHT_OFFSET
    }

    override fun text(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int) {
        if (maximum <= 0 || text.isEmpty()) return
        val label = fitLabel(text, size, maximum)
        if (label.isEmpty()) return
        val renderer = FontManager.FONT_RENDERER
        val scale = scaleFor(size)
        val tint = Color4b(effective(color))
        renderer.draw(
            gfx, label.asPlainText(), x, centerY, tint, false, scale,
            HorizontalAnchor.START, VerticalAnchor.MIDDLE,
        )
        // 原实现把同一文本偏移 TEXT_WEIGHT_OFFSET 再画一遍，模拟 medium 字重。
        renderer.draw(
            gfx, label.asPlainText(), x + OpaiStyle.TEXT_WEIGHT_OFFSET, centerY, tint, false, scale,
            HorizontalAnchor.START, VerticalAnchor.MIDDLE,
        )
    }

    private fun fitLabel(text: String, size: Float, maximum: Float): String {
        if (textWidth(text, size) <= maximum) return text
        if (textWidth("...", size) > maximum) return ""
        var label = text
        while (label.isNotEmpty() && textWidth(label + "...", size) > maximum) {
            label = label.substring(0, label.length - 1)
        }
        return label + "..."
    }

    override fun clip(x: Float, y: Float, width: Float, height: Float, content: () -> Unit) {
        // MC 26.3: enableScissor(x0,y0,x1,y1) builds ScreenRectangle(w=x1-x0,h=y1-y0);
        // zero/negative size throws IllegalArgumentException and crashes the frame.
        if (!(width > 0.5f) || !(height > 0.5f)) return
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return
        val left = kotlin.math.floor(x.toDouble()).toInt()
        val top = kotlin.math.floor(y.toDouble()).toInt()
        val right = kotlin.math.ceil((x + width).toDouble()).toInt()
        val bottom = kotlin.math.ceil((y + height).toDouble()).toInt()
        if (right <= left || bottom <= top) return
        gfx.enableScissor(left, top, right, bottom)
        try {
            content()
        } finally {
            gfx.disableScissor()
        }
    }

    override fun opacity(factor: Float, content: () -> Unit) {
        val previous = localOpacity
        localOpacity *= factor
        try {
            content()
        } finally {
            localOpacity = previous
        }
    }

    override fun scale(factor: Float, centerX: Float, centerY: Float, content: () -> Unit) {
        gfx.pose().withPush {
            translate(centerX, centerY)
            scale(factor, factor)
            translate(-centerX, -centerY)
            content()
        }
    }

    override fun icon(icon: OpaiIcons.Icon, x: Float, y: Float, size: Float, color: Int) {
        OpaiIcons.draw(this, icon, x, y, size, color)
    }

    /** nvgRadialGradient(ink -> clear) 的等价近似：由外向内叠同心圆。 */
    override fun ripple(
        x: Float, y: Float, width: Float, height: Float, corner: Float,
        centerX: Float, centerY: Float, radius: Float, color: Int,
    ) {
        if (width <= 0 || height <= 0 || radius <= 0) return
        val tint = effective(color)
        val baseAlpha = (tint ushr 24) and 255
        if (baseAlpha <= 0) return
        val steps = 10
        clip(x, y, width, height) {
            for (i in 0 until steps) {
                val t = (i + 1f) / steps
                val r = radius * t
                val a = (baseAlpha * t / steps).roundToInt()
                if (a <= 0) continue
                val stepColor = (min(255, a) shl 24) or (tint and 0xFFFFFF)
                gfx.drawRoundedRect(
                    centerX - r, centerY - r, centerX + r, centerY + r,
                    corner.coerceAtMost(r), Color4b(stepColor),
                )
            }
        }
    }

    fun check(x: Float, y: Float, size: Float, color: Int) {
        val tint = effective(color)
        val points = floatArrayOf(
            x, y + size * 0.5f,
            x + size * 0.38f, y + size * 0.88f,
            x + size, y,
        )
        gfx.drawLines(points, tint, gfx.getBoundsXYWH(x - 1, y - 1, size + 2, size + 2), true)
    }
}

/** Samsara 在 24x24 局部坐标用 NanoVG 路径绘制图标，这里用等价图元重建同一轮廓。 */
object OpaiIcons {

    enum class Icon { PLUS, GEAR, BACK, REFRESH, SAVE, LOAD, DELETE, FOLDER, UPLOAD, CHECK }

    fun draw(surface: OpaiSurface, icon: Icon, x: Float, y: Float, size: Float, color: Int) {
        val s = size / 24f

        fun px(v: Float) = x + v * s
        fun py(v: Float) = y + v * s
        fun box(l: Float, t: Float, w: Float, h: Float, r: Float) =
            surface.rounded(px(l), py(t), w * s, h * s, r * s, color)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float) {
            val lx = min(x1, x2)
            val ly = min(y1, y2)
            val w = abs(x2 - x1)
            val h = abs(y2 - y1)
            if (w >= h) box(lx, ly - thickness / 2, max(w, thickness), thickness, thickness / 2)
            else box(lx - thickness / 2, ly, thickness, max(h, thickness), thickness / 2)
        }

        when (icon) {
            Icon.CHECK -> {
                line(2f, 13f, 9f, 21f, 1.3f)
                line(9f, 21f, 23f, 2f, 1.3f)
            }
            Icon.PLUS -> {
                line(12f, 2f, 12f, 22f, 2.6f)
                line(2f, 12f, 22f, 12f, 2.6f)
            }
            Icon.BACK -> {
                line(15f, 3f, 6f, 12f, 2.6f)
                line(6f, 12f, 15f, 21f, 2.6f)
            }
            Icon.GEAR -> {
                for (i in 0 until 32) {
                    val angle = i * Math.PI / 16
                    val radius = if (i % 4 == 0 || i % 4 == 3) 11f else 8.7f
                    val cx = 12 + (Math.cos(angle) * radius).toFloat()
                    val cy = 12 + (Math.sin(angle) * radius).toFloat()
                    box(cx - 1.3f, cy - 1.3f, 2.6f, 2.6f, 1.3f)
                }
                box(8f, 8f, 8f, 8f, 4f)
            }
            Icon.REFRESH -> {
                for (i in 0 until 18) {
                    val a0 = -2.75f + i * (2.4f / 18f)
                    val a1 = -2.75f + (i + 1) * (2.4f / 18f)
                    line(
                        12 + (Math.cos(a0.toDouble()) * 8).toFloat(), 12 + (Math.sin(a0.toDouble()) * 8).toFloat(),
                        12 + (Math.cos(a1.toDouble()) * 8).toFloat(), 12 + (Math.sin(a1.toDouble()) * 8).toFloat(),
                        2.6f,
                    )
                }
                for (i in 0 until 18) {
                    val a0 = .39f + i * (2.4f / 18f)
                    val a1 = .39f + (i + 1) * (2.4f / 18f)
                    line(
                        12 + (Math.cos(a0.toDouble()) * 8).toFloat(), 12 + (Math.sin(a0.toDouble()) * 8).toFloat(),
                        12 + (Math.cos(a1.toDouble()) * 8).toFloat(), 12 + (Math.sin(a1.toDouble()) * 8).toFloat(),
                        2.6f,
                    )
                }
                line(20f, 10f, 14.5f, 10f, 2.6f)
                line(14.5f, 10f, 20f, 4.5f, 2.6f)
                line(20f, 4.5f, 20f, 10f, 2.6f)
                line(4f, 14f, 9.5f, 14f, 2.6f)
                line(9.5f, 14f, 4f, 19.5f, 2.6f)
                line(4f, 19.5f, 4f, 14f, 2.6f)
            }
            Icon.SAVE -> {
                box(3f, 2f, 19f, 20f, 1.5f)
                box(7f, 3f, 9f, 6f, 0.5f)
                box(7f, 13f, 11f, 7f, 1f)
            }
            Icon.LOAD -> {
                box(4f, 3f, 16f, 19f, 2f)
                box(7f, 1f, 10f, 4f, 1f)
                line(12f, 8f, 12f, 17f, 1.8f)
                line(8f, 13f, 12f, 17f, 1.8f)
                line(16f, 13f, 12f, 17f, 1.8f)
            }
            Icon.DELETE -> {
                box(6f, 7f, 12f, 15f, 0.5f)
                line(4f, 4f, 20f, 4f, 2.6f)
                line(9f, 1f, 15f, 1f, 2.6f)
            }
            Icon.FOLDER -> {
                box(2f, 5f, 8f, 3f, 0.5f)
                line(10f, 5f, 12f, 8f, 1.2f)
                box(2f, 8f, 20f, 13f, 0.5f)
            }
            Icon.UPLOAD -> {
                line(6f, 12f, 18f, 5f, 2.6f)
                line(6f, 12f, 18f, 19f, 2.6f)
                box(1.5f, 8.5f, 7f, 7f, 3.5f)
                box(15.5f, 0.5f, 7f, 7f, 3.5f)
                box(15.5f, 16.5f, 7f, 7f, 3.5f)
            }
        }
    }
}