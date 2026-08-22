/*
 * ModuleAutoEatGapple — 移植自 EzGapple.js (1.8.9 Script)
 * Stuck 取消移动 + 拦截 C03 计数 → 切槽 UseItem → release 视角包 → 切回
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleAutoEatGapple : ClientModule(
    "AutoEatGapple",
    ModuleCategories.PLAYER,
    aliases = listOf("EzGapple", "AutoGapple"),
) {

    private val c03Count by int("C03 Packet Player", 32, 32..40)
    private val triggerHealth by int("Health", 19, 1..20)
    private val autoEat by boolean("Auto Gapple", true)
    private val preferEnchanted by boolean("Prefer Enchanted", false)
    private val blockFix by boolean("Block Fix", true)
    private val showProgress by boolean("Show Progress", true)
    private val showTag by boolean("Show Tick Tag", true)

    private val barWidth by float("Bar Width", 140f, 60f..300f)
    private val barHeight by float("Bar Height", 7f, 3f..20f)
    private val barRadius by float("Bar Radius", 3f, 0f..10f)
    private val progressStart by color("Progress Start", Color4b(76, 157, 240, 255))
    private val progressEnd by color("Progress End", Color4b(53, 200, 167, 255))
    private val barBg by color("Bar Background", Color4b(0, 0, 0, 128))

    private var ticks = 0
    private var pauseTicks = 0
    private var cancelMove = false
    private var shouldEat = false
    private var isEating = false
    private var savedYaw = 0f
    private var savedPitch = 0f
    private var motionX = 0.0
    private var motionY = 0.0
    private var motionZ = 0.0
    private var savedMotion = false
    private var sequence = 0
    private var anim = 0f
    private var lastNs = 0L

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

    private fun setSelectedSlot(slot: Int) {
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

    private fun getGApple(): Int {
        val inv = mc.player?.inventory ?: return -1
        var golden = -1
        var ench = -1
        for (i in 0..8) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            when (stack.item) {
                Items.ENCHANTED_GOLDEN_APPLE -> ench = i
                Items.GOLDEN_APPLE -> golden = i
            }
        }
        return if (preferEnchanted) {
            if (ench != -1) ench else golden
        } else {
            if (golden != -1) golden else ench
        }
    }

    private fun send(packet: Packet<*>) {
        runCatching { mc.connection?.send(packet) }
    }

    private fun sendUse() {
        val p = mc.player ?: return
        val hand = InteractionHand.MAIN_HAND
        runCatching {
            for (c in ServerboundUseItemPacket::class.java.constructors) {
                try {
                    val pkt = when (c.parameterCount) {
                        1 -> c.newInstance(hand)
                        2 -> c.newInstance(hand, sequence++)
                        4 -> c.newInstance(hand, sequence++, p.yRot, p.xRot)
                        else -> continue
                    }
                    send(pkt as Packet<*>)
                    return
                } catch (_: Throwable) {
                }
            }
            mc.gameMode?.useItem(p, hand)
        }
    }

    private fun stuck() {
        val p = mc.player ?: return
        if (!savedMotion) {
            motionX = p.deltaMovement.x
            motionY = p.deltaMovement.y
            motionZ = p.deltaMovement.z
            savedMotion = true
        }
        cancelMove = true
        runCatching {
            p.setDeltaMovement(0.0, p.deltaMovement.y, 0.0)
            p.xxa = 0f
            p.zza = 0f
        }
    }

    private fun stopStuck() {
        cancelMove = false
        val p = mc.player
        if (savedMotion && p != null) {
            runCatching { p.setDeltaMovement(motionX, motionY, motionZ) }
            savedMotion = false
        }
    }

    /** 对应 JS release(): C05 look + (ticks-1) 个 C03 */
    private fun release() {
        val p = mc.player ?: return
        runCatching {
            for (c in ServerboundMovePlayerPacket.Rot::class.java.constructors) {
                try {
                    val pkt = when (c.parameterCount) {
                        3 -> c.newInstance(savedYaw, savedPitch, p.onGround())
                        4 -> c.newInstance(savedYaw, savedPitch, p.onGround(), true)
                        else -> continue
                    }
                    send(pkt as Packet<*>)
                    break
                } catch (_: Throwable) {
                }
            }
        }
        val n = (ticks - 1).coerceAtLeast(0).coerceAtMost(40)
        repeat(n) {
            runCatching {
                for (c in ServerboundMovePlayerPacket.StatusOnly::class.java.constructors) {
                    try {
                        val pkt = when (c.parameterCount) {
                            1 -> c.newInstance(p.onGround())
                            2 -> c.newInstance(p.onGround(), true)
                            else -> continue
                        }
                        send(pkt as Packet<*>)
                        break
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun checkHealthCondition(): Boolean {
        if (!autoEat) {
            shouldEat = false
            isEating = false
            return false
        }
        val p = mc.player ?: return false
        val hp = p.health
        val max = p.maxHealth
        if (hp <= triggerHealth) {
            shouldEat = true
            isEating = true
        }
        if (hp >= max && shouldEat) {
            shouldEat = false
            isEating = false
        }
        return shouldEat
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (!cancelMove || ticks >= c03Count) return@handler
        val packet = event.packet
        if (packet is ServerboundMovePlayerPacket) {
            runCatching {
                val y = packet.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "getYRot" || it.name == "yRot")
                }?.invoke(packet) as? Float
                val x = packet.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "getXRot" || it.name == "xRot")
                }?.invoke(packet) as? Float
                if (y != null) savedYaw = y
                if (x != null) savedPitch = x
            }
            ticks++
            runCatching { event.cancelEvent() }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler
        val slot = getGApple()
        val cont = checkHealthCondition()

        if (cont && slot >= 0) {
            if (pauseTicks == 0) {
                stuck()
            } else if (pauseTicks > 0) {
                stopStuck()
                pauseTicks--
            }

            if (ticks >= c03Count) {
                val prev = selectedSlot()
                // C09 切到金苹果
                send(ServerboundSetCarriedItemPacket(slot))
                setSelectedSlot(slot)
                // C08 使用
                sendUse()
                release()
                // C09 切回
                send(ServerboundSetCarriedItemPacket(prev))
                setSelectedSlot(prev)
                // BlockFix：再发一次当前手物品 placement/use
                if (blockFix) {
                    sendUse()
                }
                pauseTicks++
                ticks = 0
            }
        } else {
            stopStuck()
            ticks = 0
            pauseTicks = 0
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!showProgress || !isEating) return@handler
        val target = (ticks / c03Count.toFloat()).coerceIn(0f, 1f)
        val now = System.nanoTime()
        val ft = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        anim += (target - anim) * (1f - exp(-14f * ft))

        val ctx = event.context
        val sw = try {
            ctx.guiWidth().toFloat()
        } catch (_: Throwable) {
            mc.window.guiScaledWidth.toFloat()
        }
        val sh = try {
            ctx.guiHeight().toFloat()
        } catch (_: Throwable) {
            mc.window.guiScaledHeight.toFloat()
        }
        val w = barWidth
        val h = barHeight
        val x = (sw - w) / 2f
        val y = sh * 0.75f

        if (barRadius > 0.5f) {
            ctx.drawRoundedRect(x, y, x + w, y + h, barRadius, barBg)
            if (anim > 0.02f) {
                val mid = x + w * anim * 0.5f
                val end = x + w * anim
                ctx.drawRoundedRect(x, y, mid.coerceAtLeast(x + barRadius), y + h, barRadius, progressStart)
                ctx.drawRoundedRect(mid, y, end.coerceAtLeast(mid + 0.5f), y + h, barRadius, progressEnd)
            }
        } else {
            ctx.drawQuad(x, y, x + w, y + h, barBg)
            ctx.drawQuad(x, y, x + w * anim, y + h, progressStart)
        }

        val pct = if (showTag) "$ticks / $c03Count" else "${(anim * 100).roundToInt()}%"
        ctx.text(mc.font, pct, (x + w + 5).roundToInt(), y.roundToInt(), -1, true)
    }

    override fun onEnabled() {
        shouldEat = false
        isEating = false
        ticks = 0
        pauseTicks = 0
        stopStuck()
    }

    override fun onDisabled() {
        ticks = 0
        pauseTicks = 0
        shouldEat = false
        isEating = false
        stopStuck()
        anim = 0f
    }
}
