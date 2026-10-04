/*
 * ModulePotionStatusRenderer —— 还原 PotionStatusRenderer.java
 * 原生 Overlay，无 Web / 无 Skia
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.effect.MobEffectInstance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ModulePotionStatusRenderer : ClientModule(
    "PotionStatusRenderer",
    ModuleCategories.RENDER,
    aliases = listOf("PotionHUD", "EffectsHUD"),
) {

    private val posX by float("Offset X", 8f, 0f..800f)
    private val posY by float("Offset Y", 80f, 0f..600f)
    private val scale by float("Scale", 1f, 0.6f..1.8f)
    private val rightSide by boolean("Right Side", true)

    private val width by float("Width", 134f, 80f..220f)
    private val rowH by float("Row Height", 28f, 18f..40f)
    private val gap by float("Gap", 5f, 0f..16f)
    private val pad by float("Padding", 7f, 2f..16f)
    private val bgRadius by float("BG Radius", 11f, 0f..20f)
    private val itemRadius by float("Item Radius", 8f, 0f..16f)

    private val bgColor by color("Background", Color4b(0x6E, 0x73, 0x7A, 0x7A))
    private val borderColor by color("Border", Color4b(255, 255, 255, 48))
    private val textColor by color("Text", Color4b(0xF6, 0xFF, 0xFF, 0xFF))
    private val subTextColor by color("Sub Text", Color4b(0xCF, 0xFF, 0xFF, 0xFF))
    private val showCountdown by boolean("Countdown", true)
    private val showAmplifier by boolean("Amplifier", true)
    private val animSpeed by float("Anim Speed", 10f, 2f..24f)

    private const val HIDE_DELAY_MS = 260L

    private data class Visual(
        var effect: MobEffectInstance? = null,
        var key: String = "",
        var targetY: Float = 0f,
        var currentY: Float = 0f,
        var slide: Float = 0f,
        var rowAlpha: Float = 0f,
        var fillProgress: Float = 1f,
        var displayTicks: Float = 0f,
        var maxTicks: Float = 1f,
        var hiding: Boolean = false,
        var hideAt: Long = 0L,
    )

    private val visuals = LinkedHashMap<String, Visual>()
    private var lastNs = 0L
    private var bgProgress = 0f

    private fun easeOutCubic(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    private fun withA(c: Color4b, a: Float) =
        Color4b(c.r, c.g, c.b, (c.a * a.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255))

    private fun effectKey(e: MobEffectInstance): String {
        return try {
            e.effect.registeredName + ":" + e.amplifier
        } catch (_: Throwable) {
            e.toString()
        }
    }

    private fun effectName(e: MobEffectInstance): String {
        return try {
            e.effect.value().displayName.string
        } catch (_: Throwable) {
            try {
                e.descriptionId
            } catch (_: Throwable) {
                "Effect"
            }
        }
    }

    private fun effectColor(e: MobEffectInstance): Color4b {
        return try {
            val rgb = e.effect.value().color
            Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 220)
        } catch (_: Throwable) {
            Color4b(120, 160, 255, 220)
        }
    }

    private fun formatDuration(ticks: Float): String {
        if (ticks < 0) return "∞"
        val sec = (ticks / 20f).roundToInt().coerceAtLeast(0)
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    private fun amplifier(amp: Int): String {
        if (!showAmplifier || amp <= 0) return ""
        return when (amp) {
            1 -> "II"
            2 -> "III"
            3 -> "IV"
            4 -> "V"
            else -> (amp + 1).toString()
        }
    }

    private fun syncEffects(dt: Float) {
        val player = mc.player ?: return
        val active = try {
            player.activeEffects.toList()
        } catch (_: Throwable) {
            emptyList()
        }
        val seen = HashSet<String>()
        val now = System.currentTimeMillis()

        active.forEachIndexed { i, e ->
            val key = effectKey(e)
            seen += key
            val v = visuals.getOrPut(key) {
                Visual(key = key, currentY = pad + i * (rowH + gap), slide = 0f, rowAlpha = 0f)
            }
            v.effect = e
            v.hiding = false
            v.targetY = pad + i * (rowH + gap)
            val dur = try {
                if (e.isInfiniteDuration) -1f else e.duration.toFloat()
            } catch (_: Throwable) {
                e.duration.toFloat()
            }
            v.displayTicks = dur
            if (v.maxTicks < dur) v.maxTicks = max(dur, 1f)
            if (dur < 0) {
                v.fillProgress = 1f
            } else {
                val targetFill = (dur / v.maxTicks).coerceIn(0f, 1f)
                v.fillProgress += (targetFill - v.fillProgress) * min(1f, dt * animSpeed)
            }
            v.slide += (1f - v.slide) * min(1f, dt * animSpeed)
            v.rowAlpha += (1f - v.rowAlpha) * min(1f, dt * animSpeed)
            v.currentY += (v.targetY - v.currentY) * min(1f, dt * animSpeed)
        }

        val it = visuals.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (k in seen) continue
            if (!v.hiding) {
                v.hiding = true
                v.hideAt = now + HIDE_DELAY_MS
            }
            v.slide += (0f - v.slide) * min(1f, dt * animSpeed)
            v.rowAlpha += (0f - v.rowAlpha) * min(1f, dt * animSpeed)
            if (v.rowAlpha < 0.02f && now > v.hideAt) it.remove()
        }

        val count = visuals.values.count { it.rowAlpha > 0.02f }
        val targetBg = if (count > 0) 1f else 0f
        bgProgress += (targetBg - bgProgress) * min(1f, dt * animSpeed)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val nowNs = System.nanoTime()
        val dt = if (lastNs != 0L) ((nowNs - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = nowNs

        syncEffects(dt)
        if (bgProgress < 0.02f) return@handler

        val ctx = event.context
        val font = mc.font
        val s = scale
        val w = width * s
        val sw = ctx.guiWidth().toFloat()
        val count = visuals.values.count { it.rowAlpha > 0.02f }
        val contentH = (count * rowH + max(0, count - 1) * gap + pad * 2f) * s
        val h = contentH * bgProgress

        var x = if (rightSide) sw - w - posX else posX
        x = x.coerceIn(0f, max(0f, sw - w))
        val y = posY

        // background panel
        val bgA = bgProgress
        ctx.drawRoundedRect(x, y, x + w * bgProgress, y + h, bgRadius * s, withA(bgColor, bgA))
        val border = withA(borderColor, bgA)
        ctx.drawQuad(x, y, x + w * bgProgress, y + 1f, border)
        ctx.drawQuad(x, y + h - 1f, x + w * bgProgress, y + h, border)

        val sorted = visuals.values.filter { it.rowAlpha > 0.02f }.sortedBy { it.currentY }
        for (v in sorted) {
            val e = v.effect ?: continue
            val slide = easeOutCubic(v.slide)
            val alpha = v.rowAlpha * bgProgress
            val rowY = y + v.currentY * s
            val drawX = x + pad * s + (1f - slide) * -24f * s
            val drawW = (width - pad * 2f) * s

            // dark base
            val dark = Color4b(20, 22, 28, (180 * alpha).roundToInt())
            ctx.drawRoundedRect(drawX, rowY, drawX + drawW, rowY + rowH * s, itemRadius * s, dark)

            // fill by remaining duration
            val fillW = drawW * v.fillProgress.coerceIn(0f, 1f)
            if (fillW > 1f) {
                ctx.drawRoundedRect(
                    drawX, rowY, drawX + fillW, rowY + rowH * s, itemRadius * s,
                    withA(effectColor(e), alpha * 0.86f),
                )
            }
            // gloss
            ctx.drawRoundedRect(
                drawX, rowY, drawX + drawW, rowY + rowH * s * 0.45f, itemRadius * s,
                Color4b(255, 255, 255, (12 * alpha).roundToInt()),
            )

            // icon placeholder
            val iconBox = 16f * s
            ctx.drawRoundedRect(
                drawX + 4f * s, rowY + (rowH * s - iconBox) / 2f,
                drawX + 4f * s + iconBox, rowY + (rowH * s - iconBox) / 2f + iconBox,
                4f * s, Color4b(255, 255, 255, (30 * alpha).roundToInt()),
            )

            val name = effectName(e)
            val amp = amplifier(e.amplifier)
            val textX = (drawX + iconBox + 10f * s).roundToInt()
            if (showCountdown) {
                ctx.text(font, name, textX, (rowY + 6f * s).roundToInt(), withA(textColor, alpha).argb, false)
                val time = if (v.displayTicks < 0) "∞" else formatDuration(v.displayTicks)
                val sub = if (amp.isEmpty()) time else "$amp  $time"
                ctx.text(font, sub, textX, (rowY + 16f * s).roundToInt(), withA(subTextColor, alpha).argb, false)
            } else {
                val label = if (amp.isEmpty()) name else "$name $amp"
                ctx.text(font, label, textX, (rowY + 10f * s).roundToInt(), withA(textColor, alpha).argb, false)
            }
        }
    }
}
