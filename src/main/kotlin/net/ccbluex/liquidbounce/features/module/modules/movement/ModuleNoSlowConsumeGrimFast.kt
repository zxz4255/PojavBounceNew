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
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerUseMultiplier
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.opposite
import net.ccbluex.liquidbounce.utils.item.isFood
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.PotionItem

/**
 * Port of Opal's `GrimFastNoSlow`.
 *
 * Offhand swap based no-slow for Grim:
 * 1. While consuming, the use key is released and a swap packet is sent (item moves to the offhand).
 * 2. Once the server confirms the swap (equipment change packet / fallback ticks), the use key is
 *    pressed again and the slowdown is cancelled.
 * 3. Pongs are queued during the swap and flushed afterwards to keep the connection alive.
 *
 * @anticheat Grim
 */
internal class ModuleNoSlowConsumeGrimFast(override val parent: ModeValueGroup<*>) : Mode("GrimFast") {

    private val keepSprinting by boolean("KeepSprinting", true)

    private companion object {
        const val SWAP_FALLBACK_TICKS = 2
        const val WAIT_FALLBACK_TICKS = 4
        const val IDLE_RESET_TICKS = 5
    }

    private val pongQueue = ArrayDeque<ServerboundPongPacket>()

    private var useState = UseState.IDLE
    private var didSwapOffhand = false
    private var waitTicks = 0
    private var swapTicks = 0
    private var idleTicks = 0

    /**
     * Force override for the use key state.
     * `null` restores the physical key state (Opal's `restoreUseKeyState`).
     */
    private var forcedUseKey: Boolean? = null

    @Suppress("unused")
    private val keybindHandler = handler<KeybindIsPressedEvent> { event ->
        if (event.keyBinding == mc.options.keyUse) {
            forcedUseKey?.let { event.isPressed = it }
        }
    }

    @Suppress("unused")
    private val useMultiplierHandler = handler<PlayerUseMultiplier> { event ->
        if (!player.isUsingItem) {
            return@handler
        }

        val activeStack = player.useItem
        if (!isFoodOrPotion(activeStack) || player.useItemRemainingTicks <= 0) {
            this.resetOffhandState()
            return@handler
        }

        val otherHand = player.usingItemHand.opposite
        if (isUseAnimation(player.getItemInHand(otherHand).useAnimation)) {
            this.resetOffhandState()
            return@handler
        }

        if (this.useState != UseState.USING) {
            this.forcedUseKey = false
        }

        if (this.useState == UseState.IDLE) {
            this.useState = UseState.WAITING
            this.waitTicks = 0
            this.swapTicks = 0
            return@handler
        }

        if (this.useState == UseState.USING) {
            event.forward = 1f
            event.sideways = 1f
            if (this.keepSprinting) {
                player.setSprinting(true)
            }
        }
    }

    @Suppress("unused")
    private val gameTickHandler = handler<GameTickEvent> {
        if (mc.player == null || mc.gui.screen() != null || mc.gui.overlay() != null) {
            this.resetOffhandState()
            return@handler
        }

        if (this.useState == UseState.WAITING && ++this.waitTicks >= WAIT_FALLBACK_TICKS) {
            this.startSwap()
        }

        if (this.useState == UseState.SWAPPING && ++this.swapTicks >= SWAP_FALLBACK_TICKS) {
            this.beginUsing()
        }

        if (this.useState == UseState.USING) {
            if (player.isUsingItem) {
                this.idleTicks = 0
            } else if (++this.idleTicks >= IDLE_RESET_TICKS) {
                this.resetOffhandState()
            }
        } else {
            this.idleTicks = 0
        }
    }

    @Suppress("unused")
    private val sendPacketHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }

        val packet = event.packet
        if (packet is ServerboundPongPacket) {
            if (this.useState != UseState.IDLE) {
                event.cancelEvent()
                this.pongQueue.add(packet)
                if (this.useState == UseState.WAITING) {
                    this.startSwap()
                }
            }
            return@handler
        }

        if (packet is ServerboundPlayerActionPacket) {
            when (packet.action) {
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                    if (this.useState == UseState.USING) {
                        this.resetOffhandState()
                    }

                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND ->
                    if (this.useState != UseState.IDLE) {
                        this.resetOffhandState()
                    }

                else -> {}
            }
        }
    }

    @Suppress("unused")
    private val receivePacketHandler = handler<PacketEvent> { event ->
        if (mc.player == null) {
            this.resetOffhandState()
            return@handler
        }

        if (event.origin != TransferOrigin.INCOMING) {
            return@handler
        }

        val packet = event.packet
        if (this.useState == UseState.SWAPPING && this.isEquipmentChangePacket(packet)) {
            this.beginUsing()
            return@handler
        }

        if (packet is ClientboundSetEntityMotionPacket
            && packet.id == player.id
            && this.useState == UseState.USING
        ) {
            this.forcedUseKey = false
        }
    }

    override fun disable() {
        this.resetOffhandState()
        super.disable()
    }

    private fun beginUsing() {
        this.forcedUseKey = true
        this.useState = UseState.USING
        this.waitTicks = 0
        this.swapTicks = 0
        this.idleTicks = 0
    }

    private fun startSwap() {
        this.useState = UseState.SWAPPING
        this.didSwapOffhand = true
        this.waitTicks = 0
        this.swapTicks = 0
        this.sendSwapOffhand()
    }

    private fun resetOffhandState() {
        if (this.useState == UseState.IDLE && this.pongQueue.isEmpty() && !this.didSwapOffhand) {
            this.clearOffhandState()
            return
        }

        while (!this.pongQueue.isEmpty()) {
            sendPacketSilently(this.pongQueue.remove())
        }

        if (this.didSwapOffhand) {
            this.sendSwapOffhand()
        }

        this.clearOffhandState()
        this.forcedUseKey = null
    }

    private fun clearOffhandState() {
        this.pongQueue.clear()
        this.useState = UseState.IDLE
        this.didSwapOffhand = false
        this.waitTicks = 0
        this.swapTicks = 0
        this.idleTicks = 0
    }

    private fun sendSwapOffhand() {
        sendPacketSilently(
            ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO,
                Direction.DOWN
            )
        )
    }

    private fun isEquipmentChangePacket(packet: Packet<*>) =
        packet is ClientboundSetEquipmentPacket

    private fun isFoodOrPotion(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }

        val action = stack.useAnimation
        return (stack.isFood && action == ItemUseAnimation.EAT)
            || action == ItemUseAnimation.DRINK
            || stack.item is PotionItem
    }

    private fun isUseAnimation(action: ItemUseAnimation): Boolean {
        return action == ItemUseAnimation.EAT
            || action == ItemUseAnimation.DRINK
            || action == ItemUseAnimation.BOW
            || action == ItemUseAnimation.SPEAR
            || action == ItemUseAnimation.CROSSBOW
    }

    private enum class UseState {
        IDLE,
        WAITING,
        SWAPPING,
        USING
    }

}
