package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.world.entity.player.Player

/**
 * Simple head aimbot for LiquidBounce 0.39.
 *
 * When a player is within the configured range, the module automatically rotates the
 * local player to look at the target's eye position (head). The rotation speed and
 * smoothing are handled by the internal [RotationsValueGroup] which provides the
 * standard angle‑smooth processors used by the other rotation‑based modules.
 *
 * Configurable values:
 * - **Range** – maximum distance (in blocks) at which the aimbot activates.
 * - The smoothing settings are available under the "Rotations" sub‑section just like
 *   in other aim modules.
 */
object ModuleHeadAimbot : ClientModule("HeadAimbot", ModuleCategories.COMBAT) {

    /** Maximum distance (in blocks) to start aiming at a player. */
    private val range = float("Range", 5f, 1f..10f)

    /** Tracks players sorted by distance; we only care about the closest one. */
    private val targetTracker = tree(TargetTracker(TargetPriority.DISTANCE, range = range))

    /** Rotation configuration (smoothness, movement correction, etc.). */
    private val rotations = RotationsValueGroup(this)

    init {
        // No additional sub‑configuration required; the target tracker and rotation group are
        // already registered via `tree(...)`.
    }

    /** Rotate towards the nearest player's head on each game tick. */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // Find the closest player (excluding ourselves) within the configured range.
        val target = targetTracker.targets()
            .filterIsInstance<Player>()
            .firstOrNull() ?: return@handler

        // Compute the eye (head) position of the target.
        val headPos = target.position()
            .add(0.0, target.eyeHeight.toDouble(), 0.0)

        // Build the rotation that looks directly at the head.
        val rotation = Rotation.lookingAt(point = headPos, from = player.eyePosition)

        // Request the rotation from the rotation manager.
        RotationManager.setRotationTarget(
            rotation = rotation,
            considerInventory = false,
            valueGroup = rotations,
            priority = Priority.NORMAL,
            provider = this@ModuleHeadAimbot
        )
    }

    /** Reset the current target when the module is disabled. */
    override fun onDisabled() {
        targetTracker.reset()
    }
}
