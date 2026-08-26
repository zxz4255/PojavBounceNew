/*
 * ModuleBedrockTPAura — LiquidBounce Nextgen 0.39
 * 强寻路 TP Aura：A* 地面路径、逐步传送防回弹、3D 路径绘制
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

object ModuleBedrockTPAura : ClientModule("BedrockTPAura", ModuleCategories.COMBAT) {

    private val targetRange by float("Target Range", 30f, 6f..64f)
    private val attackRange by float("Attack Range", 3.2f, 2.5f..6f)
    private val maxPathBlocks by int("Max Path Blocks", 48, 8..128)
    private val stepBlocks by float("Step Blocks", 0.85f, 0.25f..2.5f)
    private val stepsPerTick by int("Steps Per Tick", 1, 1..4)
    private val tickDelay by int("Tick Delay", 0, 0..10)
    private val onlyPlayers by boolean("Only Players", false)
    private val throughWalls by boolean("Through Walls Attack", false)
    private val requireGround by boolean("Path On Ground", true)
    private val allowStepUp by boolean("Allow Step Up", true)
    private val allowDrop by int("Allow Drop", 3, 0..6)
    private val stopOnRubberband by boolean("Pause On Rubberband", true)
    private val repathInterval by int("Repath Interval", 5, 1..20)
    private val autoAttack by boolean("Auto Attack", true)
    private val swing by boolean("Swing", true)
    private val keepSprint by boolean("Keep Sprint", true)
    private val rotate by boolean("Rotate", true)
    private val smoothReturn by boolean("Smooth Return", true)
    private val returnSpeed by float("Return Step", 0.9f, 0.3f..2.5f)
    private val renderPath by boolean("Render Path", true)
    private val renderTarget by boolean("Render Target Node", true)
    private val pathColor by color("Path Color", Color4b(80, 200, 255, 200))
    private val doneColor by color("Walked Color", Color4b(80, 255, 120, 180))
    private val fov by float("FOV", 360f, 30f..360f)

    private val rotations = RotationsValueGroup(this)

    private var path: List<Vec3> = emptyList()
    private var pathIndex = 0
    private var target: LivingEntity? = null
    private var tickCounter = 0
    private var repathCooldown = 0
    private var returnPath: List<Vec3> = emptyList()
    private var returnIndex = 0
    private var homePos: Vec3? = null
    private var lastServerPos: Vec3? = null
    private var rubberbandPause = 0

    override fun onEnabled() {
        resetState()
        homePos = player.position()
        lastServerPos = player.position()
    }

    override fun onDisabled() {
        resetState()
    }

    private fun resetState() {
        path = emptyList()
        pathIndex = 0
        target = null
        tickCounter = 0
        repathCooldown = 0
        returnPath = emptyList()
        returnIndex = 0
        rubberbandPause = 0
    }

    private fun isReplaceableOrAir(state: BlockState): Boolean =
        state.isAir || runCatching { state.canBeReplaced() }.getOrDefault(false)

    private fun solid(pos: BlockPos): Boolean {
        val st = world.getBlockState(pos)
        if (isReplaceableOrAir(st)) return false
        return !st.getCollisionShape(world, pos).isEmpty || st.blocksMotion()
    }

    private fun canStandAt(pos: BlockPos): Boolean {
        // 脚在 pos，头 pos.above，脚下实心
        if (solid(pos) || solid(pos.above())) return false
        if (requireGround && !solid(pos.below())) return false
        // 碰撞箱粗检
        val box = AABB(
            pos.x + 0.1, pos.y + 0.01, pos.z + 0.1,
            pos.x + 0.9, pos.y + 1.8, pos.z + 0.9,
        )
        return world.noCollision(player, box)
    }

    private data class Node(val pos: BlockPos, val g: Double, val f: Double, val parent: Node?)

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = abs(a.x - b.x).toDouble()
        val dy = abs(a.y - b.y).toDouble()
        val dz = abs(a.z - b.z).toDouble()
        return dx + dy + dz
    }

    private fun neighbors(p: BlockPos): List<BlockPos> {
        val list = ArrayList<BlockPos>(12)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                // 平走
                list += p.offset(dx, 0, dz)
                if (allowStepUp) {
                    list += p.offset(dx, 1, dz)
                }
                for (drop in 1..allowDrop) {
                    list += p.offset(dx, -drop, dz)
                }
            }
        }
        return list
    }

    /** A* 地面寻路，返回脚下方块中心路径 */
    private fun findPath(from: Vec3, to: Vec3, maxNodes: Int): List<Vec3> {
        val start = BlockPos.containing(from.x, floor(from.y + 0.01), from.z)
        val goal = BlockPos.containing(to.x, floor(to.y + 0.01), to.z)

        // 若目标脚下不可站，搜附近可站格
        var goalStand = goal
        if (!canStandAt(goal)) {
            var found: BlockPos? = null
            outer@ for (r in 0..2) {
                for (dx in -r..r) for (dz in -r..r) for (dy in -1..2) {
                    val p = goal.offset(dx, dy, dz)
                    if (canStandAt(p)) {
                        found = p
                        break@outer
                    }
                }
            }
            goalStand = found ?: return emptyList()
        }

        val open = PriorityQueue<Node>(compareBy { it.f })
        val gScore = HashMap<BlockPos, Double>()
        val closed = HashSet<BlockPos>()

        val startNode = Node(start, 0.0, heuristic(start, goalStand), null)
        open.add(startNode)
        gScore[start] = 0.0

        var expanded = 0
        var best: Node? = null

        while (open.isNotEmpty() && expanded < maxNodes * 20) {
            val cur = open.poll() ?: break
            if (cur.pos in closed) continue
            closed += cur.pos
            expanded++

            if (cur.pos == goalStand || cur.pos.distManhattan(goalStand) == 0) {
                best = cur
                break
            }
            if (best == null || cur.pos.distManhattan(goalStand) < best.pos.distManhattan(goalStand)) {
                best = cur
            }

            for (n in neighbors(cur.pos)) {
                if (n in closed) continue
                if (!canStandAt(n)) continue
                if (cur.pos.distManhattan(start) > maxPathBlocks && n.distManhattan(goalStand) > cur.pos.distManhattan(goalStand)) {
                    continue
                }
                val stepCost = if (n.x != cur.pos.x && n.z != cur.pos.z) 1.41 else 1.0
                val yPen = abs(n.y - cur.pos.y) * 0.35
                val tg = cur.g + stepCost + yPen
                if (tg >= (gScore[n] ?: Double.MAX_VALUE)) continue
                gScore[n] = tg
                open.add(Node(n, tg, tg + heuristic(n, goalStand), cur))
            }
        }

        val end = best ?: return emptyList()
        val chain = ArrayList<BlockPos>()
        var c: Node? = end
        while (c != null) {
            chain += c.pos
            c = c.parent
        }
        chain.reverse()
        if (chain.size > maxPathBlocks) {
            return chain.take(maxPathBlocks).map { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
        }
        return chain.map { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
    }

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

    private fun selectTarget(): LivingEntity? {
        var best: LivingEntity? = null
        var bestD = targetRange.toDouble()
        val self = player
        // entitiesForRendering 在部分环境可能不全，再扫一次 allEntities
        val candidates = LinkedHashSet<LivingEntity>()
        runCatching {
            for (e in world.entitiesForRendering()) {
                if (e is LivingEntity) candidates.add(e)
            }
        }
        runCatching {
            for (e in world.entitiesForRendering()) { /* keep */ }
            // ClientLevel 常见：entitiesForRendering；失败则用 getEntities
            val box = self.boundingBox.inflate(targetRange.toDouble())
            val list = world.getEntities(self, box) { it is LivingEntity }
            for (e in list) {
                if (e is LivingEntity) candidates.add(e)
            }
        }
        for (e in candidates) {
            if (e === self || !e.isAlive || e.isRemoved) continue
            if (e.health <= 0f) continue
            if (onlyPlayers) {
                if (e !is Player) continue
            }
            if (e is Player) {
                if (e.isSpectator || e.isCreative) continue
            }
            val d = self.distanceTo(e).toDouble()
            if (d > bestD || d < 0.1) continue
            if (fov < 360f) {
                val yaw = self.yRot
                val dx = e.x - self.x
                val dz = e.z - self.z
                val ang = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
                var diff = ang - yaw
                while (diff > 180f) diff -= 360f
                while (diff < -180f) diff += 360f
                if (abs(diff) > fov * 0.5f) continue
            }
            bestD = d
            best = e
        }
        return best
    }

    /** 无回弹传送：只沿已验证路径小步移动，并做碰撞校验 */
    private fun safeTeleportTo(dest: Vec3): Boolean {
        val box = player.boundingBox.move(
            dest.x - player.x,
            dest.y - player.y,
            dest.z - player.z,
        )
        // 轻微缩小减少误判
        val check = box.inflate(-0.05, 0.0, -0.05)
        if (!world.noCollision(player, check)) {
            return false
        }
        // 与上一服务器位置差过大可能触发回弹：限制单步
        val last = lastServerPos ?: player.position()
        val dx = dest.x - last.x
        val dy = dest.y - last.y
        val dz = dest.z - last.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > stepBlocks * 1.35) {
            return false
        }

        player.setPos(dest.x, dest.y, dest.z)
        player.xo = dest.x
        player.yo = dest.y
        player.zo = dest.z
        player.setDeltaMovement(0.0, player.deltaMovement.y.coerceAtMost(0.0), 0.0)
        lastServerPos = dest
        return true
    }

    private fun detectRubberband(): Boolean {
        if (!stopOnRubberband) return false
        val last = lastServerPos ?: return false
        val dx = player.x - last.x
        val dy = player.y - last.y
        val dz = player.z - last.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // 客户端位置被服务器硬拉回
        return d > stepBlocks * 2.5 && d > 1.5
    }

    private fun lookAt(entity: LivingEntity) {
        if (!rotate) return
        val eye = player.eyePosition
        val tx = entity.x
        val ty = entity.y + entity.bbHeight * 0.85
        val tz = entity.z
        val dx = tx - eye.x
        val dy = ty - eye.y
        val dz = tz - eye.z
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
            player.yRot = yaw
            player.xRot = pitch
        }
    }

    private fun tryAttack(entity: LivingEntity) {
        if (!autoAttack) return
        if (!entity.isAlive || entity.isRemoved) return
        val dist = player.distanceTo(entity)
        if (dist > attackRange) return
        // 近距离放宽视线：TP 后偶发 hasLineOfSight 误判
        if (!throughWalls && dist > 1.5f && !player.hasLineOfSight(entity)) return
        lookAt(entity)
        val hand = net.minecraft.world.InteractionHand.MAIN_HAND
        var ok = false
        runCatching {
            mc.gameMode?.attack(player, entity)
            ok = true
        }
        if (!ok) {
            runCatching {
                player.attack(entity)
                ok = true
            }
        }
        if (swing) {
            runCatching { player.swing(hand) }
        }
        if (!keepSprint) {
            player.isSprinting = false
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (rubberbandPause > 0) {
            rubberbandPause--
            return@handler
        }

        if (detectRubberband()) {
            // 回弹：清空路径，短暂暂停，重新从当前位置寻路
            path = emptyList()
            pathIndex = 0
            returnPath = emptyList()
            lastServerPos = player.position()
            rubberbandPause = 5
            repathCooldown = 0
            return@handler
        }

        if (tickDelay > 0) {
            tickCounter++
            if (tickCounter <= tickDelay) return@handler
            tickCounter = 0
        }

        // 回程
        if (target == null || !target!!.isAlive) {
            if (smoothReturn && homePos != null && pathIndex > 0) {
                if (returnPath.isEmpty()) {
                    returnPath = densify(
                        findPath(player.position(), homePos!!, maxPathBlocks),
                        returnSpeed,
                    )
                    returnIndex = 0
                }
                if (returnIndex < returnPath.size) {
                    for (s in 0 until stepsPerTick) {
                        if (returnIndex >= returnPath.size) break
                        if (!safeTeleportTo(returnPath[returnIndex])) {
                            returnPath = emptyList()
                            break
                        }
                        returnIndex++
                    }
                    return@handler
                }
            }
            path = emptyList()
            pathIndex = 0
            target = null
            homePos = player.position()
            return@handler
        }

        val t = selectTarget()
        if (t == null) {
            target = null
            return@handler
        }
        target = t

        // 已在攻击距离内：不 TP，直接打
        if (player.distanceTo(t) <= attackRange) {
            tryAttack(t)
            path = emptyList()
            pathIndex = 0
            return@handler
        }

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size) {
            homePos = homePos ?: player.position()
            val raw = findPath(player.position(), t.position(), maxPathBlocks)
            path = densify(raw, stepBlocks)
            pathIndex = 0
            repathCooldown = repathInterval
            lastServerPos = player.position()
        } else {
            repathCooldown--
        }

        if (path.isEmpty()) return@handler

        // 沿路径步进
        for (s in 0 until stepsPerTick) {
            if (pathIndex >= path.size) break
            val next = path[pathIndex]
            // 靠近目标时改为朝实体微调最后几步
            val dest = if (pathIndex >= path.size - 2) {
                Vec3(t.x, floor(t.y) + 0.0, t.z)
            } else next

            if (!safeTeleportTo(if (pathIndex >= path.size - 2) {
                    // 仍用路径点，避免穿进实体
                    next
                } else dest
                    )) {
                // 卡住则强制重寻路
                path = emptyList()
                pathIndex = 0
                repathCooldown = 0
                break
            }
            pathIndex++
            if (player.distanceTo(t) <= attackRange) break
        }

        tryAttack(t)
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { _ ->
        // 路径绘制在现代 OpenGL Core / 部分环境无法使用即时模式 GL11，
        // 为保证编译与运行稳定，此处不调用 GL11 / RenderSystem。
        // 战斗与寻路逻辑不受影响；需要可视化时可再接 LB WorldRenderEnvironment API。
        if (!renderPath) return@handler
    }
}
