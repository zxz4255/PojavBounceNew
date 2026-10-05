/*
 * ModuleTellyScaffold — 修正放置位置
 * 在脚下方 / 移动方向寻找可放空气格，并对「相邻固体方块的对应面」点击放置
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
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

object ModuleTellyScaffold : ClientModule("TellyScaffold", ModuleCategories.WORLD) {

    private val range by float("Range", 4.5f, 1f..8f)
    private val delay by intRange("Delay", 0..2, 0..20, "ticks")
    private val antiSway by boolean("AntiSway", true)
    private val disableSafeWalk by boolean("DisableSafeWalk", true)
    private val placePriority by boolean("Prefer Under Feet", true)
    private val expand by int("Expand", 1, 0..3)
    private val tower by boolean("Same Y Only", true)

    private val rotations = RotationsValueGroup(this)
    private var placementCooldown = 0
    private var safeWalkWasEnabled = false

    override fun onEnabled() {
        placementCooldown = 0
        if (disableSafeWalk) {
            runCatching {
                safeWalkWasEnabled = ModuleSafeWalk.enabled
                if (ModuleSafeWalk.enabled) ModuleSafeWalk.enabled = false
            }
        }
    }

    override fun onDisabled() {
        placementCooldown = 0
        if (disableSafeWalk) {
            runCatching {
                if (safeWalkWasEnabled) ModuleSafeWalk.enabled = true
            }
        }
        safeWalkWasEnabled = false
    }

    private fun delayTicks(): Int {
        val r = delay
        return if (r.first >= r.last) r.first else Random.nextInt(r.first, r.last + 1)
    }

    private fun lookingAt(point: Vec3, from: Vec3): Rotation {
        runCatching {
            val m = Rotation::class.java.methods.firstOrNull { it.name == "lookingAt" }
            if (m != null) {
                val r = m.invoke(null, point, from)
                if (r is Rotation) return r
            }
        }
        val dx = point.x - from.x
        val dy = point.y - from.y
        val dz = point.z - from.z
        val dist = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = Math.toDegrees(-atan2(dy, dist)).toFloat()
        return Rotation(yaw, pitch.coerceIn(-90f, 90f))
    }

    private fun isReplaceable(state: BlockState): Boolean =
        state.isAir || runCatching { state.canBeReplaced() }.getOrDefault(false)

    private fun isSolidPlaceable(pos: BlockPos): Boolean {
        val st = world.getBlockState(pos)
        if (isReplaceable(st)) return false
        return runCatching { st.isSolid }.getOrDefault(!st.isAir)
    }

    /**
     * 计算本 tick 要「填上」的空气格列表（脚下优先，再沿移动方向扩展）。
     */
    private fun candidatePlacePositions(): List<BlockPos> {
        val base = player.blockPosition()
        val under = base.below()
        val list = ArrayList<BlockPos>()

        if (placePriority && isReplaceable(world.getBlockState(under))) {
            list += under
        }

        // 水平移动方向
        val mx = player.deltaMovement.x
        val mz = player.deltaMovement.z
        val sx = when {
            mx > 0.08 -> 1
            mx < -0.08 -> -1
            else -> 0
        }
        val sz = when {
            mz > 0.08 -> 1
            mz < -0.08 -> -1
            else -> 0
        }

        // 看向方向（站定时仍可搭）
        val yaw = player.yRot
        val lookX = -kotlin.math.sin(Math.toRadians(yaw.toDouble()))
        val lookZ = kotlin.math.cos(Math.toRadians(yaw.toDouble()))
        val fx = when {
            sx != 0 -> sx
            lookX > 0.5 -> 1
            lookX < -0.5 -> -1
            else -> 0
        }
        val fz = when {
            sz != 0 -> sz
            lookZ > 0.5 -> 1
            lookZ < -0.5 -> -1
            else -> 0
        }

        for (i in 0..expand) {
            val ox = fx * i
            val oz = fz * i
            val p = under.offset(ox, 0, oz)
            if (isReplaceable(world.getBlockState(p)) && p !in list) list += p
            // 斜向
            if (fx != 0 && fz != 0) {
                val p2 = under.offset(fx * i, 0, 0)
                val p3 = under.offset(0, 0, fz * i)
                if (isReplaceable(world.getBlockState(p2)) && p2 !in list) list += p2
                if (isReplaceable(world.getBlockState(p3)) && p3 !in list) list += p3
            }
        }

        if (list.isEmpty() && isReplaceable(world.getBlockState(under))) {
            list += under
        }
        return list
    }

    /**
     * 对「要放置的空气格」找一个相邻固体，生成正确的 BlockHitResult：
     * 点击的是 neighbor，面朝向 placePos。
     */
    private fun findHitAgainst(placePos: BlockPos): BlockHitResult? {
        val eye = player.eyePosition
        var best: BlockHitResult? = null
        var bestDist = Double.MAX_VALUE

        for (dir in Direction.entries) {
            if (tower && dir.axis == Direction.Axis.Y && dir == Direction.UP) continue
            val neighbor = placePos.relative(dir)
            if (!isSolidPlaceable(neighbor)) continue
            // 点击 neighbor 上朝向 placePos 的面 = dir.opposite
            val side = dir.opposite
            val hitVec = Vec3(
                neighbor.x + 0.5 + side.stepX * 0.5,
                neighbor.y + 0.5 + side.stepY * 0.5,
                neighbor.z + 0.5 + side.stepZ * 0.5,
            )
            val dist = eye.distanceToSqr(hitVec)
            if (dist > range * range) continue
            if (dist < bestDist) {
                bestDist = dist
                best = BlockHitResult(hitVec, side, neighbor, false)
            }
        }
        return best
    }

    private fun pickPlacement(): Pair<BlockPos, BlockHitResult>? {
        for (pos in candidatePlacePositions()) {
            val hit = findHitAgainst(pos) ?: continue
            return pos to hit
        }
        return null
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (placementCooldown > 0) {
            placementCooldown--
            return@handler
        }

        val held = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (held.isEmpty || held.item !is BlockItem) return@handler

        val pick = pickPlacement() ?: return@handler
        val (placePos, hit) = pick

        // 瞄准点击点
        val rot = lookingAt(hit.location, player.eyePosition)
        runCatching {
            RotationManager.setRotationTarget(
                rotation = rot,
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.NORMAL,
                provider = this@ModuleTellyScaffold,
            )
        }

        if (antiSway) applyAntiSway(placePos)

        val placed = runCatching {
            doPlacement(
                hit,
                hand = InteractionHand.MAIN_HAND,
                swingMode = SwingMode.DO_NOT_HIDE,
            )
            true
        }.getOrElse {
            runCatching {
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                true
            }.getOrDefault(false)
        }

        if (placed) placementCooldown = delayTicks()
    }

    private fun applyAntiSway(placePos: BlockPos) {
        val eyes = player.eyePosition
        val center = Vec3.atCenterOf(placePos)
        val dx = eyes.x - center.x
        val dz = eyes.z - center.z
        if (sqrt(dx * dx + dz * dz) <= 0.02) return
        val current = runCatching { RotationManager.currentRotation }.getOrNull()
            ?: Rotation(player.yRot, player.xRot)
        val correction = (if (dx > 0) -1f else 1f) * 1.5f
        runCatching {
            RotationManager.setRotationTarget(
                rotation = Rotation(current.yaw + correction, current.pitch),
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.NORMAL,
                provider = this@ModuleTellyScaffold,
            )
        }
    }
}
