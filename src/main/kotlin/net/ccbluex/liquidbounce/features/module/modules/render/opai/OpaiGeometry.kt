/*
 * OpaiLayout / OpaiContentLayout / OpaiMotion / OpaiInteraction / OpaiFeedback
 * —— Samsara opai 几何、布局、动效、交互、点击反馈的 1:1 移植。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/opai/OpaiGeometry.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.opai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/* ============================== OpaiLayout ============================== */

/** Geometry shared by the renderer, fallback renderer and hit testing. */
object OpaiLayout {
    const val COL_W = 152.0f
    const val COL_GAP = 8.0f
    const val HEADER_H = 21.0f
    const val ROW_H = 19.6f
    const val FOOTER_H = 7.0f
    const val RADIUS = 7.0f
    const val TEXT_PAD = 10.0f

    data class Viewport(
        val columnWidth: Float,
        val left: Float,
        val top: Float,
        val maxBodyHeight: Float,
    )

    fun viewport(width: Int, height: Int, columns: Int): Viewport {
        val margin = min(30.0f, width * 0.0382f)
        val colW = min(COL_W, width - margin * 2)
        val top = max(8.0f, height * 0.112f)
        val maxBody = max(ROW_H, height - top - HEADER_H - max(8.0f, height * 0.10f))
        return Viewport(colW, margin, top, min(16.0f * ROW_H + FOOTER_H, maxBody))
    }

    fun horizontalOverflow(width: Int, columns: Int, layout: Viewport): Float =
        max(0.0f, layout.left * 2 + columns * layout.columnWidth + (columns - 1) * COL_GAP - width)

    fun bodyHeight(rows: Int, maxHeight: Float): Float = min(maxHeight, rows * ROW_H + FOOTER_H)

    fun maxScroll(rows: Int, bodyHeight: Float): Float = max(0.0f, rows * ROW_H - (bodyHeight - FOOTER_H))

    fun rowIndex(localY: Double, scroll: Float, bodyHeight: Float, rows: Int): Int {
        if (localY < 0.0 || localY >= bodyHeight - FOOTER_H) return -1
        val index = floor((localY + scroll) / ROW_H).toInt()
        return if (index in 0 until rows) index else -1
    }

    fun displayName(name: String): String = name
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "\$1 \$2")
        .replace(Regex("([a-z0-9])([A-Z])"), "\$1 \$2")
}

/* ============================== OpaiMotion ============================== */

/** Exponential approach with the original rate semantics (per second). */
class OpaiMotion(initial: Float, private val rate: Double) {

    companion object {
        fun now(): Double = System.nanoTime() / 1_000_000.0

        fun openingScale(elapsedMs: Double): Float {
            val seconds = max(0.0, elapsedMs) / 1000
            return if (seconds >= .32) 1f
            else (1 + .18 * exp(-17 * seconds) * kotlin.math.cos(23 * seconds)).toFloat()
        }
    }

    private var time = Double.NaN
    private var value = initial
    private var destination = initial

    fun approach(target: Float, now: Double): Float {
        if (!time.isNaN()) {
            val dt = max(0.0, now - time) / 1000
            value = (destination + (value - destination) * exp(-rate * dt)).toFloat()
            if (abs(value - destination) < .0005f) value = destination
        }
        time = now
        destination = target
        return value
    }

    fun value(): Float = value

    fun snap(newValue: Float, now: Double) {
        value = newValue
        destination = newValue
        time = now
    }
}

/* ============================ OpaiInteraction ============================ */

object OpaiInteraction {
    enum class Action { NONE, DRAG, EXPAND, TOGGLE, SLIDE, SELECT, BIND }

    fun header(button: Int): Action =
        if (button == 0) Action.DRAG else if (button == 1) Action.EXPAND else Action.NONE

    fun content(kind: OpaiContentLayout.Kind, button: Int): Action = when (kind) {
        OpaiContentLayout.Kind.MODULE ->
            if (button == 0) Action.TOGGLE else if (button == 1) Action.EXPAND else if (button == 2) Action.BIND else Action.NONE
        OpaiContentLayout.Kind.MODE -> if (button == 0) Action.EXPAND else Action.NONE
        OpaiContentLayout.Kind.BOOLEAN -> if (button == 0) Action.TOGGLE else Action.NONE
        OpaiContentLayout.Kind.NUMBER -> if (button == 0) Action.SLIDE else Action.NONE
        OpaiContentLayout.Kind.OPTION -> if (button == 0) Action.SELECT else Action.NONE
        OpaiContentLayout.Kind.PADDING -> Action.NONE
    }
}

/* ============================= OpaiFeedback ============================= */

/** Click-origin ink and short press feedback, independent of the value transition. */
class OpaiFeedback {

    private companion object {
        const val DURATION = 420.0
    }

    private data class Pulse(val x: Float, val y: Float, val started: Double)

    private val pulses = ArrayDeque<Pulse>()
    private val hover = OpaiMotion(0f, 20.0)
    private var pressedAt = Double.NEGATIVE_INFINITY

    fun click(localX: Float, localY: Float, width: Float, height: Float, now: Double) {
        pressedAt = now
        while (pulses.size >= 3) pulses.removeFirst()
        pulses.addLast(
            Pulse(
                (localX / width).coerceIn(0f, 1f),
                (localY / height).coerceIn(0f, 1f),
                now,
            )
        )
    }

    fun hover(hovered: Boolean, now: Double): Float = hover.approach(if (hovered) 1f else 0f, now)

    fun press(now: Double): Float {
        val age = now - pressedAt
        return if (age < 0 || age >= 220) 0f
        else (sin(Math.PI * age / 220) * exp(-age / 220)).toFloat()
    }

    fun paint(
        surface: OpaiSurface,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        corner: Float,
        color: Int,
        now: Double,
    ) {
        pulses.removeAll { now - it.started >= DURATION }
        for (pulse in pulses) {
            val t = ((now - pulse.started) / DURATION).coerceIn(0.0, 1.0).toFloat()
            val cx = pulse.x * w
            val cy = pulse.y * h
            val maximum = hypot(max(cx, w - cx).toDouble(), max(cy, h - cy).toDouble()).toFloat()
            val growth = 1 - (1 - min(1f, t / .75f)).toDouble().pow(3).toFloat()
            val alpha = (((color ushr 24) and 255) * .22f * (1 - t) * (1 - t)).toInt()
            surface.ripple(
                x, y, w, h, corner,
                x + cx, y + cy, max(.5f, maximum * growth),
                (color and 0xFFFFFF) or (alpha shl 24),
            )
        }
    }
}

/* =========================== OpaiContentLayout =========================== */

/** One content coordinate system for rendering, scrolling and input. */
class OpaiContentLayout<T> {

    companion object {
        const val BOOLEAN_H = 19.6f
        const val NUMBER_H = 20.0f
        const val MODE_H = 40.0f
        const val OPTION_H = 20.0f
        const val PADDING_H = 0.0f
        const val FIELD_Y = 16.0f
        const val FIELD_H = 21.0f
        const val SLIDER_Y = 15.6f

        fun field(x: Float, y: Float, width: Float): Rect =
            Rect(x + OpaiLayout.TEXT_PAD - 2, y + FIELD_Y, width - OpaiLayout.TEXT_PAD * 2 + 4, FIELD_H)

        fun sliderHit(x: Float, y: Float, width: Float): Rect =
            Rect(x + OpaiLayout.TEXT_PAD - 3, y + SLIDER_Y - 6, width - OpaiLayout.TEXT_PAD * 2 + 6, 12f)

        fun sliderFraction(mouseX: Double, columnX: Float, columnWidth: Float): Double =
            ((mouseX - columnX - OpaiLayout.TEXT_PAD) / max(1f, columnWidth - OpaiLayout.TEXT_PAD * 2)).coerceIn(0.0, 1.0)

        fun scrollbar(bodyHeight: Float, contentHeight: Float, scroll: Float): Scrollbar {
            val visible = max(0f, bodyHeight - OpaiLayout.FOOTER_H)
            val track = max(0f, visible - 4)
            val thumb = min(track, max(12f, track * visible / max(1f, contentHeight)))
            val travel = track - thumb
            val maximum = max(0f, contentHeight - visible)
            return Scrollbar(2 + (if (maximum == 0f) 0f else travel * (scroll / maximum).coerceIn(0f, 1f)), thumb, travel)
        }

        fun scrollFromThumb(thumbTop: Float, bodyHeight: Float, contentHeight: Float): Float {
            val bar = scrollbar(bodyHeight, contentHeight, 0f)
            val maximum = max(0f, contentHeight - (bodyHeight - OpaiLayout.FOOTER_H))
            return if (bar.travel == 0f) 0f else ((thumbTop - 2) / bar.travel).coerceIn(0f, 1f) * maximum
        }
    }

    enum class Kind { MODULE, BOOLEAN, NUMBER, MODE, OPTION, PADDING }

    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
    }

    data class Scrollbar(val top: Float, val height: Float, val travel: Float)

    data class Entry<T>(
        val target: T,
        val kind: Kind,
        val top: Float,
        val height: Float,
        val option: Int,
        val clipTop: Float,
        val clipBottom: Float,
    ) {
        fun visibleTop(): Float = max(top, clipTop)
        fun visibleBottom(): Float = min(top + height, clipBottom)
        fun visible(): Boolean = visibleBottom() > visibleTop()
    }

    private val entries = ArrayList<Entry<T>>()
    private var height = 0f

    fun add(target: T, kind: Kind) = add(target, kind, 1.0f)

    fun add(target: T, kind: Kind, scale: Float) {
        val entryHeight = when (kind) {
            Kind.MODULE -> OpaiLayout.ROW_H
            Kind.BOOLEAN -> BOOLEAN_H
            Kind.NUMBER -> NUMBER_H
            Kind.MODE -> MODE_H
            Kind.OPTION -> OPTION_H
            Kind.PADDING -> PADDING_H
        } * scale
        append(target, kind, entryHeight, -1)
    }

    fun addOptions(target: T, count: Int) = addOptions(target, count, 1.0f)

    fun addOptions(target: T, count: Int, scale: Float) {
        for (i in 0 until count) append(target, Kind.OPTION, OPTION_H * scale, i)
    }

    private fun append(target: T, kind: Kind, entryHeight: Float, option: Int) {
        entries.add(Entry(target, kind, height, entryHeight, option, height, height + entryHeight))
        height += entryHeight
    }

    /** Reveal an intact section through one animated clip, rather than squashing each control. */
    fun reveal(section: OpaiContentLayout<T>, progress: Float) {
        val occupied = section.height * progress.coerceIn(0f, 1f)
        if (occupied <= 0) return
        val start = height
        for (item in section.entries) {
            entries.add(
                Entry(
                    item.target, item.kind, start + item.top, item.height, item.option,
                    start + item.clipTop, min(start + item.clipBottom, start + occupied),
                )
            )
        }
        height += occupied
    }

    fun entries(): List<Entry<T>> = entries

    fun height(): Float = height

    fun bodyHeight(maximum: Float): Float = min(maximum, height + OpaiLayout.FOOTER_H)

    fun maxScroll(bodyHeight: Float): Float = max(0f, height - (bodyHeight - OpaiLayout.FOOTER_H))

    fun hit(localY: Double, scroll: Float, bodyHeight: Float): Entry<T>? {
        if (localY < 0 || localY >= bodyHeight - OpaiLayout.FOOTER_H) return null
        val contentY = localY + scroll
        for (entry in entries) {
            if (contentY >= entry.visibleTop() && contentY < entry.visibleBottom()) {
                return if (entry.kind == Kind.PADDING) null else entry
            }
        }
        return null
    }
}
