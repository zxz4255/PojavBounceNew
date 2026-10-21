/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Ported from Samsara's NameTags module (com.samsara.module.visual.NameTags).
 */
package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/**
 * Capsule painter for [ModuleSamsaraNametags].
 *
 * Geometry matches Samsara's `NameTags.Geometry` / `NameTags.Painter`:
 * a row of capsules, each a nine-slice texture (`capsule.png`) with an optional
 * drop shadow (`shadow.png`) and a heart glyph (`heart.png`) for the health part.
 * If the textures are missing from the resource pack, primitives are drawn instead.
 */
internal object SamsaraNametagRenderer {

    const val HEIGHT = 30f
    const val PADDING = 7f
    const val GAP = 2f
    const val RADIUS = SamsaraNametagTextures.CAPSULE_EDGE
    const val FONT_SIZE = 16f
    const val HEART_WIDTH = 13.5f
    const val HEART_HEIGHT = 13f
    const val EQUIPMENT_STEP = 32f
    const val EQUIPMENT_HEIGHT = 36f

    val GREEN = Color4b(0xFF88D922.toInt())
    val YELLOW = Color4b(0xFFFFFF55.toInt())
    val RED = Color4b(0xFFFF3333.toInt())
    val EQUAL = Color4b(0xFFFFAA00.toInt())
    val DISTANCE = Color4b(0xFF888888.toInt())
    val COUNT = Color4b(0xFFFFCC00.toInt())
    val WHITE = Color4b(0xFFFFFFFF.toInt())

    val EQUIPMENT_SLOTS = listOf(
        EquipmentSlot.MAINHAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
        EquipmentSlot.OFFHAND,
    )

    class Part(val text: Component, val color: Color4b, val heart: Boolean = false)

    class Capsule(val part: Part, val x: Float, val width: Float)

    private val font get() = FontManager.FONT_RENDERER

    private val textScale: Float get() = font.scaleToVanillaFont * (FONT_SIZE / 9f)

    fun measure(text: Component): Float = font.getStringWidth(text, textScale)

    /**
     * Lays the [parts] out in a row, like `NameTags.Geometry.layout`.
     */
    fun layout(parts: List<Part>): Pair<List<Capsule>, Float> {
        val capsules = ArrayList<Capsule>(parts.size)
        var x = 0f
        for (part in parts) {
            val width = PADDING * 2 + measure(part.text) + if (part.heart) HEART_WIDTH + 2.5f else 0f
            capsules += Capsule(part, x, width)
            x += width + GAP
        }
        return capsules to (x - GAP).coerceAtLeast(0f)
    }

    fun draw(
        graphics: GuiGraphicsExtractor,
        capsules: List<Capsule>,
        totalWidth: Float,
        centerX: Float,
        centerY: Float,
        scale: Float,
        background: Color4b,
        shadow: Color4b?,
        equipment: List<ItemStack>,
        owner: LivingEntity?,
    ) {
        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(centerX, centerY)
            graphics.pose().scale(scale, scale)

            val top = -HEIGHT / 2f
            for (capsule in capsules) {
                val x = capsule.x - totalWidth / 2f

                SamsaraNametagTextures.drawPanel(
                    graphics = graphics,
                    x = x,
                    y = top,
                    width = capsule.width,
                    height = HEIGHT,
                    argb = background.argb,
                    shadowArgb = shadow?.argb,
                )

                var textX = x + PADDING
                if (capsule.part.heart) {
                    val heartY = top + HEIGHT / 2f - HEART_HEIGHT / 2f
                    val textured = SamsaraNametagTextures.drawHeart(
                        graphics = graphics,
                        x = textX,
                        y = heartY,
                        width = HEART_WIDTH,
                        height = HEART_HEIGHT,
                        argb = capsule.part.color.argb,
                    )
                    if (!textured) {
                        drawHeartPrimitive(graphics, textX, heartY, HEART_WIDTH, HEART_HEIGHT, capsule.part.color)
                    }
                    textX += HEART_WIDTH + 2.5f
                }

                font.draw(
                    graphics,
                    capsule.part.text,
                    textX,
                    top + (HEIGHT - FONT_SIZE) / 2f,
                    capsule.part.color,
                    shadow = true,
                    scale = textScale,
                )
            }

            if (equipment.isNotEmpty()) {
                var itemX = -(equipment.size * EQUIPMENT_STEP - 4f) / 2f
                val itemY = top - EQUIPMENT_HEIGHT
                for (stack in equipment) {
                    graphics.pose().pushMatrix()
                    try {
                        graphics.pose().translate(itemX, itemY)
                        graphics.pose().scale(1.75f, 1.75f)
                        if (owner != null) {
                            graphics.item(owner, stack, 0, 0, 0)
                        } else {
                            graphics.item(stack, 0, 0)
                        }
                        if (stack.count > 1) {
                            val count = stack.count.toString()
                            font.draw(
                                graphics,
                                Component.literal(count),
                                17f - font.getStringWidth(Component.literal(count)),
                                9f,
                                WHITE,
                                shadow = true,
                                scale = 1f,
                            )
                        }
                    } finally {
                        graphics.pose().popMatrix()
                    }
                    itemX += EQUIPMENT_STEP
                }
            }
        } finally {
            graphics.pose().popMatrix()
        }
    }

    /**
     * Fallback heart for resource packs without `heart.png`.
     */
    private fun drawHeartPrimitive(
        g: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Color4b,
    ) {
        val radius = width / 2f
        g.drawRoundedRect(x, y, x + radius, y + radius, radius * 0.5f, fillColor = color)
        g.drawRoundedRect(x + radius, y, x + width, y + radius, radius * 0.5f, fillColor = color)
        g.drawTriangle(
            x, y + radius * 0.75f,
            x + width, y + radius * 0.75f,
            x + radius, y + height * 1.0f,
            fillColor = color,
        )
    }

    fun healthColor(health: Int): Color4b = when {
        health >= 14 -> GREEN
        health >= 8 -> YELLOW
        else -> RED
    }

    fun armorDifference(local: Int, target: Int): Part {
        val difference = local - target
        return Part(
            Component.literal(if (difference == 0) "=" else difference.toString()),
            when {
                difference == 0 -> EQUAL
                difference > 0 -> GREEN
                else -> RED
            },
        )
    }

    fun equipmentOf(entity: LivingEntity): List<ItemStack> =
        EQUIPMENT_SLOTS.map { entity.getItemBySlot(it) }.filterNot { it.isEmpty }
}