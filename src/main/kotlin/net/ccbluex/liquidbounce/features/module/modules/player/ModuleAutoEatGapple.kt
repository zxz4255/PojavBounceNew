/*
 * ModuleAutoEatGapple — 不切本地手持，纯发包吃金苹果
 *
 * 原理（原版协议限制）:
 *   UseItem 只会使用「服务端当前选中热键槽」的物品。
 *   因此必须对服务端发 SetCarriedItem → 金苹果槽，吃完再发回原槽。
 *   本地 inventory.selected **始终不改**，画面上手持武器/物品不受干扰。
 *
 * 另支持：金苹果在副手时直接 OFF_HAND 使用，主手完全不动。
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.config.types.list.Tagged
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
    aliases = listOf("SilentGapple", "PacketGapple", "NoSwitchGapple"),
) {

    private enum class Prefer(override val tag: String) : Tagged {
        /** 优先副手，没有再热键栏静默 */
        OFFHAND_FIRST("Offhand First"),
        /** 只用热键栏静默发包 */
        HOTBAR_SILENT("Hotbar Silent"),
        /** 只要副手有 */
        OFFHAND_ONLY("Offhand Only"),
    }

    private val prefer by enumChoice("Prefer", Prefer.OFFHAND_FIRST)
    private val health by float("Health", 14f, 1f..20f)
    private val until by float("Stop At Health", 20f, 1f..20f)
    private val preferEnchanted by boolean("Prefer Enchanted", false)
    private val minEatTicks by int("Min Eat Ticks", 32, 28..40)
    private val maxEatTicks by int("Max Eat Ticks", 48, 32..80)
    private val cooldownTicks by int("Cooldown Ticks", 8, 0..40)
    /** 开始后延迟几 tick 再 Use，让切槽包先到 */
    private val useDelayTicks by int("Use Delay", 1, 0..5)

    private val showProgress by boolean("Show Progress", true)
    private val barWidth by float("Bar Width", 120f, 40f..300f)
    private val barHeight by float("Bar Height", 8f, 3f..20f)
    private val barRadius by float("Bar Radius", 3f, 0f..10f)
    private val barBg by color("Bar Background", Color4b(20, 20, 20, 180))
    private val barFill by color("Bar Fill", Color4b(255, 200, 50, 230))
    private val barEnch by color("Bar Enchanted", Color4b(190, 90, 255, 230))

    private enum class Path { NONE, OFFHAND, HOTBAR }

    private var path = Path.NONE
    private var eating = false
    private var serverSlot = -1      // 服务端临时槽（金苹果）
    private var restoreSlot = -1     // 吃完恢复的服务端槽
    private var usingEnch = false
    private var sequence = 0
    private var ticksEating = 0
    private var cooldown = 0
    private var useSent = false
    private var anim = 0f
    private var lastNs = 0L

    private fun isGapStack(item: net.minecraft.world.item.Item?): Boolean =
        item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE

    private fun offhandIsGap(): Boolean {
        val st = mc.player?.offhandItem ?: return false
        return isGapStack(st.item)
    }

    private fun findHotbarGap(): Int {
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

    private fun currentSelected(): Int {
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

    /** 只改服务端选中槽，绝不改本地 selected → 手持模型不变 */
    private fun serverSelect(slot: Int) {
        runCatching {
            mc.connection?.send(ServerboundSetCarriedItemPacket(slot.coerceIn(0, 8)))
        }
    }

    private fun sendUse(hand: InteractionHand) {
        val p = mc.player ?: return
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

    private fun pickPath(): Path {
        val oh = offhandIsGap()
        val hb = findHotbarGap()
        return when (prefer) {
            Prefer.OFFHAND_ONLY -> if (oh) Path.OFFHAND else Path.NONE
            Prefer.HOTBAR_SILENT -> if (hb >= 0) Path.HOTBAR else Path.NONE
            Prefer.OFFHAND_FIRST -> when {
                oh -> Path.OFFHAND
                hb >= 0 -> Path.HOTBAR
                else -> Path.NONE
            }
        }
    }

    private fun startEat() {
        val p = pickPath()
        if (p == Path.NONE) return
        path = p
        eating = true
        ticksEating = 0
        useSent = false
        restoreSlot = currentSelected()

        when (p) {
            Path.OFFHAND -> {
                serverSlot = -1
                usingEnch = mc.player?.offhandItem?.item == Items.ENCHANTED_GOLDEN_APPLE
                // 副手：主手槽完全不动，直接 OFF_HAND use
            }
            Path.HOTBAR -> {
                serverSlot = findHotbarGap()
                if (serverSlot < 0) {
                    eating = false
                    path = Path.NONE
                    return
                }
                usingEnch = mc.player?.inventory?.getItem(serverSlot)?.item == Items.ENCHANTED_GOLDEN_APPLE
                // 仅服务端切到金苹果槽，本地手持不变
                serverSelect(serverSlot)
            }
            Path.NONE -> {}
        }
    }

    private fun stopEat(success: Boolean) {
        // 热键栏路径：把服务端槽切回原来的（本地本来就没变）
        if (path == Path.HOTBAR && restoreSlot in 0..8) {
            serverSelect(restoreSlot)
        }
        eating = false
        path = Path.NONE
        serverSlot = -1
        restoreSlot = -1
        useSent = false
        ticksEating = 0
        cooldown = cooldownTicks
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val player = mc.player ?: return@handler

        if (cooldown > 0) {
            cooldown--
            return@handler
        }

        if (player.isDeadOrDying) {
            if (eating) stopEat(false)
            return@handler
        }

        if (eating) {
            ticksEating++

            // 物品没了
            val stillHave = when (path) {
                Path.OFFHAND -> offhandIsGap()
                Path.HOTBAR -> serverSlot >= 0 && isGapStack(player.inventory.getItem(serverSlot).item)
                else -> false
            }
            if (!stillHave) {
                stopEat(false)
                return@handler
            }

            // 延迟后只发一次 Use（避免每 tick 重开导致进度归零）
            if (!useSent && ticksEating >= useDelayTicks + 1) {
                useSent = true
                when (path) {
                    Path.OFFHAND -> sendUse(InteractionHand.OFF_HAND)
                    Path.HOTBAR -> {
                        // 再确认一次服务端槽（只此一次强化，不每 tick 刷）
                        if (serverSlot >= 0) serverSelect(serverSlot)
                        sendUse(InteractionHand.MAIN_HAND)
                    }
                    else -> {}
                }
            }

            // 中途若 using 被打断且未满最小 tick，补一次 use（仍不切本地槽）
            if (useSent &&
                !player.isUsingItem &&
                ticksEating in (useDelayTicks + 3) until minEatTicks
            ) {
                when (path) {
                    Path.OFFHAND -> sendUse(InteractionHand.OFF_HAND)
                    Path.HOTBAR -> {
                        if (serverSlot >= 0) serverSelect(serverSlot)
                        sendUse(InteractionHand.MAIN_HAND)
                    }
                    else -> {}
                }
            }

            // 吃满最短时间且已不在 using → 恢复服务端槽
            if (ticksEating >= minEatTicks && !player.isUsingItem) {
                stopEat(true)
                return@handler
            }
            if (ticksEating >= minEatTicks && player.health >= until && !player.isUsingItem) {
                stopEat(true)
                return@handler
            }
            if (ticksEating >= maxEatTicks) {
                stopEat(true)
            }
            return@handler
        }

        // 触发
        if (player.health <= health && pickPath() != Path.NONE) {
            startEat()
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
                    (minEatTicks - ticksEating).coerceAtLeast(0)
                }
                (1f - remain / 32f).coerceIn(0f, 1f)
            }
            eating -> (ticksEating / minEatTicks.toFloat()).coerceIn(0f, 0.99f)
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
        val tag = when (path) {
            Path.OFFHAND -> "Offhand"
            Path.HOTBAR -> "Silent"
            else -> "Gapple"
        }
        val text = "$tag ${(anim * 100).roundToInt()}%"
        val tw = mc.font.width(text)
        ctx.text(mc.font, text, (x + w / 2f - tw / 2f).roundToInt(), (y - 11f).roundToInt(), -1, true)
    }

    override fun onDisabled() {
        if (eating) stopEat(false)
        anim = 0f
        cooldown = 0
    }
}
