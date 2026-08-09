package net.ccbluex.liquidbounce.integration.theme.component.components

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.ccbluex.liquidbounce.integration.theme.component.HudComponent
import net.ccbluex.liquidbounce.integration.theme.component.HudComponentTweak
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * ArrayList 组件 —— 显示已启用模块列表
 */
class ArrayListComponent(
    name: String,
    enabled: Boolean,
    alignment: Alignment,
    tweaks: Array<HudComponentTweak>,
    values: Array<JsonObject>
) : HudComponent(name, enabled, alignment, tweaks, values) {

    // 直接引用 ModuleHud 中的配置（无需重复定义）
    private val config get() = ModuleHud.ModuleList

    override fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // 如果组件或配置被禁用，不绘制
        if (!enabled || !config.enabled) return

        val client = mc
        // 获取所有已启用的模块，排除 HUD 和 ClickGUI 自身
        val modules = ModuleManager.getModules()
            .filter { it.enabled && it.name != "ClickGUI" && it.name != "HUD" }
            .sortedByDescending { it.name.length }  // 按名称长度从长到短排序

        // 限制显示数量
        val maxDisplay = config.maxDisplay
        val displayModules = if (maxDisplay > 0) modules.take(maxDisplay) else modules
        if (displayModules.isEmpty()) return

        val font = client.font
        val scWidth = client.window.guiScaledWidth
        val scHeight = client.window.guiScaledHeight

        // 读取配置参数
        val padding = config.padding
        val lineSpacing = config.lineSpacing
        val cornerRadius = config.cornerRadius.toFloat()
        val textColor = config.textColor
        val bgColorRaw = config.backgroundColor
        val bgAlpha = config.backgroundAlpha.coerceIn(0, 255)
        val position = config.position // 0=右上,1=右下,2=左上,3=左下

        // 计算每行文本宽度和总尺寸
        val lines = displayModules.map { it.name to font.width(it.name) }
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (font.lineHeight + lineSpacing) - lineSpacing
        val boxWidth = maxWidth + padding * 2
        val boxHeight = totalHeight + padding * 2

        // 计算位置（根据配置的 position）
        val xPos = when (position) {
            2, 3 -> padding                     // 左侧
            else -> scWidth - boxWidth - padding // 右侧
        }
        val yPos = when (position) {
            2, 0 -> padding + 10                // 顶部
            else -> scHeight - boxHeight - padding - 30 // 底部
        }

        // 合成背景颜色（使用配置的透明度）
        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        // 绘制圆角矩形背景
        drawRoundedRect(ctx, xPos.toFloat(), yPos.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius, bgColor)

        // 绘制所有模块名称（使用 ctx.text，与 ClickGuiScreen 一致）
        var currentY = yPos + padding
        for ((text, _) in lines) {
            ctx.text(font, text, (xPos + padding).toInt(), currentY.toInt(), textColor)
            currentY += font.lineHeight + lineSpacing
        }
    }

    /**
     * 绘制圆角矩形（完全复制 ClickGuiScreen 的实现，保证兼容）
     */
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
