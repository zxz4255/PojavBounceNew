/*
 * ModuleAutoEatGapple — 快速可用版
 * 血量过低 → 热键栏找金苹果 → 切槽 → 持续 useItem 直到吃完/血量恢复 → 切回
 * 不再等待 32 个移动包（那是 1.8 特殊绕过，高版本又慢又难生效）
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleAutoEatGapple : ClientModule(
    "AutoEatGapple",
    ModuleCategories.PLAYER,
    aliases = listOf("EzGapple", "AutoGapple", "FastGapple"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        /** 切槽 + 强制按住右键（最稳） */
        LEGIT("Legit"),
        /** 切槽 + 发包 useItem */
        PACKET("Packet"),
    }

    private val mode by enumChoice("Mode", Mode.LEGIT)
    private val health by float("Health", 14f, 1f..20f)
    private val until by float("Stop At Health", 18f, 1f..20f)
    private val preferEnchanted by boolean("Prefer Enchanted", false)
    private val switchBack by boolean("Switch Back", true)
    private val sendSlotPacket by boolean("Send Slot Packet", true)
    private val silentSwitch by boolean("Silent Switch", true)
    private val useEveryTick by boolean("Use Every Tick", true)

    private val showProgress by boolean("Show Progress", true)
    private val barWidth by float("Bar Width", 120f, 40f..300f)
    private val barHeight by float("Bar Height", 8f, 3f..20f)
    private val barRadius by float("Bar Radius", 3f, 0f..10f)
    private val barBg by color("Bar Background", Color4b(20, 20, 20, 180))
    private val barFill by color("Bar Fill", Color4b(255, 200, 50, 230))
    private val barEnch by color("Bar Enchanted", Color4b(190, 90, 255, 230))

    private var eating = false
    private var forceUse = false
    private var prevSlot = -1
    private var gapSlot = -1
    private var usingEnch = false
    private var sequence = 0
    private var anim = 0f
    private var lastNs = 0L
    private var ticksEating = 0

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

    private fun setSelectedSlot(slot: Int, local: Boolean = true) {
        val s = slot.coerceIn(0, 8)
        // 服务端切槽（静默核心）
        if (sendSlotPacket) {
            runCatching { mc.connection?.send(ServerboundSetCarriedItemPacket(s)) }
        }
        // 本地手持显示：静默时不改，避免热键栏闪烁
        if (!local || silentSwitch) return
        val inv = mc.player?.inventory ?: return
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

    /** 静默：只发包告诉服务器当前槽是金苹果，本地 selected 不变 */
    private fun silentSelect(slot: Int) {
        setSelectedSlot(slot, local = false)
    }

    private fun findGap(): Int {
        val inv = mc.player?.inventory ?: return -1
        var g = -1
        var e = -1
        for (i in 0..8) {
            val st = inv.getItem(i)
            if (st.isEmpty) continue
            when (st.item) {
                Items.ENCHANTED_GOLDEN_APPLE -> e = i
                Items.GOLDEN_APPLE -> g = i
            }
        }
        return if (preferEnchanted) (if (e != -1) e else g) else (if (g != -1) g else e)
    }

    private fun isGapInHand(): Boolean {
        val st = mc.player?.mainHandItem ?: return false
        return st.item == Items.GOLDEN_APPLE || st.item == Items.ENCHANTED_GOLDEN_APPLE
    }

    private fun needEat(): Boolean {
        val p = mc.player ?: return false
        if (p.isDeadOrDying) return false
        return p.health <= health
    }

    private fun shouldStop(): Boolean {
        val p = mc.player ?: return true
        return p.health >= until
    }

    private fun sendUsePacket() {
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
                    mc.connection?.send(pkt as net.minecraft.network.protocol.Packet<*>)
                    return
                } catch (_: Throwable) {
                }
            }
        }
        runCatching { mc.gameMode?.useItem(p, hand) }
    }

    private fun startEat() {
        val slot = findGap()
        if (slot < 0) return
        gapSlot = slot
        if (prevSlot < 0) prevSlot = selectedSlot()
        if (silentSwitch) silentSelect(slot) else setSelectedSlot(slot, local = true)
        usingEnch = mc.player?.inventory?.getItem(slot)?.item == Items.ENCHANTED_GOLDEN_APPLE
        eating = true
        ticksEating = 0
        when (mode) {
            Mode.LEGIT -> {
                // 静默时强制右键可能仍用本地手物品，故静默建议 Packet
                forceUse = !silentSwitch
                if (silentSwitch) {
                    sendUsePacket()
                } else {
                    runCatching { mc.gameMode?.useItem(mc.player, InteractionHand.MAIN_HAND) }
                }
            }
            Mode.PACKET -> {
                forceUse = false
                sendUsePacket()
            }
        }
    }

    private fun stopEat() {
        forceUse = false
        eating = false
        ticksEating = 0
        val p = mc.player
        if (p != null && p.isUsingItem) {
            runCatching { mc.gameMode?.releaseUsingItem(p) }
        }
        if (switchBack && prevSlot in 0..8) {
            if (silentSwitch) silentSelect(prevSlot) else setSelectedSlot(prevSlot, local = true)
        }
        prevSlot = -1
        gapSlot = -1
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler
        if (player.isDeadOrDying) {
            if (eating) stopEat()
            return@handler
        }

        // 进行中：血量够了或苹果没了 → 停
        if (eating) {
            ticksEating++
            if (shouldStop() || findGap() < 0) {
                stopEat()
                return@handler
            }
            // 手不在苹果上再切一次
            // 静默：本地手可能不是苹果，每 tick 保持服务端槽位
            if (gapSlot in 0..8) {
                if (silentSwitch) {
                    silentSelect(gapSlot)
                } else if (!isGapInHand()) {
                    setSelectedSlot(gapSlot, local = true)
                }
            }
            when (mode) {
                Mode.LEGIT -> {
                    if (silentSwitch) {
                        // 静默 + Legit 实际走发包使用
                        if (useEveryTick || !player.isUsingItem) sendUsePacket()
                    } else {
                        forceUse = true
                        if (!player.isUsingItem) {
                            runCatching { mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND) }
                        }
                    }
                }
                Mode.PACKET -> {
                    if (useEveryTick || !player.isUsingItem) {
                        sendUsePacket()
                    }
                }
            }
            if (ticksEating > 45 && !player.isUsingItem) {
                if (gapSlot in 0..8) {
                    if (silentSwitch) silentSelect(gapSlot) else setSelectedSlot(gapSlot, local = true)
                }
                sendUsePacket()
            }
            if (ticksEating > 80) {
                stopEat()
            }
            return@handler
        }

        // 未在吃：满足血量就开吃
        if (needEat()) {
            if (findGap() >= 0) startEat()
        }
    }

    /** Legit：强制 use 键按下 */
    @Suppress("unused")
    private val keyHandler = handler<KeybindIsPressedEvent> { event ->
        if (!forceUse) return@handler
        runCatching {
            if (event.keyBinding == mc.options.useKey) {
                event.isPressed = true
            }
        }.onFailure {
            runCatching {
                val use = mc.options.useKey
                val kb = event.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.lowercase().contains("key")
                }?.invoke(event)
                if (kb == use || kb == use.key) {
                    val f = event.javaClass.declaredFields.firstOrNull {
                        it.name.contains("pressed", true) || it.name.contains("isPressed", true)
                    }
                    f?.isAccessible = true
                    f?.setBoolean(event, true)
                }
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!showProgress) return@handler
        val player = mc.player ?: return@handler

        val target = when {
            eating && player.isUsingItem -> {
                val remain = try {
                    player.useItemRemainingTicks
                } catch (_: Throwable) {
                    32
                }
                (1f - remain / 32f).coerceIn(0f, 1f)
            }
            eating -> (ticksEating / 32f).coerceIn(0f, 0.95f)
            else -> 0f
        }
        val now = System.nanoTime()
        val ft = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        anim += (target - anim) * (1f - exp(-16f * ft))
        if (anim < 0.02f && target <= 0f) return@handler

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
        val y = sh / 2f + 28f
        val fill = if (usingEnch) barEnch else barFill

        if (barRadius > 0.5f) {
            ctx.drawRoundedRect(x, y, x + w, y + h, barRadius, barBg)
            if (anim > 0.02f) {
                ctx.drawRoundedRect(x, y, x + (w * anim).coerceAtLeast(barRadius), y + h, barRadius, fill)
            }
        } else {
            ctx.drawQuad(x, y, x + w, y + h, barBg)
            ctx.drawQuad(x, y, x + w * anim, y + h, fill)
        }

        val label = if (usingEnch) "Enchanted" else "Gapple"
        val text = "$label ${(anim * 100).roundToInt()}%"
        val tw = mc.font.width(text)
        ctx.text(mc.font, text, (x + w / 2f - tw / 2f).roundToInt(), (y - 11f).roundToInt(), -1, true)
    }

    override fun onDisabled() {
        stopEat()
        anim = 0f
    }
}
