package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 透明 HUD Screen，用于显示功能列表
 * 完全仿照 ClickGuiScreen 的绘制方式，但不绘制背景，不捕获事件
 */
class HudArrayListScreen : Screen(Component.literal("ArrayList")) {

    // 不暂停游戏
    override fun isPauseScreen() = false

    // 按 ESC 不关闭（由 ModuleHud 控制生命周期）
    override fun shouldCloseOnEsc() = false

    // 不绘制任何背景（透明）
    override fun renderBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // 故意留空，不绘制背景
    }

    override fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        drawArrayList(ctx)
    }

    // ========== 所有事件都不消费，让它们传递给游戏 ==========
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean = false
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false
    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false
    override fun charTyped(codePoint: Int, modifiers: Int): Boolean = false

    // ========== 绘制功能列表 ==========
    private fun drawArrayList(ctx: GuiGraphicsExtractor) {
        val config = ModuleHud.ModuleList
        if (!config.enabled) return

        val modules = ModuleManager.getModules()
            .filter { it.enabled && it.name != "ClickGUI" && it.name != "HUD" }
            .sortedByDescending { it.name.length }

        val maxDisplay = config.maxDisplay
        val displayModules = if (maxDisplay > 0) modules.take(maxDisplay) else modules
        if (displayModules.isEmpty()) return

        val font = mc.font
        val padding = config.padding
        val lineSpacing = config.lineSpacing
        val cornerRadius = config.cornerRadius.toFloat()
        val textColor = config.textColor
        val bgAlpha = config.backgroundAlpha.coerceIn(0, 255)
        val bgColorRaw = config.backgroundColor
        val position = config.position
        val scWidth = mc.window.guiScaledWidth
        val scHeight = mc.window.guiScaledHeight

        // 计算每一行的文本和宽度
        val lines = displayModules.map { it.name to font.width(it.name) }
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (font.lineHeight + lineSpacing) - lineSpacing
        val boxWidth = maxWidth + padding * 2
        val boxHeight = totalHeight + padding * 2

        // 计算位置
        val xPos = when (position) {
            2, 3 -> padding.toFloat()                         // 左侧
            else -> scWidth - boxWidth - padding           // 右侧
        }
        val yPos = when (position) {
            2, 0 -> padding + 10f                            // 顶部
            else -> scHeight - boxHeight - padding - 30f   // 底部
        }

        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        // 绘制圆角矩形背景（完全复用 ClickGuiScreen 的方法）
        drawRoundedRect(ctx, xPos, yPos, boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius, bgColor)

        // 绘制文本
        var currentY = yPos + padding
        for ((text, _) in lines) {
            ctx.text(font, text, (xPos + padding).toInt(), currentY.toInt(), textColor)
            currentY += font.lineHeight + lineSpacing
        }
    }

    // ========== 完全复制 ClickGuiScreen 的 drawRoundedRect ==========
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

        // 四个角的圆角（完全复制 ClickGuiScreen 的 drawCorner 实现）
        drawCorner(ctx, x1 + r, y1 + r, r, 180f, 270f, color)
        drawCorner(ctx, x2 - r, y1 + r, r, 270f, 360f, color)
        drawCorner(ctx, x2 - r, y2 - r, r, 0f, 90f, color)
        drawCorner(ctx, x1 + r, y2 - r, r, 90f, 180f, color)
    }

    private fun drawCorner(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, startAngle: Float, endAngle: Float, color: Int) {
        var a = startAngle
        while (a < endAngle) {
            val rad1 = Math.toRadians(a.toDouble())
            val rad2 = Math.toRadians((a + 6f).coerceAtMost(endAngle).toDouble())
            val px1 = cx + (kotlin.math.cos(rad1) * r).toFloat()
            val py1 = cy + (kotlin.math.sin(rad1) * r).toFloat()
            val px2 = cx + (kotlin.math.cos(rad2) * r).toFloat()
            val py2 = cy + (kotlin.math.sin(rad2) * r).toFloat()
            val minX = cx.coerceAtMost(px1).coerceAtMost(px2).toInt()
            val maxX = cx.coerceAtLeast(px1).coerceAtLeast(px2).toInt()
            val minY = cy.coerceAtMost(py1).coerceAtMost(py2).toInt()
            val maxY = cy.coerceAtLeast(py1).coerceAtLeast(py2).toInt()
            ctx.fill(minX, minY, max(minX + 1, maxX), max(minY + 1, maxY), color)
            a += 6f
        }
    }
}
