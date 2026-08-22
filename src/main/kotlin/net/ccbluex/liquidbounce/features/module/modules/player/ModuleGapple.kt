package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.interaction
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.network.sendHeldItemChange
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items

/**
 * Re‑implementation of the legacy "Gapple" module for LiquidBounce‑0.39.
 *
 * Features:
 *  • Eat golden apples automatically when health drops below the configurable threshold.
 *  • Optional **Stuck** mode freezes the player’s velocity while eating.
 *  • Optional **StopMove** blocks all movement inputs while eating.
 *  • Sends a dummy movement packet every [sendDelay] ticks to mimic the old Blink behaviour.
 *  • Renders a small progress‑bar on the screen during the eating animation.
 */
object ModuleGapple : ClientModule("Gapple", ModuleCategories.PLAYER) {
    // --------------------------------------------------------------------
    // Configuration (defaults match the original Java version)
    // --------------------------------------------------------------------
    private val heal          by int   ("Health",        20, 0..40)   // start eating when health < this value
    private val sendDelay     by int   ("SendDelay",      3, 1..10)  // send a keep‑alive packet every N ticks
    private val stuckEnabled  by boolean("Stuck",        false)      // freeze the player while eating
    private val stopMove      by boolean("StopMove",    false)      // block movement input while eating
    private val autoGapple    by boolean("AutoGapple",  false)      // continue eating automatically after each apple
    private val startColor    by color ("ProgressStartColor", Color4b(76, 157, 240, 255))
    private val endColor      by color ("ProgressEndColor",   Color4b(53, 200, 167, 255))

    // --------------------------------------------------------------------
    // Runtime state (mirrors fields from the original implementation)
    // --------------------------------------------------------------------
    private var slot = -1               // hot‑bar slot (0‑8) of the golden apple, -1 = not found
    private var c03s = 0                // number of intercepted movement packets (max 32)
    private var eating = false          // are we currently in the eating phase?
    private var pulsing = false         // used for the UI progress bar animation
    private var internalTick = 0        // simple tick counter (replaces mc.tickCount)

    // --------------------------------------------------------------------
    // Helper utilities
    // --------------------------------------------------------------------
    private fun findAppleSlot(): Int {
        for (i in 0 until 9) {
            val stack = player.inventory.getItem(i)
            if (stack.item == Items.GOLDEN_APPLE || stack.item == Items.ENCHANTED_GOLDEN_APPLE) return i
        }
        return -1
    }

    private fun sendKeepAlive() = sendPacketSilently(
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly(
            player.onGround(),
            player.horizontalCollision
        )
    )

    private fun consumeApple() {
        // Switch to apple slot
        sendHeldItemChange(slot)
        // Use the apple (handles rotation internally)
        useItem(InteractionHand.MAIN_HAND)
        // Switch back to the previously selected slot
        sendHeldItemChange(player.inventory.selectedSlot)
    }

    // --------------------------------------------------------------------
    // Event handlers (0.39 DSL)
    // --------------------------------------------------------------------
    private val gameTickHandler = handler<GameTickEvent> {
        internalTick++
        if (player.isDeadOrDying) {
            enabled = false
            return@handler
        }

        if (player.health < heal) {
            // Start eating if not already
            if (!eating) {
                slot = findAppleSlot()
                if (slot == -1) {
                    // No apples → disable module (legacy behaviour)
                    enabled = false
                    return@handler
                }
                c03s = 0
                eating = true
                pulsing = false
            }

            // Optional Stuck – freeze velocity
            if (stuckEnabled) player.setDeltaMovement(0.0, 0.0, 0.0)

            // Send dummy packet every [sendDelay] ticks
            if (internalTick % sendDelay == 0) sendKeepAlive()

            // Increment counter – after 32 packets we actually consume the apple
            c03s++
            if (c03s >= 32) {
                consumeApple()
                eating = false
                pulsing = true
                c03s = 0
                if (autoGapple) {
                    slot = findAppleSlot()
                    if (slot != -1) eating = true else enabled = false
                } else {
                    enabled = false
                }
            }
        } else {
            // Health sufficient – reset eating state
            eating = false
            pulsing = false
            c03s = 0
        }
    }

    private val movementInputHandler = handler<MovementInputEvent> { ev ->
        if (eating && stopMove) {
            ev.directionalInput = DirectionalInput.ZERO
            ev.jump = false
            ev.sneak = false
        }
    }

    private val overlayHandler = handler<OverlayRenderEvent> { ev ->
        if (eating || pulsing) {
            val ctx = ev.context
            val width = ctx.guiWidth().toFloat()
            val height = ctx.guiHeight().toFloat()
            val barWidth = 140f
            val barHeight = 7f
            val startY = (height / 4f) * 3f
            val startX = (width / 2f) - (barWidth / 2f)
            val progress = (c03s / 32f).coerceIn(0f, 1f)
            val filled = barWidth * progress

            // Background (semi‑transparent black)
            drawRoundedRect(
                startX - 2f, startY - 2f,
                startX + barWidth + 2f, startY + barHeight + 2f,
                radius = 3f,
                fillColor = Color4b(0, 0, 0, 128)
            )
            if (filled > 0f) {
                val cur = Color4b(
                    ((startColor.r * (1 - progress) + endColor.r * progress).toInt()),
                    ((startColor.g * (1 - progress) + endColor.g * progress).toInt()),
                    ((startColor.b * (1 - progress) + endColor.b * progress).toInt()),
                    255
                )
                drawRoundedRect(
                    startX, startY,
                    startX + filled, startY + barHeight,
                    radius = 2f,
                    fillColor = cur
                )
            }
        }
    }
}
