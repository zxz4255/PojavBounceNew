/*
 * ModuleArmorHudRenderer —— 还原 ArmorHudRenderer.java (New cards + Lite)
 * 原生 Overlay，无 Web / 无 Skia
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import kotlin.math.max
import kotlin.math.roundToInt

object ModuleArmorHudRenderer : ClientModule(
    "ArmorHudRenderer",
    ModuleCategories.RENDER,
    aliases = listOf("ArmorHUD", "PvpArmorHud"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        NEW("New"), LITE("Lite")
    }

    private enum class Anchor(override val tag: String) : Tagged {
        HOTBAR_LEFT("Hotbar Left"),
        CUSTOM("Custom")
    }

    private val mode by enumChoice("Mode", Mode.NEW)
    private val anchor by enumChoice("Anchor", Anchor.HOTBAR_LEFT)
    private val posX by float("Offset X", 0f, -400f..800f)
    private val posY by float("Offset Y", 0f, -400f..600f)
    private val scale by float("Scale", 1f, 0.6f..2f)

    private val cardW by float("Card Width", 54f, 24f..100f)
    private val cardH by float("Card Height", 18f, 14f..36f)
    private val cardGap by float("Card Gap", 2.5f, 0f..12f)
    private val radius by float("Radius", 5f, 0f..12f)

    private val bgColor by color("Background", Color4b(18, 18, 22, 200))
    private val borderColor by color("Border", Color4b(255, 255, 255, 40))
    private val textColor by color("Text", Color4b(255, 255, 255, 230))
    private val barBg by color("Durability BG", Color4b(255, 255, 255, 35))
    private val showEmpty by boolean("Show Empty", false)
    private val showPercent by boolean("Show Percent", true)
    private val vertical by boolean("Vertical Stack", true)

    private data class ArmorEntry(val slot: EquipmentSlot, val stack: ItemStack, val label: String)

    private fun entries(): List<ArmorEntry> {
        val p = mc.player ?: return emptyList()
        val slots = listOf(
            EquipmentSlot.HEAD to "H",
            EquipmentSlot.CHEST to "C",
            EquipmentSlot.LEGS to "L",
            EquipmentSlot.FEET to "F",
        )
        return slots.mapNotNull { (slot, lab) ->
            val stack = try {
                p.getItemBySlot(slot)
            } catch (_: Throwable) {
                ItemStack.EMPTY
            }
            if (!showEmpty && (stack.isEmpty)) return@mapNotNull null
            ArmorEntry(slot, stack, lab)
        }
    }

    private fun durabilityRatio(stack: ItemStack): Float {
        if (stack.isEmpty) return 0f
        return try {
            if (!stack.isDamageableItem) return 1f
            val max = stack.maxDamage.coerceAtLeast(1)
            val dmg = stack.damageValue.coerceIn(0, max)
            1f - dmg.toFloat() / max
        } catch (_: Throwable) {
            1f
        }
    }

    private fun durabilityColor(ratio: Float): Color4b {
        // 绿→黄→红
        val r = (255 * (1f - ratio)).roundToInt().coerceIn(40, 255)
        val g = (255 * ratio).roundToInt().coerceIn(40, 255)
        return Color4b(r, g, 60, 230)
    }

    private fun withA(c: Color4b, a: Float) =
        Color4b(c.r, c.g, c.b, (c.a * a.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255))

    private fun GuiGraphicsExtractor.drawItemSafe(stack: ItemStack, x: Float, y: Float) {
        if (stack.isEmpty) return
        runCatching {
            val methods = javaClass.methods
            methods.firstOrNull { it.name == "renderItem" && it.parameterCount in 3..5 }
                ?.let { m ->
                    when (m.parameterCount) {
                        3 -> m.invoke(this, stack, x.roundToInt(), y.roundToInt())
                        4 -> m.invoke(this, stack, x.roundToInt(), y.roundToInt(), 0)
                        else -> m.invoke(this, mc.player, stack, x.roundToInt(), y.roundToInt(), 0)
                    }
                    return
                }
            methods.firstOrNull { it.name == "item" && it.parameterCount in 3..6 }
                ?.let { m ->
                    when (m.parameterCount) {
                        3 -> m.invoke(this, stack, x.roundToInt(), y.roundToInt())
                        4 -> m.invoke(this, stack, x.roundToInt(), y.roundToInt(), 0)
                        else -> m.invoke(this, mc.player, stack, x.roundToInt(), y.roundToInt(), 0)
                    }
                }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val list = entries()
        if (list.isEmpty()) return@handler

        val ctx = event.context
        val font = mc.font
        val s = scale
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()

        val cw = cardW * s
        val ch = cardH * s
        val gap = cardGap * s

        val totalH = if (vertical) list.size * ch + (list.size - 1) * gap else ch
        val totalW = if (vertical) cw else list.size * cw + (list.size - 1) * gap

        var baseX: Float
        var baseY: Float
        if (anchor == Anchor.HOTBAR_LEFT) {
            val hotbarW = 182f
            val hotbarX = (sw - hotbarW) * 0.5f
            val hotbarY = sh - 22f
            baseX = hotbarX - cw - 3f * s + posX
            baseY = hotbarY - totalH - 3f * s + posY
        } else {
            baseX = posX
            baseY = posY
        }
        baseX = baseX.coerceIn(0f, max(0f, sw - totalW))
        baseY = baseY.coerceIn(0f, max(0f, sh - totalH))

        if (mode == Mode.LITE) {
            renderLite(ctx, font, list, baseX, baseY, s)
        } else {
            renderNew(ctx, font, list, baseX, baseY, cw, ch, gap, s)
        }
    }

    private fun renderNew(
        ctx: GuiGraphicsExtractor, font: Font, list: List<ArmorEntry>,
        baseX: Float, baseY: Float, cw: Float, ch: Float, gap: Float, s: Float,
    ) {
        list.forEachIndexed { i, e ->
            val x = if (vertical) baseX else baseX + i * (cw + gap)
            val y = if (vertical) baseY + i * (ch + gap) else baseY

            ctx.drawRoundedRect(x, y, x + cw, y + ch, radius * s, bgColor)
            // border
            ctx.drawQuad(x, y, x + cw, y + 1f, borderColor)
            ctx.drawQuad(x, y + ch - 1f, x + cw, y + ch, borderColor)

            val icon = 14f * s
            if (!e.stack.isEmpty) {
                ctx.drawItemSafe(e.stack, x + 2f * s, y + (ch - icon) / 2f - 1f)
            } else {
                ctx.text(font, e.label, (x + 4f * s).roundToInt(), (y + ch / 2f - 4f).roundToInt(), textColor.argb, false)
            }

            val ratio = durabilityRatio(e.stack)
            val barX = x + 18f * s
            val barW = cw - 22f * s
            val barY = y + ch - 5f * s
            val barH = 2.5f * s
            ctx.drawQuad(barX, barY, barX + barW, barY + barH, barBg)
            if (ratio > 0.01f && !e.stack.isEmpty) {
                ctx.drawQuad(barX, barY, barX + barW * ratio, barY + barH, durabilityColor(ratio))
            }

            if (showPercent && !e.stack.isEmpty) {
                val pct = (ratio * 100).roundToInt().toString() + "%"
                ctx.text(
                    font, pct,
                    (barX).roundToInt(), (y + 3f * s).roundToInt(),
                    textColor.argb, false,
                )
            }
        }
    }

    private fun renderLite(
        ctx: GuiGraphicsExtractor, font: Font, list: List<ArmorEntry>,
        baseX: Float, baseY: Float, s: Float,
    ) {
        val icon = 16f * s
        val gap = 2f * s
        list.forEachIndexed { i, e ->
            val x = baseX
            val y = baseY + i * (icon + gap + 8f * s)
            if (!e.stack.isEmpty) {
                ctx.drawItemSafe(e.stack, x, y)
            }
            val ratio = durabilityRatio(e.stack)
            if (showPercent && !e.stack.isEmpty) {
                val pct = (ratio * 100).roundToInt().toString()
                ctx.text(
                    font, pct,
                    (x + icon + 2.5f * s).roundToInt(), (y + 4f * s).roundToInt(),
                    durabilityColor(ratio).argb, false,
                )
            }
            // mini durability bar under icon
            ctx.drawQuad(x, y + icon + 1f, x + icon, y + icon + 2.5f * s, barBg)
            if (ratio > 0.01f) {
                ctx.drawQuad(x, y + icon + 1f, x + icon * ratio, y + icon + 2.5f * s, durabilityColor(ratio))
            }
        }
    }
}
