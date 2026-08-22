/*
 * ModuleGapple — 移植自 Lizz/LB 1.8.9 Gapple（Blink + C03 计数版）
 * 逻辑: 血量过低 → 吞包攒 C03 → 满额后发包切槽/使用金苹果/切回
 * LiquidBounce Nextgen 0.39
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleGapple : ClientModule(
    "Gapple",
    ModuleCategories.PLAYER,
    aliases = listOf("BlinkGapple", "PacketGapple"),
) {

    private val health by int("Health", 14, 1..40)
    private val needC03 by int("Need C03", 32, 16..40)
    private val sendDelay by int("Send Delay", 3, 1..10)
    private val autoGapple by boolean("Auto Gapple", true)
    private val stopMove by boolean("Stop Move", false)
    private val stuckMode by boolean("Stuck", false)

    private val showProgress by boolean("Show Progress", true)
    private val barWidth by float("Bar Width", 140f, 60f..300f)
    private val barHeight by float("Bar Height", 7f, 3f..20f)
    private val barRadius by float("Bar Radius", 3f, 0f..10f)
    private val progressStart by color("Progress Start", Color4b(76, 157, 240, 255))
    private val progressEnd by color("Progress End", Color4b(53, 200, 167, 255))
    private val barBg by color("Bar Background", Color4b(0, 0, 0, 128))

    private var slot = -1
    private var c03Count = 0
    private var eating = false
    private var blinking = false
    private var previousSlot = -1
    private val queue = ConcurrentLinkedQueue<Packet<*>>()
    private var anim = 0f
    private var lastNs = 0L
    private var sequence = 0

    private fun findGapSlot(): Int {
        val inv = mc.player?.inventory ?: return -1
        for (i in 0..8) {
            val s = inv.getItem(i)
            if (!s.isEmpty && s.item == Items.GOLDEN_APPLE) return i
        }
        for (i in 0..8) {
            val s = inv.getItem(i)
            if (!s.isEmpty && s.item == Items.ENCHANTED_GOLDEN_APPLE) return i
        }
        return -1
    }

    private fun send(packet: Packet<*>) {
        runCatching { mc.connection?.send(packet) }
    }

    private fun sendUse() {
        val p = mc.player ?: return
        val hand = InteractionHand.MAIN_HAND
        val yaw = p.yRot
        val pitch = p.xRot
        runCatching {
            for (c in ServerboundUseItemPacket::class.java.constructors) {
                try {
                    val pkt = when (c.parameterCount) {
                        1 -> c.newInstance(hand)
                        2 -> c.newInstance(hand, sequence++)
                        4 -> c.newInstance(hand, sequence++, yaw, pitch)
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

    private fun flushQueue(count: Int = Int.MAX_VALUE) {
        var n = 0
        while (n < count && queue.isNotEmpty()) {
            val pkt = queue.poll() ?: break
            send(pkt)
            n++
        }
    }

    private fun stopBlink() {
        blinking = false
        flushQueue()
        queue.clear()
    }

    private fun finishEat() {
        if (slot < 0) {
            eating = false
            stopBlink()
            return
        }
        val p = mc.player ?: return
        if (previousSlot < 0) previousSlot = p.inventory.selected

        // 切到金苹果 → 使用 → 切回（对齐 C09 + C08 + C09）
        send(ServerboundSetCarriedItemPacket(slot))
        runCatching { p.inventory.selected = slot }
        sendUse()
        stopBlink()
        send(ServerboundSetCarriedItemPacket(previousSlot.coerceIn(0, 8)))
        runCatching { p.inventory.selected = previousSlot.coerceIn(0, 8) }

        eating = false
        c03Count = 0
        previousSlot = -1

        if (!autoGapple) {
            enabled = false
        } else {
            slot = findGapSlot()
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (!eating || !blinking) return@handler
        val packet = event.packet
        // 拦截移动包，计入 C03 并入队
        if (packet is ServerboundMovePlayerPacket) {
            c03Count++
            queue.offer(packet)
            runCatching { event.cancelEvent() }
            return@handler
        }
        // 可选：吞掉其它干扰包（简化，仅队列移动包）
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler
        if (player.isDeadOrDying) {
            eating = false
            stopBlink()
            enabled = false
            return@handler
        }

        if (player.health >= health) {
            if (eating) {
                eating = false
                stopBlink()
                c03Count = 0
            }
            return@handler
        }

        // 需要吃
        if (!eating) {
            slot = findGapSlot()
            if (slot == -1) {
                enabled = false
                return@handler
            }
            eating = true
            c03Count = 0
            previousSlot = player.inventory.selected
            blinking = true
        }

        if (slot == -1) {
            slot = findGapSlot()
            if (slot == -1) {
                enabled = false
                return@handler
            }
        }

        // 攒够 C03 → 完成食用
        if (c03Count >= needC03) {
            finishEat()
            return@handler
        }

        // 按间隔释放部分队列（对齐 sendDelay / releasePacket）
        if (blinking && player.tickCount % sendDelay == 0) {
            flushQueue(1)
        }

        // StopMove：清零输入（尽量）
        if (stopMove && eating) {
            runCatching {
                player.xxa = 0f
                player.zza = 0f
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!showProgress) return@handler
        val target = if (eating) (c03Count / needC03.toFloat()).coerceIn(0f, 1f) else 0f
        val now = System.nanoTime()
        val ft = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        anim += (target - anim) * (1f - exp(-14f * ft))
        if (anim < 0.02f && target <= 0f) return@handler

        val ctx = event.context
        val sw = try { ctx.guiWidth().toFloat() } catch (_: Throwable) {
            mc.window.guiScaledWidth.toFloat()
        }
        val sh = try { ctx.guiHeight().toFloat() } catch (_: Throwable) {
            mc.window.guiScaledHeight.toFloat()
        }
        val w = barWidth
        val h = barHeight
        val x = (sw - w) / 2f
        val y = sh * 0.75f

        if (barRadius > 0.5f) {
            ctx.drawRoundedRect(x, y, x + w, y + h, barRadius, barBg)
            if (anim > 0.02f) {
                // 渐变近似：两段色
                val mid = x + w * anim * 0.5f
                val end = x + w * anim
                ctx.drawRoundedRect(x, y, mid.coerceAtLeast(x + barRadius), y + h, barRadius, progressStart)
                ctx.drawRoundedRect(mid, y, end.coerceAtLeast(mid + 0.5f), y + h, barRadius, progressEnd)
            }
        } else {
            ctx.drawQuad(x, y, x + w, y + h, barBg)
            ctx.drawQuad(x, y, x + w * anim, y + h, progressStart)
        }

        val font = mc.font
        val pct = "${(anim * 100).roundToInt()}%"
        ctx.text(font, pct, (x + w + 5).roundToInt(), y.roundToInt(), -1, true)
    }

    override fun onEnabled() {
        slot = findGapSlot()
        c03Count = 0
        eating = false
        blinking = false
        queue.clear()
    }

    override fun onDisabled() {
        eating = false
        stopBlink()
        c03Count = 0
        anim = 0f
    }
}
