/*
 * ============================================================================
 *  ModuleArrayList —— 仿 Opal v2 风格的模块列表 HUD (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39)
 *  It was skided by XiaoDao776 and it can works on PojavBounceNew
 *  渲染: 完全原生 —— 通过 OverlayRenderEvent 拿到 GuiGraphicsExtractor,
 *        使用 drawRoundedRect / drawQuad / fillGradient / mc.font 绘制,
 *        不依赖任何 Web / 浏览器组件。
 *
 *  修改: 按文本长度从长到短排序，纯白色文本，每条模块独立矩形背景
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import java.awt.Color
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleArrayList : ClientModule("OpalArrayList", ModuleCategories.RENDER) {

    /* ============================= 可调节项 ============================= */

    private enum class Side(override val tag: String) : Tagged { LEFT("Left"), RIGHT("Right") }
    private enum class ColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"), RAINBOW("Rainbow"), FADE("Fade"), SKY("Sky")
    }

    private enum class SortMode(override val tag: String) : Tagged {
        LENGTH("Length"), ALPHABETICAL("Alphabetical"), NONE("None")
    }

    private enum class BarMode(override val tag: String) : Tagged {
        NONE("None"), SOLID("Solid"), GRADIENT("Gradient")
    }

    // —— 布局 ——
    private val side by enumChoice("Side", Side.RIGHT)
    private val offsetX by int("Offset X", 4, 0..500)
    private val offsetY by int("Offset Y", 4, 0..500)
    private val spacing by int("Spacing", 2, 0..12)
    private val padding by int("Padding", 4, 0..16)
    private val upperCase by boolean("Uppercase", true)
    private val sortMode by enumChoice("Sort Mode", SortMode.LENGTH)  // 默认按长度从长到短
    private val showSelf by boolean("Show Self", false)

    // —— 外观 ——
    private val textShadow by boolean("Text Shadow", true)
    private val background by boolean("Background", true)              // 每条模块独立背景
    private val backgroundAlpha by int("Background Alpha", 80, 0..255)
    private val backgroundRadius by int("Background Radius", 2, 0..12) // 每条背景圆角，默认更小
    private val border by boolean("Border", false)

    // —— 颜色 ——
    private val colorMode by enumChoice("Color Mode", ColorMode.CUSTOM)
    private val customColor by color("Color", Color4b(0, 160, 255))
    private val rainbowSpeed by float("Rainbow Speed", 1f, 0.1f..10f)
    private val rainbowOffset by int("Rainbow Offset", 14, 0..90)

    // —— 装饰条 ——
    private val barMode by enumChoice("Bar Mode", BarMode.GRADIENT)
    private val barWidth by int("Bar Width", 2, 0..8)

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 8f, 0.5f..30f)
    private val slideIn by boolean("Slide In", true)

    /* ============================= 内部状态 ============================= */

    private class Animation(var y: Float, var slide: Float)
    private data class Entry(val module: ClientModule, val text: String, val width: Int, val height: Int)
    private data class Drawn(val entry: Entry, val y: Float, val x: Float, val color: Color4b)

    private val animations = HashMap<ClientModule, Animation>()
    private var lastFrameNs = 0L

    // 纯白色常量
    private val WHITE_TEXT = Color4b(255, 255, 255, 255)

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font
        val self = this

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now
        val smoothing = (1f - exp(-animationSpeed * frameTime)).coerceIn(0f, 1f)

        // 收集已启用模块
        var modules = ModuleManager.getModules()
            .filter { it.enabled && !it.hidden && (showSelf || it !== self) }
            .toList()

        animations.keys.retainAll(modules)

        // 排序：按文本长度从长到短（降序）
        modules = when (sortMode) {
            SortMode.LENGTH -> modules.sortedByDescending { it.name.length }
            SortMode.ALPHABETICAL -> modules.sortedBy { it.name.lowercase() }
            SortMode.NONE -> modules
        }

        if (modules.isEmpty()) return@handler

        val screenWidth = context.guiWidth()
        val fontHeight = font.lineHeight

        // 构建条目
        val entries = modules.map { module ->
            val text = if (upperCase) module.name.uppercase() else module.name
            Entry(module, text, font.width(text), fontHeight + 4)
        }

        val barEnabled = barMode != BarMode.NONE && barWidth > 0
        val barGap = if (barEnabled) barWidth + 3f else 0f

        // 逐条目计算动画位置与颜色
        val drawn = mutableListOf<Drawn>()
        var cursorY = offsetY.toFloat()
        entries.forEachIndexed { index, entry ->
            val targetY = cursorY
            cursorY += entry.height + spacing

            val anim = animations.getOrPut(entry.module) { Animation(targetY, 0f) }
            anim.y += (targetY - anim.y) * smoothing
            anim.slide += (1f - anim.slide) * smoothing

            // 每条模块的宽度 = 文本宽度 + barGap + padding*2
            val itemWidth = entry.width + barGap + padding * 2f
            val baseX = when (side) {
                Side.RIGHT -> screenWidth - offsetX - itemWidth
                Side.LEFT -> offsetX.toFloat()
            }
            val x = if (slideIn) {
                val slideDistance = itemWidth + 24f
                if (side == Side.RIGHT) {
                    baseX + (1f - anim.slide) * slideDistance
                } else {
                    baseX - (1f - anim.slide) * slideDistance
                }
            } else {
                baseX
            }

            drawn += Drawn(entry, anim.y, x, resolveColor(index, anim.y))
        }

        // 绘制每条模块（独立矩形背景 + 文字）
        drawn.forEach { d ->
            val barX = when (side) {
                Side.RIGHT -> (d.x + d.entry.width + barGap + padding * 2f - barWidth - padding + padding).let {
                    // bar 在最右侧
                    d.x + d.entry.width + barGap + padding * 2f - padding - barWidth
                }
                Side.LEFT -> d.x
            }
            val textX = when (side) {
                Side.RIGHT -> d.x + padding
                Side.LEFT -> d.x + barWidth + 3f + padding
            }
            val textY = d.y + (d.entry.height - fontHeight) / 2f

            // 每条模块的独立背景矩形
            val bgX = d.x
            val bgY = d.y
            val bgW = d.entry.width + barGap + padding * 2f
            val bgH = d.entry.height.toFloat()

            if (background) {
                val bgColor = Color4b(0, 0, 0, backgroundAlpha)
                if (backgroundRadius > 0) {
                    context.drawRoundedRect(
                        bgX, bgY,
                        bgX + bgW, bgY + bgH,
                        backgroundRadius.toFloat(), bgColor, Color4b.TRANSPARENT, 0f
                    )
                } else {
                    context.drawQuad(bgX, bgY, bgX + bgW, bgY + bgH, bgColor, Color4b.TRANSPARENT)
                }
            }

            // 侧边装饰条
            if (barEnabled) {
                when (barMode) {
                    BarMode.NONE -> Unit
                    BarMode.SOLID -> context.drawQuad(
                        barX, d.y + 2f,
                        barX + barWidth, d.y + d.entry.height - 2f,
                        d.color
                    )
                    BarMode.GRADIENT -> context.fillGradient(
                        barX.roundToInt(), (d.y + 2f).roundToInt(),
                        (barX + barWidth).roundToInt(), (d.y + d.entry.height - 2f).roundToInt(),
                        d.color.argb, Color4b(d.color.r, d.color.g, d.color.b, 0).argb
                    )
                }
            }

            // 文字 — 纯白色
            context.text(font, d.entry.text, textX.roundToInt(), textY.roundToInt(), WHITE_TEXT.argb, textShadow)
        }
    }

    /* ============================= 工具函数 ============================= */

    private fun resolveColor(index: Int, y: Float): Color4b {
        val time = System.currentTimeMillis() / 1000f
        return when (colorMode) {
            ColorMode.CUSTOM -> customColor
            ColorMode.RAINBOW -> hueColor(time * 36f * rainbowSpeed + index * rainbowOffset)
            ColorMode.FADE -> hueColor(index * rainbowOffset.toFloat())
            ColorMode.SKY -> hueColor(y / 720f * 360f + time * 18f * rainbowSpeed, saturation = 0.65f)
        }
    }

    private fun hueColor(hueDeg: Float, saturation: Float = 1f, brightness: Float = 1f): Color4b {
        var hue = hueDeg % 360f
        if (hue < 0f) hue += 360f
        return Color4b(Color.getHSBColor(hue / 360f, saturation, brightness))
    }
}
