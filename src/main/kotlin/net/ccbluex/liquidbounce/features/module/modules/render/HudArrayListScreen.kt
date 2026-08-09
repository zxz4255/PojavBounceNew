package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class HudArrayListScreen : Screen(Component.literal("ArrayList")) {

    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = false

    override fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        drawArrayList(ctx)
    }

    private fun drawArrayList(ctx: GuiGraphicsExtractor) {
        val config = ModuleHud.ModuleList
        if (!config.enabled) return

        val client = mc
        val font = client.font

        val modules = ModuleManager.getModules()
            .filter { it.enabled && it.name != "ClickGUI" && it.name != "HUD" }
            .sortedByDescending { it.name.length }

        val maxDisplay = config.maxDisplay
        val displayModules = if (maxDisplay > 0) modules.take(maxDisplay) else modules
        if (displayModules.isEmpty()) return

        val padding = config.padding
        val lineSpacing = config.lineSpacing
        val cornerRadius = config.cornerRadius.toFloat()
        val textColor = config.textColor
        val bgColorRaw = config.backgroundColor
        val bgAlpha = config.backgroundAlpha.coerceIn(0, 255)
        val position = config.position
        val scale = config.scale

        val scaledLineHeight = (font.lineHeight * scale).toInt()
        val scaledPadding = (padding * scale).toInt()
        val scaledLineSpacing = (lineSpacing * scale).toInt()

        val lines = displayModules.map { it.name to (font.width(it.name) * scale).toInt() }
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (scaledLineHeight + scaledLineSpacing) - scaledLineSpacing
        val boxWidth = maxWidth + scaledPadding * 2
        val boxHeight = totalHeight + scaledPadding * 2

        val scWidth = client.window.guiScaledWidth
        val scHeight = client.window.guiScaledHeight

        val xPos = when (position) {
            2, 3 -> scaledPadding.toFloat()
            else -> scWidth - boxWidth - scaledPadding
        }
        val yPos = when (position) {
            2, 0 -> scaledPadding + 10f
            else -> scHeight - boxHeight - scaledPadding - 30f
        }

        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        drawRoundedRect(ctx, xPos, yPos, boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius * scale, bgColor)

        var currentY = yPos + scaledPadding
        for ((text, _) in lines) {
            ctx.text(font, text, (xPos + scaledPadding).toInt(), currentY.toInt(), textColor)
            currentY += scaledLineHeight + scaledLineSpacing
        }
    }

    private fun drawRoundedRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
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
