/*
 * ============================================================================
 *  ModuleTargetInfo —— 仿 Opal v2 TargetInfoElement 的目标信息 HUD (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39)
 *  修改: 默认位置居中偏右下，自定义 X/Y 坐标，背景不透明度独立控制并修复闪烁
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.piglin.Piglin
import net.minecraft.world.entity.monster.skeleton.Skeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import java.text.DecimalFormat
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleTargetInfo : ClientModule("TargetInfo", ModuleCategories.RENDER) {
init {
    enabled = true
}

    /* ============================= 可调节项 ============================= */

    private enum class TargetMode(override val tag: String) : Tagged {
        KILL_AURA("KillAura"), LAST_ATTACK("LastAttack"), BOTH("Both")
    }

    private enum class HealthBarMode(override val tag: String) : Tagged {
        GRADIENT("Gradient"), SOLID("Solid"), NONE("None")
    }

    // —— 布局 ——
    // 默认位置：屏幕中心偏右下方（1920x1080 参考：X≈960, Y≈540）
    private val targetInfoX by int("Position X", 611, 0..2000)       // 自定义 X 坐标，默认居中偏右
    private val targetInfoY by int("Position Y", 280, 0..1200)       // 自定义 Y 坐标，默认居中偏下
    private val scaleValue by float("Scale", 1.2f, 0.5f..2f)            // 整体缩放
    private val background by boolean("Background", true)              // 面板背景
    // 背景不透明度独立设置，不受进出场动画影响（修复闪烁问题）
    private val backgroundAlpha by int("Background Alpha", 75, 0..255)
    private val backgroundRadius by int("Background Radius", 5, 0..12)
    private val border by boolean("Border", false)                     // 面板描边

    // —— 目标来源 ——
    private val targetMode by enumChoice("Target Mode", TargetMode.BOTH)
    private val showSelfInChat by boolean("Show Self In Chat", true)

    // —— 内容 ——
    private val showName by boolean("Show Name", true)
    private val showHead by boolean("Show Head", true)
    private val showHealthText by boolean("Show Health Text", true)
    private val showHealthBar by boolean("Show Health Bar", true)
    private val showEquipment by boolean("Show Equipment", false)
    private val showEnchantments by boolean("Show Enchantments", false)
    private val damageTint by boolean("Hurt Flash", true)

    // —— 外观 ——
    private val textShadow by boolean("Text Shadow", true)
    private val nameColor by color("Name Color", Color4b.WHITE)
    private val healthTextColor by color("Health Text Color", Color4b.WHITE)
    private val healthBarMode by enumChoice("Health Bar Mode", HealthBarMode.GRADIENT)
    private val accentColor by color("Accent Color", Color4b(0, 170, 255))
    private val accentColor2 by color("Accent Color 2", Color4b(0, 255, 170))

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 40f, 0.5f..40f)
    private val healthAnimSpeed by float("Health Anim Speed", 20f, 0.5f..20f)

    /* ============================= 内部状态 ============================= */

    private val hpFormat = DecimalFormat("0.#")
    private var displayEntity: LivingEntity? = null
    private var targetAnim = 0f
    private var healthAnim = 0f
    private var lastFrameNs = 0L

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now
        val smooth = (1f - exp(-animationSpeed * frameTime)).coerceIn(0f, 1f)
        val hpSmooth = (1f - exp(-healthAnimSpeed * frameTime)).coerceIn(0f, 1f)

        // 目标解析 + 进出场动画
        val resolved = resolveTarget()
        targetAnim += (if (resolved != null) 1f else 0f - targetAnim) * smooth
        if (resolved != null) {
            displayEntity = resolved
        }
        val entity = displayEntity ?: return@handler
        if (resolved == null && targetAnim < 0.01f) {
            displayEntity = null
            return@handler
        }
        val alpha = targetAnim
        val alphaOf: (Int) -> Int = { (it * alpha).roundToInt().coerceIn(0, 255) }

        // 文本
        val name = entity.displayName?.string ?: entity.name.string
        val hpText = hpFormat.format(entity.health + entity.absorptionAmount)
        val hpTextWidth = font.width(hpText).toFloat()
        val heartWidth = 5f

        // 面板尺寸
        val padding = 4f
        val headSize = 20f
        val headOffset = 24f
        val equipmentWidth = 55f
        val nameWidth = font.width(name).toFloat()

        val contentWidth = maxOf(50f, equipmentWidth, nameWidth + hpTextWidth + heartWidth + 8f)
        val panelWidth = padding * 2 + contentWidth + headOffset + 1f
        val panelHeight = 41f

        // 使用自定义 X/Y 坐标
        val posX = targetInfoX.toFloat()
        val posY = targetInfoY.toFloat()

        context.pose().withPush {
            translate(posX, posY)
            scale(scaleValue, scaleValue)

            // —— 背景 + 边框 ——
            // 关键修复：背景不透明度使用固定的 backgroundAlpha，不乘以 alphaOf（不再闪烁）
            if (background || border) {
                val fill = if (background) Color4b(0, 0, 0, backgroundAlpha) else Color4b.TRANSPARENT
                val outline = if (border) Color4b(255, 255, 255, 120) else Color4b.TRANSPARENT
                if (backgroundRadius > 0) {
                    context.drawRoundedRect(
                        0f, 0f, panelWidth, panelHeight,
                        backgroundRadius.toFloat(), fill, outline, 1f
                    )
                } else {
                    context.drawQuad(0f, 0f, panelWidth, panelHeight, fill, outline)
                }
            }

            // —— 头部 ——
            if (showHead) {
                val texture = headTexture(entity)
                if (texture != null) {
                    val headX = (padding + 0.5f).roundToInt()
                    val headY = padding.roundToInt()
                    val size = headSize.roundToInt()
                    context.blit(
                        texture,
                        headX, headY, headX + size, headY + size,
                        8f / 64f, 16f / 64f, 8f / 64f, 16f / 64f
                    )
                    if (damageTint && entity.hurtTime > 0 && entity.hurtDuration > 0) {
    context.fill(headX, headY, headX + size, headY + size, (0x80 shl 24) or 0x00FF0000)
}
                }
            }

            // —— 名称 ——
            if (showName) {
                context.text(
                    font, name,
                    (padding + headOffset).roundToInt(), 10,
                    nameColor.alpha(alphaOf(255)).argb, textShadow
                )
            }

            // —— 生命值数字 + 心形图标 ——
            if (showHealthText) {
                val hpX = (panelWidth - padding - hpTextWidth).roundToInt()
                context.text(
                    font, hpText,
                    hpX, 10,
                    healthTextColor.alpha(alphaOf(255)).argb, textShadow
                )
                context.drawCircle(
                    hpX - heartWidth - 2f, 7f, 2.5f,
                    colorGetter = { Color4b(255, 194, 71).alpha(alphaOf(255)).argb }
                )
            }

            // —— 生命条 ——
            if (showHealthBar && healthBarMode != HealthBarMode.NONE) {
                val maxHp = entity.maxHealth + entity.absorptionAmount
                val curHp = entity.health + entity.absorptionAmount
                val truePct = if (maxHp > 0f) (curHp / maxHp).coerceIn(0f, 1f) else 0f
                healthAnim += (truePct - healthAnim) * hpSmooth

                val barX = padding + 0.5f
                val barY = 33f
                val barH = 4f
                val barW = panelWidth - padding * 2 - hpTextWidth - heartWidth - 4f
                context.drawRoundedRect(
                    barX, barY, barX + barW, barY + barH, 2f,
                    Color4b(0, 0, 0, alphaOf(150))
                )
                val fillW = healthAnim * barW
                if (fillW > 0.5f) {
                    when (healthBarMode) {
                        HealthBarMode.GRADIENT -> context.fillGradient(
                            barX.roundToInt(), barY.roundToInt(),
                            (barX + fillW).roundToInt(), (barY + barH).roundToInt(),
                            accentColor.alpha(alphaOf(255)).argb,
                            accentColor2.alpha(alphaOf(255)).argb
                        )
                        HealthBarMode.SOLID -> context.drawQuad(
                            barX, barY, barX + fillW, barY + barH,
                            accentColor.alpha(alphaOf(255))
                        )
                        HealthBarMode.NONE -> Unit
                    }
                }
            }

            // —— 装备栏 ——
            if (showEquipment) {
                val slots = EquipmentSlot.VALUES
                    .filter { it.type == EquipmentSlot.Type.HUMANOID_ARMOR } + EquipmentSlot.MAINHAND
                val stacks = slots.reversed().map { entity.getItemBySlot(it) }
                val stackScale = 0.625f
                val boxSize = 11f
                val startX = padding + headOffset - 0.5f
                val boxY = 20f

                stacks.forEachIndexed { index, stack ->
                    val boxX = startX + index * 11f
                    context.drawRoundedRect(
                        boxX, boxY, boxX + boxSize, boxY + boxSize, 1.5f,
                        Color4b(0, 0, 0, alphaOf(50))
                    )
                    if (stack.isEmpty) {
                        return@forEachIndexed
                    }
                    val offset = (boxSize - 16f * stackScale) / 2f
                    context.pose().withPush {
                        translate(boxX + offset, boxY + offset)
                        scale(stackScale, stackScale)
                        context.item(stack, 0, 0)
                        if (showEnchantments) {
                            val short = enchantShort(stack)
                            if (short != null) {
                                context.text(font, short, 2, 7, Color4b.WHITE.alpha(alphaOf(255)).argb, true)
                            }
                        }
                    }
                }
            }
        }
    }

    /* ============================= 工具函数 ============================= */

    private fun resolveTarget(): LivingEntity? {
        var target: LivingEntity? = when (targetMode) {
            TargetMode.KILL_AURA -> if (ModuleKillAura.enabled) KillAuraTargetTracker.target else null
            TargetMode.LAST_ATTACK -> mc.player?.lastHurtMob
            TargetMode.BOTH -> {
                if (ModuleKillAura.enabled) {
                    KillAuraTargetTracker.target ?: mc.player?.lastHurtMob
                } else {
                    mc.player?.lastHurtMob
                }
            }
        }
        if (target != null && target.isDeadOrDying) {
            target = null
        }
        if (target == null && showSelfInChat && mc.gui.screen() is ChatScreen) {
            target = mc.player
        }
        return target
    }

    private fun headTexture(entity: LivingEntity): Identifier? = when (entity) {
        is AbstractClientPlayer -> entity.skin.body().texturePath()
        is Skeleton -> Identifier.withDefaultNamespace("textures/entity/skeleton/skeleton.png")
        is Zombie -> Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png")
        is Creeper -> Identifier.withDefaultNamespace("textures/entity/creeper/creeper.png")
        is Piglin -> Identifier.withDefaultNamespace("textures/entity/piglin/piglin.png")
        else -> null
    }

    private fun enchantShort(stack: ItemStack): String? {
        for (entry in EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            val level = entry.intValue
            if (level <= 0) continue
            val description = entry.key.value().description().string
            val abbr = description.split(' ')
                .mapNotNull { it.firstOrNull() }
                .take(2)
                .joinToString("")
                .uppercase()
            return "$abbr$level"
        }
        return null
    }
}
