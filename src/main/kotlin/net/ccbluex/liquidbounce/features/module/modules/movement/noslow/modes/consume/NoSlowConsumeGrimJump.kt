package net.ccbluex.liquidbounce.features.module.modules.movement.noslow.modes.consume

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerUseMultiplier
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.onGroundTicks

class NoSlowConsumeGrimJump(override val parent: ModeValueGroup<*>) : Mode("GrimJump") {

    private val keepSprinting by boolean("KeepSprinting", true)

    @Suppress("unused")
    private val useMultiplierHandler = handler<PlayerUseMultiplier> { event ->
        if (!player.isUsingItem) return@handler
        if (player.onGroundTicks == 1) {
            event.forward = 1f
            event.sideways = 1f
            if (keepSprinting && !player.isSprinting) {
                player.setSprinting(true)
            }
        }
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (!player.onGround() || !player.isUsingItem || !event.directionalInput.isMoving) {
            return@handler
        }
        event.jump = true
    }
}
