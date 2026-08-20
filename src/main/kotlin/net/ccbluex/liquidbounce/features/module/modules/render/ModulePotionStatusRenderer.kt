package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.entity.effect.StatusEffect
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.util.math.MathHelper
import java.util.*
import kotlin.math.*

object ModulePotionStatusRenderer : ClientModule(
    "PotionStatus",
    ModuleCategories.RENDER,
    aliases = listOf("PotionHUD", "Effects"),
) {

    /* ============================= 可调节参数 ============================= */

    // 布局
    private val posX by float("Pos X", 2f, 0f..2000f)
    private val posY by float("Pos Y", 2f, 0f..1200f)
    private val scale by float("Scale", 1.0f, 0.5f..2.0f)
    private val verticalMode by boolean("Vertical", true)
    private val iconSize by float("Icon Size", 20f, 12f..32f)
    private val textSize by float("Text Size", 10f, 6f..16f)
    private val spacing by float("Spacing", 2f, 0f..10f)

    // 背景
    private val background by boolean("Background", true)
    private val bgColor by color("BG Color", Color4b(0x00, 0x00, 0x00, 0x88))
    private val bgRadius by float("BG Radius", 6f, 0f..16f)

    // 颜色
    private val nameColor by color("Name Color", Color4b(0xFF, 0xFF, 0xFF, 0xFF))
    private val durationColor by color("Duration Color", Color4b(0xCC, 0xCC, 0xCC, 0xFF))
    private val amplifierColor by color("Amplifier Color", Color4b(0xFF, 0xAA, 0x00, 0xFF))

    // 动画
    private val fadeAnimSpeed by float("Fade Speed", 10f, 1f..30f)
    private val blinkThreshold by int("Blink Threshold", 10, 5..30)

    /* ============================= 常量 ============================= */

    private const val ICON_TEX_SIZE = 18
    private const val MAX_DURATION = 3600  // 1小时上限

    /* ============================= 运行时状态 ============================= */

    private var displayAlpha = 0f
    private var lastRenderTime = 0L

    /* ============================= 数据类 ============================= */

    private data class PotionDisplay(
        val effect: StatusEffectInstance,
        val name: String,
        val durationText: String,
        val amplifierText: String,
        val color: Int,
        val isBad: Boolean,
        val isInfinite: Boolean,
        val progress: Float,  // 0-1 剩余时间比例
    )

    /* ============================= 工具方法 ============================= */

    private fun easeTo(current: Float, target: Float, speed: Float, dt: Float): Float {
        if (current < 0f) return target
        val t = (1f - (1f - 0.15f).pow(dt * speed)).coerceIn(0f, 1f)
        return current + (target - current) * t
    }

    private fun formatDuration(ticks: Int): String {
        if (ticks >= 32767 * 20) return "∞"  // 无限时长
        val seconds = ticks / 20
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) {
            String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds)
        } else {
            "${remainingSeconds}s"
        }
    }

    private fun getEffectColor(effect: StatusEffect): Int {
        return when (effect) {
            StatusEffects.SPEED -> 0x7CAFC6
            StatusEffects.SLOWNESS -> 0x5A6C81
            StatusEffects.HASTE -> 0xD9C043
            StatusEffects.MINING_FATIGUE -> 0x4A4217
            StatusEffects.STRENGTH -> 0x932423
            StatusEffects.INSTANT_HEALTH -> 0xF82423
            StatusEffects.INSTANT_DAMAGE -> 0x430A09
            StatusEffects.JUMP_BOOST -> 0x786297
            StatusEffects.NAUSEA -> 0x551D4A
            StatusEffects.REGENERATION -> 0xCD5CAB
            StatusEffects.RESISTANCE -> 0x99453A
            StatusEffects.FIRE_RESISTANCE -> 0xE49A3A
            StatusEffects.WATER_BREATHING -> 0x2E5299
            StatusEffects.INVISIBILITY -> 0x7F8392
            StatusEffects.BLINDNESS -> 0x1F1F23
            StatusEffects.NIGHT_VISION -> 0xC2FF66
            StatusEffects.HUNGER -> 0x587653
            StatusEffects.WEAKNESS -> 0x484D48
            StatusEffects.POISON -> 0x4E9331
            StatusEffects.WITHER -> 0x352A27
            StatusEffects.HEALTH_BOOST -> 0xF87D23
            StatusEffects.ABSORPTION -> 0x2552A5
            StatusEffects.GLOWING -> 0x94A061
            StatusEffects.LEVITATION -> 0xCEFFFF
            StatusEffects.LUCK -> 0x339900
            StatusEffects.UNLUCK -> 0xC0A44D
            StatusEffects.SLOW_FALLING -> 0xFFEFD1
            StatusEffects.CONDUIT_POWER -> 0x1DC2D1
            StatusEffects.DOLPHINS_GRACE -> 0x88A3BE
            StatusEffects.BAD_OMEN -> 0x0B6128
            StatusEffects.HERO_OF_THE_VILLAGE -> 0x44FF44
            StatusEffects.DARKNESS -> 0x292721
            else -> effect.color
        }
    }

    private fun getAmplifierString(amplifier: Int): String {
        return when (amplifier) {
            0 -> "I"
            1 -> "II"
            2 -> "III"
            3 -> "IV"
            4 -> "V"
            else -> "${amplifier + 1}"
        }
    }

    private fun shouldBlink(durationTicks: Int): Boolean {
        val seconds = durationTicks / 20
        return seconds in 1..blinkThreshold && (System.currentTimeMillis() / 500) % 2 == 0L
    }

    /* ============================= 渲染 ============================= */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled) return@handler

        val player = mc.player ?: return@handler
        val effects = player.statusEffects

        val now = System.currentTimeMillis()
        val dt = if (lastRenderTime != 0L) ((now - lastRenderTime) / 1000f).coerceIn(0.001f, 0.05f) else 0.016f
        lastRenderTime = now

        // 淡出动画
        val targetAlpha = if (effects.isNotEmpty()) 1f else 0f
        displayAlpha = easeTo(displayAlpha, targetAlpha, fadeAnimSpeed, dt)

        if (displayAlpha < 0.01f) return@handler

        val context = event.context

        // 收集并排序药水效果
        val displayList = effects.map { effect ->
            val type = effect.effectType
            val color = getEffectColor(type.value())
            val durationTicks = effect.duration
            val isInfinite = durationTicks >= 32767 * 20

            PotionDisplay(
                effect = effect,
                name = type.value().name.string,
                durationText = if (isInfinite) "∞" else formatDuration(durationTicks),
                amplifierText = getAmplifierString(effect.amplifier),
                color = color,
                isBad = type.value().category == net.minecraft.entity.effect.StatusEffectCategory.HARMFUL,
                isInfinite = isInfinite,
                progress = if (isInfinite) 1f else (durationTicks.toFloat() / (MAX_DURATION * 20)).coerceIn(0f, 1f)
            )
        }.sortedWith(compareByDescending<PotionDisplay> {
            it.effect.duration
        }.thenBy {
            it.name
        })

        if (displayList.isEmpty()) return@handler

        renderPotionList(context, displayList)
    }

    private fun renderPotionList(context: GuiGraphicsExtractor, potions: List<PotionDisplay>) {
        val scale = this.scale
        val baseX = posX
        val baseY = posY
        val iconSize = this.iconSize
        val textSize = this.textSize
        val spacing = this.spacing

        context.matrices.push()
        context.matrices.translate(baseX, baseY, 0f)
        context.matrices.scale(scale, scale, 1f)

        val textRenderer = mc.textRenderer

        if (verticalMode) {
            // 垂直布局
            var currentY = 0f

            for (potion in potions) {
                val blink = !potion.isInfinite && shouldBlink(potion.effect.duration)
                if (blink) continue  // 闪烁时隐藏

                val alpha = (displayAlpha * 255).toInt()

                // 背景
                if (background) {
                    val itemHeight = iconSize + 4f
                    val bgW = iconSize + 8f + max(
                        textRenderer.getWidth(potion.name),
                        textRenderer.getWidth(potion.durationText)
                    )

                    drawRoundedRect(
                        context,
                        0f, currentY,
                        bgW + 8f, itemHeight,
                        bgRadius,
                        Color4b(bgColor.r, bgColor.g, bgColor.b, (bgColor.a * displayAlpha).toInt())
                    )
                }

                // 图标背景 (使用药水颜色)
                val potionColor = Color4b(
                    (potion.color shr 16) and 0xFF,
                    (potion.color shr 8) and 0xFF,
                    potion.color and 0xFF,
                    alpha
                )

                drawRoundedRect(
                    context,
                    2f, currentY + 2f,
                    iconSize - 4f, iconSize - 4f,
                    4f,
                    potionColor
                )

                // 药水图标 (简化版 - 使用颜色方块代替实际图标)
                renderPotionIcon(context, potion, 4f, currentY + 4f, iconSize - 8f)

                // 名称
                val nameCol = Color4b(
                    nameColor.r, nameColor.g, nameColor.b,
                    (nameColor.a * displayAlpha).toInt()
                )
                context.drawText(
                    textRenderer,
                    potion.name,
                    (iconSize + 4f).toInt(),
                    (currentY + 2f).toInt(),
                    nameCol.toARGB(),
                    true
                )

                // 时长
                val durCol = if (potion.isInfinite)
                    Color4b(0x55, 0xFF, 0x55, (0xFF * displayAlpha).toInt())
                else
                    Color4b(
                        durationColor.r, durationColor.g, durationColor.b,
                        (durationColor.a * displayAlpha).toInt()
                    )

                context.drawText(
                    textRenderer,
                    potion.durationText,
                    (iconSize + 4f).toInt(),
                    (currentY + 2f + textSize + 2f).toInt(),
                    durCol.toARGB(),
                    false
                )

                // 等级
                if (potion.effect.amplifier > 0) {
                    val ampCol = Color4b(
                        amplifierColor.r, amplifierColor.g, amplifierColor.b,
                        (amplifierColor.a * displayAlpha).toInt()
                    )
                    val ampX = iconSize + 4f + textRenderer.getWidth(potion.durationText) + 4f
                    context.drawText(
                        textRenderer,
                        potion.amplifierText,
                        ampX.toInt(),
                        (currentY + 2f + textSize + 2f).toInt(),
                        ampCol.toARGB(),
                        false
                    )
                }

                currentY += iconSize + spacing
            }
        } else {
            // 水平布局
            var currentX = 0f

            for (potion in potions) {
                val blink = !potion.isInfinite && shouldBlink(potion.effect.duration)
                if (blink) continue

                val alpha = (displayAlpha * 255).toInt()

                // 背景
                if (background) {
                    drawRoundedRect(
                        context,
                        currentX, 0f,
                        iconSize + 4f, iconSize + 4f,
                        bgRadius,
                        Color4b(bgColor.r, bgColor.g, bgColor.b, (bgColor.a * displayAlpha).toInt())
                    )
                }

                // 图标
                val potionColor = Color4b(
                    (potion.color shr 16) and 0xFF,
                    (potion.color shr 8) and 0xFF,
                    potion.color and 0xFF,
                    alpha
                )

                drawRoundedRect(
                    context,
                    currentX + 2f, 2f,
                    iconSize, iconSize,
                    4f,
                    potionColor
                )

                renderPotionIcon(context, potion, currentX + 4f, 4f, iconSize - 4f)

                // 时长文字 (在图标下方)
                if (iconSize >= 16f) {
                    val durCol = if (potion.isInfinite)
                        Color4b(0x55, 0xFF, 0x55, (0xFF * displayAlpha).toInt())
                    else
                        Color4b(
                            durationColor.r, durationColor.g, durationColor.b,
                            (durationColor.a * displayAlpha).toInt()
                        )

                    val durText = if (potion.durationText.length > 4) potion.durationText.substring(0, 4) else potion.durationText
                    context.drawText(
                        textRenderer,
                        durText,
                        (currentX + iconSize / 2 - textRenderer.getWidth(durText) / 2 + 2).toInt(),
                        (iconSize + 6f).toInt(),
                        durCol.toARGB(),
                        false
                    )
                }

                currentX += iconSize + spacing + 4f
            }
        }

        context.matrices.pop()
    }

    private fun renderPotionIcon(context: GuiGraphicsExtractor, potion: PotionDisplay, x: Float, y: Float, size: Float) {
        // 使用原生方式渲染简化药水图标
        // 在LB NextGen中可以使用drawItem或自定义渲染

        val alpha = (displayAlpha * 255).toInt()

        // 绘制药水瓶形状
        val bottleColor = if (potion.isBad)
            Color4b(0x88, 0x33, 0x33, alpha)
        else
            Color4b(0x33, 0x88, 0x33, alpha)

        // 瓶身
        drawRoundedRect(
            context,
            x + size * 0.2f, y + size * 0.15f,
            size * 0.6f, size * 0.7f,
            2f,
            bottleColor
        )

        // 瓶口
        drawRoundedRect(
            context,
            x + size * 0.35f, y,
            size * 0.3f, size * 0.2f,
            1f,
            Color4b(0xCC, 0xCC, 0xCC, alpha)
        )
    }
}
