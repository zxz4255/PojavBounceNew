/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Nine-slice nametag textures taken from Samsara
 * (`assets/samsara/textures/hud/nametags/{capsule,shadow,heart}.png`).
 *
 * Texture sizes (4x source resolution):
 *   capsule.png 160x160 -> 40x40 logical, edge 12
 *   shadow.png  224x224 -> 56x56 logical, edge 20
 *   heart.png    48x56  -> 12x14  logical
 */
package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.min

/**
 * Draws Samsara's nine-slice nametag pieces with [GuiGraphicsExtractor.blit].
 *
 * The source textures are 4x oversampled, exactly like in Samsara, so a slice
 * covering `w x h` logical pixels reads `4w x 4h` texture pixels and scales down.
 */
internal object SamsaraNametagTextures {

    private const val NAMESPACE = "liquidbounce"
    private const val PATH = "textures/hud/nametags"

    private const val OVERSAMPLE = 4

    val CAPSULE: Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "$PATH/capsule.png")
    val SHADOW: Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "$PATH/shadow.png")
    val HEART: Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "$PATH/heart.png")

    /** Logical size of the whole capsule texture. */
    const val CAPSULE_SIZE = 40

    /** Logical size of the whole shadow texture. */
    const val SHADOW_SIZE = 56

    /** Corner radius of a capsule, in logical pixels. */
    const val CAPSULE_EDGE = 12f

    /** Blur radius of the shadow, in logical pixels. */
    const val SHADOW_EDGE = 20f

    const val HEART_SOURCE_WIDTH = 12
    const val HEART_SOURCE_HEIGHT = 14
    const val HEART_TEXTURE_WIDTH = HEART_SOURCE_WIDTH * OVERSAMPLE
    const val HEART_TEXTURE_HEIGHT = HEART_SOURCE_HEIGHT * OVERSAMPLE

    /** One cell of a nine-slice grid. All values are in logical pixels. */
    class Slice(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val sourceX: Int,
        val sourceY: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private val availability = HashMap<Identifier, Boolean>()

    /**
     * True when the texture ships in the resource pack. Cached, because the
     * lookup runs for every nametag on every frame otherwise.
     */
    fun available(texture: Identifier): Boolean = availability.getOrPut(texture) {
        mc.resourceManager.getResource(texture).isPresent
    }

    /**
     * Splits a `width x height` box into up to nine cells, like Samsara's
     * `NameTags.Geometry.slices`.
     */
    fun slices(shadow: Boolean, width: Float, height: Float): List<Slice> {
        val edge = if (shadow) SHADOW_EDGE else CAPSULE_EDGE
        val size = if (shadow) SHADOW_SIZE else CAPSULE_SIZE

        val edgeX = min(edge, width / 2f)
        val edgeY = min(edge, height / 2f)

        val xs = floatArrayOf(0f, edgeX, width - edgeX, width)
        val ys = floatArrayOf(0f, edgeY, height - edgeY, height)

        val source = intArrayOf(0, edge.toInt(), size - edge.toInt(), size)

        val out = ArrayList<Slice>(9)
        for (row in 0..2) {
            for (column in 0..2) {
                val w = xs[column + 1] - xs[column]
                val h = ys[row + 1] - ys[row]
                if (w <= 0f || h <= 0f) {
                    continue
                }
                out += Slice(
                    x = xs[column],
                    y = ys[row],
                    width = w,
                    height = h,
                    sourceX = source[column],
                    sourceY = source[row],
                    sourceWidth = source[column + 1] - source[column],
                    sourceHeight = source[row + 1] - source[row],
                )
            }
        }
        return out
    }

    /**
     * Draws one nine-slice cell at ([x], [y]) covering [slice]'s logical size.
     */
    fun drawSlice(
        graphics: GuiGraphicsExtractor,
        texture: Identifier,
        slice: Slice,
        x: Float,
        y: Float,
        argb: Int,
    ) {
        if (slice.sourceWidth <= 0 || slice.sourceHeight <= 0) {
            return
        }

        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(x + slice.x, y + slice.y)
            graphics.pose().scale(
                slice.width / slice.sourceWidth,
                slice.height / slice.sourceHeight,
            )
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                0,
                0,
                (slice.sourceX * OVERSAMPLE).toFloat(),
                (slice.sourceY * OVERSAMPLE).toFloat(),
                slice.sourceWidth,
                slice.sourceHeight,
                slice.sourceWidth * OVERSAMPLE,
                slice.sourceHeight * OVERSAMPLE,
                (if (texture === SHADOW) SHADOW_SIZE else CAPSULE_SIZE) * OVERSAMPLE,
                (if (texture === SHADOW) SHADOW_SIZE else CAPSULE_SIZE) * OVERSAMPLE,
                argb,
            )
        } finally {
            graphics.pose().popMatrix()
        }
    }

    /**
     * Draws a capsule panel (optionally with its drop shadow) at ([x], [y]).
     */
    fun drawPanel(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        argb: Int,
        shadowArgb: Int? = null,
    ) {
        if (shadowArgb != null && available(SHADOW)) {
            val pad = 8f
            for (slice in slices(true, width + pad * 2, height + pad * 2)) {
                drawSlice(graphics, SHADOW, slice, x - pad, y - pad, shadowArgb)
            }
        }

        if (available(CAPSULE)) {
            for (slice in slices(false, width, height)) {
                drawSlice(graphics, CAPSULE, slice, x, y, argb)
            }
            return
        }

        // Fallback when the resource pack has no capsule texture.
        graphics.drawRoundedRect(
            x, y, x + width, y + height, CAPSULE_EDGE,
            fillColor = net.ccbluex.liquidbounce.render.engine.type.Color4b(argb),
        )
    }

    /**
     * Draws the heart glyph at ([x], [y]) scaled to [width] x [height].
     */
    fun drawHeart(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        argb: Int,
    ): Boolean {
        if (!available(HEART)) {
            return false
        }

        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(x, y)
            graphics.pose().scale(
                width / HEART_SOURCE_WIDTH,
                height / HEART_SOURCE_HEIGHT,
            )
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                HEART,
                0,
                0,
                0f,
                0f,
                HEART_SOURCE_WIDTH,
                HEART_SOURCE_HEIGHT,
                HEART_TEXTURE_WIDTH,
                HEART_TEXTURE_HEIGHT,
                HEART_TEXTURE_WIDTH,
                HEART_TEXTURE_HEIGHT,
                argb,
            )
        } finally {
            graphics.pose().popMatrix()
        }
        return true
    }
}