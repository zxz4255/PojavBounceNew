/*
 * LiquidBounce - A free and open-source Minecraft client
 *
 * DynamicIsland: port of the Samsara client's Dynamic Island (com.samsara.ui.dynamicIsland:
 * DynamicIslandManager / State / Status / Painter / NanoSurface) as a standalone render module.
 *
 * Layout constants, the damped spring, the notice queue, the idle status bar, the progress panels,
 * the layered shadow and the vector icons follow the original. NanoVG, the two Samsara fonts and
 * the per-region backdrop blur do not exist in LiquidBounce; see the notes next to each
 * replacement and the accompanying port notes.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.BlockCountChangeEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.world.fucker.ModuleFucker
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.VerticalAnchor
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.render.BreakingProgress
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================================
// State machine (port of DynamicIslandState)
// ============================================================================================

internal object IslandMetrics {
    const val TOP = 15f
    const val IDLE_TOP = 21f
    const val IDLE_HEIGHT = 23f
    const val ROW_HEIGHT = 36f
    const val SCAFFOLD_HEIGHT = 50f
    const val BREAKING_HEIGHT = 48f
    const val BREAKING_TITLE_FONT = 9f
    const val BREAKING_DETAIL_FONT = 8f
    const val SCAFFOLD_TEXT_X = 36f
    const val SCAFFOLD_RIGHT_PADDING = 8f
    const val RADIUS = 8.5f
    const val TITLE_FONT = 10f
    const val DETAIL_FONT = 9f
    const val NOTICE_TEXT_X = 36f
    const val NOTICE_RIGHT_PADDING = 5.5f
    const val MAX_NOTICES = 3
    const val FADE_MS = 200L
    const val TOGGLE_MS = 800L
    const val MORPH_DECAY = 8.7
    const val MORPH_FREQUENCY = 8.0
}

internal enum class IslandIcon { TOGGLE, SUCCESS, WARNING, INFO, SCAFFOLD, BREAKING }

internal fun interface IslandTextWidth {
    fun measure(text: String, size: Float): Float

    fun measureBreaking(text: String, size: Float): Float = measure(text, size)
}

internal data class IslandRow(
    val title: String,
    val detail: String,
    val status: String,
    val icon: IslandIcon,
    val enabled: Boolean,
    val toggle: Float,
    val progress: Float,
    val y: Float,
    val height: Float,
    val opacity: Float,
)

internal data class IslandFrame(
    val width: Float,
    val height: Float,
    val idleOpacity: Float,
    val idle: IslandStatus.Layout,
    val rows: List<IslandRow>,
    val contentWidth: Float,
    val top: Float,
    val radius: Float,
)

internal data class IslandDetailLine(val prefix: String, val status: String, val statusOffset: Float)

internal data class IslandPanel(val width: Float, val height: Float, val top: Float, val radius: Float)

internal class IslandState {
    private val notices = ArrayList<Notice>()
    private val width = Morph()
    private val height = Morph()
    private val position = Morph()
    private var wasIdle = true
    private var wasPanel = false

    @Synchronized
    fun post(
        key: String, title: String, detail: String, status: String, icon: IslandIcon,
        enabled: Boolean, now: Long, duration: Long,
    ) {
        post(key, title, detail, status, icon, enabled, 0f, now, duration)
    }

    @Synchronized
    fun postScaffold(detail: String, progress: Float, now: Long) {
        notices.removeAll { it.key != "scaffold" }
        post("scaffold", "Scaffold Toggled", detail, "", IslandIcon.SCAFFOLD, true, progress.coerceIn(0f, 1f), now, 500)
    }

    @Synchronized
    fun postBreaking(blockName: String, progress: Float, now: Long) {
        val title = "Breaking $blockName"
        // A new defense block retargets the same shell, without carrying the old block's width.
        notices.removeAll { it.key != "bed-aura" || title != it.title }
        val clamped = progress.coerceIn(0f, 1f)
        post(
            "bed-aura", title, "Break Progress: " + (clamped * 100).toInt() + "%", "",
            IslandIcon.BREAKING, true, clamped, now, 500,
        )
    }

    @Synchronized
    fun remove(key: String) {
        notices.removeAll { it.key == key }
    }

    private fun post(
        key: String, title: String, detail: String, status: String, icon: IslandIcon,
        enabled: Boolean, progress: Float, now: Long, duration: Long,
    ) {
        notices.removeAll { now >= it.expiresAt }
        var notice = notices.firstOrNull { it.key == key }
        if (notice == null) {
            notice = Notice(key, now)
            notices.add(notice)
        }
        notice.title = title
        notice.detail = detail
        notice.status = status
        notice.icon = icon
        notice.enabled = enabled
        notice.progress = progress
        notice.expiresAt = now + duration
        notice.toggle.target(if (enabled) 1f else 0f, now)
        while (notices.size > IslandMetrics.MAX_NOTICES) {
            notices.removeAt(0)
        }
    }

    @Synchronized
    fun frame(
        now: Long, viewportWidth: Float, status: IslandStatus, measure: IslandTextWidth,
        reducedMotion: Boolean, panel: IslandPanel? = null,
    ): IslandFrame {
        val available = max(1f, viewportWidth - 16)
        val idle = status.layout(measure, available)
        val idleWidth = min(available, idle.width)
        val rows = ArrayList<IslandRow>()
        var occupied = 0f
        var largestWidth = 0f
        notices.removeAll { now >= it.expiresAt }
        for (notice in notices) {
            if (panel != null) break
            val progressPanel = notice.icon == IslandIcon.SCAFFOLD || notice.icon == IslandIcon.BREAKING
            val fullHeight = if (notice.icon == IslandIcon.BREAKING) IslandMetrics.BREAKING_HEIGHT
            else if (progressPanel) IslandMetrics.SCAFFOLD_HEIGHT else IslandMetrics.ROW_HEIGHT
            rows.add(
                IslandRow(
                    notice.title, notice.detail, notice.status, notice.icon, notice.enabled,
                    if (reducedMotion) (if (notice.enabled) 1f else 0f) else notice.toggle.value(now),
                    notice.progress, occupied, fullHeight, 1f,
                )
            )
            val textWidth = if (notice.icon == IslandIcon.BREAKING) {
                max(
                    measure.measureBreaking(notice.title, IslandMetrics.BREAKING_TITLE_FONT),
                    measure.measureBreaking("Break Progress: 100%", IslandMetrics.BREAKING_DETAIL_FONT),
                )
            } else {
                max(
                    measure.measure(notice.title, IslandMetrics.TITLE_FONT),
                    measure.measure(notice.detail, IslandMetrics.DETAIL_FONT) +
                        measure.measure(notice.status, IslandMetrics.DETAIL_FONT) +
                        (if (notice.status.isEmpty()) 0f else measure.measure("!", IslandMetrics.DETAIL_FONT)),
                )
            }
            // Mining reserves all three percentage digits before the first frame.
            val contentWidth = if (progressPanel) {
                IslandMetrics.SCAFFOLD_TEXT_X + textWidth + IslandMetrics.SCAFFOLD_RIGHT_PADDING
            } else {
                IslandMetrics.NOTICE_TEXT_X + textWidth + IslandMetrics.NOTICE_RIGHT_PADDING
            }
            notice.contentWidth = max(notice.contentWidth, contentWidth)
            largestWidth = max(largestWidth, min(available, notice.contentWidth))
            occupied += fullHeight
        }
        val isIdle = panel == null && rows.isEmpty()
        val targetWidth = if (panel != null) min(available, panel.width) else if (isIdle) idleWidth else largestWidth
        val targetHeight = if (panel != null) panel.height
        else occupied.coerceIn(IslandMetrics.IDLE_HEIGHT, IslandMetrics.MAX_NOTICES * IslandMetrics.ROW_HEIGHT)
        // Fixed row coordinates keep the title, status and punctuation together during every frame.
        val impulse = if (isIdle != wasIdle || (panel != null) != wasPanel) (if (isIdle) 5.5 else 7.25) else 0.0
        val animatedWidth = width.value(targetWidth, now, reducedMotion, impulse).coerceIn(1f, available)
        val animatedHeight = max(1f, height.value(targetHeight, now, reducedMotion, impulse))
        val scaffold = rows.any { it.icon == IslandIcon.SCAFFOLD }
        val top = position.value(
            if (panel != null) panel.top else if (scaffold) IslandMetrics.TOP else IslandMetrics.IDLE_TOP,
            now, reducedMotion, impulse,
        )
        // Round the shrinking shell into a capsule when returning to idle.
        val radius = if (isIdle) animatedHeight / 2 else if (panel != null) panel.radius else IslandMetrics.RADIUS
        wasIdle = isIdle
        wasPanel = panel != null
        return IslandFrame(
            animatedWidth, animatedHeight, if (isIdle) 1f else 0f, idle, rows.toList(), targetWidth, top, radius,
        )
    }

    @Synchronized
    fun clear() {
        notices.clear()
        width.clear()
        height.clear()
        position.clear()
        wasIdle = true
        wasPanel = false
    }

    /** Analytical damped spring: identical at any frame rate, with velocity retained on interruption. */
    private class Morph {
        private var current = 0.0
        private var target = 0.0
        private var velocity = 0.0
        private var sampled = -1L

        fun value(destination: Float, now: Long, reducedMotion: Boolean, impulse: Double): Float {
            if (sampled < 0 || reducedMotion) {
                current = destination.toDouble()
                target = current
                velocity = 0.0
            } else {
                val seconds = max(0L, now - sampled) / 1000.0
                val displacement = current - target
                val sineCoefficient = (velocity + IslandMetrics.MORPH_DECAY * displacement) / IslandMetrics.MORPH_FREQUENCY
                val cosine = cos(IslandMetrics.MORPH_FREQUENCY * seconds)
                val sine = sin(IslandMetrics.MORPH_FREQUENCY * seconds)
                val decay = exp(-IslandMetrics.MORPH_DECAY * seconds)
                val offset = displacement * cosine + sineCoefficient * sine
                current = target + decay * offset
                velocity = decay * (-IslandMetrics.MORPH_DECAY * offset +
                    IslandMetrics.MORPH_FREQUENCY * (-displacement * sine + sineCoefficient * cosine))
                if (destination.toDouble() != target) {
                    target = destination.toDouble()
                    // Start promptly from rest; a reversal retains the spring's actual velocity.
                    if (abs(velocity) < max(.1, abs(destination - current) * .1)) {
                        velocity = (destination - current) * impulse
                    }
                }
                if (abs(current - target) < .01 && abs(velocity) < .1) {
                    current = target
                    velocity = 0.0
                }
            }
            sampled = now
            return current.toFloat()
        }

        fun clear() {
            sampled = -1
            current = 0.0
            target = 0.0
            velocity = 0.0
        }
    }

    private class Notice(val key: String, now: Long) {
        val toggle = Transition(now)
        var title = ""
        var detail = ""
        var status = ""
        var icon = IslandIcon.INFO
        var enabled = false
        var progress = 0f
        var contentWidth = 0f
        var expiresAt = 0L
    }

    private class Transition(now: Long) {
        var from = 0f
        var to = 0f
        var started = now

        fun target(destination: Float, now: Long) {
            if (destination != to) {
                from = value(now)
                to = destination
                started = now
            }
        }

        fun value(now: Long): Float {
            val progress = ((now - started) / IslandMetrics.FADE_MS.toFloat()).coerceIn(0f, 1f)
            val eased = if (to < from) progress
            else if (progress >= 1) 1f else 1 - Math.pow(2.0, (-10 * progress).toDouble()).toFloat()
            return from + (to - from) * eased
        }
    }

    companion object {
        fun fit(text: String, size: Float, width: Float, measure: IslandTextWidth): String {
            if (width <= 0) {
                return ""
            }
            // Adding/subtracting the insets can lose a few float ULPs. Do not ellipsize a fitting line.
            if (measure.measure(text, size) <= width + .01f) {
                return text
            }
            if (measure.measure("...", size) > width) {
                return ""
            }
            var end = text.length
            while (end > 0 && measure.measure(text.substring(0, end) + "...", size) > width) {
                end = text.offsetByCodePoints(end, -1)
            }
            return text.substring(0, end) + "..."
        }

        fun detailLine(detail: String, status: String, width: Float, measure: IslandTextWidth): IslandDetailLine {
            val suffix = fit(status, IslandMetrics.DETAIL_FONT, width, measure)
            val remaining = max(0f, width - measure.measure(suffix, IslandMetrics.DETAIL_FONT))
            val truncated = measure.measure(detail, IslandMetrics.DETAIL_FONT) > remaining + .01f
            val gap = if (truncated && suffix.isNotEmpty()) measure.measure(" ", IslandMetrics.DETAIL_FONT) else 0f
            var prefix = fit(detail, IslandMetrics.DETAIL_FONT, max(0f, remaining - gap), measure)
            if (prefix.isNotEmpty() && gap > 0) {
                prefix += " "
            }
            return IslandDetailLine(prefix, suffix, measure.measure(prefix, IslandMetrics.DETAIL_FONT))
        }
    }
}

// ============================================================================================
// Idle status layout (port of DynamicIslandStatus)
// ============================================================================================

internal data class IslandStatus(val username: String, val server: String?, val ping: Int, val fps: Int) {

    enum class Symbol { CHROME, USER, LINK, REFRESH, BED }

    data class Part(val text: String, val icon: Symbol?, val color: Int, val x: Float, val width: Float)

    data class Layout(val parts: List<Part>, val width: Float)

    fun singleplayer(): Boolean = server == null

    fun layout(measure: IslandTextWidth, available: Float): Layout {
        val parts = ArrayList<Part>()
        // Shorten only genuinely oversized names/addresses, never because the shell is animating.
        val fixed = INSET + RIGHT_INSET + 4 * (ICON_SIZE + ICON_GAP) +
            3 * (2 + measure.measure("  \u00B7  ", FONT_SIZE)) + measure.measure("Opai", FONT_SIZE) +
            measure.measure(max(0, fps).toString() + " fps", FONT_SIZE) +
            (if (singleplayer()) 0f else measure.measure(pingText() + " to ", FONT_SIZE))
        var connection = if (singleplayer()) "singleplayer" else server!!
        val remaining = max(0f, available - fixed)
        val userWidth = measure.measure(username, FONT_SIZE)
        val connectionWidth = measure.measure(connection, FONT_SIZE)
        val userBudget = if (userWidth + connectionWidth <= remaining) userWidth
        else min(userWidth, max(remaining - connectionWidth, remaining * .4f))
        val user = IslandState.fit(username, FONT_SIZE, userBudget, measure)
        connection = IslandState.fit(
            connection, FONT_SIZE,
            max(0f, remaining - measure.measure(user, FONT_SIZE)), measure,
        )
        var x = INSET
        x = icon(parts, Symbol.CHROME, LAVENDER, x)
        x = label(parts, "Opai", LAVENDER, x, measure)
        x = separator(parts, x, measure)
        x = icon(parts, Symbol.USER, FOREGROUND, x)
        x = label(parts, user, FOREGROUND, x, measure)
        x = separator(parts, x, measure)
        x = icon(parts, Symbol.LINK, if (singleplayer()) FOREGROUND else pingColor(), x)
        if (!singleplayer()) {
            x = label(parts, pingText(), pingColor(), x, measure)
            x = label(parts, " to ", FOREGROUND, x, measure)
        }
        x = label(parts, connection, FOREGROUND, x, measure)
        x = separator(parts, x, measure)
        x = icon(parts, Symbol.REFRESH, FOREGROUND, x)
        x = label(parts, max(0, fps).toString() + " fps", FOREGROUND, x, measure)
        return Layout(parts.toList(), x + RIGHT_INSET)
    }

    private fun pingText(): String = if (ping < 0) "...ms" else ping.toString() + "ms"

    private fun pingColor(): Int = if (ping < 0) FOREGROUND else if (ping < 100) ONLINE
    else if (ping <= 400) PING_ORANGE else PING_RED

    companion object {
        const val FONT_SIZE = 8f
        const val INSET = 9f
        const val RIGHT_INSET = 12.5f
        const val ICON_SIZE = 10.5f
        const val ICON_GAP = 3.5f
        val FOREGROUND = 0xFFE4E1E6.toInt()
        val LAVENDER = 0xFFBBC3FF.toInt()
        val ONLINE = 0xFF80C810.toInt()
        val PING_ORANGE = 0xFFC87D28.toInt()
        val PING_RED = 0xFFCA3E2E.toInt()

        private fun icon(parts: MutableList<Part>, icon: Symbol, color: Int, x: Float): Float {
            parts.add(Part("", icon, color, x, ICON_SIZE))
            return x + ICON_SIZE + ICON_GAP
        }

        private fun label(parts: MutableList<Part>, text: String, color: Int, x: Float, measure: IslandTextWidth): Float {
            val width = measure.measure(text, FONT_SIZE)
            parts.add(Part(text, null, color, x, width))
            return x + width
        }

        private fun separator(parts: MutableList<Part>, x: Float, measure: IslandTextWidth): Float =
            label(parts, "  \u00B7  ", FOREGROUND, x + 1.25f, measure) + .75f
    }
}

// ============================================================================================
// Painter (port of DynamicIslandPainter). Every coordinate is in logical, pre-scale units.
// ============================================================================================

/** Samsara OpaiStyle.Palette, reduced to the four fields the island reads. */
internal enum class IslandTheme(
    override val tag: String,
    val accent: Int,
    val toggleOutline: Int,
    val hudProgress: Int,
    val hudKnob: Int,
) : Tagged {
    LAVENDER("Lavender", 0xFFBBC3FF.toInt(), 0xFF938F99.toInt(), 0xFF9CA5DA.toInt(), 0xFF5B5F7C.toInt()),
    LIGHT_PINK("Light Pink", 0xFFFFB4AA.toInt(), 0xFF9E8D8B.toInt(), 0xFFD19997.toInt(), 0xFF7C5752.toInt()),
}

internal interface IslandSurface : IslandTextWidth {
    fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int)
    fun text(text: String, x: Float, centerY: Float, size: Float, color: Int)
    fun breakingText(text: String, x: Float, centerY: Float, size: Float, color: Int) {
        text(text, x, centerY, size, color)
    }

    fun shadow(x: Float, y: Float, width: Float, height: Float, radius: Float)
    fun clip(x: Float, y: Float, width: Float, height: Float, content: () -> Unit)
    fun symbol(symbol: IslandStatus.Symbol, x: Float, y: Float, size: Float, color: Int)
}

internal object IslandPainter {
    /** HudGlassStyle.BODY */
    val BACKGROUND = 0x98000000.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val DETAIL = 0xFFFFFFFF.toInt()
    val ENABLED = 0xFF55FF55.toInt()
    val DISABLED = 0xFFFF5555.toInt()
    const val SHADOW_EXTENT = 8f
    const val SHADOW_STEP = .25f

    fun paint(surface: IslandSurface, frame: IslandFrame, viewportWidth: Float, palette: IslandTheme) {
        paintShell(surface, frame, viewportWidth)
        paintContent(surface, frame, viewportWidth, palette)
    }

    fun paintShell(surface: IslandSurface, frame: IslandFrame, viewportWidth: Float) {
        val x = (viewportWidth - frame.width) / 2
        val radius = frame.radius
        val top = frame.top
        surface.shadow(x, top, frame.width, frame.height, radius)
        surface.rounded(x, top, frame.width, frame.height, radius, BACKGROUND)
    }

    fun paintContent(surface: IslandSurface, frame: IslandFrame, viewportWidth: Float, palette: IslandTheme) {
        val x = (viewportWidth - frame.width) / 2
        val top = frame.top
        surface.clip(x + 2, top + 1, max(0f, frame.width - 4), frame.height - 2) {
            if (frame.idleOpacity > 0) {
                drawIdle(surface, frame, x, palette)
            }
            for (row in frame.rows) {
                if (row.opacity <= 0 || row.height <= 0) {
                    continue
                }
                val y = top + row.y
                surface.clip(x + 3, y, max(0f, frame.width - 6), row.height) {
                    drawRow(surface, row, x, y, frame.width, max(frame.width, frame.contentWidth), palette)
                }
            }
        }
    }

    private fun drawIdle(surface: IslandSurface, frame: IslandFrame, x: Float, palette: IslandTheme) {
        val centerY = frame.top + IslandMetrics.IDLE_HEIGHT / 2
        // The layout is based on final content width, so FPS changes only animate the clip.
        var previousIcon: IslandStatus.Symbol? = null
        for (part in frame.idle.parts) {
            val color = alpha(
                if (part.icon == IslandStatus.Symbol.CHROME || previousIcon == IslandStatus.Symbol.CHROME)
                    palette.accent else part.color,
                frame.idleOpacity,
            )
            previousIcon = part.icon
            if (part.icon == null) {
                surface.text(part.text, x + part.x, centerY, IslandStatus.FONT_SIZE, color)
            } else {
                surface.symbol(part.icon, x + part.x, centerY - part.width / 2, part.width, color)
            }
        }
    }

    private fun drawRow(
        surface: IslandSurface, row: IslandRow, x: Float, y: Float, width: Float, contentWidth: Float,
        palette: IslandTheme,
    ) {
        val opacity = row.opacity
        if (row.icon == IslandIcon.SCAFFOLD || row.icon == IslandIcon.BREAKING) {
            drawProgressPanel(surface, row, x, y, width, contentWidth, opacity, palette)
            return
        }
        drawIcon(surface, row, x, y, palette)
        val textX = x + IslandMetrics.NOTICE_TEXT_X
        // Reveal a stable line through the animated clip; never re-ellipsize "has been" while growing.
        val textWidth = max(0f, contentWidth - IslandMetrics.NOTICE_TEXT_X - IslandMetrics.NOTICE_RIGHT_PADDING)
        surface.text(
            IslandState.fit(row.title, IslandMetrics.TITLE_FONT, textWidth, surface), textX, y + 13.5f,
            IslandMetrics.TITLE_FONT, alpha(TEXT, opacity),
        )
        val punctuationWidth = if (row.status.isEmpty()) 0f else surface.measure("!", IslandMetrics.DETAIL_FONT)
        val line = IslandState.detailLine(row.detail, row.status, max(0f, textWidth - punctuationWidth), surface)
        surface.text(line.prefix, textX, y + 24.75f, IslandMetrics.DETAIL_FONT, alpha(DETAIL, opacity))
        // Drawing returns no width: always measure the prefix once, independent of screen X.
        surface.text(
            line.status, textX + line.statusOffset, y + 24.75f, IslandMetrics.DETAIL_FONT,
            alpha(if (row.enabled) ENABLED else DISABLED, opacity),
        )
        if (line.status.isNotEmpty()) {
            surface.text(
                "!", textX + line.statusOffset + surface.measure(line.status, IslandMetrics.DETAIL_FONT),
                y + 24.75f, IslandMetrics.DETAIL_FONT, alpha(TEXT, opacity),
            )
        }
    }

    private fun drawProgressPanel(
        surface: IslandSurface, row: IslandRow, x: Float, y: Float, width: Float, contentWidth: Float,
        opacity: Float, palette: IslandTheme,
    ) {
        val breaking = row.icon == IslandIcon.BREAKING
        val tile = alpha(0xC8141616.toInt(), opacity)
        val ink = alpha(palette.accent, opacity)
        val tileSize = if (breaking) 27f else 28f
        surface.rounded(x + 4, y + 5, tileSize, tileSize, 7f, tile)
        if (breaking) {
            surface.symbol(IslandStatus.Symbol.BED, x + 10, y + 13.5f, 16f, ink)
        } else {
            drawCube(surface, x + 18, y + 19, 14f, 1f, ink)
        }
        val textWidth = max(0f, contentWidth - IslandMetrics.SCAFFOLD_TEXT_X - IslandMetrics.SCAFFOLD_RIGHT_PADDING)
        if (breaking) {
            val measure = IslandTextWidth { text, size -> surface.measureBreaking(text, size) }
            surface.breakingText(
                IslandState.fit(row.title, IslandMetrics.BREAKING_TITLE_FONT, textWidth, measure),
                x + IslandMetrics.SCAFFOLD_TEXT_X, y + 12, IslandMetrics.BREAKING_TITLE_FONT, ink,
            )
            surface.breakingText(
                IslandState.fit(row.detail, IslandMetrics.BREAKING_DETAIL_FONT, textWidth, measure),
                x + IslandMetrics.SCAFFOLD_TEXT_X, y + 24, IslandMetrics.BREAKING_DETAIL_FONT, alpha(TEXT, opacity),
            )
        } else {
            surface.text(
                IslandState.fit(row.title, IslandMetrics.TITLE_FONT, textWidth, surface),
                x + IslandMetrics.SCAFFOLD_TEXT_X, y + 13, IslandMetrics.TITLE_FONT, ink,
            )
            surface.text(
                IslandState.fit(row.detail, IslandMetrics.DETAIL_FONT, textWidth, surface),
                x + IslandMetrics.SCAFFOLD_TEXT_X, y + 25, IslandMetrics.DETAIL_FONT, alpha(TEXT, opacity),
            )
        }
        val barWidth = max(0f, width - 8)
        val barY = y + (if (breaking) 35 else 37)
        val barHeight = if (breaking) 7.5f else 8f
        surface.rounded(
            x + 4, barY, barWidth, barHeight, barHeight / 2,
            alpha(if (breaking) 0x7034343D else 0xFF343636.toInt(), opacity),
        )
        val filledWidth = barWidth * row.progress.coerceIn(0f, 1f)
        if (filledWidth > 0) {
            surface.rounded(
                x + 4, barY, filledWidth, barHeight, min(barHeight / 2, filledWidth / 2),
                alpha(palette.hudProgress, opacity),
            )
        }
    }

    private fun drawCube(surface: IslandSurface, cx: Float, cy: Float, size: Float, stroke: Float, color: Int) {
        val half = size * 0.5f
        val top = cy - half
        val bottom = cy + half
        val left = cx - half * 0.9f
        val right = cx + half * 0.9f
        val shoulder = cy - half * 0.5f
        val lower = cy + half * 0.5f
        surface.line(cx, top, right, shoulder, stroke, color)
        surface.line(right, shoulder, cx, cy, stroke, color)
        surface.line(cx, cy, left, shoulder, stroke, color)
        surface.line(left, shoulder, cx, top, stroke, color)
        surface.line(left, shoulder, left, lower, stroke, color)
        surface.line(left, lower, cx, bottom, stroke, color)
        surface.line(cx, bottom, right, lower, stroke, color)
        surface.line(right, lower, right, shoulder, stroke, color)
        surface.line(cx, cy, cx, bottom, stroke, color)
    }

    private fun drawIcon(surface: IslandSurface, row: IslandRow, x: Float, y: Float, palette: IslandTheme) {
        val a = row.opacity
        if (row.icon == IslandIcon.TOGGLE) {
            val t = row.toggle
            surface.rounded(x + 5, y + 10, 26f, 16f, 8f, alpha(mix(palette.toggleOutline, palette.accent, t), a))
            surface.rounded(x + 6, y + 11, 24f, 14f, 7f, alpha(mix(0xFF36343B.toInt(), palette.accent, t), a))
            val knobSize = 8 + 3.5f * t
            surface.rounded(
                x + 8.5f + 8.5f * t, y + 13.5f - 1.25f * t,
                knobSize, knobSize, knobSize / 2, alpha(mix(0xFF858488.toInt(), palette.hudKnob, t), a),
            )
            return
        }
        val tile = when (row.icon) {
            IslandIcon.SUCCESS -> 0xC85F8F50.toInt()
            IslandIcon.WARNING -> 0xC88F5050.toInt()
            else -> 0xC8307593.toInt()
        }
        surface.rounded(x + 7, y + 8, 24f, 24f, 7f, alpha(tile, a))
        val ink = alpha(TEXT, a)
        if (row.icon == IslandIcon.SUCCESS) {
            surface.line(x + 13, y + 20, x + 18, y + 25, 2f, ink)
            surface.line(x + 18, y + 25, x + 26, y + 14, 2f, ink)
        } else if (row.icon == IslandIcon.WARNING) {
            surface.line(x + 19, y + 13, x + 19, y + 22, 2f, ink)
            surface.rounded(x + 18, y + 25, 2f, 2f, 1f, ink)
        } else {
            surface.line(x + 19, y + 16, x + 19, y + 26, 2f, ink)
            surface.rounded(x + 18, y + 12, 2f, 2f, 1f, ink)
        }
    }

    fun alpha(color: Int, opacity: Float): Int =
        (((color ushr 24) * opacity.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)

    fun shadowCoverage(distance: Float): Float {
        val normalized = max(0f, distance) / 2.8f
        return .5f * exp((-.78f * normalized - .5f * normalized * normalized).toDouble()).toFloat()
    }

    fun shadowLayer(spread: Float): Int {
        val previous = shadowCoverage(spread + SHADOW_STEP)
        val desired = shadowCoverage(spread)
        return (255 * (desired - previous) / (1 - previous)).roundToInt() shl 24
    }

    private fun mix(from: Int, to: Int, amount: Float): Int {
        var result = 0
        var shift = 0
        while (shift <= 24) {
            val a = (from ushr shift) and 255
            val b = (to ushr shift) and 255
            result = result or ((a + (b - a) * amount).roundToInt() shl shift)
            shift += 8
        }
        return result
    }
}

// ============================================================================================
// Vector geometry toolkit. NanoVG is not available in LiquidBounce, so path commands are
// flattened here, triangulated once (ear clipping) and drawn as batched GUI triangles with a
// half-device-pixel alpha fringe, which approximates how NanoVG anti-aliases fills.
// ============================================================================================

internal class IslandPoint(val x: Double, val y: Double)

/** NanoVG-compatible path builder (moveTo / lineTo / bezierTo / quadTo / arc / circle / close). */
internal class IslandPath {
    class Sub(val points: ArrayList<IslandPoint>, var closed: Boolean)

    val subs = ArrayList<Sub>()
    private var current: Sub? = null

    fun moveTo(x: Double, y: Double) {
        val sub = Sub(arrayListOf(IslandPoint(x, y)), false)
        subs.add(sub)
        current = sub
    }

    fun lineTo(x: Double, y: Double) {
        val sub = current
        if (sub == null) moveTo(x, y) else push(sub, x, y)
    }

    private fun push(sub: Sub, x: Double, y: Double) {
        val last = sub.points.last()
        if (abs(last.x - x) > 1e-7 || abs(last.y - y) > 1e-7) {
            sub.points.add(IslandPoint(x, y))
        }
    }

    fun bezierTo(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double) {
        val sub = current ?: return
        val p0 = sub.points.last()
        for (i in 1..BEZIER_SEGMENTS) {
            val t = i.toDouble() / BEZIER_SEGMENTS
            val u = 1 - t
            val bx = u * u * u * p0.x + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x
            val by = u * u * u * p0.y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y
            push(sub, bx, by)
        }
    }

    /** NanoVG promotes a quadratic to a cubic with 2/3 control-point interpolation. */
    fun quadTo(cx: Double, cy: Double, x: Double, y: Double) {
        val sub = current ?: return
        val p0 = sub.points.last()
        bezierTo(
            p0.x + 2.0 / 3 * (cx - p0.x), p0.y + 2.0 / 3 * (cy - p0.y),
            x + 2.0 / 3 * (cx - x), y + 2.0 / 3 * (cy - y), x, y,
        )
    }

    /** NanoVG nvgArc: NVG_CW sweeps with increasing angle, NVG_CCW with decreasing angle. */
    fun arc(cx: Double, cy: Double, r: Double, a0: Double, a1: Double, clockwise: Boolean) {
        val twoPi = 2 * PI
        var da = a1 - a0
        if (clockwise) {
            if (abs(da) >= twoPi) {
                da = twoPi
            } else {
                while (da < 0) da += twoPi
            }
        } else {
            if (abs(da) >= twoPi) {
                da = -twoPi
            } else {
                while (da > 0) da -= twoPi
            }
        }
        val n = max(2, ceil(abs(da) / (PI / 32)).toInt())
        for (i in 0..n) {
            val a = a0 + da * i / n
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            if (i == 0) lineTo(x, y) else push(current!!, x, y)
        }
    }

    fun circle(cx: Double, cy: Double, r: Double) {
        val points = ArrayList<IslandPoint>()
        for (i in 0 until CIRCLE_SEGMENTS) {
            val a = 2 * PI * i / CIRCLE_SEGMENTS
            points.add(IslandPoint(cx + cos(a) * r, cy + sin(a) * r))
        }
        subs.add(Sub(points, true))
        current = null
    }

    fun close() {
        val sub = current ?: return
        sub.closed = true
        if (sub.points.size > 1) {
            val first = sub.points.first()
            val last = sub.points.last()
            if (abs(first.x - last.x) < 1e-7 && abs(first.y - last.y) < 1e-7) {
                sub.points.removeAt(sub.points.size - 1)
            }
        }
        current = null
    }

    companion object {
        const val BEZIER_SEGMENTS = 16
        const val CIRCLE_SEGMENTS = 48
    }
}

/** A simple CCW polygon: triangulated once, with outward miter normals for the AA fringe. */
internal class IslandPolygon(
    val xs: FloatArray,
    val ys: FloatArray,
    val triangles: IntArray,
    val normalX: FloatArray,
    val normalY: FloatArray,
) {
    val count: Int get() = xs.size

    /**
     * Emits the polygon at (ox, oy) scaled by [scale]. The interior is the exact polygon, so the
     * precomputed triangulation can never fold over itself, and an alpha ramp of half a device pixel
     * ([fringe] / 2) fades out just beyond the edge for anti-aliasing. NanoVG centres a one-pixel ramp
     * on the edge instead, so shapes here are about a quarter of a device pixel bolder.
     */
    fun emit(batch: IslandBatch, ox: Float, oy: Float, scale: Float, argb: Int, fringe: Float) {
        if ((argb ushr 24) == 0) return
        val ramp = fringe * 0.5f
        val clear = argb and 0xFFFFFF
        val bx = FloatArray(count)
        val by = FloatArray(count)
        val px = FloatArray(count)
        val py = FloatArray(count)
        for (i in 0 until count) {
            bx[i] = ox + xs[i] * scale
            by[i] = oy + ys[i] * scale
            px[i] = bx[i] + normalX[i] * ramp
            py[i] = by[i] + normalY[i] * ramp
        }
        var t = 0
        while (t < triangles.size) {
            val a = triangles[t]
            val b = triangles[t + 1]
            val c = triangles[t + 2]
            batch.vertex(bx[a], by[a], argb)
            batch.vertex(bx[b], by[b], argb)
            batch.vertex(bx[c], by[c], argb)
            t += 3
        }
        for (i in 0 until count) {
            val j = (i + 1) % count
            batch.vertex(bx[i], by[i], argb)
            batch.vertex(bx[j], by[j], argb)
            batch.vertex(px[j], py[j], clear)
            batch.vertex(bx[i], by[i], argb)
            batch.vertex(px[j], py[j], clear)
            batch.vertex(px[i], py[i], clear)
        }
    }
}

internal object IslandGeometry {
    private const val EPS = 1e-9

    fun signedArea(p: List<IslandPoint>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[i]
            val r = p[(i + 1) % p.size]
            a += q.x * r.y - r.x * q.y
        }
        return a / 2
    }

    private fun cross(o: IslandPoint, a: IslandPoint, b: IslandPoint): Double =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun inside(p: IslandPoint, a: IslandPoint, b: IslandPoint, c: IslandPoint): Boolean =
        cross(a, b, p) >= -EPS && cross(b, c, p) >= -EPS && cross(c, a, p) >= -EPS

    private fun same(p: IslandPoint, q: IslandPoint): Boolean = abs(p.x - q.x) < 1e-9 && abs(p.y - q.y) < 1e-9

    /** Ear clipping for a simple counter-clockwise polygon. Returns triangle index triples. */
    fun triangulate(p: List<IslandPoint>): IntArray {
        val out = ArrayList<Int>()
        val idx = ArrayList<Int>()
        for (i in p.indices) idx.add(i)
        var guard = 0
        while (idx.size > 3 && guard < 10000) {
            guard++
            val n = idx.size
            var found = false
            for (k in 0 until n) {
                val i0 = idx[(k - 1 + n) % n]
                val i1 = idx[k]
                val i2 = idx[(k + 1) % n]
                val a = p[i0]
                val b = p[i1]
                val c = p[i2]
                if (cross(a, b, c) <= EPS) continue
                var blocked = false
                for (j in idx) {
                    if (j == i0 || j == i1 || j == i2) continue
                    val q = p[j]
                    if (same(q, a) || same(q, b) || same(q, c)) continue
                    if (inside(q, a, b, c)) {
                        blocked = true
                        break
                    }
                }
                if (blocked) continue
                out.add(i0)
                out.add(i1)
                out.add(i2)
                idx.removeAt(k)
                found = true
                break
            }
            if (!found) {
                // Degenerate input: drop the vertex with the smallest |cross| so the loop always ends.
                var best = Double.MAX_VALUE
                var bestIndex = 0
                for (k in 0 until n) {
                    val cr = abs(cross(p[idx[(k - 1 + n) % n]], p[idx[k]], p[idx[(k + 1) % n]]))
                    if (cr < best) {
                        best = cr
                        bestIndex = k
                    }
                }
                idx.removeAt(bestIndex)
            }
        }
        if (idx.size == 3 && cross(p[idx[0]], p[idx[1]], p[idx[2]]) > EPS) {
            out.add(idx[0])
            out.add(idx[1])
            out.add(idx[2])
        }
        return out.toIntArray()
    }

    /** Builds a drawable polygon: removes duplicate points, forces CCW, triangulates, computes normals. */
    fun polygon(raw: List<IslandPoint>): IslandPolygon {
        val pts = ArrayList<IslandPoint>()
        for (q in raw) {
            if (pts.isEmpty() || !same(q, pts.last())) pts.add(q)
        }
        if (pts.size > 1 && same(pts.first(), pts.last())) pts.removeAt(pts.size - 1)
        if (signedArea(pts) < 0) pts.reverse()
        val n = pts.size
        val ex = DoubleArray(n)
        val ey = DoubleArray(n)
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            // Outward normal of a counter-clockwise polygon edge.
            ex[i] = dy / len
            ey[i] = -dx / len
        }
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (j in 0 until n) {
            val prev = (j - 1 + n) % n
            var mx = ex[prev] + ex[j]
            var my = ey[prev] + ey[j]
            val ml = hypot(mx, my)
            var s = 1.0
            if (ml < 1e-9) {
                mx = ex[j]
                my = ey[j]
            } else {
                mx /= ml
                my /= ml
                s = 1.0 / max(mx * ex[j] + my * ey[j], 0.35)
            }
            nx[j] = (mx * s).toFloat()
            ny[j] = (my * s).toFloat()
        }
        return IslandPolygon(
            FloatArray(n) { pts[it].x.toFloat() },
            FloatArray(n) { pts[it].y.toFloat() },
            triangulate(pts),
            nx, ny,
        )
    }

    /** Open polyline -> simple polygon with round end caps; interior joins are mitered. */
    fun strokeOutline(raw: List<IslandPoint>, half: Double, capSegments: Int = 10): List<IslandPoint> {
        val pts = ArrayList<IslandPoint>()
        for (q in raw) {
            if (pts.isEmpty() || !same(q, pts.last())) pts.add(q)
        }
        val n = pts.size
        val normalX = DoubleArray(n - 1)
        val normalY = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            val len = hypot(dx, dy)
            normalX[i] = -dy / len
            normalY[i] = dx / len
        }
        val left = ArrayList<IslandPoint>()
        val right = ArrayList<IslandPoint>()
        for (i in 0 until n) {
            var nx: Double
            var ny: Double
            var s = 1.0
            if (i == 0) {
                nx = normalX[0]
                ny = normalY[0]
            } else if (i == n - 1) {
                nx = normalX[n - 2]
                ny = normalY[n - 2]
            } else {
                val ax = normalX[i - 1]
                val ay = normalY[i - 1]
                val bx = normalX[i]
                val by = normalY[i]
                var mx = ax + bx
                var my = ay + by
                val ml = hypot(mx, my)
                mx /= ml
                my /= ml
                s = 1.0 / max(mx * ax + my * ay, 0.35)
                nx = mx
                ny = my
            }
            left.add(IslandPoint(pts[i].x + nx * half * s, pts[i].y + ny * half * s))
            right.add(IslandPoint(pts[i].x - nx * half * s, pts[i].y - ny * half * s))
        }
        val outline = ArrayList<IslandPoint>(left)
        val end = pts[n - 1]
        var a0 = atan2(normalY[n - 2], normalX[n - 2])
        for (k in 1 until capSegments) {
            val a = a0 - PI * k / capSegments
            outline.add(IslandPoint(end.x + cos(a) * half, end.y + sin(a) * half))
        }
        for (i in right.indices.reversed()) outline.add(right[i])
        val start = pts[0]
        a0 = atan2(-normalY[0], -normalX[0])
        for (k in 1 until capSegments) {
            val a = a0 - PI * k / capSegments
            outline.add(IslandPoint(start.x + cos(a) * half, start.y + sin(a) * half))
        }
        return outline
    }

    /** Round-capped line segment as a convex polygon (capsule), in logical units. */
    fun capsule(x1: Float, y1: Float, x2: Float, y2: Float, half: Float): IslandPolygon {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val len = hypot(dx, dy)
        val pts = ArrayList<IslandPoint>()
        val seg = 8
        if (len < 1e-6) {
            for (i in 0 until seg * 2) {
                val a = 2 * PI * i / (seg * 2)
                pts.add(IslandPoint(x1 + cos(a) * half, y1 + sin(a) * half))
            }
        } else {
            val base = atan2(dy, dx)
            // Half circle around the end point, then half circle around the start point.
            for (i in 0..seg) {
                val a = base - PI / 2 + PI * i / seg
                pts.add(IslandPoint(x2 + cos(a) * half, y2 + sin(a) * half))
            }
            for (i in 0..seg) {
                val a = base + PI / 2 + PI * i / seg
                pts.add(IslandPoint(x1 + cos(a) * half, y1 + sin(a) * half))
            }
        }
        return polygon(pts)
    }
}

/** Accumulates triangles (three vertices each) and submits them as one GUI element. */
internal class IslandBatch {
    private var xs = FloatArray(512)
    private var ys = FloatArray(512)
    private var colors = IntArray(512)
    var size = 0
        private set
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE

    fun vertex(x: Float, y: Float, argb: Int) {
        if (size == xs.size) {
            xs = xs.copyOf(size * 2)
            ys = ys.copyOf(size * 2)
            colors = colors.copyOf(size * 2)
        }
        xs[size] = x
        ys[size] = y
        colors[size] = argb
        size++
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, argb: Int) {
        vertex(x1, y1, argb)
        vertex(x2, y2, argb)
        vertex(x3, y3, argb)
    }

    fun flush(ctx: GuiGraphicsExtractor) {
        if (size == 0 || size % 3 != 0) return
        val bounds = ctx.getBounds(minX - 1f, minY - 1f, maxX + 1f, maxY + 1f)
        val count = size
        // The vertex callback runs later in the frame, so the element needs its own copies.
        val vx = xs.copyOf(count)
        val vy = ys.copyOf(count)
        val vc = colors.copyOf(count)
        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.GUI.triangles(false),
            bounds = bounds,
        ) { pose ->
            for (i in 0 until count) {
                addVertexWith2DPose(pose, vx[i], vy[i]).setColor(vc[i])
            }
        }
    }
}

// ============================================================================================
// Icon geometry (port of the path data in DynamicIslandNanoSurface.symbol, 24x24 view box)
// ============================================================================================

internal object IslandIcons {
    private fun fills(path: IslandPath): List<IslandPolygon> =
        path.subs.filter { it.closed && it.points.size >= 3 }.map { IslandGeometry.polygon(it.points) }

    private fun bedPath(): IslandPath {
        val p = IslandPath()
        // A single filled frame avoids the seams of overlapping rounded rectangles.
        p.moveTo(0.0, .75)
        p.bezierTo(0.0, .336, .336, 0.0, .75, 0.0)
        p.lineTo(2.25, 0.0)
        p.bezierTo(2.664, 0.0, 3.0, .336, 3.0, .75)
        p.lineTo(3.0, 9.0)
        p.lineTo(24.0, 9.0)
        p.lineTo(24.0, 15.0)
        p.lineTo(21.0, 15.0)
        p.lineTo(21.0, 12.0)
        p.lineTo(3.0, 12.0)
        p.lineTo(3.0, 15.0)
        p.lineTo(0.0, 15.0)
        p.close()
        p.circle(7.05, 5.15, 3.1)
        p.moveTo(10.8, 3.0)
        p.lineTo(19.2, 3.0)
        p.bezierTo(21.85, 3.0, 24.0, 4.6, 24.0, 6.6)
        p.lineTo(24.0, 8.4)
        p.lineTo(10.8, 8.4)
        p.close()
        return p
    }

    private fun userPath(): IslandPath {
        val p = IslandPath()
        p.circle(12.0, 7.5, 5.0)
        p.moveTo(3.5, 21.0)
        p.bezierTo(3.5, 17.0, 6.5, 14.5, 10.0, 14.5)
        p.lineTo(14.0, 14.5)
        p.bezierTo(17.5, 14.5, 20.5, 17.0, 20.5, 21.0)
        p.quadTo(20.5, 22.0, 19.5, 22.0)
        p.lineTo(4.5, 22.0)
        p.quadTo(3.5, 22.0, 3.5, 21.0)
        p.close()
        return p
    }

    private fun linkPath(): IslandPath {
        val p = IslandPath()
        p.moveTo(8.0, 16.5)
        p.lineTo(6.5, 16.5)
        p.bezierTo(3.8, 16.5, 2.25, 15.0, 2.25, 12.0)
        p.bezierTo(2.25, 9.0, 3.8, 7.5, 6.5, 7.5)
        p.lineTo(10.0, 7.5)
        p.bezierTo(12.8, 7.5, 14.0, 9.0, 14.0, 12.0)
        p.lineTo(14.0, 13.25)
        p.moveTo(16.0, 7.5)
        p.lineTo(17.5, 7.5)
        p.bezierTo(20.2, 7.5, 21.75, 9.0, 21.75, 12.0)
        p.bezierTo(21.75, 15.0, 20.2, 16.5, 17.5, 16.5)
        p.lineTo(14.0, 16.5)
        p.bezierTo(11.2, 16.5, 10.0, 15.0, 10.0, 12.0)
        p.lineTo(10.0, 10.75)
        return p
    }

    private fun refreshPath(): IslandPath {
        val p = IslandPath()
        p.moveTo(12.5, 1.0)
        p.lineTo(4.5, 6.5)
        p.lineTo(12.5, 12.0)
        p.quadTo(13.0, 12.25, 13.0, 11.5)
        p.lineTo(13.0, 9.0)
        p.bezierTo(17.5, 9.0, 20.5, 11.5, 21.5, 15.5)
        p.bezierTo(21.5, 8.5, 18.5, 5.5, 13.0, 5.0)
        p.lineTo(13.0, 1.5)
        p.quadTo(13.0, .75, 12.5, 1.0)
        p.close()
        return p
    }

    /** nvgTranslate(24, 24) + nvgRotate(PI): a point (x, y) lands on (24 - x, 24 - y). */
    private fun rotated(path: IslandPath): List<IslandPolygon> =
        path.subs.filter { it.closed && it.points.size >= 3 }
            .map { sub -> IslandGeometry.polygon(sub.points.map { IslandPoint(24 - it.x, 24 - it.y) }) }

    private fun chromePaths(): List<IslandPath> {
        val result = ArrayList<IslandPath>()
        // Keep the three lobes separate where parallel cuts meet the center clearance.
        val outerRadius = 12.0
        val innerRadius = 6.0
        val cutDistance = 5.0
        val gap = 1.75
        val upperCut = cutDistance + gap / 2
        val lowerCut = cutDistance - gap / 2
        for (i in 0 until 3) {
            val rotation = i * PI * 2 / 3
            val outerStart = rotation - (PI * 2 / 3) - asin(lowerCut / outerRadius)
            val outerEnd = rotation - asin(upperCut / outerRadius)
            val innerEnd = rotation - asin(upperCut / innerRadius)
            val innerStart = rotation - (PI * 2 / 3) - asin(lowerCut / innerRadius)
            val p = IslandPath()
            p.arc(12.0, 12.0, outerRadius, outerStart, outerEnd, true)
            p.lineTo(12 + innerRadius * cos(innerEnd), 12 + innerRadius * sin(innerEnd))
            p.arc(12.0, 12.0, innerRadius, innerEnd, innerStart, false)
            p.close()
            result.add(p)
        }
        val center = IslandPath()
        center.circle(12.0, 12.0, 4.0)
        result.add(center)
        return result
    }

    private val table: Map<IslandStatus.Symbol, List<IslandPolygon>> = mapOf(
        IslandStatus.Symbol.BED to fills(bedPath()),
        IslandStatus.Symbol.USER to fills(userPath()),
        // LINK is stroked in the original (width 2.5, round caps and joins); the stroke is converted to an outline.
        IslandStatus.Symbol.LINK to linkPath().subs.map { IslandGeometry.polygon(IslandGeometry.strokeOutline(it.points, 1.25)) },
        IslandStatus.Symbol.REFRESH to fills(refreshPath()) + rotated(refreshPath()),
        IslandStatus.Symbol.CHROME to chromePaths().flatMap { fills(it) },
    )

    fun shapes(symbol: IslandStatus.Symbol): List<IslandPolygon> = table.getValue(symbol)
}

// ============================================================================================
// Surface (port of DynamicIslandNanoSurface onto GuiGraphicsExtractor)
// ============================================================================================

/**
 * Samsara draws with two fonts: sourcesans3-semibold for notices and googlesans-bold for the idle
 * bar (size 8) and the breaking panel. LiquidBounce only exposes one global font renderer, so both
 * resolve to it. Swap the body of [renderer] to route either role to another font renderer.
 */
internal object IslandFonts {
    @Suppress("UNUSED_PARAMETER")
    fun renderer(size: Float, breaking: Boolean) = FontManager.FONT_RENDERER

    fun letterSpacing(size: Float): Float =
        if (size == IslandStatus.FONT_SIZE) 0f else if (size == IslandMetrics.DETAIL_FONT) .125f else .14f

    private val widths = HashMap<String, Float>()

    /** Width of [text] at NanoVG font size [size], including the trailing tracking. */
    fun measure(text: String, size: Float, breaking: Boolean): Float {
        if (text.isEmpty()) return 0f
        val key = (if (breaking) "b" else "n") + size + "|" + text
        widths[key]?.let { return it }
        val font = renderer(size, breaking)
        val scale = size / font.height
        val value = font.getStringWidth(text.asPlainText(), scale) +
            text.codePointCount(0, text.length) * letterSpacing(size)
        if (widths.size > 2048) widths.clear()
        widths[key] = value
        return value
    }
}

internal class IslandCanvas(
    private val ctx: GuiGraphicsExtractor,
    /** Uniform island scale, applied by the pose as translate(0, offsetY) * scale(scale). */
    private val scale: Float,
    private val offsetY: Float,
    guiScale: Int,
    screenWidth: Int,
    screenHeight: Int,
) : IslandSurface {
    private class Clip(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** One device pixel expressed in logical units: the width of the anti-aliasing fringe. */
    private val fringe = 1f / (scale * max(1, guiScale))
    private val clips = arrayListOf(Clip(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat()))

    override fun measure(text: String, size: Float): Float = IslandFonts.measure(text, size, false)

    override fun measureBreaking(text: String, size: Float): Float = IslandFonts.measure(text, size, true)

    override fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) {
        if (width <= 0f || height <= 0f || (color ushr 24) == 0) return
        // Same clamp as nvgRoundedRect for circular corners.
        ctx.drawRoundedRect(x, y, x + width, y + height, min(radius, min(width, height) / 2), Color4b(color), null)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int) {
        if ((color ushr 24) == 0) return
        val batch = IslandBatch()
        IslandGeometry.capsule(x1, y1, x2, y2, stroke / 2).emit(batch, 0f, 0f, 1f, color, fringe)
        batch.flush(ctx)
    }

    override fun text(text: String, x: Float, centerY: Float, size: Float, color: Int) =
        drawText(text, x, centerY, size, color, false)

    override fun breakingText(text: String, x: Float, centerY: Float, size: Float, color: Int) =
        drawText(text, x, centerY, size, color, true)

    private fun drawText(text: String, x: Float, centerY: Float, size: Float, color: Int, breaking: Boolean) {
        if (text.isEmpty() || (color ushr 24) == 0) return
        val font = IslandFonts.renderer(size, breaking)
        val fontScale = size / font.height
        val spacing = IslandFonts.letterSpacing(size)
        val ink = Color4b(color)
        if (spacing == 0f) {
            font.draw(ctx, text.asPlainText(), x, centerY, ink, false, fontScale, null, VerticalAnchor.MIDDLE)
            return
        }
        // Tracking is applied per glyph after kerning, exactly like the original glyph loop.
        var start = 0
        var index = 0
        while (start < text.length) {
            val next = start + Character.charCount(text.codePointAt(start))
            val pen = if (start == 0) 0f else font.getStringWidth(text.substring(0, start).asPlainText(), fontScale)
            font.draw(
                ctx, text.substring(start, next).asPlainText(), x + pen + index * spacing, centerY,
                ink, false, fontScale, null, VerticalAnchor.MIDDLE,
            )
            start = next
            index++
        }
    }

    override fun shadow(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        if (width <= 0f || height <= 0f) return
        val batch = IslandBatch()
        var spread = IslandPainter.SHADOW_EXTENT
        while (spread > 0) {
            val layer = IslandPainter.shadowLayer(spread)
            if (layer != 0) ring(batch, x, y, width, height, radius, spread, layer)
            spread -= IslandPainter.SHADOW_STEP
        }
        batch.flush(ctx)
    }

    /**
     * One shadow layer: the area between nvgRoundedRect(x-s, y-s, w+2s, h+2s, r+s) and the shell
     * nvgRoundedRect(x, y, w, h, r) cut out as a hole. Both outlines share their corner centres, so
     * the ring is a strip of quads between matching inner and outer outline points.
     */
    private fun ring(batch: IslandBatch, x: Float, y: Float, w: Float, h: Float, radius: Float, spread: Float, argb: Int) {
        val rx = max(0f, min(radius, w / 2))
        val ry = max(0f, min(radius, h / 2))
        val segments = (4 + max(rx, ry) * 0.8f).toInt().coerceIn(5, 14)
        val n = 4 * (segments + 1)
        val ix = FloatArray(n)
        val iy = FloatArray(n)
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        var k = 0
        for (corner in 0 until 4) {
            // Top-left, top-right, bottom-right, bottom-left: angles advance a quarter turn each.
            val cx = if (corner == 0 || corner == 3) x + rx else x + w - rx
            val cy = if (corner < 2) y + ry else y + h - ry
            val start = PI.toFloat() + corner * (PI.toFloat() / 2)
            for (i in 0..segments) {
                val a = start + (PI.toFloat() / 2) * i / segments
                val c = cos(a)
                val s = sin(a)
                ix[k] = cx + rx * c
                iy[k] = cy + ry * s
                ox[k] = cx + (rx + spread) * c
                oy[k] = cy + (ry + spread) * s
                k++
            }
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            batch.triangle(ix[i], iy[i], ix[j], iy[j], ox[j], oy[j], argb)
            batch.triangle(ix[i], iy[i], ox[j], oy[j], ox[i], oy[i], argb)
        }
    }

    override fun clip(x: Float, y: Float, width: Float, height: Float, content: () -> Unit) {
        if (width <= 0f || height <= 0f) return
        val parent = clips.last()
        val x0 = max(parent.x0, x * scale)
        val y0 = max(parent.y0, offsetY + y * scale)
        val x1 = min(parent.x1, (x + width) * scale)
        val y1 = min(parent.y1, offsetY + (y + height) * scale)
        // The GUI scissor stack turns an empty intersection into a 0x0 rectangle that crashes the
        // renderer, whereas nvgIntersectScissor simply draws nothing. Skip the content instead.
        if (x1 - x0 < 1f || y1 - y0 < 1f) return
        ctx.pose().withPush {
            // Scissor rectangles are transformed by the pose; the identity pose passes GUI pixels
            // straight through, rounded outwards so the clip never cuts more than the original.
            identity()
            ctx.enableScissor(floor(x0).toInt(), floor(y0).toInt(), ceil(x1).toInt(), ceil(y1).toInt())
        }
        clips.add(Clip(x0, y0, x1, y1))
        try {
            content()
        } finally {
            clips.removeAt(clips.size - 1)
            ctx.disableScissor()
        }
    }

    override fun symbol(symbol: IslandStatus.Symbol, x: Float, y: Float, size: Float, color: Int) {
        if ((color ushr 24) == 0) return
        val factor = size / 24
        val batch = IslandBatch()
        for (polygon in IslandIcons.shapes(symbol)) {
            polygon.emit(batch, x, y, factor, color, fringe)
        }
        batch.flush(ctx)
    }
}

// ============================================================================================
// Server label (port of DynamicIslandManager.ServerLabel)
// ============================================================================================

internal class IslandServerLabel {
    private var connection: Any? = null
    private var hypixel = false

    fun address(connection: Any?, singleplayer: Boolean, address: String, scoreboard: Scoreboard?, playerName: String?): String? {
        if (singleplayer || connection == null) {
            reset()
            return if (singleplayer) null else "server"
        }
        if (this.connection !== connection) {
            reset()
            this.connection = connection
        }
        if (!hypixel && scoreboard != null) hypixel = hasHypixelFooter(scoreboard, playerName)
        return if (hypixel) "mc.hypixel.net" else address
    }

    fun reset() {
        connection = null
        hypixel = false
    }

    private companion object {
        val HYPIXEL = Regex("(?i)(?<![\\w.-])www\\.hypixel\\.(?:net|com)(?![\\w.-])")
        val FORMATTING = Regex("\u00A7.")
        val DISPLAY_ORDER: Comparator<PlayerScoreEntry> =
            compareByDescending<PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }

        fun hasHypixelFooter(scoreboard: Scoreboard, playerName: String?): Boolean {
            val team = if (playerName == null) null else scoreboard.getPlayersTeam(playerName)
            val teamObjective = if (team == null || team.getColor().isEmpty()) null
            else scoreboard.getDisplayObjective(team.getColor().get().displaySlot())
            val objective = teamObjective ?: scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false
            return scoreboard.listPlayerScores(objective).filter { !it.isHidden() }.sortedWith(DISPLAY_ORDER).take(15)
                .any { entry ->
                    val entryTeam = scoreboard.getPlayersTeam(entry.owner())
                    val text = PlayerTeam.formatNameForTeam(entryTeam, entry.ownerName()).string.replace(FORMATTING, "")
                    HYPIXEL.containsMatchIn(text)
                }
        }
    }
}

// ============================================================================================
// Module
// ============================================================================================

/**
 * Samsara's Dynamic Island as a standalone Render module: a capsule at the top of the screen that
 * shows the status bar while idle and morphs into notification rows for module toggles.
 */
internal object ModuleOpaiDynamicIsland : ClientModule("OpaiDynamicIsland", ModuleCategories.RENDER) {

    private val theme by enumChoice("Theme", IslandTheme.LAVENDER)

    /** Samsara HudLayouts clamps the island scale to 0.5..2 with a default of 1. */
    private val uiScale by float("Scale", 1f, 0.5f..2f)

    /** Samsara always posts a notice when a module is toggled. */
    private val toggleNotices by boolean("ToggleNotices", true)

    private val state = IslandState()
    private val serverLabel = IslandServerLabel()
    private val toggleSplit = Regex("(?<=[a-z])(?=[A-Z])")

    private val measure = object : IslandTextWidth {
        override fun measure(text: String, size: Float): Float = IslandFonts.measure(text, size, false)

        override fun measureBreaking(text: String, size: Float): Float = IslandFonts.measure(text, size, true)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent>(priority = EventPriorityConvention.READ_FINAL_STATE) { event ->
        render(event.context)
    }

    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { event ->
        if (!toggleNotices || mc.player == null) return@handler
        val label = toggleSplit.replace(event.moduleName, " ")
        state.post(
            "module:" + event.moduleName, "Module Toggled", "$label has been ",
            if (event.enabled) "Enabled" else "Disabled", IslandIcon.TOGGLE, event.enabled, now(),
            IslandMetrics.TOGGLE_MS,
        )
    }

    @Suppress("unused")
    private val blockCountHandler = handler<BlockCountChangeEvent> { event ->
        if (!enabled || mc.player == null) return@handler
        val count = event.count
        if (count == null) {
            state.remove("scaffold")
        } else {
            state.postScaffold("$count blocks", 1f, now())
        }
    }

    override fun onDisabled() {
        state.clear()
        serverLabel.reset()
    }

    fun notifySuccess(title: String, description: String) = notice(title, description, IslandIcon.SUCCESS, 2600)

    fun notifyWarning(title: String, description: String) = notice(title, description, IslandIcon.WARNING, 3000)

    fun notifyInfo(title: String, description: String) = notice(title, description, IslandIcon.INFO, 2600)

    /** Progress panels: Scaffold via BlockCountChangeEvent; bed/block break via ModuleFucker.breakingProgress(). */
    fun postScaffold(detail: String, progress: Float) {
        if (enabled && mc.player != null) state.postScaffold(detail, progress, now())
    }

    fun postBreaking(blockName: String, progress: Float) {
        if (enabled && mc.player != null) state.postBreaking(blockName, progress, now())
    }

    private fun notice(title: String, description: String, icon: IslandIcon, duration: Long) {
        if (enabled && mc.player != null) {
            state.post(icon.name + ":" + title + ":" + description, title, description, "", icon, false, now(), duration)
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    /** Vanilla's condition for showing the player list; PlayerTabOverlay.visible has no getter. */
    private fun tabListVisible(): Boolean {
        val level = mc.level ?: return false
        val player = mc.player ?: return false
        return mc.options.keyPlayerList.isDown &&
            (!mc.isLocalServer() || player.connection.getListedOnlinePlayers().size > 1 ||
                level.scoreboard.getDisplayObjective(DisplaySlot.LIST) != null)
    }

    private fun defaultStatus(): IslandStatus {
        val username = mc.user?.name ?: "Player"
        var ping = -1
        val player = mc.player
        val connection = mc.connection
        if (player != null && connection != null) {
            val info = connection.getPlayerInfo(player.uuid)
            if (info != null) ping = max(0, info.latency)
        }
        val server = mc.currentServer
        val address = serverLabel.address(
            mc.connection, mc.hasSingleplayerServer(), server?.ip ?: "server",
            mc.level?.scoreboard, mc.player?.scoreboardName,
        )
        return IslandStatus(username, address, ping, mc.fps)
    }

    private fun render(ctx: GuiGraphicsExtractor) {
        if (mc.player == null || mc.level == null) {
            state.clear()
            if (mc.connection == null) serverLabel.reset()
            return
        }
        if (mc.gui.overlay() != null || (!mc.gui.hud.isHidden && tabListVisible())) return

        updateBedBreaking()

        val window = mc.window
        val reducedMotion = mc.options.screenEffectScale().get() <= 0
        val factor = uiScale
        val logicalWidth = window.guiScaledWidth.toFloat() / factor
        val frame = state.frame(now(), logicalWidth, defaultStatus(), measure, reducedMotion, null)
        val offsetY = frame.top * (1 - factor)
        val canvas = IslandCanvas(ctx, factor, offsetY, window.guiScale, window.guiScaledWidth, window.guiScaledHeight)
        ctx.pose().withPush {
            translate(0f, offsetY)
            scale(factor, factor)
            IslandPainter.paint(canvas, frame, logicalWidth, theme)
        }
    }

    /** Wire ModuleFucker (BedBreaker) break progress into the island panel. */
    private fun updateBedBreaking() {
        if (!enabled) return
        val progress = if (ModuleFucker.enabled) ModuleFucker.breakingProgress() else null
        if (progress == null) {
            state.remove("bed-aura")
            return
        }
        val level = mc.level ?: return
        val blockName = level.getBlockState(progress.pos).block.name.string
        state.postBreaking(blockName, progress.value, now())
    }
}
