/*
 * ============================================================================
 *  ArrayList —— 仿 Opal v2 风格的模块列表 HUD (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39)
 *
 *  修改:
 *  - 按像素宽度从长到短排序
 *  - 每条模块独立矩形背景
 *  - 文字颜色模式: CUSTOM / RAINBOW / FADE / SKY / RAINBOW_TEXT / FADE2 / LB / SOLSTICE (新增)
 *  - Bar 模式: NONE / SOLID / GRADIENT / FOLLOW(跟随文字) / CUSTOM(自定义)
 *  - 自定义整体大小 Scale
 *  - 水印支持多种颜色模式: SkyBlue / Fade / Rainbow / Custom
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
import net.ccbluex.liquidbounce.render.withPush
import java.awt.Color
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleArrayList : ClientModule("ArrayList【Skid+fix】", ModuleCategories.RENDER) {
    init { enabled = true }

    /* ============================= 可调节项 ============================= */

    private enum class Side(override val tag: String) : Tagged { LEFT("Left"), RIGHT("Right") }

    // 文字颜色模式
    private enum class ColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"), RAINBOW("Rainbow"), FADE("Fade"), SKY("Sky"),
        RAINBOW_TEXT("RainbowText"),  // 彩虹渐变文字
        FADE2("Fade2"),              // 白色 ↔ 天蓝色渐变循环
        LB("LB"),                    // 文字白色，Bar 天蓝色
        SOLSTICE("Solstice")         // 【新增】Solstice 三色渐变 (粉‑蓝‑白)
    }

    private enum class SortMode(override val tag: String) : Tagged {
        LENGTH("Length"), ALPHABETICAL("Alphabetical"), NONE("None")
    }

    // Bar 模式
    private enum class BarMode(override val tag: String) : Tagged {
        NONE("None"), SOLID("Solid"), GRADIENT("Gradient"),
        FOLLOW("Follow"),   // 跟随文字颜色模式
        CUSTOM("Custom")    // 自定义 Bar 颜色
    }

    // 水印颜色模式
    private enum class WaterMarkColorMode(override val tag: String) : Tagged {
        SKY_BLUE("SkyBlue"), FADE("Fade"), RAINBOW("Rainbow"), CUSTOM("Custom")
    }

    // —— 布局 ——
    private val side by enumChoice("Side", Side.RIGHT)
    private val offsetX by int("Offset X", 4, 0..500)
    private val offsetY by int("Offset Y", 4, 0..500)
    private val spacing by int("Spacing", 2, 0..12)
    private val padding by int("Padding", 4, 0..16)
    private val customScale by float("Scale", 1.0f, 0.5f..3.0f)
    private val upperCase by boolean("Uppercase", false)
    private val sortMode by enumChoice("Sort Mode", SortMode.LENGTH)
    private val showSelf by boolean("Show Self", true)

    // —— 外观 ——
    private val textShadow by boolean("Text Shadow", true)
    private val background by boolean("Background", true)
    private val backgroundAlpha by int("Background Alpha", 80, 0..255)
    private val backgroundRadius by int("Background Radius", 2, 0..6)
    private val border by boolean("Border", false)

    // —— 颜色 ——
    private val colorMode by enumChoice("Color Mode", ColorMode.SKY)
    private val customColor by color("Color", Color4b(0, 160, 255))
    private val rainbowSpeed by float("Rainbow Speed", 6f, 0.1f..10f)
    private val rainbowOffset by int("Rainbow Offset", 5, 0..90)
    private val rainbowTextSpeed by float("Rainbow Text Speed", 6f, 0.1f..20f)

    // —— 装饰条 ——
    private val barMode by enumChoice("Bar Mode", BarMode.FOLLOW)
    private val barWidth by int("Bar Width", 2, 0..8)
    private val barCustomColor by color("Bar Color", Color4b.WHITE)

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 50f, 1f..50f)
    private val slideIn by boolean("Slide In", true)

    // ==================== 水印 ====================
    private val waterMarkEnabled by boolean("WaterMark", true)
    private val waterMarkText by text("WaterMark Text", "LiquidBounce0.39")
    private val waterMarkScale by float("WaterMark Scale", 1.0f, 0.5f..3.0f)
    private val waterMarkX by int("WaterMark X", 4, 0..2000)
    private val waterMarkY by int("WaterMark Y", 4, 0..2000)
    private val waterMarkBgAlpha by int("WaterMark Bg Alpha", 80, 0..255)
    private val waterMarkColorMode by enumChoice("WM Color Mode", WaterMarkColorMode.SKY_BLUE)
    private val waterMarkCustomColor by color("WM Custom Color", Color4b(0, 160, 255))
    private val waterMarkRainbowSpeed by float("WM Rainbow Speed", 2f, 0.1f..20f)

    /* ============================= 内部状态 ============================= */

    private class Animation(var y: Float, var slide: Float)
    private data class Entry(val module: ClientModule, val text: String, val width: Int, val height: Int)
    private data class Drawn(val entry: Entry, val y: Float, val x: Float, val color: Color4b)

    private val animations = HashMap<ClientModule, Animation>()
    private var lastFrameNs = 0L

    private val WHITE = Color4b(255, 255, 255, 255)
    private val SKY_BLUE = Color4b(0, 160, 255, 255)

    // ----- 新增：Solstice 三色板 (取自 ModuleSolsticeArraylist) -----
    private val SOLSTICE_COLORS = listOf(
        Color4b(0xE9, 0xA8, 0xBC),    // 粉
        Color4b(0x6E, 0xC8, 0xF1),    // 蓝
        Color4b(255, 255, 255)        // 白
    )
    // ------------------------------------------------------------

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

        // ----- 水印（在列表之前）-----
        if (waterMarkEnabled) renderWaterMark(context, font)

        // 收集已启用模块
        var modules = ModuleManager.getModules()
            .filter { it.enabled && !it.hidden && (showSelf || it !== self) }
            .toList()

        animations.keys.retainAll(modules)

        // 排序：按像素宽度从长到短
        modules = when (sortMode) {
            SortMode.LENGTH -> modules.sortedByDescending { mod ->
                val displayName = if (upperCase) mod.name.uppercase() else mod.name
                font.width(displayName)
            }
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

            val itemWidth = entry.width + barGap + padding * 2f
            val baseX = when (side) {
                Side.RIGHT -> screenWidth - offsetX - itemWidth
                Side.LEFT -> offsetX.toFloat()
            }
            val x = if (slideIn) {
                val slideDistance = itemWidth + 24f
                if (side == Side.RIGHT) baseX + (1f - anim.slide) * slideDistance
                else baseX - (1f - anim.slide) * slideDistance
            } else baseX

            drawn += Drawn(entry, anim.y, x, resolveColor(index, anim.y))
        }

        // 整体缩放
        context.pose().withPush {
            if (customScale != 1f) {
                scale(customScale, customScale)
            }

            // 绘制每条模块
            drawn.forEach { d ->
                val barX = when (side) {
                    Side.RIGHT -> d.x + d.entry.width + barGap + padding * 2f - padding - barWidth
                    Side.LEFT -> d.x
                }
                val textX = when (side) {
                    Side.RIGHT -> d.x + padding
                    Side.LEFT -> d.x + barWidth + 3f + padding
                }
                val textY = d.y + (d.entry.height - fontHeight) / 2f

                // 背景
                val bgX = d.x
                val bgY = d.y
                val bgW = d.entry.width + barGap + padding * 2f
                val bgH = d.entry.height.toFloat()

                if (background) {
                    val bgColor = Color4b(0, 0, 0, backgroundAlpha)
                    if (backgroundRadius > 0) {
                        context.drawRoundedRect(
                            bgX, bgY, bgX + bgW, bgY + bgH,
                            backgroundRadius.toFloat(), bgColor, Color4b.TRANSPARENT, 0f
                        )
                    } else {
                        context.drawQuad(bgX, bgY, bgX + bgW, bgY + bgH, bgColor, Color4b.TRANSPARENT)
                    }
                }

                // —— 确定 Bar 颜色 ——
                val effectiveBarColor: Color4b = when (barMode) {
                    BarMode.CUSTOM -> barCustomColor
                    BarMode.FOLLOW -> d.color
                    else -> d.color
                }

                // —— 侧边装饰条 ——
                if (barEnabled) {
                    when (barMode) {
                        BarMode.NONE -> Unit
                        BarMode.SOLID, BarMode.FOLLOW, BarMode.CUSTOM -> context.drawQuad(
                            barX, d.y + 2f,
                            barX + barWidth, d.y + d.entry.height - 2f,
                            effectiveBarColor
                        )
                        BarMode.GRADIENT -> context.fillGradient(
                            barX.roundToInt(), (d.y + 2f).roundToInt(),
                            (barX + barWidth).roundToInt(), (d.y + d.entry.height - 2f).roundToInt(),
                            effectiveBarColor.argb, effectiveBarColor.copy(alpha = 0).argb
                        )
                    }
                }

                // —— 确定文字颜色 ——
                val textDrawColor: Int = when (colorMode) {
                    ColorMode.LB -> WHITE.argb
                    else -> d.color.argb
                }

                // 文字
                context.text(font, d.entry.text, textX.roundToInt(), textY.roundToInt(), textDrawColor, textShadow)
            }
        }
    }

    /* ============================= 水印渲染 ============================= */

    private fun renderWaterMark(context: Any, font: Any) {
        @Suppress("UNCHECKED_CAST")
        val ctx = context as? net.minecraft.client.gui.GuiGraphicsExtractor ?: return
        @Suppress("UNCHECKED_CAST")
        val f = font as? net.minecraft.client.gui.Font ?: return

        val wmText = waterMarkText
        val wmScale = waterMarkScale
        val wmPad = 4f * wmScale
        val wmBgX = waterMarkX.toFloat() - wmPad
        val wmBgY = waterMarkY.toFloat() - wmPad
        val wmBgW = f.width(wmText) * wmScale + wmPad * 2f
        val wmBgH = f.lineHeight * wmScale + wmPad * 2f

        ctx.drawRoundedRect(
            wmBgX, wmBgY, wmBgX + wmBgW, wmBgY + wmBgH,
            2f * wmScale,
            Color4b(0, 0, 0, waterMarkBgAlpha), Color4b.TRANSPARENT, 0f
        )

        val wmTime = (System.currentTimeMillis() % 100000) / 1000f
        val wmTextColor = when (waterMarkColorMode) {
            WaterMarkColorMode.SKY_BLUE -> SKY_BLUE.argb
            WaterMarkColorMode.FADE -> {
                val t = (sin(wmTime * 2.0 * waterMarkRainbowSpeed) + 1.0) / 2.0
                lerpColor(SKY_BLUE, WHITE, t.toFloat()).argb
            }
            WaterMarkColorMode.RAINBOW -> {
                val hue = (wmTime * 60f * waterMarkRainbowSpeed) % 360f
                hueColor(hue).argb
            }
            WaterMarkColorMode.CUSTOM -> waterMarkCustomColor.argb
        }

        val wmTextX = wmBgX + wmPad
        val wmTextY = wmBgY + wmPad
        ctx.text(f, wmText, wmTextX.roundToInt(), wmTextY.roundToInt(), wmTextColor, textShadow)
    }

    /* ============================= 工具函数 ============================= */

    private fun resolveColor(index: Int, y: Float): Color4b {
        val time = (System.currentTimeMillis() % 100000) / 1000f
        return when (colorMode) {
            ColorMode.CUSTOM -> customColor
            ColorMode.RAINBOW -> hueColor(time * 36f * rainbowSpeed + index * rainbowOffset)
            ColorMode.FADE -> hueColor(index * rainbowOffset.toFloat())
            ColorMode.SKY -> hueColor(y / 720f * 360f + time * 18f * rainbowSpeed, saturation = 0.65f)
            ColorMode.RAINBOW_TEXT -> {
                val hue = (time * 60f * rainbowTextSpeed + index * 20f) % 360f
                hueColor(hue)
            }
            ColorMode.FADE2 -> {
                val t = ((sin(time * 2.0 * rainbowTextSpeed) + 1.0) / 2.0).toFloat()
                lerpColor(WHITE, SKY_BLUE, t)
            }
            ColorMode.LB -> SKY_BLUE
            // ----- 新增 Solstice 分支 -----
            ColorMode.SOLSTICE -> themedColor(index.toFloat())
        }
    }

    // ----- 新增：Solstice 三色渐变（取自 ModuleSolsticeArraylist）-----
    private fun themedColor(index: Float): Color4b {
        val totalTime = 10000f / 3f          // 总循环周期（毫秒）
        val now = System.currentTimeMillis()
        val angle = ((now + index.toLong()) % totalTime.toLong()).toFloat()
        val segmentTime = totalTime / SOLSTICE_COLORS.size
        val seg = (angle / segmentTime).toInt().coerceIn(0, SOLSTICE_COLORS.size - 1)
        val t = (angle / segmentTime - seg).coerceIn(0f, 1f)
        val next = (seg + 1) % SOLSTICE_COLORS.size
        return lerpColor(SOLSTICE_COLORS[seg], SOLSTICE_COLORS[next], t)
    }
    // ----------------------------------------------------------------

    private fun hueColor(hueDeg: Float, saturation: Float = 1f, brightness: Float = 1f): Color4b {
        var hue = hueDeg % 360f
        if (hue < 0f) hue += 360f
        return Color4b(Color.getHSBColor(hue / 360f, saturation, brightness))
    }

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val tt = t.coerceIn(0f, 1f)
        return Color4b(
            (a.r + (b.r - a.r) * tt).roundToInt().coerceIn(0, 255),
            (a.g + (b.g - a.g) * tt).roundToInt().coerceIn(0, 255),
            (a.b + (b.b - a.b) * tt).roundToInt().coerceIn(0, 255),
            (a.a + (b.a - a.a) * tt).roundToInt().coerceIn(0, 255)
        )
    }

    private fun Color4b.copy(red: Int = this.r, green: Int = this.g, blue: Int = this.b, alpha: Int = this.a): Color4b {
        return Color4b(red, green, blue, alpha)
    }
}