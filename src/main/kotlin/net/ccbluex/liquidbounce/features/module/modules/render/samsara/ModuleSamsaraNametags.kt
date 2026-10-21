/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Ported from Samsara's NameTags module (com.samsara.module.visual.NameTags).
 */
package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.roundToInt

/**
 * Nametags in Samsara's capsule style: health, name, armour difference and distance
 * for players, name and stack size for dropped items.
 *
 * Replaces the vanilla nametag rendering while enabled, like Samsara's
 * `NameTags.replaces`.
 */
object ModuleSamsaraNametags : ClientModule("SamsaraNametags", ModuleCategories.RENDER) {

    private val players by boolean("Players", true)
    private val items by boolean("Items", true)
    private val playerArmor by boolean("PlayerArmor", true)
    private val armorDifference by boolean("ArmorDifference", true)
    private val distance by boolean("Distance", true)
    private val health by boolean("Health", true)
    private val background by color("Background", Color4b(0x98000000.toInt()))
    private val dropShadow by boolean("Shadow", true)
    private val shadowColor by color("ShadowColor", Color4b(0x80000000.toInt()))
    private val scale by float("Scale", 0.6f, 0.1f..2f, "x")

    private class Label(
        val entity: Entity,
        val x: Float,
        val y: Float,
        val distanceSquared: Double,
        val opacity: Float,
    )

    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        val level = mc.level ?: return@handler
        val self = mc.player ?: return@handler
        if (mc.gui.hud.isHidden) return@handler

        val graphics = event.context
        val labels = ArrayList<Label>()

        for (entity in level.entitiesForRendering()) {
            if (!accepts(entity)) {
                continue
            }

            val position = entity.interpolateCurrentPosition(event.tickDelta)
            val rise = if (entity is ItemEntity) {
                entity.bbHeight + 0.35
            } else {
                entity.getEyeHeight(entity.pose) + 0.55
            }
            val screen = WorldToScreen.calculateScreenPos(
                net.minecraft.world.phys.Vec3(position.x, position.y + rise, position.z),
            ) ?: continue
            if (!screen.x.isFinite() || !screen.y.isFinite()) {
                continue
            }

            val opacity = edgeOpacity(screen.x, graphics.guiWidth().toFloat())
            if (opacity < 4f / 255f) {
                continue
            }

            labels += Label(entity, screen.x, screen.y, position.distanceToSqr(mc.gameRenderer.mainCamera().position()), opacity)
        }

        labels.sortWith(
            compareByDescending<Label> { it.distanceSquared }.thenBy { it.entity.id },
        )

        for (label in labels) {
            drawLabel(graphics, label, self)
        }
    }

    private fun accepts(entity: Entity): Boolean {
        if (entity.isRemoved) {
            return false
        }
        return when (entity) {
            is Player -> players
            is ItemEntity -> items && !entity.item.isEmpty
            else -> false
        }
    }

    private fun drawLabel(
        graphics: net.minecraft.client.gui.GuiGraphicsExtractor,
        label: Label,
        self: Player,
    ) {
        val entity = label.entity
        val parts = ArrayList<SamsaraNametagRenderer.Part>()

        var equipment = emptyList<net.minecraft.world.item.ItemStack>()
        var owner: LivingEntity? = null

        when (entity) {
            is Player -> {
                if (health) {
                    val actual = entity.getActualHealth().roundToInt()
                    parts += SamsaraNametagRenderer.Part(
                        Component.literal(actual.toString()),
                        SamsaraNametagRenderer.healthColor(actual),
                        heart = true,
                    )
                }
                parts += SamsaraNametagRenderer.Part(formattedName(entity), Color4b.WHITE)
                if (armorDifference) {
                    parts += SamsaraNametagRenderer.armorDifference(self.armorValue, entity.armorValue)
                }
                if (distance) {
                    parts += SamsaraNametagRenderer.Part(
                        Component.literal("${self.distanceTo(entity).roundToInt()}m"),
                        SamsaraNametagRenderer.DISTANCE,
                    )
                }
                if (playerArmor) {
                    equipment = SamsaraNametagRenderer.equipmentOf(entity)
                    owner = entity
                }
            }

            is ItemEntity -> {
                parts += SamsaraNametagRenderer.Part(entity.item.hoverName, Color4b.WHITE)
                parts += SamsaraNametagRenderer.Part(
                    Component.literal("x${entity.item.count}"),
                    SamsaraNametagRenderer.COUNT,
                )
            }
        }

        if (parts.isEmpty()) {
            return
        }

        val (capsules, totalWidth) = SamsaraNametagRenderer.layout(parts)
        SamsaraNametagRenderer.draw(
            graphics = graphics,
            capsules = capsules,
            totalWidth = totalWidth,
            centerX = label.x,
            centerY = label.y,
            scale = scale,
            background = background.fade(label.opacity),
            shadow = if (dropShadow) shadowColor.fade(label.opacity) else null,
            equipment = equipment,
            owner = owner,
        )
    }

    /**
     * Samsara fades nametags out towards the screen edges (`Geometry.opacity`).
     */
    private fun edgeOpacity(x: Float, width: Float): Float {
        if (!x.isFinite() || width <= 0f) {
            return 0f
        }
        val edge = kotlin.math.abs(x * 2f / width - 1f)
        val t = ((edge - 0.75f) / 0.25f).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    private fun formattedName(player: Player): Component {
        val name = player.team?.getFormattedName(player.name) ?: player.name
        return when {
            player.isInvisible -> name.copy().withStyle(ChatFormatting.GOLD)
            player.isShiftKeyDown -> name.copy().withStyle(ChatFormatting.DARK_RED)
            else -> name
        }
    }
}