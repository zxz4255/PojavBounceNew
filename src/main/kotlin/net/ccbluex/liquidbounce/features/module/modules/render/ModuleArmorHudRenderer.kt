package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.DrawContext
import net.minecraft.item.ArmorItem
import net.minecraft.item.ItemStack
import net.minecraft.util.math.MathHelper
import kotlin.math.*

object ModuleArmorHudRenderer : ClientModule(
    "ArmorHUD",
    ModuleCategories.RENDER,
    aliases = listOf("ArmorStatus", "ArmorInfo"),
) {

    /* ============================= 可调节参数 ============================= */

    // 布局
    private val posX by float("Pos X", 2f, 0f..2000f)
    private val posY by float("Pos Y", 2f, 0f..1200f)
    private val scale by float("Scale", 1.0f, 0.5f..2.0f)
    private val verticalMode by boolean("Vertical", true)
    private val showDurabilityBar by boolean("Durability Bar", true)
    private val showPercentage by boolean("Percentage", true)
    private val showItemName by boolean("Item Name", false)
    private val iconSize by float("Icon Size", 18f, 10f..32f)
    private val spacing by float("Spacing", 2f, 0f..10f)

    // 背景
    private val background by boolean("Background", true)
    private val bgColor by color("BG Color", Color4b(0x00, 0x00, 0x00, 0x88))
    private val bgRadius by float("BG Radius", 6f, 0f..16f)

    // 颜色
    private val textColor by color("Text Color", Color4b(0xFF, 0xFF, 0xFF, 0xFF))
    private val durabilityHigh by color("Durability High", Color4b(0x22, 0xC5, 0x5E, 0xFF))
    private val durabilityMid by color("Durability Mid", Color4b(0xFF, 0xC8, 0x57, 0xFF))
    private val durabilityLow by color("Durability Low", Color4b(0xFF, 0x55, 0x55, 0xFF))

    // 动画
    private val fadeAnimSpeed by float("Fade Speed", 10f, 1f..30f)
    private val damageFlash by boolean("Damage Flash", true)
    private val flashDuration by int("Flash Duration", 300, 50..1000)

    // 行为
    private val hideIfFull by boolean("Hide If Full", false)
    private val alwaysShow by boolean("Always Show", false)

    /* ============================= 运行时状态 ============================= */

    private var displayAlpha = 0f
    private var lastRenderTime = 0L
    private var flashTimes = mutableMapOf<Int, Long>()  // slot -> flash start time

    /* ============================= 数据类 ============================= */

    private data class ArmorDisplay(
        val slot: Int,
        val stack: ItemStack,
        val durability: Int,
        val maxDurability: Int,
        val percentage: Float,
        val itemName: String,
    )

    /* ============================= 工具方法 ============================= */

    private fun easeTo(current: Float, target: Float, speed: Float, dt: Float): Float {
        if (current < 0f) return target
        val t = (1f - (1f - 0.15f).pow(dt * speed)).coerceIn(0f, 1f)
        return current + (target - current) * t
    }

    private fun getDurabilityColor(percentage: Float): Color4b {
        return when {
            percentage > 0.6f -> durabilityHigh
            percentage > 0.25f -> durabilityMid
            else -> durabilityLow
        }
    }

    private fun getFlashFactor(slot: Int): Float {
        val startTime = flashTimes[slot] ?: return 0f
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed < 0 || elapsed >= flashDuration) {
            flashTimes.remove(slot)
            return 0f
        }
        return 1f - elapsed.toFloat() / flashDuration
    }

    /* ============================= 渲染 ============================= */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled) return@handler

        val player = mc.player ?: return@handler

        // 收集护甲数据
        val armorItems = mutableListOf<ArmorDisplay>()
        val armorSlots = listOf(3, 2, 1, 0)  // 头盔、胸甲、护腿、靴子

        for (slot in armorSlots) {
            val stack = player.inventory.getArmorStack(slot)
            if (stack.isEmpty) continue

            val maxDura = stack.maxDamage.coerceAtLeast(1)
            val currentDura = maxDura - stack.damage
            val percentage = currentDura.toFloat() / maxDura

            // 检测耐久变化
            if (damageFlash && stack.damage > 0) {
                val key = slot
                if (!flashTimes.containsKey(key)) {
                    flashTimes[key] = System.currentTimeMillis()
                }
            }

            armorItems.add(ArmorDisplay(
                slot = slot,
                stack = stack,
                durability = currentDura,
                maxDurability = maxDura,
                percentage = percentage,
                itemName = stack.name.string
            ))
        }

        val now = System.currentTimeMillis()
        val dt = if (lastRenderTime != 0L) ((now - lastRenderTime) / 1000f).coerceIn(0.001f, 0.05f) else 0.016f
        lastRenderTime = now

        // 淡出动画
        val targetAlpha = if (armorItems.isNotEmpty() && (!hideIfFull || armorItems.any { it.percentage < 1f })) 1f else 0f
        displayAlpha = easeTo(displayAlpha, targetAlpha, fadeAnimSpeed, dt)

        if (displayAlpha < 0.01f) return@handler
        if (!alwaysShow && armorItems.isEmpty()) return@handler

        renderArmorList(event.context, armorItems)
    }

    private fun renderArmorList(context: DrawContext, armorItems: List<ArmorDisplay>) {
        val scale = this.scale
        val baseX = posX
        val baseY = posY
        val iconSize = this.iconSize

        context.matrices.push()
        context.matrices.translate(baseX, baseY, 0f)
        context.matrices.scale(scale, scale, 1f)

        val textRenderer = mc.textRenderer

        if (verticalMode) {
            // 垂直布局 (从上到下: 头盔、胸甲、护腿、靴子)
            var currentY = 0f

            for (armor in armorItems.sortedByDescending { it.slot }) {
                val flashFactor = getFlashFactor(armor.slot)
                val alpha = displayAlpha * (1f - flashFactor * 0.3f)  // 受伤时变暗

                // 背景
                if (background) {
                    val itemHeight = if (showDurabilityBar) iconSize + 8f else iconSize + 2f
                    val bgW = if (showPercentage || showItemName) {
                        max(iconSize + 4f, textRenderer.getWidth("${armor.percentage.roundToInt()}%") + 8f)
                    } else {
                        iconSize + 4f
                    }

                    drawRoundedRect(
                        context,
                        0f, currentY,
                        bgW, itemHeight,
                        bgRadius,
                        Color4b(
                            bgColor.r, bgColor.g, bgColor.b,
                            (bgColor.a * alpha).toInt()
                        )
                    )
                }

                // 物品图标
                context.drawItem(armor.stack, 2, currentY.toInt() + 2)

                // 闪烁效果
                if (flashFactor > 0f) {
                    drawRoundedRect(
                        context,
                        2f, currentY + 2f,
                        iconSize - 4f, iconSize - 4f,
                        2f,
                        Color4b(0xFF, 0x00, 0x00, (flashFactor * 0.5f * 255).toInt())
                    )
                }

                // 耐久度条
                if (showDurabilityBar) {
                    val barX = 2f
                    val barY = currentY + iconSize + 2f
                    val barW = iconSize - 4f
                    val barH = 3f

                    // 背景
                    drawRoundedRect(
                        context,
                        barX, barY,
                        barW, barH,
                        barH * 0.5f,
                        Color4b(0x33, 0x33, 0x33, (0xFF * alpha).toInt())
                    )

                    // 填充
                    val fillW = max(barH, barW * armor.percentage)
                    val duraColor = getDurabilityColor(armor.percentage)
                    drawRoundedRect(
                        context,
                        barX, barY,
                        fillW, barH,
                        barH * 0.5f,
                        Color4b(
                            duraColor.r, duraColor.g, duraColor.b,
                            (duraColor.a * alpha).toInt()
                        )
                    )
                }

                // 百分比文字
                if (showPercentage) {
                    val pctText = "${(armor.percentage * 100).roundToInt()}%"
                    val textCol = Color4b(
                        textColor.r, textColor.g, textColor.b,
                        (textColor.a * alpha).toInt()
                    )
                    context.drawText(
                        textRenderer,
                        pctText,
                        (iconSize + 6f).toInt(),
                        (currentY + iconSize / 2 - 4f).toInt(),
                        textCol.toARGB(),
                        true
                    )
                }

                // 物品名称
                if (showItemName) {
                    val nameText = if (armor.itemName.length > 12) armor.itemName.substring(0, 12) + "..." else armor.itemName
                    val nameCol = Color4b(
                        textColor.r, textColor.g, textColor.b,
                        (textColor.a * alpha * 0.7f).toInt()
                    )
                    context.drawText(
                        textRenderer,
                        nameText,
                        (iconSize + 6f).toInt(),
                        (currentY + 2f).toInt(),
                        nameCol.toARGB(),
                        false
                    )
                }

                currentY += iconSize + spacing + if (showDurabilityBar) 8f else 2f
            }
        } else {
            // 水平布局
            var currentX = 0f

            for (armor in armorItems.sortedByDescending { it.slot }) {
                val flashFactor = getFlashFactor(armor.slot)
                val alpha = displayAlpha * (1f - flashFactor * 0.3f)

                // 背景
                if (background) {
                    val itemWidth = if (showDurabilityBar) iconSize + 4f else iconSize + 2f
                    val itemHeight = if (showPercentage || showItemName) iconSize + 14f else iconSize + 4f

                    drawRoundedRect(
                        context,
                        currentX, 0f,
                        itemWidth, itemHeight,
                        bgRadius,
                        Color4b(
                            bgColor.r, bgColor.g, bgColor.b,
                            (bgColor.a * alpha).toInt()
                        )
                    )
                }

                // 物品图标
                context.drawItem(armor.stack, currentX.toInt() + 2, 2)

                // 闪烁
                if (flashFactor > 0f) {
                    drawRoundedRect(
                        context,
                        currentX + 2f, 2f,
                        iconSize - 4f, iconSize - 4f,
                        2f,
                        Color4b(0xFF, 0x00, 0x00, (flashFactor * 0.5f * 255).toInt())
                    )
                }

                // 耐久度条 (垂直或在下方)
                if (showDurabilityBar) {
                    val barX = currentX + 2f
                    val barY = iconSize + 4f
                    val barW = iconSize - 4f
                    val barH = 3f

                    drawRoundedRect(
                        context,
                        barX, barY,
                        barW, barH,
                        barH * 0.5f,
                        Color4b(0x33, 0x33, 0x33, (0xFF * alpha).toInt())
                    )

                    val fillW = max(barH, barW * armor.percentage)
                    val duraColor = getDurabilityColor(armor.percentage)
                    drawRoundedRect(
                        context,
                        barX, barY,
                        fillW, barH,
                        barH * 0.5f,
                        Color4b(
                            duraColor.r, duraColor.g, duraColor.b,
                            (duraColor.a * alpha).toInt()
                        )
                    )
                }

                // 百分比
                if (showPercentage) {
                    val pctText = "${(armor.percentage * 100).roundToInt()}%"
                    val textCol = Color4b(
                        textColor.r, textColor.g, textColor.b,
                        (textColor.a * alpha).toInt()
                    )
                    context.drawText(
                        textRenderer,
                        pctText,
                        (currentX + iconSize / 2 - textRenderer.getWidth(pctText) / 2 + 2).toInt(),
                        (iconSize + (if (showDurabilityBar) 10f else 4f)).toInt(),
                        textCol.toARGB(),
                        false
                    )
                }

                currentX += iconSize + spacing + 4f
            }
        }

        context.matrices.pop()
    }
}
