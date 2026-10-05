/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.toTextureProperty
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.scaledDimension
import net.ccbluex.liquidbounce.utils.math.toRadians
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Character2D module
 *
 * Draws a flat 2D character image on the HUD, with a configurable glow behind it,
 * a procedural breathing (squash and stretch) and a swaying animation.
 */
object ModuleCharacter2D : ClientModule("Character2D", ModuleCategories.RENDER) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.character2d"

    /** The character image, loaded from a PNG or JPEG file chosen by the user. */
    private val imageFile by file(
        "Image",
        supportedExtensions = setOf("png", "jpg", "jpeg"),
    ).toTextureProperty(this)

    /** Anchor position of the character's feet, as a fraction of the screen. */
    private val posX by float("PosX", 0.85F, 0F..1F)
    private val posY by float("PosY", 0.55F, 0F..1F)

    /** Size multiplier. The character is [BASE_HEIGHT] pixels tall at scale 1. */
    private val charScale by float("Scale", 1F, 0.1F..5F)

    private val opacity by int("Opacity", 255, 0..255)
    private val tint by color("Tint", Color4b.WHITE)

    private object Glow : ToggleableValueGroup(ModuleCharacter2D, "Glow", true) {
        val color by color("Color", Color4b(90, 170, 255))

        /** Glow size, as a multiple of the character's size. */
        val radius by float("Radius", 1.4F, 0.5F..3F)

        /** Glow brightness, 0 hides it. */
        val intensity by int("Intensity", 160, 0..255)
    }

    private object Breathing : ToggleableValueGroup(ModuleCharacter2D, "Breathing", true) {
        /** Cycles per second. */
        val speed by float("Speed", 0.6F, 0.1F..3F, "Hz")

        /** Vertical stretch amplitude, the horizontal squash is half of it. */
        val amount by float("Amount", 0.04F, 0F..0.25F)
    }

    private object Sway : ToggleableValueGroup(ModuleCharacter2D, "Sway", true) {
        /** Cycles per second. */
        val speed by float("Speed", 1.2F, 0.1F..4F, "Hz")

        /** Maximum swing angle to either side. */
        val angle by float("Angle", 6F, 0F..30F, "deg")
    }

    init {
        tree(Glow)
        tree(Breathing)
        tree(Sway)
    }

    private const val BASE_HEIGHT = 100F

    /** Wall clock start, so animation speed does not depend on the game FPS. */
    private val startNanos = System.nanoTime()

    /**
     * Default character image bundled in the client resources, used when no file is chosen.
     * Location: src/main/resources/resources/liquidbounce/character2d/character.png
     * If the file is missing, this resolves to null and the character is simply not drawn.
     */
    private val bundledTexture by lazy {
        runCatching {
            LiquidBounce.resource("character2d/character.png")
                .readNativeImage()
                .asTexture { "Character2D bundled" }
        }.getOrNull()
    }

    /** Built-in soft glow sprite, the same one used by PotionFX. */
    private val glowTexture by lazy {
        LiquidBounce.resource("particles/glow.png")
            .readNativeImage()
            .asTexture { "Character2D glow" }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (inGame) {
            drawCharacter(event)
        }
    }

    private fun drawCharacter(event: OverlayRenderEvent) {
        val texture = imageFile ?: bundledTexture ?: return
        val pixels = texture.pixels ?: return

        val aspect = pixels.width.toFloat() / pixels.height.toFloat()
        val height = BASE_HEIGHT * charScale
        val width = height * aspect

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000F
        val breathStretch = if (Breathing.enabled) {
            Breathing.amount * oscillate(elapsed, Breathing.speed)
        } else {
            0F
        }
        val swayRad = if (Sway.enabled) {
            (oscillate(elapsed, Sway.speed) * Sway.angle).toRadians()
        } else {
            0F
        }

        with(event.context) {
            val (screenWidth, screenHeight) = mc.window.scaledDimension
            val pivotX = screenWidth * posX
            val pivotY = screenHeight * posY

            if (Glow.enabled && Glow.intensity > 0) {
                drawGlow(pivotX, pivotY, width, height)
            }

            pose().withPush {
                // Pivot at the feet so sway and breathing stay grounded.
                translate(pivotX, pivotY)

                if (Sway.enabled) {
                    rotate(swayRad)
                }

                if (Breathing.enabled) {
                    // Squash horizontally while stretching vertically to keep the volume roughly constant.
                    scale(1F - breathStretch * 0.5F, 1F + breathStretch)
                }

                drawTexQuad(
                    texture.textureSetup,
                    -width * 0.5F, -height,
                    width * 0.5F, 0F,
                    argb = tint.alpha(opacity).argb,
                    pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
                )
            }
        }
    }

    /**
     * Draws the glow centered on the character's body, behind it.
     * Drawn before the character and without sway or breathing, so it stays a stable halo.
     */
    private fun GuiGraphicsExtractor.drawGlow(pivotX: Float, pivotY: Float, width: Float, height: Float) {
        val size = max(width, height) * Glow.radius
        val glowArgb = Glow.color.alpha(Glow.intensity).argb

        pose().withPush {
            translate(pivotX, pivotY - height * 0.5F)
            drawTexQuad(
                glowTexture.textureSetup,
                -size * 0.5F, -size * 0.5F,
                size * 0.5F, size * 0.5F,
                argb = glowArgb,
                pipeline = ClientRenderPipelines.GUI.TexQuadNoCull,
            )
        }
    }

    /** Smooth wave in the range -1..1 at [hz] cycles per second. */
    private fun oscillate(seconds: Float, hz: Float): Float = sin(2.0 * PI * seconds * hz).toFloat()

}
