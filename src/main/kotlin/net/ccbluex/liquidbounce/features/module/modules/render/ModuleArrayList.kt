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
 *
 *  【ModuleArrayList77 新增】
 *  - Arraylist 位置支持正/负数自定义 (负数=从屏幕右/下边缘回退)
 *  - Glow 边缘发光 (参考 Solstice 描边多层写法): 总开关 / 强度 / 范围 / 密度 / 位置偏移,
 *    可选 每条模块背景边缘 / 整个列表背景边缘, 颜色跟随文字颜色模式
 *  - 背景边缘渲染模式: Glow(发光) / Shadow(阴影) / Both / None,
 *    Edge Size 自定义边缘带大小
 *  - Shadow 阴影: 黑色沿边缘多层描边(同 Glow 写法, 非偏移叠影),
 *    支持范围 / 强度 / 密度 / 位置偏移
 *  - 水印位置同样支持负数自定义
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

object ModuleArrayList : ClientModule("ArrayList[fix+skid]", ModuleCategories.RENDER) {
    init { enabled = true }

    /* ============================= 可调节项 ============================= */

    private enum class Side(override val tag: String) : Tagged { LEFT("Left"), RIGHT("Right") }

    // 边缘渲染模式 (Glow = 参考 Solstice 描边发光; Shadow = 黑色沿边缘多层描边)
    private enum class EdgeMode(override val tag: String) : Tagged {
        NONE("None"), GLOW("Glow"), SHADOW("Shadow"), BOTH("Both")
    }

    // Glow 发光目标: 背景边缘 / 字体 / 两者
    private enum class GlowMode(override val tag: String) : Tagged {
        EDGE("Edge"), TEXT("Text"), BOTH("Both")
    }

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

    // Bar 左右方向 (【修复】独立于 Side 切换 Bar 在条目左侧/右侧)
    private enum class BarSide(override val tag: String) : Tagged {
        AUTO("Auto"), LEFT("Left"), RIGHT("Right")
    }

    // 水印颜色模式
    private enum class WaterMarkColorMode(override val tag: String) : Tagged {
        SKY_BLUE("SkyBlue"), FADE("Fade"), RAINBOW("Rainbow"), CUSTOM("Custom")
    }

    // —— 布局 (支持正负数: 负数 = 从屏幕右/下边缘回退) ——
    private val side by enumChoice("Side", Side.RIGHT)
    private val offsetX by int("Offset X", 4, -2000..2000)
    private val offsetY by int("Offset Y", 4, -2000..2000)
    private val spacing by int("Spacing", 0, 0..12)
    private val padding by int("Padding", 3, 0..16)
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
    private val barSide by enumChoice("Bar Side", BarSide.AUTO)   // 【修复】Bar 方向独立切换

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 50f, 1f..50f)
    private val slideIn by boolean("Slide In", true)

    // —— Glow 边缘发光 (参考 Solstice 描边多层写法, 颜色跟随文字颜色模式) ——
    private val glowEnabled by boolean("Glow", false)
    private val glowMode by enumChoice("Glow Mode", GlowMode.EDGE)   // 【新增】字体发光模式
    private val glowRange by float("Glow Range", 6f, 0f..24f)
    private val glowStrength by float("Glow Strength", 1.9f, 0f..5f)
    private val glowDensity by int("Glow Density", 2, 1..12)
    private val glowOffsetX by float("Glow Offset X", 0f, -16f..16f)
    private val glowOffsetY by float("Glow Offset Y", 0f, -16f..16f)

    // —— 背景边缘渲染模式 + 自定义边缘大小 (Edge Size 缩放边缘带厚度) ——
    private val perItemEdgeMode by enumChoice("Item Edge Mode", EdgeMode.NONE)
    private val listEdgeMode by enumChoice("List Edge Mode", EdgeMode.NONE)
    private val edgeSize by float("Edge Size", 8f, 0f..32f)

    // —— Shadow 阴影 (黑色沿边缘多层描边, 同 Glow 写法) ——
    private val shadowEnabled by boolean("Shadow", false)
    private val shadowRange by float("Shadow Range", 6f, 0f..24f)
    private val shadowOffsetX by float("Shadow Offset X", 3f, -20f..20f)
    private val shadowOffsetY by float("Shadow Offset Y", 3f, -20f..20f)
    private val shadowStrength by float("Shadow Strength", 0.5f, 0.1f..2f)
    private val shadowDensity by int("Shadow Density", 3, 0..10)

    // ==================== 水印 ====================
    private val waterMarkEnabled by boolean("WaterMark", true)
    private val waterMarkText by text("WaterMark Text", "LiquidBounce0.39")
    private val waterMarkScale by float("WaterMark Scale", 1.0f, 0.5f..3.0f)
    private val waterMarkX by int("WaterMark X", 4, -2000..2000)
    private val waterMarkY by int("WaterMark Y", 4, -2000..2000)
    private val waterMarkBgAlpha by int("WaterMark Bg Alpha", 80, 0..255)
    private val waterMarkColorMode by enumChoice("WM Color Mode", WaterMarkColorMode.FADE)
    private val waterMarkCustomColor by color("WM Custom Color", Color4b(0, 160, 255))
    private val waterMarkRainbowSpeed by float("WM Rainbow Speed", 2f, 0.1f..20f)

    /* ============================= 内部状态 ============================= */

    private class Animation(var y: Float, var slide: Float)
    private data class Entry(val module: ClientModule, val text: String, val width: Int, val height: Int)
    private data class Drawn(val entry: Entry, val y: Float, val x: Float, val color: Color4b)

    private val animations = HashMap<ClientModule, Animation>()
    private var lastFrameNs = 0L

    // 每帧绘制的条目矩形 [x1, y1, x2, y2], 供列表边缘/阴影使用
    private data class RectF(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private val drawnItemRects = mutableListOf<RectF>()

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

    /**
     * 正/负数位置解析 (right = 贴屏幕右边缘对齐):
     *  - 正数: 保持原语义 (RIGHT 侧 = 距右边缘; LEFT 侧 = 距左边缘)
     *  - 负数: 从对应边缘回退 |x| 像素
     */
    private fun resolveX(x: Int, w: Int, right: Boolean, screenW: Int): Int =
        if (x < 0) screenW + x - (if (right) 0 else w)
        else if (right) screenW - x - w
        else x

    /** 正/负数 Y 位置解析: 正数=距顶部; 负数=从屏幕底部回退 |y| 像素 */
    private fun resolveY(y: Int, h: Int, screenH: Int): Int =
        if (y < 0) screenH + y - h else y

    /**
     * 发光边缘 (写法与 drawShadowEdge 完全一致, 仅颜色/参数来源不同):
     * 多层描边逐层外扩, 内缘贴齐矩形边缘, 支持自定义位置偏移
     */
    private fun drawGlowEdge(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color4b, offX: Float, offY: Float, strength: Float, density: Int,
    ) {
        val range = glowRange
        if (range <= 0f || strength <= 0f || density <= 0) return
        if (density <= 1) {
            val w = range * 0.55f
            ctx.drawRoundedRect(
                x1 - w + offX, y1 - w + offY, x2 + w + offX, y2 + w + offY,
                w * 0.5f, Color4b.TRANSPARENT, color.alpha((strength * 255).roundToInt().coerceIn(0, 255)), w,
            )
            return
        }
        for (i in 0 until density) {
            val t = i / (density - 1).toFloat()
            val w = range * (0.3f + 0.7f * t)          // 描边厚度逐层增加
            val a = (strength * (0.55f + 0.3f * (1f - t)) * 255).roundToInt().coerceIn(0, 255)
            val off = w * 0.5f
            ctx.drawRoundedRect(
                x1 - off + offX, y1 - off + offY, x2 + off + offX, y2 + off + offY,
                w * 0.5f, Color4b.TRANSPARENT, color.alpha(a), w,
            )
        }
    }

    /**
     * 阴影 (沿边缘多层描边写法, 同 Glow, 非偏移叠影):
     * 黑色描边逐层外扩, 支持范围(Shadow Range)/强度/密度 + 自定义位置偏移
     */
    private fun drawShadowEdge(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        offX: Float, offY: Float, strength: Float, density: Int,
    ) {
        val range = shadowRange
        if (range <= 0f || strength <= 0f || density <= 0) return
        val shadowColor = Color4b(0, 0, 0, 255)
        if (density <= 1) {
            val w = range * 0.55f
            ctx.drawRoundedRect(
                x1 - w + offX, y1 - w + offY, x2 + w + offX, y2 + w + offY,
                w * 0.5f, Color4b.TRANSPARENT, shadowColor.alpha((strength * 255).roundToInt().coerceIn(0, 255)), w,
            )
            return
        }
        for (i in 0 until density) {
            val t = i / (density - 1).toFloat()
            val w = range * (0.3f + 0.7f * t)
            val a = (strength * (0.55f + 0.3f * (1f - t)) * 255).roundToInt().coerceIn(0, 255)
            val off = w * 0.5f
            ctx.drawRoundedRect(
                x1 - off + offX, y1 - off + offY, x2 + off + offX, y2 + off + offY,
                w * 0.5f, Color4b.TRANSPARENT, shadowColor.alpha(a), w,
            )
        }
    }

    /** 条目/列表边缘渲染: 按 EdgeMode 选择 Glow / Shadow / Both, Edge Size 缩放边缘大小 */
    private fun renderEdge(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color4b, mode: EdgeMode,
    ) {
        if (mode == EdgeMode.NONE) return
        // 【自定义边缘大小】Edge Size 默认 8 = 1x, 范围 0~32
        val edgeScale = (edgeSize / 8f).coerceAtLeast(0f)
        when (mode) {
            EdgeMode.NONE -> Unit
            // 边缘发光仅在 GlowMode != TEXT 时绘制 (TEXT = 纯字体发光)
            EdgeMode.GLOW -> if (glowEnabled && glowMode != GlowMode.TEXT && edgeScale > 0f) {
                drawGlowEdge(ctx, x1, y1, x2, y2, color,
                    glowOffsetX * edgeScale, glowOffsetY * edgeScale, glowStrength, glowDensity)
            }
            EdgeMode.SHADOW -> if (shadowEnabled && edgeScale > 0f) {
                drawShadowEdge(ctx, x1, y1, x2, y2,
                    shadowOffsetX * edgeScale, shadowOffsetY * edgeScale, shadowStrength, shadowDensity)
            }
            EdgeMode.BOTH -> {
                if (glowEnabled && glowMode != GlowMode.TEXT && edgeScale > 0f) {
                    drawGlowEdge(ctx, x1, y1, x2, y2, color,
                        glowOffsetX * edgeScale, glowOffsetY * edgeScale, glowStrength, glowDensity)
                }
                if (shadowEnabled && edgeScale > 0f) {
                    drawShadowEdge(ctx, x1, y1, x2, y2,
                        shadowOffsetX * edgeScale, shadowOffsetY * edgeScale, shadowStrength, shadowDensity)
                }
            }
        }
    }

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
        val screenHeight = context.guiHeight()
        val fontHeight = font.lineHeight

        // 构建条目
        val entries = modules.map { module ->
            val text = if (upperCase) module.name.uppercase() else module.name
            Entry(module, text, font.width(text), fontHeight + 4)
        }

        val barEnabled = barMode != BarMode.NONE && barWidth > 0
        val barGap = if (barEnabled) barWidth + 3f else 0f

        // 【ModuleArrayList77】负数 Y = 整个列表底部距屏幕下边缘回退
        val totalH = entries.sumOf { it.height + spacing } - (if (entries.isNotEmpty()) spacing else 0)
        var cursorY = (if (offsetY < 0) screenHeight + offsetY - totalH else offsetY).toFloat()

        // 逐条目计算动画位置与颜色
        val drawn = mutableListOf<Drawn>()
        entries.forEachIndexed { index, entry ->
            val targetY = cursorY
            cursorY += entry.height + spacing

            val anim = animations.getOrPut(entry.module) { Animation(targetY, 0f) }
            anim.y += (targetY - anim.y) * smoothing
            anim.slide += (1f - anim.slide) * smoothing

            val itemWidth = entry.width + barGap + padding * 2f
            // 【ModuleArrayList77】正负数位置: 负数 = 从屏幕右/下边缘回退
            val offX = resolveX(offsetX, itemWidth.roundToInt(), side == Side.RIGHT, screenWidth)
            val baseX = offX.toFloat()
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

            drawnItemRects.clear()

            // 绘制每条模块
            drawn.forEach { d ->
                // 【修复】Bar 方向独立切换: AUTO 跟随 Side, 也可手动 LEFT/RIGHT
                val barLeft = when (barSide) {
                    BarSide.AUTO -> side == Side.LEFT
                    BarSide.LEFT -> true
                    BarSide.RIGHT -> false
                }
                val barX = if (barLeft) d.x
                else d.x + d.entry.width + barGap + padding * 2f - padding - barWidth
                val textX = if (barLeft) d.x + barWidth + 3f + padding
                else d.x + padding
                val textY = d.y + (d.entry.height - fontHeight) / 2f

                // 背景
                val bgX = d.x
                val bgY = d.y
                val bgW = d.entry.width + barGap + padding * 2f
                val bgH = d.entry.height.toFloat()

                // —— 单条目边缘渲染 (Glow 颜色跟随文字颜色模式) ——
                renderEdge(context, bgX, bgY, bgX + bgW, bgY + bgH, d.color, perItemEdgeMode)

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

                // —— 边框 ——
                if (border) {
                    context.drawQuad(bgX, bgY, bgX + bgW, bgY + 1f, d.color, Color4b.TRANSPARENT)
                    context.drawQuad(bgX, bgY + bgH - 1f, bgX + bgW, bgY + bgH, d.color, Color4b.TRANSPARENT)
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

                // —— 字体发光 (GlowMode.TEXT / BOTH) ——
                if (glowEnabled && glowMode != GlowMode.EDGE) {
                    val tw = font.width(d.entry.text).toFloat()
                    val th = fontHeight.toFloat()
                    drawGlowEdge(
                        context,
                        textX.toFloat(), textY.toFloat(), textX.toFloat() + tw, textY.toFloat() + th,
                        d.color, glowOffsetX, glowOffsetY, glowStrength, glowDensity,
                    )
                }

                // 文字
                context.text(font, d.entry.text, textX.roundToInt(), textY.roundToInt(), textDrawColor, textShadow)

                drawnItemRects += RectF(bgX, bgY, bgX + bgW, bgY + bgH)
            }

            // —— 整个列表的边缘渲染 (沿列表整体外轮廓, 小外扩包住列表) ——
            if (listEdgeMode != EdgeMode.NONE && drawnItemRects.isNotEmpty()) {
                val minX = drawnItemRects.minOf { it.x1 } - 3f
                val maxX = drawnItemRects.maxOf { it.x2 } + 3f
                val minY = drawnItemRects.minOf { it.y1 } - 2f
                val maxY = drawnItemRects.maxOf { it.y2 } + 2f
                val listColor = resolveColor(0, minY)
                renderEdge(context, minX, minY, maxX, maxY, listColor, listEdgeMode)
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
        // 【ModuleArrayList77】水印位置支持负数: 负数 = 从屏幕右/下边缘回退
        val wmW = f.width(wmText) * wmScale + wmPad * 2f
        val wmH = f.lineHeight * wmScale + wmPad * 2f
        val wmBgX = resolveX(waterMarkX, wmW.roundToInt(), false, ctx.guiWidth()).toFloat() - wmPad
        val wmBgY = resolveY(waterMarkY, wmH.roundToInt(), ctx.guiHeight()).toFloat() - wmPad
        val wmBgW = wmW
        val wmBgH = wmH

        ctx.drawRoundedRect(
            wmBgX, wmBgY, wmBgX + wmBgW, wmBgY + wmBgH,
            2f * wmScale,
            Color4b(0, 0, 0, waterMarkBgAlpha), Color4b.TRANSPARENT, 0f
        )

        val wmTime = (System.currentTimeMillis() % 100000) / 1000f
        // 【修复】FADE 模式返回 null → 走逐字符蓝→白渐变 (原来整条同色呼吸, 无渐变)
        val wmTextColor: Int? = when (waterMarkColorMode) {
            WaterMarkColorMode.SKY_BLUE -> SKY_BLUE.argb
            WaterMarkColorMode.FADE -> null
            WaterMarkColorMode.RAINBOW -> {
                val hue = (wmTime * 60f * waterMarkRainbowSpeed) % 360f
                hueColor(hue).argb
            }
            WaterMarkColorMode.CUSTOM -> waterMarkCustomColor.argb
        }

        val wmTextX = wmBgX + wmPad
        val wmTextY = wmBgY + wmPad
        if (wmTextColor != null) {
            ctx.text(f, wmText, wmTextX.roundToInt(), wmTextY.roundToInt(), wmTextColor, textShadow)
        } else {
            // 【修复】FADE: 水印文字从左到右 天蓝→白色 渐变
            var cx = wmTextX
            for (i in wmText.indices) {
                val ch = wmText[i].toString()
                val t = if (wmText.length > 1) i / (wmText.length - 1).toFloat() else 0f
                val c = lerpColor(SKY_BLUE, WHITE, t)
                ctx.text(f, ch, cx.roundToInt(), wmTextY.roundToInt(), c.argb, textShadow)
                cx += f.width(ch)
            }
        }
    }

    /* ============================= 工具函数 ============================= */

    private fun resolveColor(index: Int, y: Float): Color4b {
        val time = (System.currentTimeMillis() % 100000) / 1000f
        return when (colorMode) {
            ColorMode.CUSTOM -> customColor
            ColorMode.RAINBOW -> hueColor(time * 36f * rainbowSpeed + index * rainbowOffset)
            // 【修复】FADE: 随时间流动的渐变色 (原来每条固定色相, 无渐变效果)
            ColorMode.FADE -> hueColor(time * 36f * rainbowSpeed + index * rainbowOffset, saturation = 0.65f)
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