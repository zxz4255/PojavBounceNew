/*
 * ModuleAutoGapple — LiquidBounce Nextgen 0.39
 * 模式1：热键栏/背包静默切槽 + 发包食用（间隔可调）
 * 模式2：背包有金苹果则自动放副手并食用
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.max

object ModuleAutoEatGapple : ClientModule(
    "AutoEatGapple",
    ModuleCategories.PLAYER,
    aliases = listOf("AutoGap", "SilentGapple"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        SILENT_SLOT("Silent Slot"),
        OFFHAND("Offhand"),
    }

    private enum class Prefer(override val tag: String) : Tagged {
        ENCHANTED("Enchanted First"),
        NORMAL("Normal First"),
        ANY("Any"),
    }

    private val mode by enumChoice("Mode", Mode.SILENT_SLOT)
    private val prefer by enumChoice("Prefer", Prefer.ENCHANTED)

    /** 两次完整食用之间的间隔（tick，20tick≈1s） */
    private val eatDelayTicks by int("Eat Delay Ticks", 40, 0..200)
    /** 开始按住使用后，至少保持多少 tick（金苹果约 32tick） */
    private val holdTicks by int("Hold Use Ticks", 34, 20..60)
    /** 血量低于此值才吃（20=满血，设 20 表示总是可吃） */
    private val healthThreshold by float("Health Below", 14f, 1f..20f)
    private val onlyWhenHurt by boolean("Only When Hurt", true)
    private val requireNoScreen by boolean("No Screen", true)
    private val switchBack by boolean("Switch Back", true)
    private val includeInventory by boolean("Search Inventory", true)
    private val silentHotbar by boolean("Silent Hotbar", true)
    private val autoDisableEmpty by boolean("Disable If Empty", false)

    // 运行时状态
    private var cooldown = 0
    private var eating = false
    private var eatProgress = 0
    private var savedSlot = -1
    private var activeSlot = -1
    private var offhandMoved = false
    private var pendingOffhandSlot = -1

    private fun isGapple(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val item = stack.item
        return item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE
    }

    private fun isEnchanted(stack: ItemStack): Boolean =
        !stack.isEmpty && stack.item == Items.ENCHANTED_GOLDEN_APPLE

    private fun isNormal(stack: ItemStack): Boolean =
        !stack.isEmpty && stack.item == Items.GOLDEN_APPLE

    private fun score(stack: ItemStack): Int {
        if (!isGapple(stack)) return -1
        return when (prefer) {
            Prefer.ENCHANTED -> if (isEnchanted(stack)) 2 else 1
            Prefer.NORMAL -> if (isNormal(stack)) 2 else 1
            Prefer.ANY -> 1
        }
    }

    /** 0-8 热键栏，9-35 主背包 */
    private fun findGappleSlot(): Int {
        val inv = player.inventory
        var best = -1
        var bestScore = -1
        val maxSlot = if (includeInventory) 36 else 9
        for (i in 0 until maxSlot) {
            val stack = inv.getItem(i)
            val s = score(stack)
            if (s > bestScore) {
                bestScore = s
                best = i
            }
        }
        // 副手
        val off = player.offhandItem
        if (score(off) > bestScore) {
            return -2 // 特殊：已在副手
        }
        return best
    }

    private fun invSelected(): Int {
        // Inventory.selected 在高版本可能 private，用反射
        return runCatching {
            val f = Inventory::class.java.getDeclaredField("selected")
            f.isAccessible = true
            f.getInt(player.inventory)
        }.getOrElse {
            runCatching {
                val m = player.inventory.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (
                        it.name == "getSelectedSlot" ||
                            it.name == "getSelectedHotbarSlot" ||
                            it.name.equals("getSelected", true)
                        )
                }
                (m?.invoke(player.inventory) as? Int) ?: 0
            }.getOrDefault(0)
        }
    }

    private fun setInvSelected(slot: Int) {
        if (slot !in 0..8) return
        runCatching {
            val f = Inventory::class.java.getDeclaredField("selected")
            f.isAccessible = true
            f.setInt(player.inventory, slot)
        }
        runCatching {
            // 发包同步热键栏（静默切槽核心）
            val pktClass = Class.forName(
                "net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket",
            )
            val ctor = pktClass.constructors.first { it.parameterCount == 1 }
            val pkt = ctor.newInstance(slot)
            player.connection.send(pkt as net.minecraft.network.protocol.Packet<*>)
        }
        // 非静默时也改客户端显示
        if (!silentHotbar) {
            runCatching {
                val f = Inventory::class.java.getDeclaredField("selected")
                f.isAccessible = true
                f.setInt(player.inventory, slot)
            }
        }
    }

    private fun restoreSlot() {
        if (!switchBack || savedSlot < 0) return
        setInvSelected(savedSlot.coerceIn(0, 8))
        savedSlot = -1
    }

    /** 背包槽 → 副手（window click 反射，兼容不同映射名） */
    private fun moveToOffhand(invSlot: Int): Boolean {
        val handler = player.containerMenu ?: return false
        val syncId = handler.containerId
        // 玩家物品栏：热键 36-44，主背包 9-35，副手 45
        val clickSlot = when {
            invSlot in 0..8 -> 36 + invSlot
            invSlot in 9..35 -> invSlot
            else -> return false
        }
        return runCatching {
            mc.gameMode?.handleInventoryMouseClick(
                syncId,
                clickSlot,
                40, // offhand swap button in some versions; fallback below
                net.minecraft.world.inventory.ClickType.SWAP,
                player,
            )
            true
        }.getOrElse {
            // 兼容：PICKUP 两次交换
            runCatching {
                val gm = mc.gameMode ?: return@runCatching false
                val type = net.minecraft.world.inventory.ClickType.PICKUP
                gm.handleInventoryMouseClick(syncId, clickSlot, 0, type, player)
                gm.handleInventoryMouseClick(syncId, 45, 0, type, player)
                true
            }.getOrDefault(false)
        }
    }

    private fun shouldEat(): Boolean {
        if (requireNoScreen && mc.screen != null) return false
        if (player.isUsingItem && !eating) return false
        if (onlyWhenHurt) {
            if (player.health + player.absorptionAmount > healthThreshold) return false
        } else {
            if (player.health + player.absorptionAmount > healthThreshold && healthThreshold < 20f) return false
        }
        return true
    }

    private fun startUse(hand: InteractionHand) {
        runCatching {
            mc.gameMode?.useItem(player, hand)
        }
        runCatching {
            player.startUsingItem(hand)
        }
        if (hand == InteractionHand.MAIN_HAND) {
            // 部分版本需按住 use key 逻辑；发包已由 useItem 触发
        }
    }

    private fun stopUse() {
        runCatching {
            mc.gameMode?.releaseUsingItem(player)
        }
        runCatching {
            // 若仍在使用则释放
            if (player.isUsingItem) {
                player.releaseUsingItem()
            }
        }
        eating = false
        eatProgress = 0
        activeSlot = -1
        restoreSlot()
        cooldown = eatDelayTicks
    }

    private fun tickSilent() {
        if (eating) {
            eatProgress++
            // 保持选中金苹果槽
            if (activeSlot in 0..8) {
                setInvSelected(activeSlot)
            }
            // 持续确保在使用
            if (!player.isUsingItem && eatProgress < holdTicks) {
                startUse(InteractionHand.MAIN_HAND)
            }
            if (eatProgress >= holdTicks || (!player.isUsingItem && eatProgress > 5 && player.useItemRemainingTicks <= 0)) {
                // 完成一轮
                stopUse()
            }
            return
        }

        if (cooldown > 0) {
            cooldown--
            return
        }
        if (!shouldEat()) return

        val slot = findGappleSlot()
        if (slot == -1) {
            if (autoDisableEmpty) enabled = false
            return
        }
        if (slot == -2) {
            // 副手已有：直接用副手
            eating = true
            eatProgress = 0
            startUse(InteractionHand.OFF_HAND)
            return
        }

        if (slot in 0..8) {
            savedSlot = invSelected()
            activeSlot = slot
            setInvSelected(slot)
            eating = true
            eatProgress = 0
            startUse(InteractionHand.MAIN_HAND)
        } else if (includeInventory && slot in 9..35) {
            // 背包：先尝试挪到热键栏空位再静默切
            val emptyHotbar = (0..8).firstOrNull { player.inventory.getItem(it).isEmpty }
            if (emptyHotbar != null) {
                swapInv(slot, emptyHotbar)
                savedSlot = invSelected()
                activeSlot = emptyHotbar
                setInvSelected(emptyHotbar)
                eating = true
                eatProgress = 0
                startUse(InteractionHand.MAIN_HAND)
            } else {
                // 与当前热键交换
                val cur = invSelected().coerceIn(0, 8)
                swapInv(slot, cur)
                savedSlot = cur
                activeSlot = cur
                setInvSelected(cur)
                eating = true
                eatProgress = 0
                startUse(InteractionHand.MAIN_HAND)
            }
        }
    }

    private fun swapInv(from: Int, toHotbar: Int) {
        val handler = player.containerMenu ?: return
        val syncId = handler.containerId
        val fromClick = if (from in 0..8) 36 + from else from
        val toClick = 36 + toHotbar
        runCatching {
            val gm = mc.gameMode ?: return
            val type = net.minecraft.world.inventory.ClickType.SWAP
            // button = hotbar index for SWAP
            gm.handleInventoryMouseClick(syncId, fromClick, toHotbar, type, player)
        }.onFailure {
            runCatching {
                val gm = mc.gameMode ?: return@runCatching
                val pickup = net.minecraft.world.inventory.ClickType.PICKUP
                gm.handleInventoryMouseClick(syncId, fromClick, 0, pickup, player)
                gm.handleInventoryMouseClick(syncId, toClick, 0, pickup, player)
            }
        }
    }

    private fun tickOffhand() {
        if (eating) {
            eatProgress++
            if (!player.isUsingItem && eatProgress < holdTicks) {
                startUse(InteractionHand.OFF_HAND)
            }
            if (eatProgress >= holdTicks || (!player.isUsingItem && eatProgress > 5)) {
                stopUse()
                offhandMoved = false
            }
            return
        }

        if (cooldown > 0) {
            cooldown--
            return
        }
        if (!shouldEat()) return

        // 副手已有金苹果
        if (isGapple(player.offhandItem)) {
            eating = true
            eatProgress = 0
            startUse(InteractionHand.OFF_HAND)
            return
        }

        val slot = findGappleSlot()
        if (slot < 0) {
            if (autoDisableEmpty) enabled = false
            return
        }
        // 从热键/背包挪到副手
        if (moveToOffhand(slot)) {
            offhandMoved = true
            pendingOffhandSlot = slot
            // 下一 tick 再吃，等物品到位
            cooldown = 2
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (player.isDeadOrDying) {
            eating = false
            eatProgress = 0
            return@handler
        }
        when (mode) {
            Mode.SILENT_SLOT -> tickSilent()
            Mode.OFFHAND -> tickOffhand()
        }
    }

    override fun onDisabled() {
        if (eating) stopUse()
        eating = false
        eatProgress = 0
        cooldown = 0
        savedSlot = -1
        activeSlot = -1
        offhandMoved = false
    }
}
