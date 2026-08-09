/*
 * LiquidBounce Nextgen - ArrayList Module
 * 
 * 功能：在屏幕右上角按长度从长到短排列显示所有已启用模块。
 * 配置项完整，支持缩放、颜色、背景、圆角、排序等。
 * 完全自包含，使用 mc.font 和 GuiGraphicsExtractor 绘制，兼容安卓。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.Render2DEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.value.*
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 独立 ArrayList 模块，自带全套设置，渲染不依赖任何外部工具。
 */
object ArrayListModule : ClientModule("ArrayList", ModuleCategory.RENDER) {

    // ==================== 设置项（标准 Value 类） ====================
    private val scaleValue = FloatValue("Scale", 1.0f, 0.5f..3.0f)
    private val maxDisplayValue = IntValue("MaxDisplay", -1, -1..50)   // -1 表示无限制
    private val textColorValue = ColorValue("TextColor", Color.WHITE)
    private val backgroundColorValue = ColorValue("BackgroundColor", Color(0, 0, 0, 100))
    private val backgroundAlphaValue = IntValue("BgAlpha", 100, 0..255)
    private val cornerRadiusValue = FloatValue("CornerRadius", 3.0f, 0.0f..8.0f)
    private val paddingValue = FloatValue("Padding", 4.0f, 1.0f..10.0f)
    private val lineSpacingValue = FloatValue("LineSpacing", 2.0f, 0.0f..8.0f)
    private val sortModeValue = ListValue("SortMode", listOf("Length", "Alphabet", "EnableTime"), "Length")
    private val showTotalCountValue = BoolValue("ShowTotalCount", false)

    // 记录模块启用时间（用于 EnableTime 排序）
    private val enableTimeMap = mutableMapOf<String, Long>()
    // 记录上一次检测到的启用状态，用于检测状态变化
    private val lastEnabledState = mutableMapOf<String, Boolean>()

    // 渲染事件监听
    private val renderHandler = handler<Render2DEvent> { event ->
        render(event.graphics)
    }

    // ==================== 渲染逻辑 ====================
    private fun render(ctx: GuiGraphicsExtractor) {
        if (!enabled) return

        val modules = ModuleManager.getModules()
            .filter { it.enabled && it != this && it.name != "ClickGUI" && it.name != "HUD" }
            .toMutableList()

        if (modules.isEmpty()) return

        // 更新启用时间：检测模块启用状态变化，记录变化时的时间
        modules.forEach { mod ->
            val current = mod.enabled
            val previous = lastEnabledState[mod.name] ?: false
            if (current && !previous) {
                enableTimeMap[mod.name] = System.currentTimeMillis()
            }
            lastEnabledState[mod.name] = current
        }

        // 排序
        val sortMode = sortModeValue.get()
        when (sortMode.lowercase()) {
            "alphabet" -> modules.sortBy { it.name.lowercase() }
            "enabletime" -> modules.sortByDescending { enableTimeMap[it.name] ?: 0L }
            else -> modules.sortByDescending { it.name.length } // "Length"
        }

        // 最大显示限制
        val max = maxDisplayValue.get()
        val displayModules = if (max > 0) modules.take(max) else modules
        if (displayModules.isEmpty()) return

        val font = mc.font
        val scW = mc.window.guiScaledWidth
        val scH = mc.window.guiScaledHeight

        val s = scaleValue.get()
        val pad = paddingValue.get() * s
        val lineGap = lineSpacingValue.get() * s
        val radius = cornerRadiusValue.get() * s

        // 计算每行宽度和总尺寸
        val lines = displayModules.map { module ->
            val displayName = if (showTotalCountValue.get()) {
                val count = ModuleManager.getModules().count { it.enabled }
                "${module.name} [$count]"
            } else module.name
            displayName to font.width(displayName) * s
        }
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0f
        val totalHeight = lines.size * (font.lineHeight * s + lineGap) - lineGap
        val boxW = maxWidth + pad * 2
        val boxH = totalHeight + pad * 2

        // 固定在右上角，留边距 4px
        val margin = 4f * s
        val x = scW - boxW - margin
        val y = margin

        // 背景颜色（合并透明度）
        val bgColor = (backgroundColorValue.get().getRGB() and 0x00FFFFFF) or ((backgroundAlphaValue.get() shl 24) and 0xFF000000.toInt())

        // 绘制圆角背景
        drawRoundedRect(ctx, x, y, boxW, boxH, radius, bgColor)

        // 绘制文字
        var curY = y + pad
        val textColorInt = textColorValue.get().getRGB()
        for ((text, width) in lines) {
            // 右对齐
            val tx = x + boxW - pad - width
            ctx.text(font, text, tx.toInt(), curY.toInt(), textColorInt)
            curY += font.lineHeight * s + lineGap
        }
    }

    // ==================== 绘图工具（直接取自 ClickGuiScreen，保证一致性） ====================
    private fun fillRect(ctx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        if (x2 <= x1 || y2 <= y1) return
        ctx.fill(x1, y1, x2, y2, color)
    }

    private fun fillRect(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        if (x2 <= x1 || y2 <= y1) return
        ctx.fill(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), color)
    }

    private fun drawRoundedRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        if (r <= 0.5f) {
            fillRect(ctx, x, y, x + w, y + h, color)
            return
        }
        val x1 = x
        val y1 = y
        val x2 = x + w
        val y2 = y + h
        fillRect(ctx, x1 + r, y1, x2 - r, y2, color)
        fillRect(ctx, x1, y1 + r, x1 + r, y2 - r, color)
        fillRect(ctx, x2 - r, y1 + r, x2, y2 - r, color)
        drawCorner(ctx, x1 + r, y1 + r, r, 180f, 270f, color)
        drawCorner(ctx, x2 - r, y1 + r, r, 270f, 360f, color)
        drawCorner(ctx, x2 - r, y2 - r, r, 0f, 90f, color)
        drawCorner(ctx, x1 + r, y2 - r, r, 90f, 180f, color)
    }

    private fun drawCorner(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, start: Float, end: Float, color: Int) {
        var a = start
        while (a < end) {
            val rad1 = Math.toRadians(a.toDouble())
            val rad2 = Math.toRadians((a + 6f).coerceAtMost(end).toDouble())
            val px1 = cx + (cos(rad1) * r).toFloat()
            val py1 = cy + (sin(rad1) * r).toFloat()
            val px2 = cx + (cos(rad2) * r).toFloat()
            val py2 = cy + (sin(rad2) * r).toFloat()
            val minX = cx.coerceAtMost(px1).coerceAtMost(px2).toInt()
            val maxX = cx.coerceAtLeast(px1).coerceAtLeast(px2).toInt()
            val minY = cy.coerceAtMost(py1).coerceAtMost(py2).toInt()
            val maxY = cy.coerceAtLeast(py1).coerceAtLeast(py2).toInt()
            fillRect(ctx, minX, minY, max(minX + 1, maxX), max(minY + 1, maxY), color)
            a += 6f
        }
    }

    // ==================== 模块初始化 ====================
    init {
        // 初始化所有已启用模块的时间戳，并记录状态
        ModuleManager.getModules().forEach { mod ->
            if (mod.enabled && mod != this) {
                enableTimeMap[mod.name] = System.currentTimeMillis()
                lastEnabledState[mod.name] = true
            }
        }
    }
}
