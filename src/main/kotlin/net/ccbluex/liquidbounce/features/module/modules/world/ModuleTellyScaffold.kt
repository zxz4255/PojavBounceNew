/*
 * ModuleTellyScaffold — 修复 0.39 API 兼容
 * Range / Rotation / Delay / AntiSway / DisableSafeWalk
 */
package net.ccbluex.liquidbounce.features.module.modules.world

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleSafeWalk
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

object ModuleTellyScaffold : ClientModule("TellyScaffold", ModuleCategories.WORLD) {

    private val range by float("Range", 4.5f, 1f..8f)
    private val rotationDuration by int("RotationDuration", 15, 1..200, "ticks")
    private val delay by intRange("Delay", 0..5, 0..40, "ticks")
    private val antiSway by boolean("AntiSway", true)
    private val disableSafeWalk by boolean("DisableSafeWalk", true)

    private val rotations = RotationsValueGroup(this)
    private var placementCooldown = 0
    private var safeWalkWasEnabled = false

    override fun onEnabled() {
        placementCooldown = 0
        if (disableSafeWalk) {
            runCatching {
                safeWalkWasEnabled = ModuleSafeWalk.enabled
                if (ModuleSafeWalk.enabled) {
                    ModuleSafeWalk.enabled = false
                }
            }
        }
    }

    override fun onDisabled() {
        placementCooldown = 0
        if (disableSafeWalk) {
            runCatching {
                if (safeWalkWasEnabled) {
                    ModuleSafeWalk.enabled = true
                }
            }
        }
        safeWalkWasEnabled = false
    }

    private fun lookingAt(point: Vec3, from: Vec3): Rotation {
        // 优先官方 API
        runCatching {
            val m = Rotation::class.java.methods.firstOrNull {
                it.name == "lookingAt" && it.parameterCount >= 2
            }
            if (m != null) {
                val r = when (m.parameterCount) {
                    2 -> m.invoke(null, point, from)
                    else -> m.invoke(null, point, from)
                }
                if (r is Rotation) return r
            }
        }
        val dx = point.x - from.x
        val dy = point.y - from.y
        val dz = point.z - from.z
        val dist = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = Math.toDegrees(-atan2(dy, dist)).toFloat()
        return Rotation(yaw, pitch)
    }

    private fun currentRotation(): Rotation {
        runCatching {
            val cur = RotationManager.currentRotation
            if (cur != null) return cur
        }
        val p = player
        return Rotation(p.yRot, p.xRot)
    }

    private fun delayTicks(): Int {
        val r = delay
        return if (r.first >= r.last) r.first
        else Random.nextInt(r.first, r.last + 1)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (placementCooldown > 0) {
            placementCooldown--
            return@handler
        }

        val heldStack = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (heldStack.isEmpty || heldStack.item !is BlockItem) return@handler

        val targetPos = player.blockPosition().below()
        if (!world.getBlockState(targetPos).isAir) return@handler

        val targetVec = Vec3.atCenterOf(targetPos).add(0.0, 0.5, 0.0)
        val desiredRotation = lookingAt(targetVec, player.eyePosition)

        runCatching {
            RotationManager.setRotationTarget(
                rotation = desiredRotation,
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.NORMAL,
                provider = this@ModuleTellyScaffold,
            )
        }

        if (antiSway) {
            applyAntiSwayCorrection()
        }

        val interactRange = runCatching {
            maxOf(player.blockInteractionRange(), player.entityInteractionRange())
        }.getOrDefault(range.toDouble())

        val traceResult = runCatching {
            traceFromPlayer(
                desiredRotation,
                range = maxOf(interactRange, range.toDouble()),
            )
        }.getOrNull() ?: return@handler

        if (traceResult.type != HitResult.Type.BLOCK) return@handler
        val blockHit = traceResult as? BlockHitResult ?: return@handler

        // doPlacement 签名：不要传 Rotation；hand / swingMode 用命名参数
        val placed = runCatching {
            doPlacement(
                blockHit,
                hand = InteractionHand.MAIN_HAND,
                swingMode = SwingMode.DO_NOT_HIDE,
            )
            true
        }.getOrElse {
            // 回退：原版交互
            runCatching {
                mc.gameMode?.useItemOn(
                    player,
                    InteractionHand.MAIN_HAND,
                    blockHit,
                )
                true
            }.getOrDefault(false)
        }

        if (placed) {
            placementCooldown = delayTicks()
        }
    }

    private fun applyAntiSwayCorrection() {
        if (!antiSway) return
        val eyes = player.eyePosition
        val laneBlock = player.blockPosition().below()
        val laneCenter = Vec3.atCenterOf(laneBlock)
        val dx = eyes.x - laneCenter.x
        val dz = eyes.z - laneCenter.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance <= 0.02) return

        val correction = (if (dx > 0) -1f else 1f) * 2f
        val current = currentRotation()
        val newRotation = Rotation(current.yaw + correction, current.pitch)
        runCatching {
            RotationManager.setRotationTarget(
                rotation = newRotation,
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.NORMAL,
                provider = this@ModuleTellyScaffold,
            )
        }
    }
}
