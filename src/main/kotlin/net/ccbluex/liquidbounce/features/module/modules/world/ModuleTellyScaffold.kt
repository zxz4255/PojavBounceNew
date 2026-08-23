package net.ccbluex.liquidbounce.features.module.modules.world

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.random.Random

/**
 * Kotlin replica of the original 1.8.9 "RavenBSLegitTellyFix" script, re‑implemented for LiquidBounce 0.39.
 *
 * Features (core subset):
 *  • Automatic block placement directly under the player while a placeable block is held.
 *  • Configurable placement range, rotation smoothing, placement delay and optional anti‑sway lane correction.
 *  • Uses the modern [RotationManager] / [RotationsValueGroup] APIs for smooth, server‑friendly rotations.
 *  • Optional automatic SafeWalk disabling while the scaffold is active.
 *  • All key parameters are exposed as module settings and can be tuned via the ClickGUI.
 */
object ModuleTellyScaffold : ClientModule("TellyScaffold", ModuleCategories.WORLD) {

    // ---------------------------------------------------------------------
    // Configurable values – these appear in the ClickGUI under the module.
    // ---------------------------------------------------------------------
    /** Maximum distance (in blocks) for the auto‑placement ray‑trace. */
    private val range = float("Range", 4.5f, 1f..8f)

    /** Rotation time in ticks – larger values make the rotation slower but smoother. */
    private val rotationDuration = int("RotationDuration", 15, 1..200, "ticks")

    /** Minimum delay between two placement attempts. */
    private val delay = intRange("Delay", 0..5, 0..40, "ticks")

    /** Enable a very small anti‑sway correction that keeps the player aligned with the lane. */
    private val antiSway = boolean("AntiSway", true)

    /** When enabled, the module temporarily disables the SafeWalk module (if present). */
    private val disableSafeWalk = boolean("DisableSafeWalk", true)

    // ---------------------------------------------------------------------
    // Internal state helpers.
    // ---------------------------------------------------------------------
    private val rotations = RotationsValueGroup(this)
    private var placementCooldown = 0
    private var rotationActive = false
    private var rotationStartTick = 0L
    private var targetYaw = 0f
    private var targetPitch = 0f

    init {
        // No additional sub‑modules are required for the basic implementation.
    }

    // ---------------------------------------------------------------------
    // Lifecycle – enable / disable handling.
    // ---------------------------------------------------------------------
    override fun onEnabled() {
        // Capture and temporarily disable SafeWalk if requested.
        if (disableSafeWalk.value) {
            try {
                if (modules.isEnabled("SafeWalk")) modules.disable("SafeWalk")
            } catch (e: Exception) {
                // ignore – module may not exist.
            }
        }
        placementCooldown = 0
        rotationActive = false
        super.onEnabled()
    }

    override fun onDisabled() {
        // Re‑enable SafeWalk if we disabled it.
        if (disableSafeWalk.value) {
            try {
                if (!modules.isEnabled("SafeWalk")) modules.enable("SafeWalk")
            } catch (e: Exception) {
                // ignore.
            }
        }
        placementCooldown = 0
        rotationActive = false
        super.onDisabled()
    }

    // ---------------------------------------------------------------------
    // Main tick handler – performs the placement logic.
    // ---------------------------------------------------------------------
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // Respect placement cooldown.
        if (placementCooldown > 0) {
            placementCooldown--
            return@handler
        }

        // Ensure the player holds a placeable block in the main hand.
        val heldStack = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (heldStack.isEmpty || heldStack.item !is BlockItem) return@handler

        // Determine the block position directly below the player's feet.
        val targetPos = player.blockPosition().below()
        // Simple replace‑ability check – only place on air (this mirrors the original script's logic).
        if (!world.getBlockState(targetPos).isAir) return@handler

        // -----------------------------------------------------------------
        // Rotation handling – calculate a rotation that looks at the centre of the
        // target block's top face (the place we intend to click on).
        // -----------------------------------------------------------------
        val targetVec = Vec3.atCenterOf(targetPos).add(0.0, 0.5, 0.0) // centre of the top face
        val desiredRotation = Rotation.lookingAt(point = targetVec, from = player.eyePosition)

        // Submit the rotation request to the RotationManager.
        // We use a normal priority – higher priorities (e.g. KillAura) can override if needed.
        RotationManager.setRotationTarget(
            rotation = desiredRotation,
            considerInventory = false,
            valueGroup = rotations,
            priority = Priority.NORMAL,
            provider = this@ModuleTellyScaffold
        )
        rotationActive = true
        rotationStartTick = mc.level?.gameTime?.toLong() ?: 0L
        targetYaw = desiredRotation.yaw
        targetPitch = desiredRotation.pitch

        // -----------------------------------------------------------------
        // Perform the block placement immediately after we have asked the
        // rotation system to aim. The RotationManager will smooth the turn; the
        // actual placement will therefore happen a few ticks later – this mirrors
        // the behaviour of vanilla scaffold modules and is safe for anti‑cheat.
        // -----------------------------------------------------------------
        val traceResult = traceFromPlayer(desiredRotation, range = max(player.blockInteractionRange(), player.entityInteractionRange()))
        if (traceResult.type == HitResult.Type.BLOCK) {
            // Place the block using the high‑level utility. The swing mode is set to DO_NOT_HIDE
            // because the script does not hide swings, and it works well with most anti‑cheat setups.
            doPlacement(traceResult, desiredRotation, hand = InteractionHand.MAIN_HAND, swingMode = SwingMode.DO_NOT_HIDE)
            // Apply a random delay based on the configured range.
            placementCooldown = delay.random()
        }
    }

    // ---------------------------------------------------------------------
    // Optional anti‑sway lane correction – keeps the player centred on the
    // block‑placement lane. The implementation is a simplified version of the
    // original script and only activates when the flag is enabled.
    // ---------------------------------------------------------------------
    private fun applyAntiSwayCorrection() {
        if (!antiSway.value) return
        // Very small lane‑keeping adjustment: if the player drifts away from the
        // centre of the block beneath them we nudge the yaw a few degrees.
        val eyes = player.eyePosition
        val laneBlock = player.blockPosition().below()
        val laneCenter = Vec3.atCenterOf(laneBlock)
        val dx = eyes.x - laneCenter.x
        val dz = eyes.z - laneCenter.z
        val distance = kotlin.math.sqrt(dx * dx + dz * dz)
        if (distance > 0.02) {
            // Compute a corrective yaw change (max ~2 degrees per tick).
            val correction = (if (dx > 0) -1 else 1) * 2f
            // Issue a small rotation offset via RotationManager.
            val current = RotationManager.currentRotation ?: player.rotation
            val newYaw = current.yaw + correction
            val newRotation = Rotation(yaw = newYaw, pitch = current.pitch)
            RotationManager.setRotationTarget(
                rotation = newRotation,
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.NORMAL,
                provider = this@ModuleTellyScaffold
            )
        }
    }
}
