/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Mascot open animation for the native ClickGUI (MC 26.3).
 *
 * On open: mascot PNG appears at the center of the screen, fades in,
 * then fades out. No swing / rock / wave — only alpha transitions.
 * Size scales with the logical viewport so it reads larger on modern
 * resolutions (about 42% of the shorter screen edge, clamped).
 */
package net.ccbluex.liquidbounce.render.clickgui

import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import kotlin.math.min

/**
 * Frame-rate independent center fade-in → fade-out greeting.
 * Reset on each new [NativeClickGuiScreen] so every open plays once.
 */
class ClickGuiMascotAnimation {

    companion object {
        val MASCOT_TEXTURE: Identifier =
            Identifier.fromNamespaceAndPath("liquidbounce", "textures/clickgui/mascot_android.png")

        /** Source texture size (mascot_android.png). */
        private const val TEX_W = 1920
        private const val TEX_H = 1080

        // ---- timing (seconds) ----
        private const val FADE_IN_DURATION = 0.55f
        private const val HOLD_DURATION = 0.35f
        private const val FADE_OUT_DURATION = 0.55f

        /**
         * Display size: fraction of the shorter logical edge.
         * Larger than the old fixed 128px so the mascot reads clearly.
         */
        private const val SIZE_FRACTION = 0.42f
        private const val SIZE_MIN = 220
        private const val SIZE_MAX = 480
    }

    private enum class Phase { FADE_IN, HOLD, FADE_OUT, DONE }

    private var phase = Phase.FADE_IN
    private var phaseStartMs = System.currentTimeMillis()

    val isDone: Boolean
        get() = phase == Phase.DONE

    private fun advanceTo(next: Phase) {
        phase = next
        phaseStartMs = System.currentTimeMillis()
    }

    private fun elapsedSec(): Float =
        (System.currentTimeMillis() - phaseStartMs) / 1000f

    /** Smooth 0→1 ease (smoothstep). */
    private fun smoothStep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Width/height of the drawn mascot in logical pixels, preserving
     * the PNG aspect ratio (1920×1080).
     */
    private fun displaySize(logicalW: Int, logicalH: Int): Pair<Float, Float> {
        val shortEdge = min(logicalW, logicalH).toFloat()
        var h = (shortEdge * SIZE_FRACTION).coerceIn(SIZE_MIN.toFloat(), SIZE_MAX.toFloat())
        var w = h * (TEX_W.toFloat() / TEX_H.toFloat())
        // If wider than screen, shrink to fit with margin
        val maxW = logicalW * 0.88f
        if (w > maxW) {
            w = maxW
            h = w * (TEX_H.toFloat() / TEX_W.toFloat())
        }
        return w to h
    }

    fun render(gfx: GuiGraphicsExtractor, logicalW: Int, logicalH: Int) {
        if (phase == Phase.DONE) return

        val t = elapsedSec()
        val alpha: Float = when (phase) {
            Phase.FADE_IN -> {
                val a = smoothStep(t / FADE_IN_DURATION)
                if (t >= FADE_IN_DURATION) advanceTo(Phase.HOLD)
                a
            }
            Phase.HOLD -> {
                if (t >= HOLD_DURATION) advanceTo(Phase.FADE_OUT)
                1f
            }
            Phase.FADE_OUT -> {
                val a = 1f - smoothStep(t / FADE_OUT_DURATION)
                if (t >= FADE_OUT_DURATION) {
                    advanceTo(Phase.DONE)
                    0f
                } else a
            }
            Phase.DONE -> return
        }

        val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
        if (alphaInt <= 0) return

        val (dw, dh) = displaySize(logicalW, logicalH)
        // Screen center; character faces left in the source art (人物左).
        val cx = (logicalW - dw) / 2f
        val cy = (logicalH - dh) / 2f

        val tint = (alphaInt shl 24) or 0x00FFFFFF
        val texture = Minecraft.getInstance().textureManager.getTexture(MASCOT_TEXTURE)
        val setup = texture.textureSetup

        // No pose rotation — previous wave/swing removed.
        gfx.drawTexQuad(
            setup,
            x0 = cx, y0 = cy,
            x1 = cx + dw, y1 = cy + dh,
            argb = tint,
        )
    }

    /** Call when a new ClickGUI screen opens so the greeting plays again. */
    fun reset() {
        phase = Phase.FADE_IN
        phaseStartMs = System.currentTimeMillis()
    }
}
