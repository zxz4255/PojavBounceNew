/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * Contains a port of Samsara's Opai Target HUD
 * (com.samsara.module.visual.Hud.TargetHud.OpaiTargetHudPainter and friends).
 * The design is kept unchanged: same panel geometry (122x40, r=6, 8px soft
 * shadow), same face slot (26px, r=4.5), same health fill + delayed damage
 * trail response curves, same name/counter layout and the same palette
 * bindings as the Opai clickgui (LAVENDER / LIGHT_PINK, values verbatim).
 *
 * Backend notes: the original renders with NanoVG + two pre-baked PNGs
 * (target-opai.png / target-opai-bar.png are 4x-supersampled 9-slice
 * rounded-soft-shadow shells; the bar PNG is a pure 20x20 pill). The same
 * pixel result is produced here through LiquidBounce's own SDF rounded-rect
 * GUI pipeline (drawRoundedRect) — the panel keeps the identical body color
 * (HudGlassStyle.BODY = 0x98000000) and the same 8px gaussian-layered shadow
 * Samsara uses for its Dynamic Island, and the bars keep the identical
 * capsule caps (radius = height/2). No foreign texture or mixin is needed.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.Minecraft
import Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OpaiTargetHud module — Samsara's "Opai" mode target display, 1:1.
 *
 * Shows the current KillAura target as a glass pill: 26px rounded face on
 * the left, name + health counter in the top row, armor icons in the middle,
 * and a capsule health bar along the bottom with a delayed damage trail
 * (fast 65ms fill response; 220ms trail starting 100ms later).
 *
 * Position follows Samsara's HudLayouts Element.TARGET defaults: centered on
 * the screen, offset (8, 4), scale 1 — exposed here as settings because
 * LiquidBounce has no HUD editor for this module.
 */
object ModuleOpaiTargetHud : ClientModule("OpaiTargetHud", ModuleCategories.RENDER) {

    // ------------------------------------------------------------ settings

    private val scale by float("Scale", 1.0f, 0.5f..2.0f)
    private val offsetX by float("OffsetX", 8f, -4096f..4096f)
    private val offsetY by float("OffsetY", 4f, -4096f..4096f)
    private val showArmor by boolean("ShowArmor", true)
    private val palette by enumChoice("Palette", Palette.LAVENDER)

    /** Subset of Samsara's OpaiStyle.Palette fields used by this painter —
     *  values kept verbatim from OpaiStyle.LAVENDER / OpaiStyle.LIGHT_PINK. */
    enum class Palette(override val tag: String, val accent: Int, val background: Int) : Tagged {
        LAVENDER("Lavender", 0xFFBBC3FF.toInt(), 0x98000000.toInt()),
        LIGHT_PINK("LightPink", 0xFFFFB4AA.toInt(), 0x98000000.toInt()),
    }

    // ================================================================
    //  Geometry constants — OpaiTargetHudPainter, verbatim.
    // ================================================================

    private const val WIDTH = 122
    private const val HEIGHT = 40
    private const val RADIUS = 6f
    private const val SHADOW_MARGIN = 8f
    private const val TEXTURE_SCALE = 4
    private const val FACE_SIZE = 26f
    private const val FACE_RADIUS = 4.5f
    private const val TEXT_SIZE = 8f
    private const val TEXT = 0xFFFFFFFF.toInt()

    private class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    // ================================================================
    //  Health animation — OpaiTargetHudHealth, verbatim.
    //  Two wall-time responses: a quick fill and a lighter, delayed damage
    //  trail. Everything runs on System.nanoTime milliseconds.
    // ================================================================

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private class Health {
        private var goal = 0f
        private var maximum = 20f
        private var fillFrom = 0f
        private var trailFrom = 0f
        private var changedAt = 0L
        private var damage = false
        private var initialized = false

        class Sample(val health: Float, val trail: Float, val maximum: Float) {
            fun fraction(): Float = health / maximum
            fun trailFraction(): Float = trail / maximum
            fun label(): String = format(health)
        }

        fun reset(health: Float, maximum: Float, now: Long) {
            this.maximum = sanitizeMaximum(maximum)
            this.goal = sanitizeHealth(health, this.maximum)
            this.fillFrom = this.goal
            this.trailFrom = this.goal
            this.changedAt = now
            this.damage = false
            this.initialized = true
        }

        fun update(health: Float, maximum: Float, now: Long): Sample {
            val max = sanitizeMaximum(maximum)
            val value = sanitizeHealth(health, max)
            if (!initialized) reset(value, max, now)
            if (value != goal || max != this.maximum) {
                val current = sample(now)
                damage = value < current.health
                fillFrom = current.health
                trailFrom = max(current.health, current.trail)
                goal = value
                this.maximum = max
                changedAt = now
            }
            return sample(now)
        }

        private fun sample(now: Long): Sample {
            val elapsed = max(0L, now - changedAt).toDouble()
            val fill = response(fillFrom, goal, elapsed, 65.0)
            val trail = if (damage) {
                max(fill, response(trailFrom, goal, max(0L, now - 100 - changedAt).toDouble(), 220.0))
            } else fill
            return Sample(sanitizeHealth(fill, maximum), sanitizeHealth(trail, maximum), maximum)
        }

        /** Exponential approach; snaps inside 0.025 of the goal. */
        private fun response(from: Float, to: Float, elapsed: Double, decay: Double): Float {
            val value = (to + (from - to) * exp(-elapsed / decay)).toFloat()
            return if (abs(value - to) < 0.025f) to else value
        }

        private fun sanitizeMaximum(value: Float): Float = if (value.isFinite()) max(1f, value) else 20f
        private fun sanitizeHealth(value: Float, maximum: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, maximum) else 0f

        companion object {
            /** "4" stays "4"; "16.5" keeps one decimal — matches the original. */
            fun format(health: Float): String {
                val rounded = (health * 10).roundToInt() / 10f
                return if (rounded == rounded.roundToInt().toFloat()) {
                    rounded.roundToInt().toString()
                } else String.format(Locale.ROOT, "%.1f", rounded)
            }
        }
    }

    private val health = Health()
    private var target: Player? = null

    // ================================================================
    //  Painter layout — OpaiTargetHudPainter.bounds / paint, verbatim.
    // ================================================================

    /** MC font at scale TEXT_SIZE/10 — matches the original surface measure. */
    private fun measure(font: Font, text: String): Float =
        font.width(text) * (TEXT_SIZE / 10f)

    private fun fit(font: Font, text: String, width: Float): String {
        if (measure(font, text) <= width) return text
        var end = text.length
        while (end > 0 && measure(font, text.substring(0, end) + "…") > width) {
            end = text.offsetByCodePoints(end, -1)
        }
        return if (end == 0) "" else text.substring(0, end) + "…"
    }

    private fun labelWidth(font: Font, maximum: Float): Float {
        val label = Health.format(maximum)
        return max(measure(font, if (label.contains(".")) label else "$label.0"), measure(font, "00.0"))
    }

    private fun computeBounds(font: Font, name: String, maximum: Float,
                              viewportWidth: Int, viewportHeight: Int): Bounds {
        val label = labelWidth(font, maximum)
        val width = min(max(WIDTH, ceil(35 + measure(font, name) + label + 4).toInt()),
            max(1, viewportWidth - 4))
        val x = Mth.clamp((viewportWidth / 2f + offsetX).toInt(), 2, max(2, viewportWidth - width - 2))
        val y = Mth.clamp((viewportHeight / 2f + offsetY).toInt(), 2, max(2, viewportHeight - HEIGHT - 2))
        return Bounds(x, y, width, HEIGHT)
    }

    // ================================================================
    //  Surface — the same drawing contract as OpaiTargetHudSurface,
    //  re-pointed at LiquidBounce's GuiGraphicsExtractor render states.
    // ================================================================

    private fun tintOpacity(color: Int, opacity: Float): Int =
        (((color ushr 24) * opacity.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)

    /** Layered gaussian shadow — identical math to Samsara's DynamicIslandPainter
     *  (the same function their baked panel PNG encodes), margin 8px, step .25. */
    private fun shadowCoverage(distance: Float): Float {
        val normalized = max(0f, distance) / 2.8f
        return 0.5f * exp(-0.78f * normalized - 0.5f * normalized * normalized)
    }

    private fun shadowLayer(spread: Float): Int {
        val previous = shadowCoverage(spread + 0.25f)
        val desired = shadowCoverage(spread)
        return (255 * (desired - previous) / (1 - previous)).roundToInt() shl 24
    }

    private fun drawPanel(gfx: GuiGraphicsExtractor, bounds: Bounds, opacity: Float, palette: Palette) {
        gfx.pose().withPush {
            translate(bounds.x.toFloat() - SHADOW_MARGIN, bounds.y.toFloat() - SHADOW_MARGIN)
            var spread = SHADOW_MARGIN
            while (spread > 0) {
                val layer = tintOpacity(shadowLayer(spread), opacity)
                if (layer != 0) {
                    gfx.drawRoundedRect(
                        -spread, -spread,
                        bounds.width + SHADOW_MARGIN * 2 + spread, bounds.height + SHADOW_MARGIN * 2 + spread,
                        (RADIUS + SHADOW_MARGIN) + spread, Color4b(layer),
                    )
                }
                spread -= 0.25f
            }
            gfx.drawRoundedRect(
                SHADOW_MARGIN, SHADOW_MARGIN,
                bounds.width + SHADOW_MARGIN, bounds.height + SHADOW_MARGIN,
                RADIUS, Color4b(tintOpacity(palette.background, opacity)),
            )
        }
    }

    private fun drawBar(gfx: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float,
                        color: Int) {
        if (width <= 0) return
        // The original slices a 20px pill texture so both caps stay circular;
        // radius=h/2 in the SDF rounded-rect pipeline is the identical shape.
        gfx.drawRoundedRect(x, y, x + width, y + height, height / 2, Color4b(color))
    }

    /** Rounded skin head extraction — face layer U=8 plus hat overlay U=40 in
     *  64px skin space, corner-cut strips at 4x supersample (1:1 port of
     *  Samsara's NativePlayerFace.extract). */
    private fun drawFace(gfx: GuiGraphicsExtractor, skin: Identifier,
                         x: Float, y: Float, size: Float, radius: Float, opacity: Float) {
        val setup = Minecraft.getInstance().textureManager.getTexture(skin).textureSetup
        gfx.pose().withPush {
            translate(x, y)
            gfx.pose().scale(1f / TEXTURE_SCALE, 1f / TEXTURE_SCALE)
            val pixels = (size * TEXTURE_SCALE).roundToInt()
            val round = (radius * TEXTURE_SCALE).roundToInt().coerceIn(0, pixels / 2)
            val color = ((255 * opacity.coerceIn(0f, 1f)).roundToInt() shl 24) or 0xFFFFFF
            for (u in intArrayOf(8, 40)) {
                for (row in 0 until round) {
                    val dy = round - row - 0.5
                    val inset = (round - sqrt(round * round - dy * dy)).roundToInt()
                    strip(gfx, setup, u, pixels, inset, row, pixels - inset * 2, 1, color)
                    strip(gfx, setup, u, pixels, inset, pixels - row - 1, pixels - inset * 2, 1, color)
                }
                strip(gfx, setup, u, pixels, 0, round, pixels, pixels - round * 2, color)
            }
        }
    }

    private fun strip(gfx: GuiGraphicsExtractor, setup: TextureSetup,
                      u: Int, pixels: Int, x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width <= 0 || height <= 0) return
        val factor = 8f / pixels
        gfx.drawTexQuad(
            setup,
            x0 = x.toFloat(), y0 = y.toFloat(), x1 = (x + width).toFloat(), y1 = (y + height).toFloat(),
            u1 = (u + x * factor) / 64, v1 = (8 + y * factor) / 64,
            u2 = (u + (x + width) * factor) / 64, v2 = (8 + (y + height) * factor) / 64,
            argb = color,
        )
    }

    private fun drawText(gfx: GuiGraphicsExtractor, font: Font,
                         text: String, x: Float, y: Float, color: Int, opacity: Float) {
        val tinted = tintOpacity(color, opacity)
        // Vanilla treats near-zero text alpha as an unspecified opaque color.
        if ((tinted ushr 24) < 4) return
        val fontScale = TEXT_SIZE / 10f
        if (fontScale == 1f) {
            gfx.text(font, text, x.roundToInt(), y.roundToInt(), tinted, false)
        } else {
            gfx.pose().withPush {
                translate(x, y)
                scale(fontScale, fontScale)
                gfx.text(font, text, 0, 0, tinted, false)
            }
        }
    }

    // ================================================================
    //  Paint entry — TargetHud.renderOpai / OpaiTargetHudPainter.paint.
    // ================================================================

    private val armorSlots =
        arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    private fun renderOpai(gfx: GuiGraphicsExtractor, mc: Minecraft, current: Player?) {
        val player = mc.player
        if (player == null || mc.level == null || mc.gui.overlay() != null || current == null ||
            current.level() != mc.level || current.isRemoved) {
            target = null
            return
        }
        val now = nowMs()
        if (current !== target) {
            target = current
            health.reset(current.health, current.maxHealth, now)
        }
        val sample = health.update(current.health, current.maxHealth, now)
        val font = mc.font
        val name = current.name.string
        val viewportWidth = mc.window.guiScaledWidth
        val viewportHeight = mc.window.guiScaledHeight
        val content = computeBounds(font, name, sample.maximum, viewportWidth, viewportHeight)
        val scaleFactor = min(scale.coerceIn(0.5f, 2f),
            min(max(1, viewportWidth - 4) / content.width.toFloat(),
                max(1, viewportHeight - 4) / content.height.toFloat()))

        val palette = palette
        gfx.pose().withPush {
            translate(content.x.toFloat(), content.y.toFloat())
            gfx.pose().scale(scaleFactor, scaleFactor)

            drawPanel(gfx, Bounds(0, 0, content.width, content.height), 1f, palette)
            val skin = (current as? AbstractClientPlayer)?.skin?.body()?.texturePath()
                ?: DefaultPlayerSkin.get(current.gameProfile).body().texturePath()
            drawFace(gfx, skin, 3f, 3f, FACE_SIZE, FACE_RADIUS, 1f)

            val label = sample.label()
            val fittedName = fit(font, name,
                content.width - 35 - labelWidth(font, sample.maximum) - 3)
            drawText(gfx, font, fittedName, 32f, 5f, TEXT, 1f)
            drawText(gfx, font, label, 32f + measure(font, fittedName) + 1.5f, 5f, palette.accent, 1f)

            if (showArmor) {
                for (slot in 0..3) {
                    val stack = current.getItemBySlot(armorSlots[slot])
                    if (stack.isEmpty) continue
                    gfx.pose().withPush {
                        translate(31.5f + slot * 15.5f, 14.5f)
                        gfx.pose().scale(0.95f, 0.95f)
                        gfx.item(current, stack, 0, 0, slot)
                    }
                }
            }

            val barWidth = max(0f, content.width - 7f)
            drawBar(gfx, 3f, 32f, barWidth, 5f, tintOpacity(0x66000000, 1f))
            // Only the remaining damage interval; the fill covers the front of it.
            drawBar(gfx, 3f, 31.25f, barWidth * sample.trailFraction(), 4.5f, tintOpacity(0x66FFFFFF, 1f))
            drawBar(gfx, 3f, 31.25f, barWidth * sample.fraction(), 4.5f, tintOpacity(palette.accent, 1f))
        }
    }

    // ================================================================
    //  Module wiring (LiquidBounce event pipeline)
    // ================================================================

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val mc = Minecraft.getInstance()
        val aura = runCatching { ModuleKillAura }.getOrNull()
        val target = if (aura != null && aura.running) {
            aura.targetTracker.target as? Player
        } else null
        renderOpai(event.context, mc, target)
    }

    override fun onDisabled() {
        target = null
    }
}