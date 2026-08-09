package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 功能列表 HUD - 显示所有已开启的模块
 * 配置项位于 ModuleHud.ModuleList 中，包括位置、颜色、透明度、圆角、内边距、行间距、最大显示数
 * 模块按名称长度从长到短排序
 */
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
        val scWidth = client.window.guiScaledWidth
        val scHeight = client.window.guiScaledHeight

        // 1. 获取所有已启用的模块（排除 HUD 和 ClickGUI）
        val allModules = ModuleManager.getModules()
            .filter { it.enabled && it.name != "ClickGUI" && it.name != "HUD" }

        // 2. 按名称长度从长到短排序
        val sortedModules = allModules.sortedByDescending { it.name.length }

        // 3. 限制显示数量
        val maxDisplay = config.maxDisplay
        val modules = if (maxDisplay > 0) sortedModules.take(maxDisplay) else sortedModules
        if (modules.isEmpty()) return

        // 4. 读取配置
        val padding = config.padding
        val lineSpacing = config.lineSpacing
        val cornerRadius = config.cornerRadius.toFloat()
        val textColor = config.textColor
        val bgColorRaw = config.backgroundColor
        val bgAlpha = config.backgroundAlpha.coerceIn(0, 255)
        val position = config.position // 0=右上, 1=右下, 2=左上, 3=左下

        // 5. 计算每一行的文本和宽度
        val lines = modules.map { module ->
            val text = module.name
            val width = font.width(text)
            Pair(text, width)
        }

        // 6. 计算整体尺寸
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (font.lineHeight + lineSpacing) - lineSpacing
        val boxWidth = maxWidth + padding * 2
        val boxHeight = totalHeight + padding * 2

        // 7. 计算位置
        val xPos = when (position) {
            2, 3 -> padding.toFloat()                         // 左侧
            else -> scWidth - boxWidth - padding           // 右侧
        }
        val yPos = when (position) {
            2, 0 -> padding + 10f                            // 顶部
            else -> scHeight - boxHeight - padding - 30f   // 底部
        }

        // 8. 合成背景颜色（保留 RGB，覆盖 Alpha）
        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        // 9. 绘制圆角矩形背景
        drawRoundedRect(ctx, xPos, yPos, boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius, bgColor)

        // 10. 绘制所有模块名称
        var currentY = yPos + padding
        for ((text, _) in lines) {
            ctx.text(font, text, (xPos + padding).toInt(), currentY.toInt(), textColor)
            currentY += font.lineHeight + lineSpacing
        }
    }

    /**
     * 绘制圆角矩形（完全复制 ClickGuiScreen 的实现）
     * @param ctx 图形上下文
     * @param x 左上角 X
     * @param y 左上角 Y
     * @param w 宽度
     * @param h 高度
     * @param radius 圆角半径
     * @param color 颜色（ARGB）
     */
    private fun drawRoundedRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        if (r <= 0.5f) {
            ctx.fill(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt(), color)
            return
        }
        val x1 = x; val y1 = y; val x2 = x + w; val y2 = y + h

        // 主体矩形
        ctx.fill((x1 + r).toInt(), y1.toInt(), (x2 - r).toInt(), y2.toInt(), color)
        ctx.fill(x1.toInt(), (y1 + r).toInt(), (x1 + r).toInt(), (y2 - r).toInt(), color)
        ctx.fill((x2 - r).toInt(), (y1 + r).toInt(), x2.toInt(), (y2 - r).toInt(), color)

        // 四个角的圆角（通过像素级绘制实现平滑圆角）
        drawCorner(ctx, x1 + r, y1 + r, r, 180f, 270f, color)
        drawCorner(ctx, x2 - r, y1 + r, r, 270f, 360f, color)
        drawCorner(ctx, x2 - r, y2 - r, r, 0f, 90f, color)
        drawCorner(ctx, x1 + r, y2 - r, r, 90f, 180f, color)
    }

    /**
     * 绘制一个圆角（通过扇形近似）
     * @param ctx 图形上下文
     * @param cx 圆心 X
     * @param cy 圆心 Y
     * @param r 半径
     * @param startAngle 起始角度（度）
     * @param endAngle 结束角度（度）
     * @param color 颜色（ARGB）
     */
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
