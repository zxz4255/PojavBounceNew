/*
 * ModuleTPAura — 移植自 Aspw/NightX 风格 TPAura → LiquidBounce 0.39
 *
 * 原逻辑：寻路点序列发包瞬移贴近 → 攻击 → 原路返回
 * KillAuraAutoBlock：LB 已内置，见 KillAura → AutoBlocking（勿重复注册）
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object ModuleBedrockTPAura : ClientModule(
    "ModuleBedrockTPAura",
    ModuleCategories.COMBAT,
    aliases = listOf("TeleportAura", "TP KillAura"),
) {

    private val cps by int("CPS", 6, 1..12)
    private val maxTargets by int("Max Targets", 1, 1..8)
    private val range by float("Range", 30f, 8f..70f)
    private val fov by float("FOV", 180f, 0f..180f)
    private val onlyPlayers by boolean("Only Players", true)
    private val swingMode by choices("Swing", arrayOf("Normal", "Packet", "None"), "Normal")
    private val attack19 by boolean("1.9+ Attack Order", true)
    private val rotations by boolean("Rotations", true)
    private val pathStep by float("Path Step", 0.35f, 0.15f..1.2f)
    private val maxPathPoints by int("Max Path Points", 80, 16..200)
    private val throughWalls by boolean("Ignore Walls Path", true)
    private val chatOnWorld by boolean("Disable On World Change", true)

    private var lastClickMs = 0L
    private var lastTarget: LivingEntity? = null
    private var busy = false

    private val attackDelayMs: Long
        get() = 1000L / cps.coerceAtLeast(1)

    override fun onDisabled() {
        lastTarget = null
        busy = false
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldChangeEvent> {
        if (chatOnWorld && enabled) {
            enabled = false
            chat("§eTPAura §7因切换世界已关闭")
        }
        lastTarget = null
        busy = false
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val p = player
        if (p == null || world == null) return@handler

        lastTarget?.takeIf { it.isAlive }?.let { t ->
            if (rotations) {
                faceEntity(t)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastClickMs < attackDelayMs) return@handler
        if (busy) return@handler

        busy = true
        lastClickMs = now
        try {
            runAttack()
        } catch (_: Exception) {
        } finally {
            busy = false
        }
    }

    private fun runAttack() {
        val p = player ?: return
        val w = world ?: return

        val candidates = ArrayList<LivingEntity>()
        for (e in w.entitiesForRendering()) {
            if (e !is LivingEntity || e === p) continue
            if (!e.isAlive) continue
            if (onlyPlayers && e !is Player) continue
            if (e is Player && (e.isSpectator || e.isCreative)) continue
            val dist = p.distanceTo(e)
            if (dist > range) continue
            if (fov < 180f && rotationDiff(e) > fov) continue
            candidates.add(e)
            if (candidates.size >= maxTargets) break
        }

        if (candidates.isEmpty()) {
            lastTarget = null
            return
        }

        candidates.sortBy { it.health }
        val start = p.position()

        for (target in candidates) {
            if (player == null || world == null) return

            val end = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
            val path = buildPath(start, end)
            if (path.isEmpty()) continue

            // 去程
            for (vec in path) {
                sendPos(vec.x, vec.y, vec.z, true)
            }

            lastTarget = target
            if (rotations) faceEntity(target)

            // 攻击顺序：1.9+ 常先 swing 再 attack，或相反
            if (attack19) {
                doSwing()
                doAttack(target)
            } else {
                doAttack(target)
                doSwing()
            }

            // 回程
            for (vec in path.asReversed()) {
                sendPos(vec.x, vec.y, vec.z, true)
            }
            sendPos(start.x, start.y, start.z, p.onGround())
        }
    }

    private fun doAttack(entity: Entity) {
        val p = player ?: return
        val conn = network ?: return
        // 与原版 C02 ATTACK 对应
        conn.send(ServerboundInteractPacket.createAttackPacket(entity, p.isShiftKeyDown))
        // 部分反作弊还看客户端攻击冷却
        runCatching { p.attack(entity) }
    }

    private fun doSwing() {
        val p = player ?: return
        when (swingMode.lowercase()) {
            "normal" -> p.swing(InteractionHand.MAIN_HAND)
            "packet" -> network?.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
            else -> {}
        }
    }

    private fun sendPos(x: Double, y: Double, z: Double, onGround: Boolean) {
        network?.send(
            ServerboundMovePlayerPacket.Pos(x, y, z, onGround, false)
        )
    }

    /** 直线插值路径（原 MainPathFinder 的轻量替代；throughWalls 时不检测方块） */
    private fun buildPath(from: Vec3, to: Vec3): List<Vec3> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 0.2) return listOf(to)

        val step = pathStep.toDouble().coerceAtLeast(0.1)
        val n = (dist / step).toInt().coerceIn(1, maxPathPoints)
        val list = ArrayList<Vec3>(n + 1)
        for (i in 1..n) {
            val t = i.toDouble() / n
            val x = from.x + dx * t
            val y = from.y + dy * t
            val z = from.z + dz * t
            if (!throughWalls && isBlocked(x, y, z)) {
                // 被挡住则截断到上一点
                break
            }
            list.add(Vec3(x, y, z))
        }
        if (list.isEmpty() || list.last().distanceTo(to) > 0.5) {
            list.add(to)
        }
        return list
    }

    private fun isBlocked(x: Double, y: Double, z: Double): Boolean {
        val w = world ?: return false
        val pos = net.minecraft.core.BlockPos.containing(x, y, z)
        val st = w.getBlockState(pos)
        if (st.isAir) return false
        return !st.getCollisionShape(w, pos).isEmpty
    }

    private fun faceEntity(entity: LivingEntity) {
        val p = player ?: return
        val eyes = p.eyePosition
        val aim = entity.position().add(0.0, entity.bbHeight * 0.9, 0.0)
        val dx = aim.x - eyes.x
        val dy = aim.y - eyes.y
        val dz = aim.z - eyes.z
        val dist = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = (-Math.toDegrees(atan2(dy, dist))).toFloat()
        RotationManager.setRotationTarget(
            Rotation(yaw, pitch),
            configurable = null,
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this,
        )
    }

    private fun rotationDiff(entity: Entity): Float {
        val p = player ?: return 180f
        val dx = entity.x - p.x
        val dz = entity.z - p.z
        var yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val playerYaw = p.yRot
        var diff = yaw - playerYaw
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return kotlin.math.abs(diff)
    }
}
