/*
 * MarlowCrystalAura —— 移植 marlowwwclient (EclipseWare) CrystalAura.java
 *
 * 原版模块名是 "CrystalAura"，但 LB 已有 ModuleCrystalAura，注册表按名字去重
 * （ModuleManager.addModule 重复即 error），因此这里命名为 MarlowCrystalAura。
 *
 * 逐项对照原版：
 *
 * 设置项（名称/默认值/范围/是否整数，全部照抄）：
 *  - Place Range        4.5  (1.0..6.0)      浮点
 *  - Break Range        4.5  (1.0..6.0)      浮点
 *  - Walls Range        3.0  (0.0..6.0)      浮点
 *  - Target Range      10.0  (4.0..16.0)     浮点
 *  - Place Delay        1.0  (0.0..10.0)     整数（原版 Setting 第 7 参 true = 整型）
 *  - Break Delay        1.0  (0.0..10.0)     整数
 *  - Min Damage         5.0  (0.0..20.0)     浮点
 *  - Max Self Damage    8.0  (0.0..20.0)     浮点
 *  - Face Place Health  8.0  (0.0..20.0)     浮点
 *  - Anti-Suicide       true                 布尔
 *  - Rotate             true                 布尔
 *  - Swap               "Silent" | "Normal" | "None"
 *  - Auto Obsidian      true
 *  - 1.13+ Place        true
 *  - Exclude Bedrock    false
 *  - Require held       false
 *  - Pause On Eat       true
 *  - Inhibit            true
 *
 * onTick 流程（顺序一致）：
 *  1. 无 player/level/gameMode 返回；有界面或已死亡返回
 *  2. PauseOnEat 且正在使用物品 → 返回
 *  3. RequireHeld 且没拿水晶 → 返回
 *  4. placedAt 清理 tick - t > 20；attackedIds 清理 tick - t > 10
 *  5. placeTicks / breakTicks 自减
 *  6. 选目标（见 findTarget）
 *  7. pending 非空：stillValid 失败则清空；否则 aim(aimPoint) 成功就 execute 并 return；
 *     aim 未完成也 return
 *  8. 无目标返回
 *  9. breakTicks == 0 时找 bestCrystal → queue(BREAK) → 立即尝试 aim+execute → return
 * 10. placeTicks == 0 时找 bestPlacement → queue(PLACE) → 尝试 aim+execute → return
 *     否则 Auto Obsidian 且背包有黑曜石 → bestObsidian → queue(OBBY) → 尝试 aim+execute
 *
 * 伤害计算（explosionDamage）：
 *  - diameter 12，dist = sqrt(distanceToSqr(explosion)) / 12，> 1 返回 0
 *  - impact = (1 - dist) * exposure
 *  - damage = (impact^2 + impact) / 2 * 7 * 12 + 1，再 * 1.5
 *  - armor = getArmorValue()，toughness = ARMOR_TOUGHNESS 属性
 *  - f = clamp(armor - damage / (2 + toughness / 4), armor * 0.2, 20)
 *  - damage *= 1 - f / 25
 *  - 抗性提升：damage *= max(0, 1 - (amplifier + 1) * 0.2)
 *  - 最后 max(0, damage)
 *
 * 暴露度（exposure）：按 AABB 三轴步进取采样点，clip 到爆炸点，MISS 计为命中，
 * 返回 hits / total。
 *
 * 目标筛选：跳过自己、非存活、旁观、创造；跳过 FriendManager / TargetFilterManager /
 * AntiBot / Teams；distanceTo > targetRange 跳过；score = dist * 2 + health + absorption，
 * 取最小。
 *
 * 破坏条件：距离 <= breakRange；无视线时距离必须 <= wallRange。
 * 破坏评分：score = dmg - self * 0.5 + (自己放的水晶 ? 2 : 0)，取最大；
 *  Inhibit 开启时跳过 attackedIds 里记录过的水晶。
 *
 * 放置条件：底座必须是黑曜石，或（基岩且未开 Exclude Bedrock）；上方空；
 *  - 1.13+ Place 关闭时要求上方两格都空
 *  - 上方两格 AABB 内不能有存活实体（attackedIds 里记录的水晶除外）
 *  - 眼睛到 (x+0.5, y+1, z+0.5) 距离 <= placeRange，且该面可见或距离 <= wallRange
 *
 * 黑曜石放置：目标脚下 ±2/±1/0 范围，pos 可替换、下方有碰撞箱、上方为空、
 * 下方在放置范围内、pos 上方两格 AABB 无实体，然后按对目标伤害取最大。
 *
 * 最小值判定 meetsMin：
 *  - dmg >= 目标生命 + 吸收 → 通过
 *  - 目标生命 + 吸收 <= Face Place Health → dmg >= 1.0
 *  - 否则 dmg >= Min Damage
 *
 * 自伤判定 selfSafe：self <= Max Self Damage；Anti-Suicide 时 self 必须
 * < 自身生命 + 吸收 - 1。
 *
 * 静默旋转：原版 RotationManager 用「鼠标灵敏度 GCD 量化 + 指数衰减 + 随机停顿」的
 * 私有实现，并且自己发旋转包。这里把该算法的数学部分（getGCD / snapToGCD 语义 /
 * keepRotated / tick / startReturn / visualTick 的量化）原样搬过来，
 * 但把「发包」交给 LB 的 RotationManager：每 tick 用算出的
 * currentServerYaw/Pitch 组一个无 processor 的 RotationTarget（process() 在
 * processors 为空时直接返回目标旋转，等价于瞬时到位），MovementCorrection 用
 * SILENT，从而保留原版的步进序列与静默语义。
 *
 * 已知差异（框架限制，不是移植遗漏）：
 *  - 原版 TargetFilterManager 是本地 JSON 名单；LB 无对应设施，这里改用
 *    FriendManager + ModuleAntiBot.isBot + scoreboard 同盟（player.isAlliedTo）。
 *  - 原版 AutoTotem.triggerInputPause() 会置 60ms 输入暂停并取消疾跑；LB 无该
 *    全局输入暂停，这里只做 setSprinting(false)。
 *  - 原版 ModuleUtils.switchToSlot 会同时改客户端选中槽并发
 *    ServerboundSetCarriedItemPacket；LB 的 Silent 模式改用 SilentHotbar，
 *    服务端看到目标槽、客户端不变，语义等价但实现路径不同。
 *  - 原版 onTick 里 `crystalSlot() != -2` 的判断永远不会为假（crystalSlot 只返回
 *    -1 / 40 / 槽位），这里保留等价行为：只要 placeTicks == 0 就尝试放置。
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.findClosestSlot
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

object MarlowCrystalAura : ClientModule(
    "MarlowCrystalAura",
    ModuleCategories.COMBAT,
    aliases = listOf("EclipseCrystalAura"),
) {

    private enum class Action { NONE, BREAK, PLACE, OBBY }

    private enum class SwapMode(override val tag: String) : Tagged {
        SILENT("Silent"), NORMAL("Normal"), NONE("None"),
    }

    // ===== 设置项（与原版一一对应）=====
    private val placeRange by float("Place Range", 4.5f, 1.0f..6.0f)
    private val breakRange by float("Break Range", 4.5f, 1.0f..6.0f)
    private val wallRange by float("Walls Range", 3.0f, 0.0f..6.0f)
    private val targetRange by float("Target Range", 10.0f, 4.0f..16.0f)
    private val placeDelay by int("Place Delay", 1, 0..10)
    private val breakDelay by int("Break Delay", 1, 0..10)
    private val minDamage by float("Min Damage", 5.0f, 0.0f..20.0f)
    private val maxSelfDamage by float("Max Self Damage", 8.0f, 0.0f..20.0f)
    private val facePlaceHealth by float("Face Place Health", 8.0f, 0.0f..20.0f)
    private val antiSuicide by boolean("Anti-Suicide", true)
    private val rotate by boolean("Rotate", true)
    private val swapMode by enumChoice("Swap", SwapMode.SILENT)
    private val autoObsidian by boolean("Auto Obsidian", true)
    private val placeOneThirteen by boolean("1.13+ Place", true)
    private val excludeBedrock by boolean("Exclude Bedrock", false)
    private val requireHeld by boolean("Require held", false)
    private val pauseOnEat by boolean("Pause On Eat", true)
    private val inhibit by boolean("Inhibit", true)

    // ===== 运行状态 =====
    private var placeTicks = 0
    private var breakTicks = 0
    private var pending = Action.NONE
    private var pendingPos: BlockPos? = null
    private var pendingCrystal: EndCrystal? = null
    private var aimedTicks = 0
    private var target: Player? = null

    private val placedAt = HashMap<BlockPos, Int>()
    private val attackedIds = HashMap<Int, Int>()

    // ===== 原版 RotationManager 的等价实现 =====
    private object SilentRotation {
        private val rand = Random()
        private var active = false
        private var keepMode = false
        private var returning = false
        private var movementCorrection = false
        private var currentYaw = 0f
        private var currentPitch = 0f
        private var returnFromYaw = 0f
        private var returnFromPitch = 0f
        private var returnTick = 0
        private var returnDuration = 0
        private var ticksSinceLastKeep = 999
        private var pauseTicksLeft = 0

        fun getGCD(): Float {
            val sens = mc.options.sensitivity().get()
            val f = sens * 0.6 + 0.2
            return (f * f * f * 8.0 * 0.15).toFloat()
        }

        fun keepRotated(yaw: Float, pitch: Float, stepDegrees: Float, moveCorrect: Boolean) {
            val player = mc.player ?: return

            val targetYaw = Mth.wrapDegrees(yaw)
            val targetPitch = Mth.clamp(pitch, -90f, 90f)
            movementCorrection = moveCorrect
            ticksSinceLastKeep = 0
            keepMode = true

            if (!active || returning) {
                currentYaw = player.yRot
                currentPitch = player.xRot
                active = true
                returning = false
                pauseTicksLeft = 0
            }

            if (pauseTicksLeft > 0) {
                pauseTicksLeft--
                return
            }
            if (rand.nextFloat() < 0.08f) {
                pauseTicksLeft = 1 + rand.nextInt(2)
                return
            }

            var gcd = getGCD()
            if (gcd < 0.001f) gcd = 0.15f

            val yawDiff = Mth.wrapDegrees(targetYaw - currentYaw)
            val pitchDiff = targetPitch - currentPitch

            var yawDesired = yawDiff * (0.15f + rand.nextFloat() * 0.30f)
            var pitchDesired = pitchDiff * (0.15f + rand.nextFloat() * 0.30f)

            val yawMax = stepDegrees * (0.7f + rand.nextFloat() * 0.6f)
            val pitchMax = stepDegrees * 0.8f * (0.7f + rand.nextFloat() * 0.6f)
            if (abs(yawDesired) > yawMax) yawDesired = sign(yawDesired) * yawMax
            if (abs(pitchDesired) > pitchMax) pitchDesired = sign(pitchDesired) * pitchMax

            val curveAmount = minOf(abs(yawDiff), 30f) * 0.08f
            yawDesired += (rand.nextFloat() - 0.5f) * curveAmount

            var yawSteps = (yawDesired / gcd).roundToInt()
            var pitchSteps = (pitchDesired / gcd).roundToInt()

            yawSteps += rand.nextInt(3) - 1
            pitchSteps += rand.nextInt(3) - 1

            val maxYawSteps = (yawDiff / gcd).roundToInt()
            val maxPitchSteps = (pitchDiff / gcd).roundToInt()
            if (abs(yawSteps) > abs(maxYawSteps)) yawSteps = maxYawSteps
            if (abs(pitchSteps) > abs(maxPitchSteps)) pitchSteps = maxPitchSteps

            if (yawDiff > 0 && yawSteps < 0) yawSteps = 0
            if (yawDiff < 0 && yawSteps > 0) yawSteps = 0
            if (pitchDiff > 0 && pitchSteps < 0) pitchSteps = 0
            if (pitchDiff < 0 && pitchSteps > 0) pitchSteps = 0

            currentYaw += yawSteps * gcd
            currentPitch += pitchSteps * gcd
            currentPitch = Mth.clamp(currentPitch, -90f, 90f)
        }

        fun tick() {
            if (!active) return
            val player = mc.player
            if (player == null) {
                hardCancel()
                return
            }

            ticksSinceLastKeep++

            if (!returning && ticksSinceLastKeep > 2) {
                if (keepMode) {
                    startReturn()
                } else {
                    hardCancel()
                    return
                }
            }

            if (returning) {
                returnTick++
                var progress = minOf(1f, returnTick.toFloat() / returnDuration)
                progress = progress * progress * (3f - 2f * progress)
                val actualYaw = player.yRot
                val actualPitch = player.xRot
                currentYaw = returnFromYaw + Mth.wrapDegrees(actualYaw - returnFromYaw) * progress
                currentPitch = returnFromPitch + (actualPitch - returnFromPitch) * progress

                if (returnTick >= returnDuration) {
                    currentYaw = player.yRot
                    currentPitch = player.xRot
                    hardCancel()
                }
            }
        }

        /** 原版在这里自己发包；这里改为向 LB 提交无 processor 的旋转目标 */
        fun submit(owner: ClientModule) {
            if (!active) return
            if (mc.player == null) return
            RotationManager.setRotationTarget(
                RotationTarget(
                    Rotation(currentYaw, currentPitch),
                    processors = emptyList(),
                    ticksUntilReset = 2,
                    resetThreshold = 0f,
                    considerInventory = false,
                    movementCorrection = if (movementCorrection) {
                        MovementCorrection.SILENT
                    } else {
                        MovementCorrection.OFF
                    },
                ),
                Priority.IMPORTANT_FOR_USAGE_3,
                owner,
            )
        }

        private fun startReturn() {
            if (returning) return
            val player = mc.player
            if (player == null) {
                hardCancel()
                return
            }

            returning = true
            returnFromYaw = currentYaw
            returnFromPitch = currentPitch
            returnTick = 0

            val yawDiff = abs(Mth.wrapDegrees(player.yRot - currentYaw))
            val pitchDiff = abs(player.xRot - currentPitch)
            val totalDiff = max(yawDiff, pitchDiff)
            returnDuration = max(4, minOf(10, (totalDiff / 15f).toInt() + 4))
        }

        fun requestReturn() {
            if (!active) return
            if (returning) return
            startReturn()
        }

        fun isActive() = active

        fun hardCancel() {
            active = false
            keepMode = false
            returning = false
            movementCorrection = false
            ticksSinceLastKeep = 999
        }

        fun serverYaw() = currentYaw
        fun serverPitch() = currentPitch
    }

    override fun onEnabled() {
        reset()
    }

    override fun onDisabled() {
        reset()
        SilentRotation.requestReturn()
    }

    private fun reset() {
        placeTicks = 0
        breakTicks = 0
        pending = Action.NONE
        pendingPos = null
        pendingCrystal = null
        aimedTicks = 0
        target = null
        placedAt.clear()
        attackedIds.clear()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val player = mc.player ?: return@handler
        val level = mc.level ?: return@handler
        if (mc.gameMode == null) return@handler
        if (mc.gui.screen() != null || player.isDeadOrDying) return@handler
        if (pauseOnEat && player.isUsingItem) return@handler
        if (requireHeld && !holdingCrystal()) return@handler

        // 旋转推进：原版在 START_CLIENT_TICK 调 tick()
        SilentRotation.tick()

        val tick = player.tickCount
        placedAt.values.removeIf { tick - it > 20 }
        attackedIds.values.removeIf { tick - it > 10 }
        if (placeTicks > 0) placeTicks--
        if (breakTicks > 0) breakTicks--

        target = findTarget()

        if (pending != Action.NONE) {
            if (!stillValid()) {
                pending = Action.NONE
            } else if (aim(aimPoint())) {
                execute()
                SilentRotation.submit(this)
                return@handler
            } else {
                SilentRotation.submit(this)
                return@handler
            }
        }

        val currentTarget = target
        if (currentTarget == null) {
            SilentRotation.submit(this)
            return@handler
        }

        val crystal = if (breakTicks == 0) bestCrystal(currentTarget) else null
        if (crystal != null) {
            queue(Action.BREAK, null, crystal)
            if (aim(aimPoint())) execute()
            SilentRotation.submit(this)
            return@handler
        }

        if (placeTicks == 0 && crystalSlot() != -2) {
            val place = bestPlacement(currentTarget)
            if (place != null) {
                queue(Action.PLACE, place, null)
                if (aim(aimPoint())) execute()
                SilentRotation.submit(this)
                return@handler
            }
            if (autoObsidian && obsidianSlot() != -1) {
                val obby = bestObsidian(currentTarget)
                if (obby != null) {
                    queue(Action.OBBY, obby, null)
                    if (aim(aimPoint())) execute()
                }
            }
        }

        SilentRotation.submit(this)
    }

    private fun queue(action: Action, pos: BlockPos?, crystal: EndCrystal?) {
        if (pending != action || pos != pendingPos || crystal !== pendingCrystal) aimedTicks = 0
        pending = action
        pendingPos = pos
        pendingCrystal = crystal
    }

    private fun stillValid(): Boolean = when (pending) {
        Action.BREAK -> pendingCrystal?.let { it.isAlive && canBreak(it) } == true
        Action.PLACE -> pendingPos?.let { canPlaceCrystal(it) && inPlaceRange(it) } == true
        Action.OBBY -> pendingPos?.let {
            mc.level?.getBlockState(it)?.canBeReplaced() == true && inPlaceRange(it)
        } == true
        Action.NONE -> false
    }

    private fun aimPoint(): Vec3 {
        val player = mc.player
        return when (pending) {
            Action.BREAK -> pendingCrystal?.boundingBox?.center ?: player?.eyePosition ?: Vec3.ZERO
            Action.PLACE -> pendingPos?.let { Vec3(it.x + 0.5, it.y + 1.0, it.z + 0.5) } ?: Vec3.ZERO
            Action.OBBY -> pendingPos?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) } ?: Vec3.ZERO
            Action.NONE -> player?.eyePosition ?: Vec3.ZERO
        }
    }

    private fun aim(point: Vec3): Boolean {
        if (!rotate) return true
        val player = mc.player ?: return false
        val rots = rotations(player.eyePosition, point)
        SilentRotation.keepRotated(rots[0], rots[1], 180f, true)

        val yawDiff = abs(Mth.wrapDegrees(rots[0] - SilentRotation.serverYaw()))
        val pitchDiff = abs(rots[1] - SilentRotation.serverPitch())
        if (yawDiff < 2f && pitchDiff < 2f) return true
        aimedTicks++
        return aimedTicks >= 2
    }

    private fun execute() {
        when (pending) {
            Action.BREAK -> pendingCrystal?.let { doBreak(it) }
            Action.PLACE -> pendingPos?.let { doPlace(it) }
            Action.OBBY -> pendingPos?.let { doObsidian(it) }
            Action.NONE -> {}
        }
        pending = Action.NONE
        pendingPos = null
        pendingCrystal = null
        aimedTicks = 0
    }

    private fun doBreak(crystal: EndCrystal) {
        val player = mc.player ?: return
        triggerInputPause()
        mc.gameMode?.attack(player, crystal)
        attackSwing()
        attackedIds[crystal.id] = player.tickCount
        breakTicks = breakDelay
    }

    private fun doPlace(base: BlockPos) {
        val slot = crystalSlot()
        if (slot == -1) return
        val hitVec = Vec3(base.x + 0.5, base.y + 1.0, base.z + 0.5)
        if (use(slot, BlockHitResult(hitVec, Direction.UP, base, false))) {
            placedAt[base.above()] = mc.player?.tickCount ?: 0
        }
        placeTicks = placeDelay
    }

    private fun doObsidian(pos: BlockPos) {
        val slot = obsidianSlot()
        if (slot == -1) return
        val support = pos.below()
        val hitVec = Vec3(support.x + 0.5, support.y + 1.0, support.z + 0.5)
        use(slot, BlockHitResult(hitVec, Direction.UP, support, false))
        placeTicks = placeDelay
    }

    private fun use(slot: Int, hit: BlockHitResult): Boolean {
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false

        triggerInputPause()

        var hand = InteractionHand.MAIN_HAND
        val original = player.inventory.selectedSlot
        var swapped = false

        if (slot == 40) {
            hand = InteractionHand.OFF_HAND
        } else if (slot != original) {
            when (swapMode) {
                SwapMode.NONE -> return false
                SwapMode.NORMAL -> {
                    player.inventory.selectedSlot = slot
                    mc.connection?.send(ServerboundSetCarriedItemPacket(slot))
                }
                SwapMode.SILENT -> {
                    SilentHotbar.selectSlotSilently(this, slot, 1)
                }
            }
            swapped = true
        }

        val result: InteractionResult = gameMode.useItemOn(player, hand, hit)
        if (result.consumesAction()) {
            player.swing(hand, SwingAnimation.DEFAULT, true)
        }
        if (swapped && swapMode == SwapMode.SILENT) {
            SilentHotbar.resetSlot(this)
        }
        return result.consumesAction()
    }

    private fun crystalSlot(): Int {
        val player = mc.player ?: return -1
        if (player.offhandItem.`is`(Items.END_CRYSTAL)) return 40
        if (player.mainHandItem.`is`(Items.END_CRYSTAL)) return player.inventory.selectedSlot
        if (swapMode == SwapMode.NONE) return -1
        return findHotbarSlot(Items.END_CRYSTAL)
    }

    private fun obsidianSlot(): Int = findHotbarSlot(Items.OBSIDIAN)

    private fun findHotbarSlot(item: net.minecraft.world.item.Item): Int {
        val player = mc.player ?: return -1
        for (i in 0 until 9) {
            if (player.inventory.getItem(i).`is`(item)) return i
        }
        return -1
    }

    private fun holdingCrystal(): Boolean {
        val player = mc.player ?: return false
        return player.mainHandItem.`is`(Items.END_CRYSTAL) || player.offhandItem.`is`(Items.END_CRYSTAL)
    }

    /** 原版 AutoTotem.triggerInputPause()：置 60ms 输入暂停并取消疾跑；LB 只做取消疾跑 */
    private fun triggerInputPause() {
        mc.player?.setSprinting(false)
    }

    /** 原版 ModuleUtils.attackSwing() */
    private fun attackSwing() {
        val player = mc.player ?: return
        player.swing(InteractionHand.MAIN_HAND, player.mainHandItem.attackAnimation, false)
        mc.connection?.send(ServerboundPunchPacket.INSTANCE)
    }

    private fun rotations(from: Vec3, to: Vec3): FloatArray {
        val diffX = to.x - from.x
        val diffY = to.y - from.y
        val diffZ = to.z - from.z
        val distXZ = sqrt(diffX * diffX + diffZ * diffZ)
        val yaw = Math.toDegrees(atan2(diffZ, diffX)).toFloat() - 90f
        val pitch = -Math.toDegrees(atan2(diffY, distXZ)).toFloat()
        return floatArrayOf(yaw, pitch)
    }

    private fun findTarget(): Player? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val range = targetRange.toDouble()

        var best: Player? = null
        var bestScore = Double.MAX_VALUE

        for (p in level.players()) {
            if (p === player) continue
            if (!p.isAlive || p.isSpectator || p.isCreative) continue
            if (FriendManager.isFriend(p)) continue
            if (ModuleAntiBot.isBot(p)) continue
            if (player.isAlliedTo(p)) continue

            val d = player.distanceTo(p)
            if (d > range) continue

            val score = d * 2.0 + p.health + p.absorptionAmount
            if (score < bestScore) {
                bestScore = score
                best = p
            }
        }
        return best
    }

    private fun canBreak(crystal: EndCrystal): Boolean {
        val player = mc.player ?: return false
        val center = crystal.boundingBox.center
        val dist = player.eyePosition.distanceTo(center)
        if (dist > breakRange) return false
        if (!hasLineOfSight(player.eyePosition, center) && dist > wallRange) return false
        return true
    }

    private fun hasLineOfSight(from: Vec3, to: Vec3): Boolean {
        val level = mc.level ?: return false
        val entity = mc.player ?: return false
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
        return hit.type == HitResult.Type.MISS
    }

    private fun bestCrystal(currentTarget: Player): EndCrystal? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val r = breakRange.toDouble()

        var best: EndCrystal? = null
        var bestScore = 0.0

        for (crystal in level.getEntitiesOfClass(EndCrystal::class.java, player.boundingBox.inflate(r + 1.0))) {
            if (!crystal.isAlive || !canBreak(crystal)) continue
            if (inhibit && attackedIds.containsKey(crystal.id)) continue

            val pos = crystal.position()
            val self = explosionDamage(player, pos)
            if (!selfSafe(self)) continue

            val dmg = explosionDamage(currentTarget, pos)
            val ours = placedAt.containsKey(crystal.blockPosition())
            if (!ours && !meetsMin(dmg, currentTarget)) continue

            val score = dmg - self * 0.5 + if (ours) 2.0 else 0.0
            if (score > bestScore) {
                bestScore = score
                best = crystal
            }
        }
        return best
    }

    private fun inPlaceRange(base: BlockPos): Boolean {
        val player = mc.player ?: return false
        val top = Vec3(base.x + 0.5, base.y + 1.0, base.z + 0.5)
        val dist = player.eyePosition.distanceTo(top)
        if (dist > placeRange) return false
        return canSeeFace(base, top) || dist <= wallRange
    }

    private fun canSeeFace(base: BlockPos, point: Vec3): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val hit = level.clip(ClipContext(player.eyePosition, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type == HitResult.Type.MISS || hit.blockPos == base
    }

    private fun canPlaceCrystal(base: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state: BlockState = level.getBlockState(base)
        val isObsidian = state.block === Blocks.OBSIDIAN
        val isBedrock = state.block === Blocks.BEDROCK && !excludeBedrock
        if (!isObsidian && !isBedrock) return false

        val up = base.above()
        if (!level.isEmptyBlock(up)) return false
        if (!placeOneThirteen && !level.isEmptyBlock(up.above())) return false

        val box = AABB(up).expandTowards(0.0, 1.0, 0.0)
        for (e in level.getEntities(null as Entity?, box) { true }) {
            if (e is EndCrystal && attackedIds.containsKey(e.id)) continue
            if (e.isAlive) return false
        }
        return true
    }

    private fun bestPlacement(currentTarget: Player): BlockPos? {
        val player = mc.player ?: return null
        val r = ceil(placeRange.toDouble()).toInt()
        val origin = player.blockPosition()

        var best: BlockPos? = null
        var bestScore = 0.0

        for (x in -r..r) {
            for (y in -r..r) {
                for (z in -r..r) {
                    val base = origin.offset(x, y, z)
                    if (!canPlaceCrystal(base) || !inPlaceRange(base)) continue

                    val explosion = Vec3(base.x + 0.5, base.y + 1.0, base.z + 0.5)
                    val self = explosionDamage(player, explosion)
                    if (!selfSafe(self)) continue

                    val dmg = explosionDamage(currentTarget, explosion)
                    if (!meetsMin(dmg, currentTarget)) continue

                    val score = dmg - self * 0.5
                    if (score > bestScore) {
                        bestScore = score
                        best = base
                    }
                }
            }
        }
        return best
    }

    private fun bestObsidian(currentTarget: Player): BlockPos? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val feet = currentTarget.blockPosition()

        var best: BlockPos? = null
        var bestScore = 0.0

        for (x in -2..2) {
            for (z in -2..2) {
                for (y in -1..0) {
                    val pos = feet.offset(x, y, z)
                    if (!level.getBlockState(pos).canBeReplaced()) continue
                    val support = level.getBlockState(pos.below())
                    if (support.getCollisionShape(level, pos.below()).isEmpty) continue
                    if (!level.isEmptyBlock(pos.above())) continue
                    if (!inPlaceRange(pos.below())) continue
                    if (level.getEntities(null as Entity?, AABB(pos).expandTowards(0.0, 2.0, 0.0)) { true }.isNotEmpty()) continue

                    val explosion = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                    val self = explosionDamage(player, explosion)
                    if (!selfSafe(self)) continue

                    val dmg = explosionDamage(currentTarget, explosion)
                    if (dmg > bestScore && meetsMin(dmg, currentTarget)) {
                        bestScore = dmg
                        best = pos
                    }
                }
            }
        }
        return best
    }

    private fun meetsMin(dmg: Double, currentTarget: Player?): Boolean {
        val t = currentTarget ?: return false
        val health = t.health + t.absorptionAmount
        if (dmg >= health) return true
        if (health <= facePlaceHealth) return dmg >= 1.0
        return dmg >= minDamage
    }

    private fun selfSafe(self: Double): Boolean {
        val player = mc.player ?: return false
        if (self > maxSelfDamage) return false
        if (antiSuicide && self >= player.health + player.absorptionAmount - 1.0) return false
        return true
    }

    private fun explosionDamage(entity: LivingEntity?, explosion: Vec3): Double {
        if (entity == null) return 0.0
        val diameter = 12.0
        val dist = sqrt(entity.distanceToSqr(explosion)) / diameter
        if (dist > 1.0) return 0.0

        val exposure = exposure(explosion, entity)
        val impact = (1.0 - dist) * exposure
        var damage = (impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0
        damage *= 1.5

        val armor = entity.armorValue.toDouble()
        val toughness = entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS)
        val f = Mth.clamp(armor - damage / (2.0 + toughness / 4.0), armor * 0.2, 20.0)
        damage *= 1.0 - f / 25.0

        if (entity.hasEffect(MobEffects.RESISTANCE)) {
            val amp = (entity.getEffect(MobEffects.RESISTANCE)?.amplifier ?: 0) + 1
            damage *= max(0.0, 1.0 - amp * 0.2)
        }
        return max(0.0, damage)
    }

    private fun exposure(source: Vec3, entity: Entity): Double {
        val level = mc.level ?: return 0.0
        val box = entity.boundingBox

        val sx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0)
        val sy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0)
        val sz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0)
        val ox = (1.0 - floor(1.0 / sx) * sx) / 2.0
        val oz = (1.0 - floor(1.0 / sz) * sz) / 2.0

        var hits = 0
        var total = 0

        var a = 0.0
        while (a <= 1.0) {
            var b = 0.0
            while (b <= 1.0) {
                var c = 0.0
                while (c <= 1.0) {
                    val point = Vec3(
                        Mth.lerp(a, box.minX, box.maxX) + ox,
                        Mth.lerp(b, box.minY, box.maxY),
                        Mth.lerp(c, box.minZ, box.maxZ) + oz,
                    )
                    val hit = level.clip(ClipContext(point, source, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
                    if (hit.type == HitResult.Type.MISS) hits++
                    total++
                    c += sz
                }
                b += sy
            }
            a += sx
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }
}