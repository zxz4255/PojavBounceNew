/*
 * ModuleAutoRod — 偏合法手感版
 * 可见切槽、有限转头、随机延迟、不静默发包、不强制完美瞄准
 * 仍可能被严格 AC 检测；无法保证不踢，只降低「一眼机」特征
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

object ModuleAutoRodPro : ClientModule(
    "AutoRodPro",
    ModuleCategories.COMBAT,
    aliases = listOf("LegitRod", "AutoFishRod"),
) {

    private val range by float("Range", 3.8f, 2f..5f)
    private val fov by float("FOV", 60f, 20f..120f)
    private val maxTurn by float("Max Turn Deg", 18f, 5f..45f)
    private val aimSmooth by float("Aim Smooth", 0.35f, 0.1f..1f)
    private val switchDelay by intRange("Switch Delay", 1..3, 0..8)
    private val throwDelay by intRange("Throw Delay", 1..3, 0..8)
    private val cooldown by intRange("Cooldown", 14..22, 8..40)
    private val pullBack by boolean("Pull Back", true)
    private val pullDelay by intRange("Pull Delay", 6..10, 3..20)
    private val switchBack by boolean("Switch Back", true)
    private val onlyGround by boolean("Only Ground", false)
    private val requireClick by boolean("Require Hold Attack", false)
    private val aimNoise by float("Aim Noise", 1.2f, 0f..4f)

    private enum class Phase { IDLE, SWITCHING, WAIT_THROW, THROWN, PULL, RESTORE }

    private var phase = Phase.IDLE
    private var phaseWait = 0
    private var prevSlot = -1
    private var rodSlot = -1
    private var cooldownLeft = 0
    private var targetId = -1

    private fun rand(r: IntRange): Int =
        if (r.first >= r.last) r.first else Random.nextInt(r.first, r.last + 1)

    private fun selectedSlot(): Int {
        val inv = mc.player?.inventory ?: return 0
        return runCatching {
            inv.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (
                    it.name == "getSelected" || it.name == "getSelectedSlot" || it.name == "selectedSlot"
                    )
            }?.invoke(inv) as? Int
        }.getOrNull()
            ?: runCatching {
                inv.javaClass.getDeclaredField("selected").also { it.isAccessible = true }.getInt(inv)
            }.getOrNull()
            ?: runCatching {
                inv.javaClass.getDeclaredField("selectedSlot").also { it.isAccessible = true }.getInt(inv)
            }.getOrNull()
            ?: 0
    }

    private fun setSlot(slot: Int) {
        val inv = mc.player?.inventory ?: return
        val s = slot.coerceIn(0, 8)
        runCatching {
            inv.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && (it.name == "setSelectedSlot" || it.name == "setSelected")
            }?.invoke(inv, s)
        }.onFailure {
            runCatching {
                inv.javaClass.getDeclaredField("selected").also { it.isAccessible = true }.setInt(inv, s)
            }
            runCatching {
                inv.javaClass.getDeclaredField("selectedSlot").also { it.isAccessible = true }.setInt(inv, s)
            }
        }
        // 不额外狂发 SetCarried 包，交给客户端同步
    }

    private fun findRod(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in 0..8) {
            val st = inv.getItem(i)
            if (!st.isEmpty && st.item == Items.FISHING_ROD) return i
        }
        return -1
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun inFov(target: Player, limit: Float): Boolean {
        val self = mc.player ?: return false
        val dx = target.x - self.x
        val dz = target.z - self.z
        val yawTo = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        return kotlin.math.abs(angleDiff(self.yRot, yawTo)) <= limit / 2f
    }

    private fun findTarget(): Player? {
        val self = mc.player ?: return null
        val world = mc.level ?: return null
        var best: Player? = null
        var bestD = range.toDouble()
        runCatching {
            for (p in world.players()) {
                if (p === self || !p.isAlive || p.isDeadOrDying) continue
                val d = self.distanceTo(p).toDouble()
                if (d > bestD) continue
                if (!inFov(p, fov)) continue
                // 简单视线
                if (!runCatching { self.hasLineOfSight(p) }.getOrDefault(true)) continue
                bestD = d
                best = p
            }
        }
        return best
    }

    private fun softAim(target: Player) {
        val self = mc.player ?: return
        val eye = self.eyePosition
        // 瞄身体中上部，加噪声，不锁死头
        val tx = target.x + Random.nextDouble(-0.05, 0.05)
        val ty = target.y + target.bbHeight * (0.55 + Random.nextDouble(-0.05, 0.08))
        val tz = target.z + Random.nextDouble(-0.05, 0.05)
        val dx = tx - eye.x
        val dy = ty - eye.y
        val dz = tz - eye.z
        val dist = sqrt(dx * dx + dz * dz)
        val targetYaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val targetPitch = Math.toDegrees(-atan2(dy, dist)).toFloat()
            .coerceIn(-90f, 90f)

        var dyaw = angleDiff(self.yRot, targetYaw)
        var dpitch = targetPitch - self.xRot
        // 限制单 tick 转角
        val max = maxTurn
        dyaw = dyaw.coerceIn(-max, max)
        dpitch = dpitch.coerceIn(-max, max)
        // 平滑
        val s = aimSmooth.coerceIn(0.1f, 1f)
        dyaw *= s
        dpitch *= s
        // 噪声
        if (aimNoise > 0f) {
            dyaw += Random.nextFloat() * aimNoise - aimNoise / 2f
            dpitch += Random.nextFloat() * aimNoise * 0.5f - aimNoise * 0.25f
        }
        self.yRot += dyaw
        self.xRot = (self.xRot + dpitch).coerceIn(-90f, 90f)
        self.yRotO = self.yRot
        self.xRotO = self.xRot
    }

    private fun useRod() {
        val p = mc.player ?: return
        // 只用客户端交互，不额外堆 UseItem 包
        runCatching { mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND) }
    }

    private fun reset() {
        phase = Phase.IDLE
        phaseWait = 0
        targetId = -1
        if (switchBack && prevSlot in 0..8) setSlot(prevSlot)
        prevSlot = -1
        rodSlot = -1
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler
        if (player.isDeadOrDying) {
            reset()
            return@handler
        }
        if (onlyGround && !player.onGround()) return@handler
        if (requireClick && !runCatching { mc.options.keyAttack.isDown }.getOrDefault(false)) {
            if (phase != Phase.IDLE) reset()
            return@handler
        }

        if (cooldownLeft > 0) {
            cooldownLeft--
            return@handler
        }

        when (phase) {
            Phase.IDLE -> {
                val rod = findRod()
                if (rod < 0) return@handler
                val target = findTarget() ?: return@handler
                // 需要已经大致对着目标（FOV 内），避免瞬转
                if (!inFov(target, fov)) return@handler
                targetId = target.id
                prevSlot = selectedSlot()
                rodSlot = rod
                softAim(target)
                phase = Phase.SWITCHING
                phaseWait = rand(switchDelay)
            }

            Phase.SWITCHING -> {
                val t = mc.level?.getEntity(targetId) as? Player
                if (t == null || !t.isAlive) {
                    reset()
                    return@handler
                }
                softAim(t)
                if (phaseWait > 0) {
                    phaseWait--
                    return@handler
                }
                if (rodSlot in 0..8) setSlot(rodSlot)
                phase = Phase.WAIT_THROW
                phaseWait = rand(throwDelay)
            }

            Phase.WAIT_THROW -> {
                val t = mc.level?.getEntity(targetId) as? Player
                if (t == null || !t.isAlive) {
                    reset()
                    return@handler
                }
                softAim(t)
                if (phaseWait > 0) {
                    phaseWait--
                    return@handler
                }
                // 确认手里是竿
                if (player.mainHandItem.item != Items.FISHING_ROD && rodSlot in 0..8) {
                    setSlot(rodSlot)
                    phaseWait = 1
                    return@handler
                }
                useRod()
                phase = if (pullBack) Phase.THROWN else Phase.RESTORE
                phaseWait = if (pullBack) rand(pullDelay) else rand(1..2)
            }

            Phase.THROWN -> {
                if (phaseWait > 0) {
                    phaseWait--
                    return@handler
                }
                // 收杆：再 use 一次（需仍是竿）
                if (player.mainHandItem.item == Items.FISHING_ROD) {
                    useRod()
                }
                phase = Phase.RESTORE
                phaseWait = rand(1..2)
            }

            Phase.RESTORE -> {
                if (phaseWait > 0) {
                    phaseWait--
                    return@handler
                }
                if (switchBack && prevSlot in 0..8) setSlot(prevSlot)
                prevSlot = -1
                rodSlot = -1
                targetId = -1
                phase = Phase.IDLE
                cooldownLeft = rand(cooldown)
            }

            Phase.PULL -> {
                phase = Phase.IDLE
            }
        }
    }

    override fun onDisabled() {
        reset()
        cooldownLeft = 0
    }
}
