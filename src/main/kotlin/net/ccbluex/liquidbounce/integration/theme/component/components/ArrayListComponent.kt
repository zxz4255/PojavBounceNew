package net.ccbluex.liquidbounce.integration.theme.component.components

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.integration.theme.component.HudComponent
import net.ccbluex.liquidbounce.integration.theme.component.HudComponentTweak
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.DrawContext

class ArrayListComponent(
    name: String,
    enabled: Boolean,
    alignment: Alignment,
    tweaks: Array<HudComponentTweak>,
    values: Array<JsonObject>
) : HudComponent(name, enabled, alignment, tweaks, values) {

    private val config get() = ModuleHud.ModuleList

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (!enabled || !config.enabled) return

        val client = mc
        val modules = ModuleManager.getModules()
            .filter { it.enabled && it.name != "ClickGUI" && it.name != "HUD" }
            .sortedByDescending { it.name.length }

        val maxDisplay = config.maxDisplay
        val displayModules = if (maxDisplay > 0) modules.take(maxDisplay) else modules
        if (displayModules.isEmpty()) return

        val font = client.font
        val scWidth = client.window.guiScaledWidth
        val scHeight = client.window.guiScaledHeight

        val padding = config.padding
        val lineSpacing = config.lineSpacing
        val cornerRadius = config.cornerRadius.toFloat()
        val textColor = config.textColor
        val bgColorRaw = config.backgroundColor
        val bgAlpha = config.backgroundAlpha.coerceIn(0, 255)
        val position = config.position

        val lines = displayModules.map { it.name to font.width(it.name) }
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (font.lineHeight + lineSpacing) - lineSpacing
        val boxWidth = maxWidth + padding * 2
        val boxHeight = totalHeight + padding * 2

        val xPos = when (position) {
            2, 3 -> padding
            else -> scWidth - boxWidth - padding
        }
        val yPos = when (position) {
            2, 0 -> padding + 10
            else -> scHeight - boxHeight - padding - 30
        }

        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        drawRoundedRect(ctx, xPos.toFloat(), yPos.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius, bgColor)

        var currentY = yPos + padding
        for ((text, _) in lines) {
            ctx.drawText(font, text, (xPos + padding).toInt(), currentY.toInt(), textColor, false)
            currentY += font.lineHeight + lineSpacing
        }
    }

    private fun drawRoundedRect(ctx: DrawContext, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        if (r <= 0.5f) {
            ctx.fill(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt(), color)
            return
        }
        val x1 = x; val y1 = y; val x2 = x + w; val y2 = y + h
        ctx.fill((x1 + r).toInt(), y1.toInt(), (x2 - r).toInt(), y2.toInt(), color)
        ctx.fill(x1.toInt(), (y1 + r).toInt(), (x1 + r).toInt(), (y2 - r).toInt(), color)
        ctx.fill((x2 - r).toInt(), (y1 + r).toInt(), x2.toInt(), (y2 - r).toInt(), color)
    }
}
