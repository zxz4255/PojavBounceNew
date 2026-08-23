/*
 * ModuleAutoRod — 靠近玩家时自动切鱼竿并右键抛出
 * LiquidBounce Nextgen 0.39
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

object ModuleAutoRodPro : ClientModule(
    "AutoRodPro",
    ModuleCategories.COMBAT,
    aliases = listOf("AutoFishRod", "RodAura"),
) {

    private enum class AimMode(override val tag: String) : Tagged {
        NONE("None"),
        SILENT("Silent Packet"),
        CLIENT("Client Look"),
    }

    private enum class SwitchMode(override val tag: String) : Tagged {
        NORMAL("Normal"),
        SILENT("Silent"),
    }

    private val range by float("Range", 4.5f, 2f..8f)
    private val fov by float("FOV", 180f, 30f..360f)
    private val aimMode by enumChoice("Aim", AimMode.CLIENT)
    private val switchMode by enumChoice("Switch", SwitchMode.NORMAL)
    private val switchBack by boolean("Switch Back", true)
    private val throwCooldown by int("Throw Cooldown", 12, 0..40)
    private val holdTicks by int("Hold Ticks", 2, 0..10)
    private val pullBack by boolean("Pull Back", true)
    private val pullDelay by int("Pull Delay", 8, 2..30)
    private val onlyGround by boolean("Only Ground", false)
    private val throughWalls by boolean("Through Walls", false)
    private val ignoreSelf by boolean("Ignore Self", true)
    private val preferClosest by boolean("Prefer Closest", true)
    private val minHurtTime by int("Target HurtTime Max", 20, 0..20)

    private var prevSlot = -1
    private var rodSlot = -1
    private var cooldown = 0
    private var phase = Phase.IDLE
    private var phaseTicks = 0
    private var sequence = 0
    private var serverOnRod = false

    private enum class Phase { IDLE, THROWN, PULL }

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

    private fun setLocalSlot(slot: Int) {
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
    }

    private fun sendSlot(slot: Int) {
        runCatching {
            mc.connection?.send(ServerboundSetCarriedItemPacket(slot.coerceIn(0, 8)))
        }
    }

    private fun switchToRod(slot: Int) {
        if (prevSlot < 0) prevSlot = selectedSlot()
        rodSlot = slot
        when (switchMode) {
            SwitchMode.NORMAL -> {
                setLocalSlot(slot)
                sendSlot(slot)
                serverOnRod = true
            }
            SwitchMode.SILENT -> {
                sendSlot(slot)
                serverOnRod = true
            }
        }
    }

    private fun restoreSlot() {
        if (!switchBack || prevSlot !in 0..8) {
            prevSlot = -1
            rodSlot = -1
            serverOnRod = false
            return
        }
        when (switchMode) {
            SwitchMode.NORMAL -> {
                setLocalSlot(prevSlot)
                sendSlot(prevSlot)
            }
            SwitchMode.SILENT -> sendSlot(prevSlot)
        }
        prevSlot = -1
        rodSlot = -1
        serverOnRod = false
    }

    private fun findRod(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in 0..8) {
            val st = inv.getItem(i)
            if (!st.isEmpty && st.item == Items.FISHING_ROD) return i
        }
        return -1
    }

    private fun sendUse() {
        val p = mc.player ?: return
        val hand = InteractionHand.MAIN_HAND
        var sent = false
        runCatching {
            for (c in ServerboundUseItemPacket::class.java.constructors) {
                try {
                    val pkt = when (c.parameterCount) {
                        1 -> c.newInstance(hand)
                        2 -> c.newInstance(hand, sequence++)
                        4 -> c.newInstance(hand, sequence++, p.yRot, p.xRot)
                        else -> continue
                    }
                    mc.connection?.send(pkt as net.minecraft.network.protocol.Packet<*>)
                    sent = true
                    break
                } catch (_: Throwable) {
                }
            }
        }
        if (!sent) {
            runCatching { mc.gameMode?.useItem(p, hand) }
        }
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun canSee(target: Player): Boolean {
        if (throughWalls) return true
        val self = mc.player ?: return false
        return runCatching {
            self.hasLineOfSight(target)
        }.getOrDefault(true)
    }

    private fun inFov(target: Player): Boolean {
        if (fov >= 360f) return true
        val self = mc.player ?: return false
        val dx = target.x - self.x
        val dz = target.z - self.z
        val yawTo = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        return kotlin.math.abs(angleDiff(self.yRot, yawTo)) <= fov / 2f
    }

    private fun findTarget(): Player? {
        val self = mc.player ?: return null
        val world = mc.level ?: return null
        val r = range.toDouble()
        var best: Player? = null
        var bestDist = Double.MAX_VALUE
        runCatching {
            for (e in world.players()) {
                if (e !is Player) continue
                if (ignoreSelf && e === self) continue
                if (!e.isAlive || e.isDeadOrDying) continue
                if (e.hurtTime > minHurtTime) continue
                val d = self.distanceTo(e).toDouble()
                if (d > r) continue
                if (!inFov(e)) continue
                if (!canSee(e)) continue
                if (preferClosest) {
                    if (d < bestDist) {
                        bestDist = d
                        best = e
                    }
                } else {
                    return e
                }
            }
        }
        return best
    }

    private fun aimAt(target: Player) {
        val self = mc.player ?: return
        val eye = self.eyePosition
        val tx = target.x
        val ty = target.y + target.bbHeight * 0.6
        val tz = target.z
        val dx = tx - eye.x
        val dy = ty - eye.y
        val dz = tz - eye.z
        val dist = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = Math.toDegrees(-atan2(dy, dist)).toFloat()
        when (aimMode) {
            AimMode.NONE -> {}
            AimMode.CLIENT -> {
                self.yRot = yaw
                self.xRot = pitch.coerceIn(-90f, 90f)
                self.yRotO = self.yRot
                self.xRotO = self.xRot
            }
            AimMode.SILENT -> {
                // 仅发包视角（若环境支持）；同时轻微改客户端以保证 use 方向
                self.yRot = yaw
                self.xRot = pitch.coerceIn(-90f, 90f)
                runCatching {
                    val conn = mc.connection ?: return@runCatching
                    for (c in Class.forName(
                        "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket\$Rot",
                    ).constructors) {
                        try {
                            val pkt = when (c.parameterCount) {
                                3 -> c.newInstance(yaw, pitch, self.onGround())
                                4 -> c.newInstance(yaw, pitch, self.onGround(), true)
                                else -> continue
                            }
                            conn.send(pkt as net.minecraft.network.protocol.Packet<*>)
                            break
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    private fun throwRod(target: Player) {
        val slot = findRod()
        if (slot < 0) return
        aimAt(target)
        switchToRod(slot)
        sendUse()
        phase = Phase.THROWN
        phaseTicks = 0
        cooldown = throwCooldown
    }

    private fun pullRod() {
        // 再右键收杆
        if (serverOnRod || findRod() == selectedSlot() || switchMode == SwitchMode.SILENT) {
            if (rodSlot >= 0 && switchMode == SwitchMode.SILENT) sendSlot(rodSlot)
            sendUse()
        }
        phaseTicks = 0
        phase = Phase.IDLE
        restoreSlot()
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler
        if (player.isDeadOrDying) {
            phase = Phase.IDLE
            restoreSlot()
            return@handler
        }
        if (onlyGround && !player.onGround()) return@handler

        if (cooldown > 0) cooldown--

        when (phase) {
            Phase.THROWN -> {
                phaseTicks++
                if (pullBack && phaseTicks >= pullDelay) {
                    pullRod()
                } else if (!pullBack && phaseTicks >= holdTicks) {
                    phase = Phase.IDLE
                    restoreSlot()
                }
            }
            Phase.PULL -> {
                phase = Phase.IDLE
                restoreSlot()
            }
            Phase.IDLE -> {
                if (cooldown > 0) return@handler
                // 已在用鱼竿/物品时跳过
                if (player.isUsingItem) return@handler
                val target = findTarget() ?: return@handler
                if (findRod() < 0) return@handler
                throwRod(target)
            }
        }
    }

    override fun onDisabled() {
        phase = Phase.IDLE
        cooldown = 0
        restoreSlot()
    }
}
