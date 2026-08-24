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
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerUseMultiplier
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.entity.onGroundTicks

/**
 * Port of Opal's `GrimJumpNoSlow`.
 *
 * Grim expects the slowdown to be skipped only on the first ground tick after landing.
 * While consuming, we also force a jump so the movement is not considered "slow".
 *
 * @anticheat Grim
 */
internal class ModuleNoSlowConsumeGrimJump(override val parent: ModeValueGroup<*>) : Mode("GrimJump") {

    private val keepSprinting by boolean("KeepSprinting", true)

    @Suppress("unused")
    private val useMultiplierHandler = handler<PlayerUseMultiplier> { event ->
        if (!player.isUsingItem) {
            return@handler
        }

        // Only skip the slowdown on the tick we land (LocalDataWatch#groundTicks == 1 in Opal)
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
