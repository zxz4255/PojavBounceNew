/*
 * LiquidBounce Nextgen (PojavBounve) - ArrayList Module
 * 
 * 功能：在屏幕右上角按长度从长到短、从上到下排列显示所有已开启的功能模块。
 * 特性：可调整大小、最大显示数、字体颜色、背景，使用原版字体渲染。
 * 右键模块可打开详细设置面板。
 */

package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleInfo
import net.ccbluex.liquidbounce.features.value.*
import net.ccbluex.liquidbounce.ui.font.Fonts
import net.ccbluex.liquidbounce.utils.render.RenderUtils
import net.ccbluex.liquidbounce.utils.render.ColorUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

@ModuleInfo(
    name = "ArrayList",
    description = "在屏幕右上角显示所有已开启的功能模块列表",
    category = ModuleCategory.HUD,
    canEnable = true
)
class ArrayListModule : Module() {

    // ==================== 设置定义 ====================
    // 列表整体缩放比例
    private val scaleValue = FloatValue("Scale", 1.0f, 0.5f, 3.0f)

    // 最大显示模块数量 (-1 表示无限制)
    private val maxDisplayValue = IntegerValue("MaxDisplay", -1, -1, 50)

    // 字体颜色（包含 Alpha）
    private val fontColorValue = ColorValue("FontColor", Color(255, 255, 255, 255).rgb)

    // 是否启用文本阴影
    private val shadowValue = BoolValue("Shadow", true)

    // 背景颜色（包含 Alpha）
    private val backgroundColorValue = ColorValue("BackgroundColor", Color(0, 0, 0, 100).rgb)

    // 背景是否画矩形边框
    private val backgroundBorderValue = BoolValue("Border", false)

    // 边框颜色
    private val borderColorValue = ColorValue("BorderColor", Color(60, 60, 60, 150).rgb)

    // 排序模式：0 = 按名称长度降序，1 = 按名称字母升序，2 = 按模块启用时间降序
    private val sortModeValue = ListValue("SortMode", arrayOf("Length", "Alphabet", "EnableTime"), "Length")

    // 字体选择：原版 / 客户端自定义平滑字体 (如有)
    private val fontStyleValue = ListValue("FontStyle", arrayOf("Minecraft", "Smooth"), "Minecraft")

    // 圆角半径（背景矩形）
    private val borderRadiusValue = FloatValue("BorderRadius", 3.0f, 0.0f, 8.0f)

    // 内边距（文字与背景边缘的距离）
    private val paddingValue = FloatValue("Padding", 4.0f, 1.0f, 10.0f)

    // 行间距
    private val lineSpacingValue = FloatValue("LineSpacing", 2.0f, 0.0f, 8.0f)

    // 显示模块总数
    private val showTotalCountValue = BoolValue("ShowTotalCount", false)

    // 背景透明度单独调整（与背景颜色Alpha结合）
    private val backgroundAlphaValue = IntegerValue("BgAlpha", 100, 0, 255)

    // 记录每个模块的启用时间戳（用于排序）
    private val moduleEnableTimeMap = mutableMapOf<String, Long>()

    // 用于动画的平滑 Y 偏移缓存
    private val moduleYCache = mutableMapOf<String, Float>()

    // ==================== 初始化 ====================
    init {
        // 注册事件监听
        LiquidBounce.eventManager.registerListener(this)
    }

    // ==================== 事件处理 ====================
    /**
     * 监听模块启用事件，记录启用时间
     */
    @EventTarget
    fun onModuleEnable(event: ModuleEvent) {
        if (event.module != this && event.module.state) {
            moduleEnableTimeMap[event.module.name] = System.currentTimeMillis()
        }
    }

    /**
     * 主要渲染逻辑，在 2D 屏幕渲染事件中绘制
     */
    @EventTarget
    fun onRender2D(event: Render2DEvent) {
        if (!state) return

        val mc = Minecraft.getMinecraft()
        if (mc.thePlayer == null || mc.theWorld == null) return

        // 获取当前启用的模块（不包括自身）
        val enabledModules = LiquidBounce.moduleManager.modules
            .filter { it.state && it != this }
            .toMutableList()

        if (enabledModules.isEmpty()) return

        // 根据排序模式排序
        sortModules(enabledModules)

        // 限制最大显示数量
        val maxDisplay = maxDisplayValue.get()
        val displayModules = if (maxDisplay > 0) enabledModules.take(maxDisplay) else enabledModules

        // 获取字体渲染器
        val fontRenderer = getSelectedFont()

        // 计算缩放后的基准值
        val scale = scaleValue.get().coerceIn(0.5f, 3.0f)
        val padding = paddingValue.get() * scale
        val lineSpacing = lineSpacingValue.get() * scale
        val borderRadius = borderRadiusValue.get() * scale

        // 预先计算所有文本宽度，找出最长宽度
        var maxTextWidth = 0f
        val textWidths = mutableListOf<Float>()
        for (module in displayModules) {
            val displayName = getModuleDisplayName(module)
            val width = fontRenderer.getStringWidth(displayName) * scale
            textWidths.add(width)
            if (width > maxTextWidth) maxTextWidth = width
        }

        // 背景宽度 = 最长文本宽度 + 左右内边距
        val backgroundWidth = maxTextWidth + padding * 2
        // 背景高度 = (文字高度 + 行间距) * 行数 - 行间距（最后一行不需要行间距）+ 上下内边距
        val textHeight = fontRenderer.FONT_HEIGHT * scale
        val totalLineHeight = textHeight + lineSpacing
        val backgroundHeight = (totalLineHeight * displayModules.size - lineSpacing) + padding * 2

        // 计算绘制起始点：屏幕右上角，距离边缘一定距离
        val margin = 2.0f * scale
        val startX = event.scaledResolution.scaledWidth - backgroundWidth - margin
        val startY = margin

        // 保存当前矩阵状态
        GlStateManager.pushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)

        // 绘制背景
        drawBackground(
            startX, startY,
            backgroundWidth, backgroundHeight,
            borderRadius
        )

        // 绘制每个模块的文字
        var currentY = startY + padding
        for ((index, module) in displayModules.withIndex()) {
            val displayName = getModuleDisplayName(module)
            val textWidth = textWidths[index]

            // 文字水平靠右对齐（背景内右侧）
            val textX = startX + backgroundWidth - padding - textWidth

            // 平滑 Y 轴动画
            val targetY = currentY
            val smoothY = moduleYCache.getOrPut(module.name) { targetY }
            val animatedY = smoothY + (targetY - smoothY) * 0.3f
            moduleYCache[module.name] = animatedY

            // 渲染文字
            drawModuleText(
                fontRenderer,
                displayName,
                textX,
                animatedY,
                scale,
                textWidth,
                fontColorValue.get()
            )

            currentY += totalLineHeight
        }

        GlStateManager.enableAlpha()
        GlStateManager.disableBlend()
        GlStateManager.popMatrix()
    }

    // ==================== 私有绘制方法 ====================
    /**
     * 绘制列表背景矩形（支持圆角和边框）
     */
    private fun drawBackground(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        val bgColor = backgroundColorValue.get()
        val alpha = backgroundAlphaValue.get().coerceIn(0, 255)
        // 重新组合颜色，使用手动设置的背景透明度
        val color = Color(
            (bgColor shr 16) and 0xFF,
            (bgColor shr 8) and 0xFF,
            bgColor and 0xFF,
            alpha
        )

        // 画圆角矩形
        RenderUtils.drawRoundedRect(x, y, x + width, y + height, radius, color.rgb)

        // 绘制边框（如果启用）
        if (backgroundBorderValue.get()) {
            val borderColor = borderColorValue.get()
            RenderUtils.drawRoundedRectOutline(
                x, y, x + width, y + height, radius, 1.5f, borderColor
            )
        }
    }

    /**
     * 绘制模块文字（支持阴影和原版字体风格）
     */
    private fun drawModuleText(
        font: FontRenderer,
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        maxWidth: Float,
        color: Int
    ) {
        GlStateManager.pushMatrix()
        GlStateManager.translate(x, y, 0f)
        GlStateManager.scale(scale, scale, 1f)

        // 绘制阴影（如果启用且不是透明）
        if (shadowValue.get() && (color ushr 24) > 0) {
            font.drawString(text, 1f, 1f, Color(0, 0, 0, (color ushr 24) / 2).rgb)
        }

        // 绘制主文字
        font.drawString(text, 0f, 0f, color)

        GlStateManager.popMatrix()
    }

    /**
     * 获取模块的显示名称（可包含额外信息）
     */
    private fun getModuleDisplayName(module: Module): String {
        val baseName = module.name
        // 如果开启了显示总数，在文本末尾附加模块计数（示例）
        return if (showTotalCountValue.get()) {
            val enabledCount = LiquidBounce.moduleManager.modules.count { it.state }
            "$baseName [$enabledCount]"
        } else {
            baseName
        }
    }

    /**
     * 根据设置返回当前使用的字体渲染器
     */
    private fun getSelectedFont(): FontRenderer {
        return when (fontStyleValue.get().lowercase()) {
            "smooth" -> Fonts.smoothFont ?: Minecraft.getMinecraft().fontRendererObj
            else -> Minecraft.getMinecraft().fontRendererObj
        }
    }

    /**
     * 模块排序逻辑
     */
    private fun sortModules(modules: MutableList<Module>) {
        when (sortModeValue.get().lowercase()) {
            "length" -> {
                modules.sortByDescending { getModuleDisplayName(it).length }
            }
            "alphabet" -> {
                modules.sortBy { getModuleDisplayName(it).lowercase() }
            }
            "enabletime" -> {
                modules.sortByDescending { moduleEnableTimeMap[it.name] ?: 0L }
            }
        }
    }

    // ==================== 模块启用/禁用 ====================
    override fun onEnable() {
        // 初始化所有已启用模块的时间戳
        for (mod in LiquidBounce.moduleManager.modules) {
            if (mod.state && mod != this) {
                if (!moduleEnableTimeMap.containsKey(mod.name)) {
                    moduleEnableTimeMap[mod.name] = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onDisable() {
        // 清理缓存的平滑位置数据
        moduleYCache.clear()
    }

    // ==================== 辅助：将设置暴露给右键菜单 ====================
    // LiquidBounce Nextgen 会自动根据模块内的 Value 实例生成设置面板，
    // 因此只需要将所有 Value 定义为类成员即可。
    // 以下显式地覆盖 getValues() 以确保所有设置项被正确识别（若框架需要）
    override fun getValues(): List<Value<*>> {
        return listOf(
            scaleValue, maxDisplayValue, fontColorValue, shadowValue,
            backgroundColorValue, backgroundBorderValue, borderColorValue,
            sortModeValue, fontStyleValue, borderRadiusValue, paddingValue,
            lineSpacingValue, showTotalCountValue, backgroundAlphaValue
        )
    }

    // 如果框架要求用 @Value 注解，此处已直接使用 Value 对象，兼容常见版本。
}
