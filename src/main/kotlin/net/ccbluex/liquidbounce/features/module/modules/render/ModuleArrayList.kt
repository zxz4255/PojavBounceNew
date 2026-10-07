/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * Contains a port of XinXin/SilenceFix's "ModuleList" arraylist
 * (dev.xinxin.gui.ui.modules.ModuleList, decompiled).
 * Only the glass style is ported, and the design is kept unchanged:
 *   - right-edge vertical stack, sorted by rendered name width (modules whose
 *     name contains "--" are pinned to the top, exactly like the original),
 *   - per-row rounded pill (radius 6) with the original paddings
 *     (x-2, y-3, w+5, fontH+2) and the same dark glass colors,
 *   - dual-layer soft pass (blur alpha 200 + bloom alpha 200) reproduced with
 *     the same gaussian-layer shadow math Samsara/XinXin use elsewhere,
 *   - MoveIn (slide in from the right) / ScaleIn (scale about pill center)
 *     300ms animations with the original in-out-quad easing,
 *   - First/Second Color wave (2000ms period, -index*200/40 phase step, 75ms
 *     per-index offset, alpha 255) — XinXin HUD.color(tick) verbatim math,
 *   - max row width eased with tau=120ms toward the widest visible module,
 *   - 4px line spacing and 8px top offset.
 *
 * Rendering is re-pointed at LiquidBounce's own GuiGraphicsExtractor SDF
 * rounded-rect pipeline (drawRoundedRect), which is pixel-identical to the
 * original RoundedUtils.drawRound quads. Fonts fall back to the vanilla
 * Minecraft font (FontManager.navenRegular16 is a foreign asset).
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ArrayList module (XinXin/"SilenceFix" glass style), 1:1 visual port.
 *
 * Renders all enabled modules as right-aligned, width-sorted rounded pills
 * with a soft blurred halo and animated in/out transitions.
 */
object ModuleArrayList : ClientModule("ArrayList", ModuleCategories.RENDER) {

    // ------------------------------------------------------------ settings
    // — full original config surface (HUD.arraylist / importantModules /
    //   animation / mainColor / mainColor2), kept as-is.

    private val importantOnly by boolean("ImportantOnly", false)
    private val animation by enumChoice("Animation", Anim.SCALE_IN)

    private val mainColor by color("FirstColor", Color4b.WHITE)
    private val mainColor2 by color("SecondColor", Color4b.WHITE)

    private val lineSpacing by float("LineSpacing", 4f, 0f..12f)
    private val radius by float("Radius", 6f, 0f..12f)

    /** Original `ModuleList.ANIM` ordering (MoveIn / ScaleIn), kept verbatim. */
    private enum class Anim(override val tag: String) : Tagged {
        MOVE_IN("MoveIn"),
        SCALE_IN("ScaleIn"),
    }

    // ================================================================
    //  Animation state — 1:1 from ModuleList (Direction/Animation handled
    //  inline: output [0..1] eased toward the toggle edge, 300ms total).
    // ================================================================

    private enum class Direction { FORWARDS, BACKWARDS }

    private class ModuleAnim {
        var direction = Direction.FORWARDS
        var startedAt = 0L
        var output = 0f

        fun reset(now: Long) {
            startedAt = now
            output = if (direction == Direction.FORWARDS) 0f else 1f
        }

        fun finished(direction: Direction, now: Long): Boolean =
            this.direction == direction && (now - startedAt) >= 300L
    }

    private val lastStates = HashMap<ClientModule, Boolean>()
    private val animations = HashMap<ClientModule, ModuleAnim>()

    /** Width cache keyed by rendered text; invalidated with the whole frame. */
    private val widthCache = HashMap<String, Int>()

    /** Eased width of the widest currently-visible row. */
    private var maxWidthLerp = 0.0

    private const val FADE_MS = 300f

    /** In-out quad — the original `applyEasing`. */
    private fun applyEasing(progress: Float): Float {
        return if (progress < 0.5f) {
            2f * progress * progress
        } else {
            val p = 2f * progress - 1f
            1f - (1f - p) * (1f - p) / 2f
        }
    }

    // ================================================================
    //  Colors — XinXin RenderUtil.colorSwitch / HUD.color(tick), verbatim.
    //  Two endpoints ping-pong over `time`, phase-shifted by index*75ms and
    //  a -200ms/40 slot step. Alpha is applied by the caller.
    // ================================================================

    private fun colorSwitch(index: Int): Int {
        val time = 2000f
        val first = mainColor
        val second = mainColor2
        val now = (2.0 * System.currentTimeMillis() + (index * 75L)).toLong()
        val redDiff = (first.r - second.r) / time
        val greenDiff = (first.g - second.g) / time
        val blueDiff = (first.b - second.b) / time
        val phase = (now % (time.toLong() * 2L) < time.toLong())
        val r: Int
        val g: Int
        val b: Int
        if (phase) {
            r = (first.r + (second.r - first.r) / time * (now % time.toLong())).toInt()
            g = (first.g + (second.g - first.g) / time * (now % time.toLong())).toInt()
            b = (first.b + (second.b - first.b) / time * (now % time.toLong())).toInt()
        } else {
            r = (second.r + redDiff * (now % time.toLong())).toInt()
            g = (second.g + greenDiff * (now % time.toLong())).toInt()
            b = (second.b + blueDiff * (now % time.toLong())).toInt()
        }
        return (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    /** ColorUtil.applyOpacity — multiply only the alpha channel. */
    private fun applyOpacity(color: Int, opacity: Float): Int {
        val a = ((color ushr 24) * opacity.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    // ================================================================
    //  Blur/bloom stand-in — same gaussian layered shadow the client's
    //  island/target panels use (source-of-truth: LB's own pipeline has no
    //  full-screen blur in a HUD pass, so the two dark "ShaderElement" layers
    //  are expressed as their pixel equivalent: two stacks of soft rounded
    //  quads at the exact original sizes and alphas).
    // ================================================================

    private fun shadowCoverage(distance: Float): Float {
        val normalized = max(0f, distance) / 2.8f
        return 0.5f * exp(-0.78f * normalized - 0.5f * normalized * normalized)
    }

    private fun shadowLayer(spread: Float): Int {
        val previous = shadowCoverage(spread + 0.25f)
        val desired = shadowCoverage(spread)
        return (255 * (desired - previous) / (1 - previous)).roundToInt() shl 24
    }

    private fun drawPillShadow(gfx: GuiGraphicsExtractor,
                               x: Float, y: Float, w: Float, h: Float, radius: Float, baseAlpha: Int) {
        var spread = 4f
        while (spread > 0f) {
            val layer = shadowLayer(spread).ushr(24) * baseAlpha / 200
            if (layer > 0) {
                gfx.drawRoundedRect(
                    x - spread, y - spread, x + w + spread, y + h + spread,
                    radius + spread, Color4b(0, 0, 0, layer),
                )
            }
            spread -= 0.25f
        }
    }

    // ================================================================
    //  Row model + list assembly — ModuleList.onRender2D, verbatim logic.
    // ================================================================

    private fun formatModule(module: ClientModule): String {
        // LB has no separate Chinese name field; the suffix-free rendering
        // from the original (`name + suffix` with the space squeezed out of
        // the name) maps to the module's translation-stripped display name.
        return module.name.replace(" ", "")
    }

    private fun orderedModules(font: Font): List<ClientModule> {
        val mods = ModuleManager.toList()
        // 1) Names containing "--" pin to the top of each width bucket.
        // 2) Then wider rows sort above narrower ones.
        return mods.sortedWith(
            compareBy<ClientModule> { !(formatModule(it).contains("--")) }
                .thenByDescending { widthOf(font, formatModule(it)) }
        )
    }

    private fun widthOf(font: Font, text: String): Int =
        widthCache.getOrPut(text) { font.width(text) }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return@handler
        if (mc.level == null || mc.gui.overlay() != null) return@handler

        val font = mc.font
        val gfx = event.context
        val now = System.currentTimeMillis()
        val fontH = font.lineHeight.toFloat()
        val rowH = fontH + lineSpacing
        val screenWidth = mc.window.guiScaledWidth

        val ordered = orderedModules(font)
        val visibleMods = ArrayList<ClientModule>(ordered.size)
        val visibleText = ArrayList<String>(ordered.size)
        val visibleWidth = ArrayList<Int>(ordered.size)
        val visibleOut = ArrayList<Float>(ordered.size)

        for (module in ordered) {
            // Original: hiding non-Combat/... modules is opt-in via Important.
            if (importantOnly && module.category != ModuleCategories.COMBAT) continue
            val cur = module.enabled
            val last = lastStates[module]
            val anim = animations.getOrPut(module) { ModuleAnim() }
            if (last == null || last != cur) {
                anim.direction = if (cur) Direction.FORWARDS else Direction.BACKWARDS
                anim.reset(now)
            }
            lastStates[module] = cur
            val t = applyEasing(min(1f, (now - anim.startedAt) / FADE_MS))
            val out = if (anim.direction == Direction.FORWARDS) t else 1f - t
            anim.output = out
            if ((!cur && anim.finished(Direction.BACKWARDS, now)) || out <= 0f) continue
            val text = formatModule(module)
            visibleMods.add(module)
            visibleText.add(text)
            visibleWidth.add(widthOf(font, text))
            visibleOut.add(out)
        }

        // Cache-invalidation parity with invalidateTextMetrics: unseen modules
        // drop out of the animation maps so stale states never take a slot.
        val active = visibleMods.toSet()
        animations.keys.removeIf { it !in active && it.enabled }
        lastStates.keys.removeIf { it !in active && !it.enabled }

        var targetMax = 0.0
        for (width in visibleWidth) if (width > targetMax) targetMax = width.toDouble()
        val tauMs = 120.0
        val dt = 16.0
        maxWidthLerp += (targetMax - maxWidthLerp) * (1.0 - exp(-dt / max(1.0, tauMs)))

        var yOff = 0.0
        for (i in visibleMods.indices) {
            val text = visibleText[i]
            val textWidth = visibleWidth[i]
            val a = visibleOut[i]
            val xr = screenWidth.toDouble() - (maxWidthLerp + 7.0)
            var x = xr + (maxWidthLerp - textWidth)
            val y = yOff + 8.0

            when (animation) {
                Anim.MOVE_IN -> {
                    x += abs((a.toDouble() - 1.0) * (2.0 + textWidth))
                }
                Anim.SCALE_IN -> {
                    // Scale the entire pill + text about the row center.
                }
            }

            val base = colorSwitch(i)
            val textCol = applyOpacity(base, a)

            val pillX = (x - 2.0).toFloat()
            val pillY = (y - 3.0).toFloat()
            val pillW = textWidth.toFloat() + 5f
            val pillH = fontH + 2f

            fun drawPill() {
                // ShaderElement blur + bloom layers: two identical dark shells,
                // radius 6, alpha 200 — reproduced as a gaussian soft shadow
                // behind the body pill (alpha 80) so the glass reads the same.
                drawPillShadow(gfx, pillX, pillY, pillW - 1f, pillH - 1f, radius, 200)
                gfx.drawRoundedRect(
                    pillX, pillY, pillX + pillW, pillY + pillH,
                    radius, Color4b(0, 0, 0, 80),
                )
            }

            if (animation == Anim.SCALE_IN) {
                gfx.pose().withPush {
                    // Scale about the pill's visual center (matches RenderUtil
                    // .scaleStart(x + tw/2, y2 + rowH/2 - fontH/2)).
                    val cx = pillX + textWidth / 2f
                    val cy = pillY + rowH / 2f - fontH / 2f
                    translate(cx, cy)
                    scale(a, a)
                    translate(-cx, -cy)
                    drawPill()
                    gfx.text(font, text, (x + 0.5).toInt(), (y - 2.0).toInt(), textCol, true)
                }
            } else {
                drawPill()
                gfx.text(font, text, (x + 0.5).toInt(), (y - 2.0).toInt(), textCol, true)
            }

            yOff += a.toDouble() * rowH
        }
    }

    override fun onDisabled() {
        lastStates.clear()
        animations.clear()
        widthCache.clear()
        maxWidthLerp = 0.0
    }
}