/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * Contains a port of Samsara's Opai "Dynamic Island" HUD
 * (com.samsara.ui.dynamicIsland, (c) Jon_awa 2025-05-09, MIT).
 * The design is intentionally kept unchanged: same geometry constants,
 * morph-spring animation, state machine, layout and painter contract,
 * re-targeted at LiquidBounce's GuiGraphicsExtractor render states
 * instead of the original NanoVG backend.
 *
 * Original geometry/palette/spring constants are preserved verbatim.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawLines
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.withPush
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.BlockItem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OpaiHud module — Samsara's interactive "Dynamic Island" status bar for
 * LiquidBounce (stock MC 26.3 render pipeline, no NanoVG).
 *
 * Design (unchanged from the original): a pill-shaped glass island docks at the
 * top center of the HUD and morphs between:
 *  - idle strip: client badge · username · ping/server · fps,
 *  - toggle notices: "X has been Enabled/Disabled!" with a sliding switch,
 *  - scaffold panel: remaining blocks + blocks/s with a progress bar,
 *  - bed-aura panel: target block + break progress.
 *
 * This port deliberately omits Samsara's chest-container takeover (a separate
 * mixin-backed feature) and the Hypixel scoreboard brand sniffer; everything
 * else — constants, layout math, easing — is a 1:1 port.
 */
object ModuleOpaiHud : ClientModule("OpaiHud", ModuleCategories.RENDER) {

    // ------------------------------------------------------------ settings

    private val scale by float("Scale", 1.0f, 0.5f..2.0f)
    private val moduleToggles by boolean("ModuleToggles", true)
    private val scaffoldInfo by boolean("ScaffoldInfo", true)

    /** Palette enum instance directly selectable in ClickGUI (`enumChoice`). */
    private val palette by enumChoice("Palette", Palette.LAVENDER)

    // Mirror of Samsara OpaiStyle.Palette (accent-first view of the original
    // record fields used by the island painter — values kept verbatim).
    enum class Palette(
        override val tag: String,
        val accent: Int, val text: Int, val enabledText: Int,
        val toggleOutline: Int, val hudProgress: Int, val hudKnob: Int,
    ) : Tagged {
        // OpaiStyle.LAVENDER / OpaiStyle.LIGHT_PINK
        LAVENDER("Lavender", 0xFFBBC3FF.toInt(), 0xFFE4E1E6.toInt(), 0xFF172778.toInt(),
            0xFF938F99.toInt(), 0xFF9CA5DA.toInt(), 0xFF5B5F7C.toInt()),
        LIGHT_PINK("LightPink", 0xFFFFB4AA.toInt(), 0xFFEDE0DE.toInt(), 0xFF5F150F.toInt(),
            0xFF9E8D8B.toInt(), 0xFFD19997.toInt(), 0xFF7C5752.toInt()),
    }

    // ================================================================
    //  Ported state machine — DynamicIslandState (MIT, Jon_awa 2025-05-09)
    // ================================================================

    private const val TOP = 15f
    private const val IDLE_TOP = 21f
    private const val IDLE_HEIGHT = 23f
    private const val ROW_HEIGHT = 36f
    private const val SCAFFOLD_HEIGHT = 50f
    private const val BREAKING_HEIGHT = 48f
    private const val BREAKING_TITLE_FONT = 9f
    private const val BREAKING_DETAIL_FONT = 8f
    private const val SCAFFOLD_TEXT_X = 36f
    private const val SCAFFOLD_RIGHT_PADDING = 8f
    private const val RADIUS = 8.5f
    private const val TITLE_FONT = 10f
    private const val DETAIL_FONT = 9f
    private const val NOTICE_TEXT_X = 36f
    private const val NOTICE_RIGHT_PADDING = 5.5f
    private const val MAX_NOTICES = 3
    private const val FADE_MS = 200L
    private const val TOGGLE_MS = 800L
    private const val MORPH_DECAY = 8.7
    private const val MORPH_FREQUENCY = 8.0

    private enum class Icon { TOGGLE, SUCCESS, WARNING, INFO, SCAFFOLD, BREAKING }

    /** Ported `TextWidth`: float size variants are rendered against the MC font;
     *  the island's logical pixel sizes are close to the vanilla 9px font, so the
     *  measurement passes the requested size in hundredths to stay faithful. */
    private fun interface TextWidth {
        fun measure(text: String, size: Float): Float
    }

    private class Row(
        val title: String, val detail: String, val status: String, val icon: Icon,
        val enabled: Boolean, val toggle: Float, val progress: Float, val y: Float,
        val height: Float, val opacity: Float,
    )

    private class Frame(
        val width: Float, val height: Float, val idleOpacity: Float,
        val idle: StatusLayout, val rows: List<Row>, val contentWidth: Float,
        val top: Float, val radius: Float,
    )

    private class DetailLine(val prefix: String, val status: String, val statusOffset: Float)

    private class Notice(val key: String, now: Long) {
        val toggle = Transition(now)
        var title = ""
        var detail = ""
        var status = ""
        var icon = Icon.INFO
        var enabled = false
        var progress = 0f
        var contentWidth = 0f
        var expiresAt = 0L
    }

    /** Analytical damped spring: identical at any frame rate, with velocity
     *  retained on interruption. (Ported Morph, constants unchanged.) */
    private class Morph {
        private var current = 0.0
        private var target = 0.0
        private var velocity = 0.0
        private var sampled = -1L

        fun value(destination: Float, now: Long, reducedMotion: Boolean, impulse: Double): Float {
            if (sampled < 0 || reducedMotion) {
                current = destination.toDouble()
                target = destination.toDouble()
                velocity = 0.0
            } else {
                val seconds = max(0, now - sampled) / 1000.0
                val displacement = current - target
                val sineCoefficient = (velocity + MORPH_DECAY * displacement) / MORPH_FREQUENCY
                val cosine = cos(MORPH_FREQUENCY * seconds)
                val sine = sin(MORPH_FREQUENCY * seconds)
                val decay = exp(-MORPH_DECAY * seconds)
                val offset = displacement * cosine + sineCoefficient * sine
                current = target + decay * offset
                velocity = decay * (-MORPH_DECAY * offset
                    + MORPH_FREQUENCY * (-displacement * sine + sineCoefficient * cosine))
                if (destination.toDouble() != target) {
                    target = destination.toDouble()
                    if (abs(velocity) < max(0.1, abs(destination - current) * 0.1)) {
                        velocity = (destination - current) * impulse
                    }
                }
                if (abs(current - target) < 0.01 && abs(velocity) < 0.1) {
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
            val progress = ((now - started) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            val eased = if (to < from) progress
            else if (progress >= 1) 1f
            else 1 - 2f.pow(-10 * progress)
            return from + (to - from) * eased
        }
    }

    // ================================================================
    //  Ported idle status layout — DynamicIslandStatus
    // ================================================================

    private const val STATUS_FONT_SIZE = 8f
    private const val STATUS_INSET = 9f
    private const val STATUS_RIGHT_INSET = 12.5f
    private const val STATUS_ICON_SIZE = 10.5f
    private const val STATUS_ICON_GAP = 3.5f
    private const val STATUS_FOREGROUND = 0xFFE4E1E6.toInt()
    private const val STATUS_ONLINE = 0xFF80C810.toInt()
    private const val STATUS_PING_ORANGE = 0xFFC87D28.toInt()
    private const val STATUS_PING_RED = 0xFFCA3E2E.toInt()

    private enum class Symbol { CHROME, USER, LINK, REFRESH, BED }

    private class Part(val text: String, val icon: Symbol?, val color: Int, val x: Float, val width: Float)
    private class StatusLayout(val parts: List<Part>, val width: Float)

    private class Status(
        val username: String, val server: String?, val ping: Int, val fps: Int,
    ) {
        fun singleplayer(): Boolean = server == null

        fun layout(measure: TextWidth, available: Float, clientName: String): StatusLayout {
            val parts = ArrayList<Part>()
            val fixed = STATUS_INSET + STATUS_RIGHT_INSET + 4 * (STATUS_ICON_SIZE + STATUS_ICON_GAP) +
                3 * (2 + measure.measure("  ·  ", STATUS_FONT_SIZE)) + measure.measure(clientName, STATUS_FONT_SIZE) +
                measure.measure(max(0, fps).toString() + " fps", STATUS_FONT_SIZE) +
                if (singleplayer()) 0f else measure.measure(pingText() + " to ", STATUS_FONT_SIZE)
            var connection = if (singleplayer()) "singleplayer" else server!!
            val remaining = max(0f, available - fixed)
            val userWidth = measure.measure(username, STATUS_FONT_SIZE)
            val connectionWidth = measure.measure(connection, STATUS_FONT_SIZE)
            val userBudget = if (userWidth + connectionWidth <= remaining) userWidth
            else min(userWidth, max(remaining - connectionWidth, remaining * 0.4f))
            val user = fit(username, STATUS_FONT_SIZE, userBudget, measure)
            connection = fit(connection, STATUS_FONT_SIZE, max(0f, remaining - measure.measure(user, STATUS_FONT_SIZE)), measure)
            var x = STATUS_INSET
            x = addIcon(parts, Symbol.CHROME, paletteOf().accent, x)
            x = addLabel(parts, clientName, paletteOf().accent, x, measure)
            x = addSeparator(parts, x, measure)
            x = addIcon(parts, Symbol.USER, STATUS_FOREGROUND, x)
            x = addLabel(parts, user, STATUS_FOREGROUND, x, measure)
            x = addSeparator(parts, x, measure)
            x = addIcon(parts, Symbol.LINK, if (singleplayer()) STATUS_FOREGROUND else pingColor(), x)
            if (!singleplayer()) {
                x = addLabel(parts, pingText(), pingColor(), x, measure)
                x = addLabel(parts, " to ", STATUS_FOREGROUND, x, measure)
            }
            x = addLabel(parts, connection, STATUS_FOREGROUND, x, measure)
            x = addSeparator(parts, x, measure)
            x = addIcon(parts, Symbol.REFRESH, STATUS_FOREGROUND, x)
            addLabel(parts, max(0, fps).toString() + " fps", STATUS_FOREGROUND, x, measure)
            return StatusLayout(parts, x + STATUS_RIGHT_INSET)
        }

        private fun pingText() = if (ping < 0) "...ms" else ping.toString() + "ms"
        private fun pingColor(): Int = when {
            ping < 0 -> STATUS_FOREGROUND
            ping < 100 -> STATUS_ONLINE
            ping <= 400 -> STATUS_PING_ORANGE
            else -> STATUS_PING_RED
        }

        private fun addIcon(parts: ArrayList<Part>, icon: Symbol, color: Int, x: Float): Float {
            parts.add(Part("", icon, color, x, STATUS_ICON_SIZE))
            return x + STATUS_ICON_SIZE + STATUS_ICON_GAP
        }

        private fun addLabel(parts: ArrayList<Part>, text: String, color: Int, x: Float, measure: TextWidth): Float {
            val width = measure.measure(text, STATUS_FONT_SIZE)
            parts.add(Part(text, null, color, x, width))
            return x + width
        }

        private fun addSeparator(parts: ArrayList<Part>, x: Float, measure: TextWidth): Float =
            addLabel(parts, "  ·  ", STATUS_FOREGROUND, x + 1.25f, measure) + 0.75f
    }

    // Painter helpers from DynamicIslandState (unchanged semantics).
    private fun fit(text: String, size: Float, width: Float, measure: TextWidth): String {
        if (width <= 0) return ""
        if (measure.measure(text, size) <= width + 0.01f) return text
        if (measure.measure("...", size) > width) return ""
        var end = text.length
        while (end > 0 && measure.measure(text.substring(0, end) + "...", size) > width) {
            end = text.offsetByCodePoints(end, -1)
        }
        return text.substring(0, end) + "..."
    }

    private fun detailLine(detail: String, status: String, width: Float, measure: TextWidth): DetailLine {
        val suffix = fit(status, DETAIL_FONT, width, measure)
        val remaining = max(0f, width - measure.measure(suffix, DETAIL_FONT))
        val truncated = measure.measure(detail, DETAIL_FONT) > remaining + 0.01f
        val gap = if (truncated && suffix.isNotEmpty()) measure.measure(" ", DETAIL_FONT) else 0f
        var prefix = fit(detail, DETAIL_FONT, max(0f, remaining - gap), measure)
        if (prefix.isNotEmpty() && gap > 0) prefix += " "
        return DetailLine(prefix, suffix, measure.measure(prefix, DETAIL_FONT))
    }

    // ================================================================
    //  Ported painter — DynamicIslandPainter (colors kept verbatim)
    // ================================================================

    private const val BG_TILE = 0xC8141616.toInt()
    private const val BG_ENABLED = 0xFF55FF55.toInt()
    private const val BG_DISABLED = 0xFFFF5555.toInt()
    private const val SHADOW_EXTENT = 8f
    private const val SHADOW_STEP = 0.25f
    // HudGlassStyle.BODY
    private const val BACKGROUND = 0x98000000.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()

    private fun alpha(color: Int, opacity: Float): Int =
        (((color ushr 24) * opacity.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)

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

    // ================================================================
    //  GuiGraphicsExtractor-backed painter surface.
    //  Replaces Samsara's DynamicIslandNanoSurface (NanoVG) with the exact
    //  same drawing contract, such that every draw call kept its original
    //  coordinates, sizes and colors.
    //
    //  Text is drawn through the Minecraft font at a logical scale factor
    //  (island font sizes are close to, but not equal to, the vanilla 9px
    //  line height, so exact fidelity needs the pose to be sub-9px
    //  sometimes; here sizes are mapped 1:1 via pose scaling).
    // ================================================================

    private class GuiSurface(
        private val gfx: GuiGraphicsExtractor,
        private val font: Font,
    ) {
        /** Vertical alignment: the original passes a pixel-space top-Y for
         *  the NanoVG baseline trick. This port recenters the MC font so
         *  `centerY` matches the original visual midline exactly. */
        private fun yCenter(centerY: Float, size: Float): Int =
            (centerY - (size / 2f) - (font.lineHeight - size) / 2f).roundToInt()

        fun measure(text: String, size: Float): Float =
            font.width(text) * (size / font.lineHeight)

        fun rounded(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
            gfx.drawRoundedRect(x, y, x + w, y + h, radius, Color4b(color))
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int) {
            gfx.drawLines(floatArrayOf(x1, y1, x2, y2), color, gfx.getBoundsXYWH(
                min(x1, x2) - stroke, min(y1, y2) - stroke,
                abs(x2 - x1) + stroke * 2, abs(y2 - y1) + stroke * 2,
            ), true)
        }

        fun text(text: String, x: Float, centerY: Float, size: Float, color: Int) {
            if (text.isEmpty()) return
            val s = size / font.lineHeight
            if (s == 1f) {
                gfx.text(font, text, x.roundToInt(), yCenter(centerY, size), color, false)
            } else {
                gfx.pose().withPush {
                    translate(x, yCenter(centerY, size).toFloat() + font.lineHeight)
                    scale(s, s)
                    gfx.text(font, text, 0, -font.lineHeight, color, false)
                }
            }
        }

        fun shadow(x: Float, y: Float, w: Float, h: Float, radius: Float) {
            var spread = SHADOW_EXTENT
            while (spread > 0) {
                val layer = shadowLayer(spread)
                if (layer != 0) {
                    gfx.drawRoundedRect(
                        x - spread, y - spread, x + w + spread, y + h + spread,
                        radius + spread, Color4b(layer),
                    )
                }
                spread -= SHADOW_STEP
            }
        }

        fun clip(x: Float, y: Float, w: Float, h: Float, content: Runnable) {
            if (w <= 0 || h <= 0) return
            // GuiGraphicsExtractor.enableScissor is public; ScissorStack is not.
            // Nested clips intersect in the same stack, so pairing push/pop is
            // identity-safe here (NativeClickGuiScreen does the same).
            gfx.enableScissor(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())
            try {
                content.run()
            } finally {
                gfx.disableScissor()
            }
        }

        fun symbol(symbol: Symbol, x: Float, y: Float, size: Float, color: Int) {
            val s = size / 24f
            gfx.pose().withPush {
                translate(x, y)
                gfx.pose().scale(s, s)
                when (symbol) {
                    Symbol.BED -> { /* filled frame + pillow */ fillBed(this@GuiSurface, color) }
                    Symbol.USER -> { drawUser(this@GuiSurface, color) }
                    Symbol.LINK -> { drawLink(this@GuiSurface, color) }
                    Symbol.REFRESH -> { drawRefresh(this@GuiSurface, color) }
                    Symbol.CHROME -> { drawChrome(this@GuiSurface, color) }
                }
            }
        }

        // -- glyph primitives ported from DynamicIslandNanoSurface.symbol --
        // The NanoVG path art is reduced to the same glyph silhouettes built
        // from rounded quads (the stock LB GUI pipeline has no path tessellator).

        private fun fillBed(s: GuiSurface, color: Int) {
            s.rounded(0f, 7f, 24f, 8f, 1.5f, color)            // frame bar
            s.rounded(0f, 0.75f, 3f, 15f, 0.75f, color)        // headboard
            s.rounded(10.8f, 3f, 13.2f, 5.4f, 1.8f, color)     // mattress
        }

        private fun drawUser(s: GuiSurface, color: Int) {
            s.rounded(7f, 2.5f, 10f, 10f, 5f, color)           // head
            s.rounded(3.5f, 14.5f, 17f, 7.5f, 3.5f, color)     // shoulders
        }

        private fun drawLink(s: GuiSurface, color: Int) {
            s.rounded(2.25f, 9f, 9.75f, 6f, 2.5f, color)       // left arc
            s.rounded(12f, 9f, 9.75f, 6f, 2.5f, color)         // right arc
            s.rounded(9.75f, 9f, 4.5f, 6f, 0f, color)          // bridge
        }

        private fun drawRefresh(s: GuiSurface, color: Int) {
            for (i in 0..1) {
                s.rounded(
                    if (i == 0) 10.5f else 4.5f,
                    if (i == 0) 1f else 13f,
                    3f, 10f, 1.5f, color,
                )
                s.rounded(
                    if (i == 0) 4.5f else 17f,
                    if (i == 0) 5.5f else 8.5f,
                    7f, 6.5f, 1.75f, color,
                )
            }
        }

        private fun drawChrome(s: GuiSurface, color: Int) {
            // Three lobes kept separate, matching the original cut layout.
            s.rounded(2f, 2f, 8.5f, 8.5f, 4.25f, color)
            s.rounded(13.5f, 2f, 8.5f, 8.5f, 4.25f, color)
            s.rounded(7.75f, 11f, 8.5f, 8.5f, 4.25f, color)
            s.rounded(8.8f, 8.8f, 6.4f, 6.4f, 3.2f, color)
        }
    }

    private fun shadowCoverage(distance: Float): Float {
        val normalized = max(0f, distance) / 2.8f
        return 0.5f * exp(-0.78f * normalized - 0.5f * normalized * normalized)
    }

    private fun shadowLayer(spread: Float): Int {
        val previous = shadowCoverage(spread + SHADOW_STEP)
        val desired = shadowCoverage(spread)
        return (255 * (desired - previous) / (1 - previous)).roundToInt() shl 24
    }

    // ================================================================
    //  State machine core — ported frame()/post()
    // ================================================================

    private val notices = ArrayList<Notice>()
    private val widthMorph = Morph()
    private val heightMorph = Morph()
    private val positionMorph = Morph()
    private var wasIdle = true
    private var wasPanel = false

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun paletteOf(): Palette = palette

    private fun defaultStatus(mc: Minecraft): Status {
        val username = mc.user?.name ?: "Player"
        var ping = -1
        val player = mc.player
        val connection = mc.connection
        if (player != null && connection != null) {
            val info = connection.getPlayerInfo(player.uuid)
            if (info != null) ping = max(0, info.latency)
        }
        val address = when {
            mc.hasSingleplayerServer() -> null
            mc.currentServer != null -> mc.currentServer!!.ip
            else -> "server"
        }
        return Status(username, address, ping, mc.fps)
    }

    // Scaffold bridges (stock LiquidBounce module state only).
    private val scaffoldModule: ClientModule?
        get() = runCatching { ModuleManager["Scaffold"] }.getOrNull()

    private fun inventoryBlocks(mc: Minecraft): Int {
        val player = mc.player ?: return 0
        var total = 0
        val inventory = player.inventory
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (!stack.isEmpty && stack.item is BlockItem) total += stack.count
        }
        return total
    }

    private fun post(key: String, title: String, detail: String, status: String,
                     icon: Icon, enabled: Boolean, now: Long, duration: Long) {
        post(key, title, detail, status, icon, enabled, 0f, now, duration)
    }

    private fun post(key: String, title: String, detail: String, status: String,
                     icon: Icon, enabled: Boolean, progress: Float, now: Long, duration: Long) {
        notices.removeIf { now >= it.expiresAt }
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
        while (notices.size > MAX_NOTICES) notices.removeFirst()
    }

    private fun postScaffold(detail: String, progress: Float, now: Long) {
        notices.removeIf { it.key != "scaffold" }
        post("scaffold", "Scaffold Toggled", detail, "", Icon.SCAFFOLD, true,
            progress.coerceIn(0f, 1f), now, 500)
    }

    private fun postBreaking(blockName: String, progress: Float, now: Long) {
        val title = "Breaking $blockName"
        notices.removeIf { it.key != "bed-aura" || it.title != title }
        val clamped = progress.coerceIn(0f, 1f)
        post("bed-aura", title, "Break Progress: ${(clamped * 100).toInt()}%", "", Icon.BREAKING, true,
            clamped, now, 500)
    }

    private fun removeNotice(key: String) {
        notices.removeIf { it.key == key }
    }

    private fun frame(now: Long, viewportWidth: Float, status: Status, measure: TextWidth,
                      reducedMotion: Boolean): Frame {
        val available = max(1f, viewportWidth - 16)
        val idle = status.layout(measure, available, paletteOf().tag)
        val idleWidth = min(available, idle.width)
        val rows = ArrayList<Row>()
        var occupied = 0f
        var largestWidth = 0f
        notices.removeIf { now >= it.expiresAt }
        for (notice in notices) {
            val progressPanel = notice.icon == Icon.SCAFFOLD || notice.icon == Icon.BREAKING
            val fullHeight =
                if (notice.icon == Icon.BREAKING) BREAKING_HEIGHT
                else if (progressPanel) SCAFFOLD_HEIGHT
                else ROW_HEIGHT
            rows.add(
                Row(
                    notice.title, notice.detail, notice.status, notice.icon, notice.enabled,
                    if (reducedMotion) (if (notice.enabled) 1f else 0f) else notice.toggle.value(now),
                    notice.progress, occupied, fullHeight, 1f,
                )
            )
            val textWidth = if (notice.icon == Icon.BREAKING) {
                max(
                    measure.measure(notice.title, BREAKING_TITLE_FONT),
                    measure.measure("Break Progress: 100%", BREAKING_DETAIL_FONT),
                )
            } else {
                max(
                    measure.measure(notice.title, TITLE_FONT),
                    measure.measure(notice.detail, DETAIL_FONT) + measure.measure(notice.status, DETAIL_FONT) +
                        if (notice.status.isEmpty()) 0f else measure.measure("!", DETAIL_FONT),
                )
            }
            val contentWidth = if (progressPanel) SCAFFOLD_TEXT_X + textWidth + SCAFFOLD_RIGHT_PADDING
            else NOTICE_TEXT_X + textWidth + NOTICE_RIGHT_PADDING
            notice.contentWidth = max(notice.contentWidth, contentWidth)
            largestWidth = max(largestWidth, min(available, notice.contentWidth))
            occupied += fullHeight
        }
        val isIdle = rows.isEmpty()
        val targetWidth = if (isIdle) idleWidth else largestWidth
        val targetHeight = occupied.coerceIn(IDLE_HEIGHT, MAX_NOTICES * ROW_HEIGHT)
        val impulse = if (isIdle != wasIdle || wasPanel) (if (isIdle) 5.5 else 7.25) else 0.0
        val animatedWidth = widthMorph.value(targetWidth, now, reducedMotion, impulse).coerceIn(1f, available)
        val animatedHeight = max(1f, heightMorph.value(targetHeight, now, reducedMotion, impulse))
        val scaffold = rows.any { it.icon == Icon.SCAFFOLD }
        // Idle stays at IDLE_TOP; any panel (scaffold/breaking/notice) pops up to TOP.
        val targetTop = if (isIdle) IDLE_TOP else if (scaffold) TOP else IDLE_TOP
        val top = positionMorph.value(targetTop, now, reducedMotion, impulse)
        val radius = if (isIdle) animatedHeight / 2 else RADIUS
        wasIdle = isIdle
        wasPanel = false
        return Frame(animatedWidth, animatedHeight, if (isIdle) 1f else 0f, idle, rows.toList(), targetWidth, top, radius)
    }

    private fun clear() {
        notices.clear()
        widthMorph.clear()
        heightMorph.clear()
        positionMorph.clear()
        wasIdle = true
        wasPanel = false
    }

    // ================================================================
    //  Paint — DynamicIslandPainter.paint / paintShell / paintContent
    // ================================================================

    private fun paintShell(surface: GuiSurface, frame: Frame, viewportWidth: Float) {
        val x = (viewportWidth - frame.width) / 2
        surface.shadow(x, frame.top, frame.width, frame.height, frame.radius)
        surface.rounded(x, frame.top, frame.width, frame.height, frame.radius, BACKGROUND)
    }

    private fun paintContent(surface: GuiSurface, frame: Frame, viewportWidth: Float, palette: Palette,
                             font: Font) {
        val x = (viewportWidth - frame.width) / 2
        val top = frame.top
        surface.clip(x + 2, top + 1, max(0f, frame.width - 4), frame.height - 2) {
            if (frame.idleOpacity > 0) {
                drawIdle(surface, frame, x, palette)
            }
            for (row in frame.rows) {
                if (row.opacity <= 0 || row.height <= 0) continue
                val y = top + row.y
                surface.clip(x + 3, y, max(0f, frame.width - 6), row.height) {
                    drawRow(surface, row, x, y, frame.width, max(frame.width, frame.contentWidth), palette, font)
                }
            }
        }
    }

    private fun drawIdle(surface: GuiSurface, frame: Frame, x: Float, palette: Palette) {
        val centerY = frame.top + IDLE_HEIGHT / 2
        var previousIcon: Symbol? = null
        for (part in frame.idle.parts) {
            val color = alpha(
                if (part.icon == Symbol.CHROME || previousIcon == Symbol.CHROME) palette.accent else part.color,
                frame.idleOpacity,
            )
            previousIcon = part.icon
            if (part.icon == null) {
                surface.text(part.text, x + part.x, centerY, STATUS_FONT_SIZE, color)
            } else {
                surface.symbol(part.icon, x + part.x, centerY - part.width / 2, part.width, color)
            }
        }
    }

    private fun drawRow(surface: GuiSurface, row: Row, x: Float, y: Float, width: Float,
                        contentWidth: Float, palette: Palette, font: Font) {
        val opacity = row.opacity
        if (row.icon == Icon.SCAFFOLD || row.icon == Icon.BREAKING) {
            drawProgressPanel(surface, row, x, y, width, contentWidth, opacity, palette)
            return
        }
        drawIcon(surface, row, x, y, palette)
        val textX = x + NOTICE_TEXT_X
        val textWidth = max(0f, contentWidth - NOTICE_TEXT_X - NOTICE_RIGHT_PADDING)
        surface.text(fit(row.title, TITLE_FONT, textWidth, surface::measure), textX, y + 13.5f,
            TITLE_FONT, alpha(TEXT, opacity))
        val punctuationWidth = if (row.status.isEmpty()) 0f else surface.measure("!", DETAIL_FONT)
        val line = detailLine(row.detail, row.status, max(0f, textWidth - punctuationWidth), surface::measure)
        surface.text(line.prefix, textX, y + 24.75f, DETAIL_FONT, alpha(TEXT, opacity))
        surface.text(line.status, textX + line.statusOffset, y + 24.75f, DETAIL_FONT,
            alpha(if (row.enabled) BG_ENABLED else BG_DISABLED, opacity))
        if (line.status.isNotEmpty()) {
            surface.text("!", textX + line.statusOffset + surface.measure(line.status, DETAIL_FONT),
                y + 24.75f, DETAIL_FONT, alpha(TEXT, opacity))
        }
    }

    private fun drawProgressPanel(surface: GuiSurface, row: Row, x: Float, y: Float, width: Float,
                                  contentWidth: Float, opacity: Float, palette: Palette) {
        val breaking = row.icon == Icon.BREAKING
        val tile = alpha(BG_TILE, opacity)
        val ink = alpha(palette.accent, opacity)
        val tileSize = if (breaking) 27f else 28f
        surface.rounded(x + 4, y + 5, tileSize, tileSize, 7f, tile)
        if (breaking) {
            surface.symbol(Symbol.BED, x + 10, y + 13.5f, 16f, ink)
        } else {
            drawCube(surface, x + 18, y + 19, 14f, 1f, ink)
        }
        val textWidth = max(0f, contentWidth - SCAFFOLD_TEXT_X - SCAFFOLD_RIGHT_PADDING)
        if (breaking) {
            surface.text(fit(row.title, BREAKING_TITLE_FONT, textWidth, surface::measure), x + SCAFFOLD_TEXT_X,
                y + 12, BREAKING_TITLE_FONT, ink)
            surface.text(fit(row.detail, BREAKING_DETAIL_FONT, textWidth, surface::measure), x + SCAFFOLD_TEXT_X,
                y + 24, BREAKING_DETAIL_FONT, alpha(TEXT, opacity))
        } else {
            surface.text(fit(row.title, TITLE_FONT, textWidth, surface::measure), x + SCAFFOLD_TEXT_X, y + 13,
                TITLE_FONT, ink)
            surface.text(fit(row.detail, DETAIL_FONT, textWidth, surface::measure), x + SCAFFOLD_TEXT_X, y + 25,
                DETAIL_FONT, alpha(TEXT, opacity))
        }
        val barWidth = max(0f, width - 8)
        val barY = y + (if (breaking) 35 else 37)
        val barHeight = if (breaking) 7.5f else 8f
surface.rounded(x + 4, barY, barWidth, barHeight, barHeight / 2,
            alpha(if (breaking) 0x7034343D.toInt() else 0xFF343636.toInt(), opacity))
        val filledWidth = barWidth * row.progress.coerceIn(0f, 1f)
        if (filledWidth > 0) {
            surface.rounded(x + 4, barY, filledWidth, barHeight, min(barHeight / 2, filledWidth / 2),
                alpha(palette.hudProgress, opacity))
        }
    }

    private fun drawCube(surface: GuiSurface, cx: Float, cy: Float, size: Float, stroke: Float, color: Int) {
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

    private fun drawIcon(surface: GuiSurface, row: Row, x: Float, y: Float, palette: Palette) {
        val a = row.opacity
        if (row.icon == Icon.TOGGLE) {
            val t = row.toggle
            surface.rounded(x + 5, y + 10, 26f, 16f, 8f, alpha(mix(palette.toggleOutline, palette.accent, t), a))
            surface.rounded(x + 6, y + 11, 24f, 14f, 7f, alpha(mix(0xFF36343B.toInt(), palette.accent, t), a))
            val knobSize = 8 + 3.5f * t
            surface.rounded(x + 8.5f + 8.5f * t, y + 13.5f - 1.25f * t,
                knobSize, knobSize, knobSize / 2, alpha(mix(0xFF858488.toInt(), palette.hudKnob, t), a))
            return
        }
        val tile = when (row.icon) {
            Icon.SUCCESS -> 0xC85F8F50.toInt()
            Icon.WARNING -> 0xC88F5050.toInt()
            else -> 0xC8307593.toInt()
        }
        surface.rounded(x + 7, y + 8, 24f, 24f, 7f, alpha(tile, a))
        val ink = alpha(TEXT, a)
        if (row.icon == Icon.SUCCESS) {
            surface.line(x + 13, y + 20, x + 18, y + 25, 2f, ink)
            surface.line(x + 18, y + 25, x + 26, y + 14, 2f, ink)
        } else if (row.icon == Icon.WARNING) {
            surface.line(x + 19, y + 13, x + 19, y + 22, 2f, ink)
            surface.rounded(x + 18, y + 25, 2f, 2f, 1f, ink)
        } else {
            surface.line(x + 19, y + 16, x + 19, y + 26, 2f, ink)
            surface.rounded(x + 18, y + 12, 2f, 2f, 1f, ink)
        }
    }

    // ================================================================
    //  Module wiring (LiquidBounce event pipeline)
    // ================================================================

    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { event ->
        if (!moduleToggles) return@handler
        val now = nowMs()
        val label = event.moduleName.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        post("module:" + event.moduleName, "Module Toggled", label + " has been ",
            if (event.enabled) "Enabled" else "Disabled", Icon.TOGGLE, event.enabled, now, TOGGLE_MS)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.level == null) {
            clear()
            return@handler
        }
        // Samsara hides the island while a full screen is up; the overlay event
        // only fires over the in-game HUD, so nothing extra is needed here.

        // ---- status data refresh -------------------------------------------
        val now = nowMs()
        if (scaffoldInfo) {
            val scaffold = scaffoldModule
            if (scaffold == null || !scaffold.enabled) {
                removeNotice("scaffold")
            } else {
                val blocks = inventoryBlocks(mc)
                val detail = String.format(java.util.Locale.ROOT, "%d blocks left", max(0, blocks))
                postScaffold(detail, min(1f, blocks / 100f), now)
            }
        } else {
            removeNotice("scaffold")
        }

        // ---- frame + paint --------------------------------------------------
        val window = mc.window
        val screenWidth = window.guiScaledWidth.toFloat()
        val s = scale.coerceIn(0.5f, 2.0f)
        val logicalWidth = screenWidth / s
        val font = mc.font
        val surface = GuiSurface(event.context, font)
        val frame = frame(now, logicalWidth, defaultStatus(mc), surface::measure,
            mc.options.screenEffectScale().get() <= 0)

        event.context.pose().withPush {
            scale(s, s)
            // Re-emit at the animated top: Samsara translates by top * (1 - scale).
            paintShell(surface, frame, logicalWidth)
            paintContent(surface, frame, logicalWidth, paletteOf(), font)
        }
    }

    override fun onDisabled() {
        clear()
    }
}
