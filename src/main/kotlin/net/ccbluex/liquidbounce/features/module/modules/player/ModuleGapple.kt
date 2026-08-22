// src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/player/ModuleGapple.kt
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.events.PreTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.interaction
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.network.sendHeldItemChange
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import kotlin.math.max

/**
 * Gapple – a direct port of the legacy Gapple module (pre‑0.39) to the new API.
 *
 * 功能概览：
 * 1. 当玩家血量低于 [heal] 时自动切换到金苹果（普通或附魔），并开始吃。
 * 2. 可选的 “Stuck” 会在吃的期间冻结玩家位置（通过将 velocity 设为 0 实现）。
 * 3. 可选的 “StopMove” 会阻止任何移动输入。
 * 4. 通过 [sendDelay] 控制每隔多少 tick 发送一次 “保持活跃” 包，以模仿原来的 Blink 行为。
 * 5. 进度条 UI（颜色可调）显示正在吃的进度（0‑32 tick）。
 *
 * 所有配置项均使用 0.39 的统一配置系统，保持原来的默认值。
 */
object ModuleGapple : ClientModule("Gapple", ModuleCategories.PLAYER) {

    // --------------------------------------------------------------------
    // 配置项（与旧版保持相同的默认值）
    // --------------------------------------------------------------------
    private val heal          by int   ("Health",        20, 0..40)          // 低于此血量才触发
    private val sendDelay     by int   ("SendDelay",      3, 1..10)         // 每 N tick 发送一次 “保持活跃” 包
    private val stuckEnabled  by boolean("Stuck",        false)           // 是否冻结玩家位置
    private val stopMove      by boolean("StopMove",    false)           // 吃东西时禁止所有移动输入
    private val noCancelC02   by boolean("NoCancelC02", false)           // 旧版专用（保留，仅作占位）
    private val noC02         by boolean("NoC02",       false)           // 旧版专用（保留，仅作占位）
    private val autoGapple    by boolean("AutoGapple",  false)           // 吃完后是否自动继续
    private val startColor    by color ("ProgressStartColor", Color4b(76, 157, 240, 255))
    private val endColor      by color ("ProgressEndColor",   Color4b(53, 200, 167, 255))

    // --------------------------------------------------------------------
    // 内部状态（与旧实现保持 1:1）
    // --------------------------------------------------------------------
    private var slot = -1                      // Hot‑bar index (0‑8) of the found golden apple
    private var c03s = 0                        // 已发送的 C03（移动）包计数，满 32 时完成一次吃
    private var eating = false                 // 是否已经进入“吃东西”状态
    private var pulsing = false                // 与原代码的 “pulsing” 同义（标记正在吃的动画阶段）
    private var canStart = false               // Blink 是否已经准备好
    private var stuckPosX = 0.0
    private var stuckPosY = 0.0
    private var stuckPosZ = 0.0

    // --------------------------------------------------------------------
    // 辅助函数
    // --------------------------------------------------------------------
    /** 在玩家的热键栏 (0‑8) 中寻找普通金苹果或附魔金苹果。 */
    private fun findGappleSlot(): Int {
        for (hotbarSlot in 0 until 9) {
            val stack = player.inventory.getItem(hotbarSlot)
            if (stack.item == Items.GOLDEN_APPLE || stack.item == Items.ENCHANTED_GOLDEN_APPLE) {
                return hotbarSlot
            }
        }
        return -1
    }

    /** 把玩家的速度（velocity）强制设为 0，用来实现 “Stuck”。 */
    private fun applyStuckIfEnabled() {
        if (stuckEnabled && eating) {
            player.setDeltaMovement(0.0, 0.0, 0.0)
        }
    }

    /** 发送一个“保持活跃”的空移动包（原实现的 Blink 里会持续发送 C03）。 */
    private fun sendKeepAlivePacket() {
        // 这里使用新版的网络工具发送一个普通的移动包（不携带位置），等价于原来的 C03PacketPlayer
        mc.connection?.sendPacketSilently(
            net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly(
                player.onGround(),
                player.horizontalCollision
            )
        )
    }

    /** 完成一次金苹果的消费：<br>
     * 1. 发送切换至金苹果的 “HeldItemChange”。<br>
     * 2. 使用金苹果（调用游戏内的 `useItem`）。<br>
     * 3. 立即切回原来的手持槽位。 */
    private fun finishConsume() {
        // 1️⃣ 切到金苹果槽位（ServerboundSetCarriedItemPacket）
        network.sendHeldItemChange(slot)

        // 2️⃣ 正式使用金苹果 – 这里直接调用游戏的 useItem 方法，等价于右键吃
        interaction.useItem(InteractionHand.MAIN_HAND)

        // 3️⃣ 恢复原手（玩家原来的 selectedSlot）
        network.sendHeldItemChange(player.inventory.selectedSlot)
    }

    // --------------------------------------------------------------------
    // 生命周期回调
    // --------------------------------------------------------------------
    override fun onEnable() {
        // 初始化计数并尝试寻找金苹果
        c03s = 0
        slot = findGappleSlot()
    }

    override fun onDisable() {
        // 退出时清理状态
        eating = false
        pulsing = false
        canStart = false
        c03s = 0
        slot = -1
    }

    // --------------------------------------------------------------------
    // 事件处理（使用新 API 的 handler）
    // --------------------------------------------------------------------
    /** 主循环 – 负责检测血量、控制 Blink、发送 Keep‑Alive、完成消费等。 */
    private val tickHandler = handler<PreTickEvent> {
        // 1️⃣ 先检查玩家是否存活
        if (player.isDeadOrDying) {
            // 死亡或离线直接关闭模块
            this@ModuleGapple.toggle(false)
            return@handler
        }

        // 2️⃣ 血量低于阈值 → 进入/保持 “eating” 状态
        if (player.health < heal) {
            if (!eating) {
                // 第一次进入吃东西的逻辑：重新寻找金苹果、重置计数
                slot = findGappleSlot()
                if (slot == -1) {
                    // 没有金苹果 → 直接关闭模块（旧实现同样会关闭）
                    this@ModuleGapple.toggle(false)
                    return@handler
                }
                c03s = 0
                eating = true
                pulsing = false
                canStart = false
            }

            // 3️⃣ 处理 “Stuck” （如果打开则冻结玩家位置）
            if (stuckEnabled) {
                if (!pulsing) {
                    // 记录下第一次进入 Stuck 时的位置
                    stuckPosX = player.x
                    stuckPosY = player.y
                    stuckPosZ = player.z
                }
                applyStuckIfEnabled()
            }

            // 4️⃣ 模拟原来的 Blink 行为：每 N tick 发送一次保持活跃的 C03
            if (mc.tickCount % sendDelay == 0) {
                sendKeepAlivePacket()
            }

            // 5️⃣ 计数到达 32（对应原来的 C03S >= 32）时完成一次吃的循环
            c03s++
            if (c03s >= 32) {
                // 结束一次吃金苹果的完整过程
                finishConsume()

                // 重置计数、进入 pulsing（用来显示进度条），并决定是否自动继续
                pulsing = true
                eating = false
                c03s = 0
                if (autoGapple) {
                    // 继续寻找下一颗金苹果
                    slot = findGappleSlot()
                    if (slot != -1) {
                        // 保持 `eating` 为 true，下一 tick 会继续
                        eating = true
                    } else {
                        // 没有金苹果了 → 关闭模块
                        this@ModuleGapple.toggle(false)
                    }
                } else {
                    this@ModuleGapple.toggle(false)
                }
            }

        } else {
            // 血量恢复到阈值以上 → 立即结束吃东西
            eating = false
            pulsing = false
            canStart = false
            c03s = 0
        }
    }

    /** 当玩家正在吃东西且启用了 “StopMove” 时，将所有移动输入清零。 */
    private val movementInputHandler = handler<MovementInputEvent> {
        if (eating && stopMove) {
            it.originalInput.forwardImpulse = 0f
            it.originalInput.leftImpulse = 0f
            it.originalInput.up = false
            it.originalInput.down = false
            it.originalInput.left = false
            it.originalInput.right = false
        }
    }

    /** UI – 在屏幕左下方绘制进度条（只在 eating/pulsing 状态下显示）。 */
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (eating || pulsing) {
            val ctx = event.context
            val width = ctx.scaledWidth.toFloat()
            val height = ctx.scaledHeight.toFloat()
            drawProgressBar(width, height)
        }
    }

    // --------------------------------------------------------------------
    // UI 渲染
    // --------------------------------------------------------------------
    private fun drawProgressBar(screenW: Float, screenH: Float) {
        // 与原版相同的 140px 长度、7px 高度的进度条
        val barWidth = 140f
        val barHeight = 7f
        val startY = (screenH / 4f) * 3f
        val startX = (screenW / 2f) - (barWidth / 2f)

        // 进度 0‑1（使用 c03s 计数，满 32 表示吃完）
        val progress = (c03s / 32f).coerceIn(0f, 1f)
        val filled = barWidth * progress

        // 背景（半透明黑）
        drawRoundedRect(
            startX - 2f, startY - 2f,
            startX + barWidth + 2f, startY + barHeight + 2f,
            radius = 3f,
            fillColor = Color4b(0, 0, 0, 128)
        )

        // 进度条本体——这里用单色（startColor），如果想要渐变可以自行插值
        if (filled > 0f) {
            drawRoundedRect(
                startX, startY,
                startX + filled, startY + barHeight,
                radius = 2f,
                fillColor = startColor
            )
        }

        // 进度文字（使用原来的 Minecraft 字体渲染）
        val percent = (progress * 100).toInt()
        net.ccbluex.liquidbounce.ui.font.Fonts.DEFAULT.drawString(
            "$percent%", startX + barWidth + 5f, startY,
            Color4b.WHITE.argb
        )
    }
}