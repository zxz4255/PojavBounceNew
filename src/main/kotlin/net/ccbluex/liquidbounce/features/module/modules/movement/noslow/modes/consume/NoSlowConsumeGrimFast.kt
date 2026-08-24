/*
 * Fix: nullable usingItemHand + pongQueue.removeFirst + sendPacketSilently(Packet)
 * Prefer moving to noslow/modes/consume/NoSlowConsumeGrimFast.kt instead of ModuleManager.
 */
package net.ccbluex.liquidbounce.features.module.modules.movement.noslow.modes.consume

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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.PotionItem

class NoSlowConsumeGrimFast(override val parent: ModeValueGroup<*>) : Mode("GrimFast") {

    private val keepSprinting by boolean("KeepSprinting", true)
    private val swapFallbackTicks by int("SwapFallbackTicks", 2, 1..10)
    private val waitFallbackTicks by int("WaitFallbackTicks", 4, 1..15)
    private val idleResetTicks by int("IdleResetTicks", 5, 1..20)

    private val pongQueue = ArrayDeque<ServerboundPongPacket>()
    private var useState = UseState.IDLE
    private var didSwapOffhand = false
    private var waitTicks = 0
    private var swapTicks = 0
    private var idleTicks = 0
    private var forcedUseKey: Boolean? = null

    @Suppress("unused")
    private val keybindHandler = handler<KeybindIsPressedEvent> { event ->
        val forced = forcedUseKey ?: return@handler
        runCatching {
            if (event.keyBinding == mc.options.keyUse) {
                event.isPressed = forced
            }
        }
    }

    @Suppress("unused")
    private val useMultiplierHandler = handler<PlayerUseMultiplier> { event ->
        if (!player.isUsingItem) return@handler

        val activeStack = player.useItem
        if (!isFoodOrPotion(activeStack) || player.useItemRemainingTicks <= 0) {
            resetOffhandState()
            return@handler
        }

        // FIX: usingItemHand is nullable
        val usingHand = player.usingItemHand ?: return@handler
        val otherHand = usingHand.opposite
        if (isUseAnimation(player.getItemInHand(otherHand).useAnimation)) {
            resetOffhandState()
            return@handler
        }

        if (useState != UseState.USING) {
            forcedUseKey = false
        }

        if (useState == UseState.IDLE) {
            useState = UseState.WAITING
            waitTicks = 0
            swapTicks = 0
            return@handler
        }

        if (useState == UseState.USING) {
            event.forward = 1f
            event.sideways = 1f
            if (keepSprinting) player.setSprinting(true)
        }
    }

    @Suppress("unused")
    private val gameTickHandler = handler<GameTickEvent> {
        if (mc.player == null) {
            resetOffhandState()
            return@handler
        }
        if (runCatching { mc.gui.screen() != null }.getOrDefault(false)) {
            resetOffhandState()
            return@handler
        }

        if (useState == UseState.WAITING && ++waitTicks >= waitFallbackTicks) startSwap()
        if (useState == UseState.SWAPPING && ++swapTicks >= swapFallbackTicks) beginUsing()
        if (useState == UseState.USING) {
            if (player.isUsingItem) idleTicks = 0
            else if (++idleTicks >= idleResetTicks) resetOffhandState()
        } else idleTicks = 0
    }

    @Suppress("unused")
    private val sendPacketHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) return@handler
        val packet = event.packet
        if (packet is ServerboundPongPacket) {
            if (useState != UseState.IDLE) {
                runCatching { event.cancelEvent() }
                pongQueue.addLast(packet)
                if (useState == UseState.WAITING) startSwap()
            }
            return@handler
        }
        if (packet is ServerboundPlayerActionPacket) {
            when (packet.action) {
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                    if (useState == UseState.USING) resetOffhandState()
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND ->
                    if (useState != UseState.IDLE) resetOffhandState()
                else -> {}
            }
        }
    }

    @Suppress("unused")
    private val receivePacketHandler = handler<PacketEvent> { event ->
        if (mc.player == null) {
            resetOffhandState()
            return@handler
        }
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val packet = event.packet
        if (useState == UseState.SWAPPING && packet is ClientboundSetEquipmentPacket) {
            beginUsing()
            return@handler
        }
        if (packet is ClientboundSetEntityMotionPacket &&
            packet.id == player.id &&
            useState == UseState.USING
        ) {
            forcedUseKey = false
        }
    }

    override fun disable() {
        resetOffhandState()
        super.disable()
    }

    private fun beginUsing() {
        forcedUseKey = true
        useState = UseState.USING
        waitTicks = 0
        swapTicks = 0
        idleTicks = 0
    }

    private fun startSwap() {
        useState = UseState.SWAPPING
        didSwapOffhand = true
        waitTicks = 0
        swapTicks = 0
        sendSwapOffhand()
    }

    private fun resetOffhandState() {
        if (useState == UseState.IDLE && pongQueue.isEmpty() && !didSwapOffhand) {
            clearOffhandState()
            return
        }
        // FIX: removeFirst() returns Packet, not Boolean
        while (pongQueue.isNotEmpty()) {
            val pong: ServerboundPongPacket = pongQueue.removeFirst()
            runCatching { sendPacketSilently(pong) }
        }
        if (didSwapOffhand) sendSwapOffhand()
        clearOffhandState()
        forcedUseKey = null
    }

    private fun clearOffhandState() {
        pongQueue.clear()
        useState = UseState.IDLE
        didSwapOffhand = false
        waitTicks = 0
        swapTicks = 0
        idleTicks = 0
    }

    private fun sendSwapOffhand() {
        runCatching {
            sendPacketSilently(
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO,
                    Direction.DOWN,
                ),
            )
        }
    }

    private fun isFoodOrPotion(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val action = stack.useAnimation
        return (stack.isFood && action == ItemUseAnimation.EAT) ||
            action == ItemUseAnimation.DRINK ||
            stack.item is PotionItem
    }

    private fun isUseAnimation(action: ItemUseAnimation): Boolean =
        action == ItemUseAnimation.EAT ||
            action == ItemUseAnimation.DRINK ||
            action == ItemUseAnimation.BOW ||
            action == ItemUseAnimation.SPEAR ||
            action == ItemUseAnimation.CROSSBOW

    private enum class UseState { IDLE, WAITING, SWAPPING, USING }
}
