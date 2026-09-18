/*
 * ModulePacketTPAura — LiquidBounce Nextgen 0.39
 *
 * ASP 客户端 TPAura 移植版：
 * A* 地面寻路 → 静默发送位置包瞬移到目标 → 攻击 → 沿路径回传。
 * 客户端本体不移动（sendPacketSilently），服务器视角短暂处于目标旁边完成攻击。
 *
 * 原 ASP 版本基于 1.8.9，此处已适配 26.x：
 *   C04PacketPlayerPosition    -> ServerboundMovePlayerPacket.Pos
 *   C02PacketUseEntity(ATTACK) -> ServerboundAttackPacket
 *   C0APacketAnimation         -> ServerboundSwingPacket
 *   MainPathFinder.computePath -> 内置 A* 地面寻路
 * 原版基于独立线程攻击，此处改为 tickHandler（DiscardLatest，天然防止攻击重叠）。
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.interaction
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.network.releaseUsingItemInTickLoop
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

object ModuleBedrockTPAura : ClientModule("ModuleBedrockTPAura", ModuleCategories.COMBAT, disableOnQuit = true) {

    /*
     * Values（对应原 ASP 版设置）
     */
    private val apsValue by int("CPS", 6, 1..10)
    private val maxTargetsValue by int("MaxTarget", 1, 1..8)
    private val rangeValue by int("Range", 30, 10..70, "m")
    private val fovValue by float("FOV", 180f, 0f..180f, "°")
    private val swingValue by enumChoice("Swing", SwingMode.NORMAL)
    // 原版 "1.9+Attack"：控制攻击包与挥手包的先后顺序
    private val attackBeforeSwing by boolean("AttackBeforeSwing", false)
    private val rotationValue by boolean("Rotations", true)
    private val autoBlockValue by boolean("AutoBlock", true)

    private val rotations = tree(RotationsValueGroup(this))

    /*
     * Variables
     */
    private val attackChronometer = Chronometer()

    var lastTarget: LivingEntity? = null
        private set
    private var isBlocking = false

    /**
     * 原版 Swing 选项：
     * [SwingMode.NORMAL] = 客户端挥手（同时发送挥手包）；
     * [SwingMode.PACKET] = 仅发送挥手包（无客户端动画）；
     * [SwingMode.NONE]   = 不挥手。
     */
    private enum class SwingMode(override val tag: String) : Tagged {
        NORMAL("Normal"),
        PACKET("Packet"),
        NONE("None"),
    }

    private val attackDelay: Long
        get() = 1000L / apsValue.toLong()

    override fun onEnabled() {
        resetState()
    }

    override fun onDisabled() {
        resetState()
    }

    private fun resetState() {
        lastTarget = null
        isBlocking = false
        attackChronometer.reset()
    }

    @Suppress("unused")
    private val updateHandler = tickHandler {
        if (mc.player == null || mc.level == null) return@tickHandler

        // 原版：lastTarget 存在且开启 Rotations 时持续面向目标
        if (lastTarget != null && rotationValue) {
            lookAt(lastTarget!!)
        }

        if (!attackChronometer.hasElapsed(attackDelay)) return@tickHandler

        runAttack()
        attackChronometer.reset()
    }

    private fun runAttack() {
        val targets = selectTargets()

        if (targets.isEmpty()) {
            lastTarget = null
            releaseBlock()
            return
        }

        // 原版：按血量升序攻击
        targets.sortBy { it.health }

        targets.forEach { target ->
            if (mc.player == null || mc.level == null) return@forEach

            val path = findPath(player.position(), target.position())

            // 沿 A* 路径瞬移到目标（静默发包，客户端位置不变，下一 tick 服务器拉回）
            for (vec in path) {
                sendPacketSilently(
                    ServerboundMovePlayerPacket.Pos(vec.x, vec.y, vec.z, true, false)
                )
            }

            lastTarget = target

            if (autoBlockValue) {
                tryBlock()
            }

            interaction.ensureHasSentCarriedItem()

            // 原版：1.9+ 顺序为攻击包在前，1.8 顺序为挥手在前
            if (attackBeforeSwing) {
                network.send(ServerboundAttackPacket(target.id))
                doSwing()
            } else {
                doSwing()
                network.send(ServerboundAttackPacket(target.id))
            }

            // 沿路径回传
            for (vec in path.asReversed()) {
                sendPacketSilently(
                    ServerboundMovePlayerPacket.Pos(vec.x, vec.y, vec.z, true, false)
                )
            }
        }
    }

    private fun doSwing() {
        when (swingValue) {
            SwingMode.NORMAL -> player.swing(InteractionHand.MAIN_HAND)
            SwingMode.PACKET -> sendPacketSilently(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
            SwingMode.NONE -> {}
        }
    }

    /**
     * 现代版本没有 1.8 剑格挡：副手有盾时举盾模拟格挡
     */
    private fun tryBlock() {
        if (player.isUsingItem) return

        if (player.offhandItem.item is ShieldItem) {
            useItem(InteractionHand.OFF_HAND)
        }

        isBlocking = true
    }

    private fun releaseBlock() {
        if (isBlocking && player.isUsingItem) {
            interaction.releaseUsingItemInTickLoop()
        }
        isBlocking = false
    }

    /*
     * Target selection
     */
    private fun selectTargets(): ArrayList<LivingEntity> {
        val targets = ArrayList<LivingEntity>()

        for (entity in world.entitiesForRendering()) {
            if (entity is LivingEntity && entity.shouldBeAttacked() && !entity.isRemoved &&
                player.distanceTo(entity) <= rangeValue
            ) {
                // 原版：FOV 限制（180 = 无限制）
                if (fovValue < 180f && RotationUtil.crosshairAngleToEntity(entity) > fovValue) {
                    continue
                }

                // 原版：MaxTarget 限制
                if (targets.size >= maxTargetsValue) {
                    break
                }

                targets.add(entity)
            }
        }

        return targets
    }

    private fun lookAt(entity: LivingEntity) {
        val eye = player.eyePosition
        val dx = entity.x - eye.x
        val dy = (entity.y + entity.bbHeight * 0.85) - eye.y
        val dz = entity.z - eye.z
        val dist = sqrt(dx * dx + dz * dz)

        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = Math.toDegrees(-atan2(dy, dist)).toFloat().coerceIn(-90f, 90f)

        runCatching {
            RotationManager.setRotationTarget(
                rotation = Rotation(yaw, pitch),
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.IMPORTANT_FOR_USAGE_1,
                provider = this@ModuleBedrockTPAura,
            )
        }.onFailure {
            // RotationManager 不可用时直接转向，保证攻击角度正确
            player.yRot = yaw
            player.xRot = pitch
        }
    }

    /*
     * Pathfinding（A* 地面寻路，对应原版 MainPathFinder）
     */
    private data class PathNode(val pos: BlockPos, val g: Double, val f: Double, val parent: PathNode?)

    private fun solid(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        if (state.isAir) return false
        return !state.getCollisionShape(world, pos).isEmpty || state.blocksMotion()
    }

    private fun canStandAt(pos: BlockPos): Boolean {
        // 脚 pos、头 pos.above 必须为空，脚下必须实心
        if (solid(pos) || solid(pos.above())) return false
        if (!solid(pos.below())) return false

        val box = AABB(
            pos.x + 0.1, pos.y + 0.01, pos.z + 0.1,
            pos.x + 0.9, pos.y + 1.8, pos.z + 0.9,
        )
        return world.noCollision(player, box)
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double =
        (abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z)).toDouble()

    private fun neighbors(p: BlockPos): List<BlockPos> {
        val list = ArrayList<BlockPos>(12)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                list += p.offset(dx, 0, dz) // 平走
                list += p.offset(dx, 1, dz) // 上台阶
                for (drop in 1..3) {
                    list += p.offset(dx, -drop, dz) // 落下
                }
            }
        }
        return list
    }

    /** A* 地面寻路，返回脚下方块中心路径（含起点） */
    private fun findPath(from: Vec3, to: Vec3): List<Vec3> {
        val start = BlockPos.containing(from.x, floor(from.y + 0.01), from.z)
        val goal = BlockPos.containing(to.x, floor(to.y + 0.01), to.z)

        // 目标脚下不可站时，搜索附近可站格
        var goalStand = goal
        if (!canStandAt(goal)) {
            var found: BlockPos? = null
            outer@ for (r in 0..2) {
                for (dy in -1..2) for (dx in -r..r) for (dz in -r..r) {
                    val p = goal.offset(dx, dy, dz)
                    if (canStandAt(p)) {
                        found = p
                        break@outer
                    }
                }
            }
            goalStand = found ?: return emptyList()
        }

        val open = PriorityQueue<PathNode>(compareBy { it.f })
        val gScore = HashMap<BlockPos, Double>()
        val closed = HashSet<BlockPos>()

        open.add(PathNode(start, 0.0, heuristic(start, goalStand), null))
        gScore[start] = 0.0

        val maxExpansions = rangeValue * 20
        var expanded = 0
        var best: PathNode? = null

        while (open.isNotEmpty() && expanded < maxExpansions) {
            val cur = open.poll() ?: break
            if (cur.pos in closed) continue
            closed += cur.pos
            expanded++

            if (cur.pos == goalStand) {
                best = cur
                break
            }
            if (best == null || cur.pos.distManhattan(goalStand) < best.pos.distManhattan(goalStand)) {
                best = cur
            }

            for (n in neighbors(cur.pos)) {
                if (n in closed) continue
                if (!canStandAt(n)) continue
                // 路径离起点太远则丢弃（曼哈顿距离限制在 Range 内）
                if (abs(n.x - start.x) + abs(n.z - start.z) > rangeValue) continue

                val stepCost = if (n.x != cur.pos.x && n.z != cur.pos.z) 1.41 else 1.0
                val tg = cur.g + stepCost + abs(n.y - cur.pos.y) * 0.35
                if (tg >= (gScore[n] ?: Double.MAX_VALUE)) continue

                gScore[n] = tg
                open.add(PathNode(n, tg, tg + heuristic(n, goalStand), cur))
            }
        }

        val end = best ?: return emptyList()

        val chain = ArrayList<BlockPos>()
        var c: PathNode? = end
        while (c != null) {
            chain += c.pos
            c = c.parent
        }
        chain.reverse()

        return densify(chain.map { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }, 1.0f)
    }

    /** 在相邻方块中心之间按 step 间距插值，让传送更平滑 */
    private fun densify(points: List<Vec3>, step: Float): List<Vec3> {
        if (points.isEmpty()) return emptyList()

        val out = ArrayList<Vec3>()
        out += points.first()

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-3) continue

            val n = ceil(len / step).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toDouble() / n
                out += Vec3(a.x + dx * t, a.y + dy * t, a.z + dz * t)
            }
        }

        return out
    }
}
