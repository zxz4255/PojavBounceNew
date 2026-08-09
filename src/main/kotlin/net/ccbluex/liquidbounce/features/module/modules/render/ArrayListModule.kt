/*
 * LiquidBounce Nextgen - ArrayList Module
 * 独立功能列表，使用 @EventTarget 监听渲染事件，配置项完整。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.values.*
import net.ccbluex.liquidbounce.event.EventTarget
import net.ccbluex.liquidbounce.event.Render2DEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object ArrayListModule : ClientModule() {

    override val name = "ArrayList"
    override val category = ModuleCategory.RENDER

    // ==================== 配置项 ====================
    private val scaleValue = FloatValue("Scale", 1.0f, 0.5f, 3.0f)
    private val maxDisplayValue = IntValue("MaxDisplay", -1, -1, 50)
    private val textColorValue = ColorValue("TextColor", Color.WHITE)
    private val backgroundColorValue = ColorValue("BackgroundColor", Color(0, 0, 0, 100))
    private val backgroundAlphaValue = IntValue("BgAlpha", 100, 0, 255)
    private val cornerRadiusValue = FloatValue("CornerRadius", 3.0f, 0.0f, 8.0f)
    private val paddingValue = FloatValue("Padding", 4.0f, 1.0f, 10.0f)
    private val lineSpacingValue = FloatValue("LineSpacing", 2.0f, 0.0f, 8.0f)
    private val sortModeValue = ListValue("SortMode", arrayOf("Length", "Alphabet", "EnableTime"), "Length")
    private val showTotalCountValue = BoolValue("ShowTotalCount", false)

    // 记录启用时间
    private val enableTimeMap = mutableMapOf<String, Long>()
    private val lastState = mutableMapOf<String, Boolean>()

    // 渲染事件
    @EventTarget
    fun onRender2D(event: Render2DEvent) {
        render(event.graphics)
    }

    private fun render(ctx: GuiGraphicsExtractor) {
        if (!enabled) return

        val modules = ModuleManager.getModules()
            .filter { it.enabled && it != this && it.name != "ClickGUI" && it.name != "HUD" }
            .toMutableList()

        if (modules.isEmpty()) return

        // 更新启用时间
        modules.forEach { mod ->
            val cur = mod.enabled
            val prev = lastState[mod.name] ?: false
            if (cur && !prev) enableTimeMap[mod.name] = System.currentTimeMillis()
            lastState[mod.name] = cur
        }

        // 排序
        when (sortModeValue.get().lowercase()) {
            "alphabet" -> modules.sortBy { it.name.lowercase() }
            "enabletime" -> modules.sortByDescending { enableTimeMap[it.name] ?: 0L }
            else -> modules.sortByDescending { it.name.length }
        }

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

        val lines = displayModules.map { mod ->
            val displayName = if (showTotalCountValue.get()) {
                val count = ModuleManager.getModules().count { it.enabled }
                "${mod.name} [$count]"
            } else mod.name
            displayName to font.width(displayName) * s
        }

        val maxWidth = lines.maxOfOrNull { it.second } ?: 0f
        val totalHeight = lines.size * (font.lineHeight * s + lineGap) - lineGap
        val boxW = maxWidth + pad * 2
        val boxH = totalHeight + pad * 2

        val margin = 4f * s
        val x = scW - boxW - margin
        val y = margin

        val bgColor = (backgroundColorValue.get().rgb and 0x00FFFFFF) or ((backgroundAlphaValue.get() shl 24) and 0xFF000000.toInt())
        drawRoundedRect(ctx, x, y, boxW, boxH, radius, bgColor)

        var curY = y + pad
        val textColor = textColorValue.get().rgb
        for ((text, width) in lines) {
            val tx = x + boxW - pad - width
            ctx.text(font, text, tx.toInt(), curY.toInt(), textColor)
            curY += font.lineHeight * s + lineGap
        }
    }

    // ==================== 绘图工具（来自 ClickGuiScreen） ====================
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
        val x1 = x; val y1 = y; val x2 = x + w; val y2 = y + h
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

    // 初始化时间戳
    init {
        ModuleManager.getModules().forEach { mod ->
            if (mod.enabled && mod != this) {
                enableTimeMap[mod.name] = System.currentTimeMillis()
                lastState[mod.name] = true
            }
        }
    }
}
