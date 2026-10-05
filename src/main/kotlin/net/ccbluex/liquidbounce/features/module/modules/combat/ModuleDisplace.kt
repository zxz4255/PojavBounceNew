/*
 * ModuleDisplace — 还原 Raven BS Displace
 * 攻击时交替偏移 yaw，改变击退方向；可选找虚空侧、延迟窗口、移动补偿
 * LiquidBounce Nextgen 0.39
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

object ModuleDisplace : ClientModule(
    "Displace",
    ModuleCategories.COMBAT,
    aliases = listOf("KBDisplace", "WTapDisplace"),
) {

    private enum class Dir(override val tag: String) : Tagged {
        LEFT("Left"),
        RIGHT("Right"),
    }

    private val yawOffset by float("Yaw Offset", 90f, 0f..180f)
    private val delayMs by int("Delay Ms", 0, 0..500)
    private val direction by enumChoice("Direction", Dir.LEFT)
    private val findVoid by boolean("Find Void", false)
    private val blink by boolean("Blink C03", false)
    private val requireAttack by boolean("Require Attack", true)
    private val range by float("Target Range", 9f, 3f..12f)
    private val hasKnockback by boolean("Require Knockback", false)
    private val onlyWhenMoving by boolean("Only When Moving", true)
    private val movementFix by boolean("Movement Fix", true)
    private val compensateStrafe by boolean("Compensate Strafe", true)

    private val rotations = RotationsValueGroup(this)

    private const val WINDOW_TICKS = 10

    private var displaceThisTick = false
    private var active = false
    private var hasKB = false
    private var compensateNextTick = false
    private var displaceLeft = false
    private var wasDisplacingLastTick = false
    private var releaseBlinkNext = false
    private var tickCounter = 0
    private val targetWindowStart = HashMap<Int, Int>()
    private val blinkQueue = ArrayDeque<ServerboundMovePlayerPacket>()
    private var blinking = false

    private fun msToTicks(ms: Int): Int =
        if (ms <= 0) 0 else (ms + 49) / 50

    private fun anyMovementKey(): Boolean {
        val opt = mc.options
        return opt.keyUp.isDown || opt.keyDown.isDown || opt.keyLeft.isDown || opt.keyRight.isDown
    }

    private fun isAttacking(): Boolean {
        if (!requireAttack) return true
        return runCatching { mc.options.keyAttack.isDown }.getOrDefault(false)
    }

    private fun hasKbEnchant(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return runCatching {
            // 1.21 组件 / 旧 enchant 表兼容
            val tag = stack.toString().lowercase()
            if (tag.contains("knockback") || tag.contains("punch")) return@runCatching true
            stack.enchantments.entrySet().any { e ->
                e.key.toString().lowercase().contains("knockback") ||
                    e.key.toString().lowercase().contains("punch")
            }
        }.getOrDefault(false)
    }

    private fun findClosestTarget(maxRange: Double): Player? {
        val self = mc.player ?: return null
        val world = mc.level ?: return null
        var best: Player? = null
        var bestD = maxRange
        runCatching {
            for (p in world.players()) {
                if (p === self || !p.isAlive || p.isDeadOrDying) continue
                val d = self.distanceTo(p).toDouble()
                if (d < bestD) {
                    bestD = d
                    best = p
                }
            }
        }
        return best
    }

    /** 检测目标左右侧虚空，决定击退方向 */
    private fun tryFindVoidDirection(target: Player): Boolean {
        val self = mc.player ?: return false
        val world = mc.level ?: return false
        var dx = target.x - self.x
        var dz = target.z - self.z
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < 0.001) return false
        dx /= dist
        dz /= dist
        val rightX = -dz
        val rightZ = dx
        val eyeY = target.y + target.eyeHeight

        var leftVoid = 0
        var rightVoid = 0
        for (i in 1..12) {
            val off = i * 0.5
            val rx = target.x + rightX * off
            val rz = target.z + rightZ * off
            val lx = target.x - rightX * off
            val lz = target.z - rightZ * off
            val rightHit = world.clip(
                ClipContext(
                    Vec3(rx, eyeY, rz),
                    Vec3(rx, eyeY - 10.0, rz),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    target,
                ),
            )
            val leftHit = world.clip(
                ClipContext(
                    Vec3(lx, eyeY, lz),
                    Vec3(lx, eyeY - 10.0, lz),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    target,
                ),
            )
            if (rightHit.type == HitResult.Type.MISS) rightVoid++
            if (leftHit.type == HitResult.Type.MISS) leftVoid++
        }
        if (leftVoid == 0 && rightVoid == 0) return false
        if (leftVoid != rightVoid) {
            displaceLeft = leftVoid > rightVoid
        }
        return true
    }

    private fun pruneTargets() {
        val world = mc.level
        if (world == null) {
            targetWindowStart.clear()
            return
        }
        val it = targetWindowStart.entries.iterator()
        while (it.hasNext()) {
            val (id, _) = it.next()
            val e = world.getEntity(id)
            if (e !is Player || !e.isAlive || e.isDeadOrDying) it.remove()
        }
    }

    private fun shouldDisplaceInWindow(target: Player?, currentTick: Int): Boolean {
        if (target == null) return true
        val id = target.id
        val start = targetWindowStart[id]
        if (start == null || currentTick - start >= WINDOW_TICKS) {
            targetWindowStart[id] = currentTick
            return true
        }
        val delayTicks = msToTicks(delayMs)
        if (delayTicks <= 0) return true
        return currentTick - start >= delayTicks
    }

    private fun releaseBlink() {
        blinking = false
        val conn = mc.connection
        while (blinkQueue.isNotEmpty()) {
            val p = blinkQueue.removeFirst()
            runCatching { conn?.send(p) }
        }
    }

    private fun applyStrafeCompensation() {
        if (!compensateStrafe) return
        val p = mc.player ?: return
        // 近似 PostInput：下一 tick 侧向补偿
        runCatching {
            if (displaceLeft) p.xxa = -1f else p.xxa = 1f
        }
    }

    private fun applyForwardBoost() {
        val p = mc.player ?: return
        runCatching { p.zza = 1f }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (!blink || !active || !displaceThisTick || releaseBlinkNext) return@handler
        val packet = event.packet
        if (packet is ServerboundMovePlayerPacket) {
            if (blinking) return@handler
            blinkQueue.addLast(packet)
            runCatching { event.cancelEvent() }
            blinking = true
            releaseBlinkNext = true
        }
    }

    @Suppress("unused")
    private val gameTickHandler = handler<GameTickEvent> {
        if (releaseBlinkNext) {
            releaseBlink()
            releaseBlinkNext = false
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler
        if (player.isDeadOrDying) {
            active = false
            displaceThisTick = false
            return@handler
        }

        tickCounter++
        val currentTick = tickCounter
        pruneTargets()

        // 物品/击退条件
        if (hasKnockback && !hasKbEnchant(player.mainHandItem)) {
            active = false
            displaceThisTick = false
            compensateNextTick = false
            wasDisplacingLastTick = false
            return@handler
        }

        if (requireAttack && !isAttacking()) {
            active = false
            displaceThisTick = false
            compensateNextTick = false
            wasDisplacingLastTick = false
            return@handler
        }

        val target = findClosestTarget(range.toDouble())
        val moving = anyMovementKey()
        val kb = hasKbEnchant(player.mainHandItem)
        active = target != null && (kb || !onlyWhenMoving || moving)
        if (!active) {
            displaceThisTick = false
            compensateNextTick = false
            wasDisplacingLastTick = false
            return@handler
        }

        if (!findVoid || !tryFindVoidDirection(target!!)) {
            displaceLeft = direction == Dir.LEFT
        }

        hasKB = kb
        displaceThisTick = !displaceThisTick
        if (displaceThisTick && !shouldDisplaceInWindow(target, currentTick)) {
            displaceThisTick = false
            compensateNextTick = false
            wasDisplacingLastTick = false
            return@handler
        }

        // 原版：结束 displace 的 tick 补一次攻击键
        if (!displaceThisTick && wasDisplacingLastTick) {
            runCatching {
                // 轻点攻击由玩家自行；此处不强制 KeyBinding
            }
        }
        wasDisplacingLastTick = displaceThisTick

        // 移动补偿
        if (compensateNextTick && !displaceThisTick) {
            compensateNextTick = false
            applyStrafeCompensation()
        } else if (displaceThisTick && !hasKB && anyMovementKey()) {
            applyForwardBoost()
            compensateNextTick = true
        }

        if (!displaceThisTick) return@handler

        // 核心：偏移 yaw
        val baseYaw = player.yRot
        val offset = yawOffset
        val newYaw = if (displaceLeft) baseYaw - offset else baseYaw + offset

        runCatching {
            RotationManager.setRotationTarget(
                rotation = Rotation(newYaw, player.xRot),
                considerInventory = false,
                valueGroup = rotations,
                priority = Priority.NORMAL,
                provider = this@ModuleDisplace,
            )
        }.onFailure {
            // 回退：直接改客户端视角
            player.yRot = newYaw
            player.yRotO = newYaw
        }

        // Movement fix 近似：保持前进相对新 yaw
        if (movementFix && anyMovementKey()) {
            applyForwardBoost()
        }
    }

    override fun onEnabled() {
        displaceThisTick = false
        active = false
        hasKB = false
        compensateNextTick = false
        wasDisplacingLastTick = false
        releaseBlinkNext = false
        tickCounter = 0
        targetWindowStart.clear()
        releaseBlink()
    }

    override fun onDisabled() {
        active = false
        compensateNextTick = false
        wasDisplacingLastTick = false
        releaseBlinkNext = false
        targetWindowStart.clear()
        releaseBlink()
    }
}
