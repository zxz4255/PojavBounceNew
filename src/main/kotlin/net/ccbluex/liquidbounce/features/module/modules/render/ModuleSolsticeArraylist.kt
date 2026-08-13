/*
 * ============================================================================
 *  ModuleSolsticeArraylist —— 移植 Solstice 的 Arraylist.cpp/hpp (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  原版功能 (Arraylist.cpp, Dear ImGui):
 *   1. 右上角逐字符彩虹水印 (Solstice V4, 字符阴影 + 圆形发光)
 *   2. 模块列表 (按宽度降序, 从右滑入动画, 主题色渐变)
 *      - 四种显示模式: Outline / Bar / Split / None
 *      - 文字阴影 / 密集发光 (ShadowRectDense)
 *      - 鼠标悬停高亮 + 点击切换模块
 *
 *  移植说明:
 *   - ImGui AddText  → context.text(mc.font) (9px, baseline 语义)
 *   - AddRectFilled  → drawQuad / drawRoundedRect
 *   - AddShadowRect/Circle → 多层半透明几何近似 (drawGlowRect / drawGlowCircle)
 *   - AddLine        → drawHorizontalLine / drawVerticalLine
 *   - getThemedColor → 粉蓝白三色循环插值 (原版色板 #E9A8BC / #6EC8F1 / 白)
 *   - MathUtils::lerp/clamp → 帧率无关指数平滑
 *   - 模块来源: ModuleManager.getModules() (enabled 且非 hidden)
 *   - 点击交互: MouseButtonEvent + mc.mouseHandler 坐标换算
 *
 *  可调节项 (20+): 顶部/右侧偏移、水印文字/发光/半径/密度、
 *        显示模式 (Outline/Bar/Split/None)、发光与密度、文字阴影与偏移、
 *        点击切换、动画速度、列表背景/样式/透明度、
 *        颜色模式 (Theme/Rainbow)、彩虹速度/饱和度/亮度等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor, 无 Web 依赖。
 *
 *  安装:
 *    1. 放入 src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleSolsticeArraylist.kt
 *    2. ModuleManager.kt: import + builtin 列表加 ModuleSolsticeArraylist,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawHorizontalLine
import net.ccbluex.liquidbounce.render.drawVerticalLine
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.IdentityHashMap
import kotlin.math.roundToInt

object ModuleSolsticeArraylist : ClientModule(
    "SolsticeArraylist[beta+skid]",
    ModuleCategories.RENDER,
    aliases = listOf("Arraylist", "SolsticeArray"),
) {

    /* ============================= 枚举 ============================= */

    private enum class Display(override val tag: String) : Tagged {
        OUTLINE("Outline"), BAR("Bar"), SPLIT("Split"), NONE("None")
    }

    private enum class BackgroundStyle(override val tag: String) : Tagged {
        OPACITY("Opacity"), SHADOW("Shadow"), BOTH("Both")
    }

    private enum class ColorMode(override val tag: String) : Tagged {
        THEME("Theme"), RAINBOW("Rainbow")
    }

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val topOffset by int("Top Offset", 10, 0..500)
    private val rightOffset by int("Right Offset", 30, 0..500)

    // —— 水印 (原版 Solstice V4) ——
    private val showWatermark by boolean("Show Watermark", true)
    private val watermarkText by text("Watermark Text", "Solstice V4")
    private val watermarkGlow by boolean("Watermark Glow", true)
    private val watermarkGlowRadius by int("Watermark Glow Radius", 10, 0..40)
    private val watermarkGlowDensity by int("Watermark Glow Density", 5, 1..8)

    // —— 列表 ——
    private val display by enumChoice("Display", Display.SPLIT)
    private val glow by boolean("Glow", true)
    private val glowStrength by float("Glow Strength", 1.9f, 0f..5f)
    private val glowDensity by int("Glow Density", 2, 1..8)
    private val textShadow by boolean("Text Shadow", true)
    private val shadowOffset by float("Shadow Offset", 1f, 0f..5f)
    private val clickToToggle by boolean("Click To Toggle", true)
    private val animationSpeed by float("Animation Speed", 12f, 0.5f..30f)

    // —— 背景 ——
    private val listBackground by boolean("List Background", false)
    private val backgroundStyle by enumChoice("Background Style", BackgroundStyle.BOTH)
    private val backgroundAlpha by int("Background Alpha", 60, 0..255)

    // —— 颜色 ——
    private val colorMode by enumChoice("Color Mode", ColorMode.THEME)
    private val rainbowSpeed by float("Rainbow Speed", 1f, 0.1f..10f)
    private val rainbowSaturation by float("Rainbow Saturation", 1f, 0f..1f)
    private val rainbowBrightness by float("Rainbow Brightness", 1f, 0f..1f)

    /* ============================= 内部状态 ============================= */

    private val anims = IdentityHashMap<ClientModule, Float>()   // 滑入动画 (原版 arrayListAnim)
    private var lastFrameNs = 0L

    private data class EntryRect(val module: ClientModule, val x: Float, val y: Float, val w: Float, val h: Float)
    private val entryRects = mutableListOf<EntryRect>()          // 每帧更新, 供点击命中

    // 原版主题色板
    private val themeColors = listOf(
        Color4b(0xE9, 0xA8, 0xBC),    // 浅粉
        Color4b(0x6E, 0xC8, 0xF1),    // 浅蓝
        Color4b(255, 255, 255, 128),  // 半透明白
    )

    /* ============================= 工具函数 ============================= */

    /** 原版 ColorUtils::LerpColors 移植 (3s 循环, 三色插值) */
    private fun themedColor(index: Float): Color4b {
        val time = 10000f / 3f
        val now = System.currentTimeMillis()
        val angle = ((now + index.toLong()) % time.toLong()).toFloat()
        val segmentTime = time / themeColors.size
        val seg = (angle / segmentTime).toInt().coerceIn(0, themeColors.size - 1)
        val t = (angle / segmentTime - seg).coerceIn(0f, 1f)
        return themeColors[seg].interpolateTo(themeColors[(seg + 1) % themeColors.size], t.toDouble())
    }

    private fun rainbowColor(index: Float): Color4b {
        val hue = (System.currentTimeMillis() / 1000f * rainbowSpeed + index / 40f) % 1f
        return Color4b.ofHSB(hue, rainbowSaturation, rainbowBrightness)
    }

    private fun resolveColor(index: Float): Color4b = when (colorMode) {
        ColorMode.THEME -> themedColor(index)
        ColorMode.RAINBOW -> rainbowColor(index)
    }

    /** 原版 drawShadowRectDense 移植: 多层描边光晕 (outline 模式, 不覆盖内部文字) */
    private fun GuiGraphicsExtractor.drawGlowRect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color4b, radius: Float, density: Int,
    ) {
        if (radius <= 0f || density <= 0) return
        if (density <= 1) {
            drawRoundedRect(
                x1 - radius * 0.3f, y1 - radius * 0.3f, x2 + radius * 0.3f, y2 + radius * 0.3f,
                radius * 0.5f, Color4b.TRANSPARENT, color, radius * 0.4f,
            )
            return
        }
        for (i in 0 until density) {
            val t = i / (density - 1).toFloat()
            val w = radius * (0.25f + 0.75f * t)        // 描边厚度逐层增加
            val a = color.a * (0.35f + 0.35f * (1f - t))  // 透明度低且逐层递减
            val inset = radius * 0.15f
            drawRoundedRect(
                x1 - inset - w * 0.25f, y1 - inset - w * 0.25f,
                x2 + inset + w * 0.25f, y2 + inset + w * 0.25f,
                w * 0.5f, Color4b.TRANSPARENT,
                color.alpha(a.roundToInt().coerceIn(0, 255)), w * 0.5f,
            )
        }
    }

    /** 原版 drawShadowCircleDense: 多层描边圆光晕 */
    private fun GuiGraphicsExtractor.drawGlowCircle(
        cx: Float, cy: Float, radius: Float, color: Color4b, density: Int,
    ) {
        if (radius <= 0f || density <= 0) return
        for (i in 0 until density) {
            val t = i / (density - 1).coerceAtLeast(1).toFloat()
            val r = radius * (0.4f + 0.6f * t)
            val a = color.a * (0.3f + 0.3f * (1f - t))
            drawRoundedRect(
                cx - r, cy - r, cx + r, cy + r, r,
                Color4b.TRANSPARENT, color.alpha(a.roundToInt().coerceIn(0, 255)), r * 0.35f,
            )
        }
    }

    /** 鼠标 GUI 坐标 (物理像素 → 缩放坐标) */
    private fun mouseGuiX(guiWidth: Int): Float =
        (mc.mouseHandler.xpos() * guiWidth / mc.window.width).toFloat()

    private fun mouseGuiY(guiHeight: Int): Float =
        (mc.mouseHandler.ypos() * guiHeight / mc.window.height).toFloat()

    /* ============================= 点击切换 ============================= */

    @Suppress("unused")
    private val clickHandler = handler<MouseButtonEvent> { event ->
        if (!clickToToggle) return@handler
        if (event.action != 1 || event.button != 0) return@handler   // 左键按下
        val mx = mouseGuiX(mc.window.guiScaledWidth)
        val my = mouseGuiY(mc.window.guiScaledHeight)
        val hit = entryRects.lastOrNull {
            mx in it.x..(it.x + it.w) && my in it.y..(it.y + it.h)
        } ?: return@handler
        hit.module.enabled = !hit.module.enabled
    }

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now

        if (showWatermark) {
            renderWatermark(context, frameTime)
        }
        renderModules(context, frameTime)
    }

    /* ------------------------- 水印 (右上角) ------------------------- */

    private fun renderWatermark(ctx: GuiGraphicsExtractor, dt: Float) {
        if (watermarkText.isEmpty()) return
        val font = mc.font
        val guiWidth = ctx.guiWidth()

        // 逐字符彩虹水印 (原版: 每字符 getThemedColor(i*100))
        var x = (guiWidth - rightOffset).toFloat()
        for (i in watermarkText.indices) {
            val ch = watermarkText[i].toString()
            val charW = font.width(ch).toFloat()
            x -= charW
            val color = resolveColor(i * 100f)

            // 圆形发光
            if (watermarkGlow && watermarkGlowRadius > 0) {
                ctx.drawGlowCircle(
                    x + charW / 2f, topOffset + 4.5f,
                    watermarkGlowRadius.toFloat(), color.alpha(255), watermarkGlowDensity,
                )
            }
            // 字符阴影 + 主字符 (baseline = 顶部 + 9)
            if (textShadow) {
                ctx.text(font, ch, (x + 3.25f).roundToInt(), (topOffset + 9f + 3.25f).roundToInt(), color.darker().argb, false)
            }
            ctx.text(font, ch, x.roundToInt(), (topOffset + 9f).roundToInt(), color.argb, false)
        }
    }

    /* ------------------------- 模块列表 ------------------------- */

    private fun renderModules(ctx: GuiGraphicsExtractor, dt: Float) {
        val font = mc.font
        val guiWidth = ctx.guiWidth().toFloat()

        // 全部非隐藏模块 (含禁用): 通过 arrayListAnim 控制滑入/滑出,
        // 模块开启时滑入显示, 关闭时滑出隐藏 (还原原版 toggle 动画)
        val modules = ModuleManager.getModules().filter { !it.hidden }
        if (modules.isEmpty()) {
            entryRects.clear()
            return
        }

        // 排序: 名称宽度降序 (原版 std::ranges::sort 按宽度)
        val sorted = modules.sortedByDescending { font.width(it.name) }

        val textH = font.lineHeight.toFloat()          // 9px
        val watermarkH = if (showWatermark) textH + 10f else 0f
        var posY = topOffset + watermarkH

        val entries = mutableListOf<EntryRect>()
        val bgRects = mutableListOf<Array<Float>>()    // [x1, y1, x2, y2, 颜色RGB占位由顺序对应]
        val bgColors = mutableListOf<Color4b>()

        for (mod in sorted) {
            var anim = anims.getOrPut(mod) { 0f }
            anim += ((if (mod.enabled) 1f else 0f) - anim) * (dt * animationSpeed).coerceAtMost(1f)
            anims[mod] = anim
            if (anim < 0.01f) continue

            val color = resolveColor(posY * 2f)
            val name = mod.name
            val textW = font.width(name).toFloat()

            // 从右侧滑入 (原版: lerp(displaySize.x + 14, endPos, anim))
            val endX = guiWidth - rightOffset - textW
            val x = lerp(guiWidth + 14f, endX, anim)
            val pad = if (display == Display.BAR || display == Display.SPLIT) 7f else 0f
            val textX = x - pad

            // 条目矩形 (原版 rect: x-3 .. x+w+4)
            val rectX = textX - 3f
            val rectZ = textX + textW + 4f + pad

            // 文字发光 (None/Bar/Split: 文字区域; Outline: 矩形区域)
            if (glow && glowStrength > 0f) {
                // 原版 glowStrength*100 是 35px 字体下的数值, 9px 字体下换算为 *6
                val glowR = glowStrength * 6f * anim
                val glowColor = color.alpha((0.45f * 255 * anim).roundToInt().coerceIn(0, 255))
                if (display == Display.OUTLINE) {
                    ctx.drawGlowRect(rectX, posY, rectZ, posY + textH, glowColor, glowR, glowDensity)
                } else {
                    ctx.drawGlowRect(textX, posY, textX + textW, posY + textH, glowColor, glowR, glowDensity)
                }
            }

            // 模式装饰
            when (display) {
                Display.BAR -> {
                    ctx.drawQuad(textX + textW, posY, textX + textW + 2f, posY + textH, color)
                }
                Display.SPLIT -> {
                    ctx.drawRoundedRect(
                        textX + textW + 2f, posY + 4f, textX + textW + 6f, posY + textH - 2f,
                        3f, color
                    )
                }
                Display.OUTLINE -> {
                    bgRects += arrayOf(rectX, posY, rectZ + 2f, posY + textH)
                    bgColors += color
                }
                Display.NONE -> Unit
            }

            // 文字阴影 (原版: 偏移 + 25% 颜色)
            if (textShadow && shadowOffset > 0f) {
                val shadowColor = Color4b(
                    (color.r * 0.25f).toInt(),
                    (color.g * 0.25f).toInt(),
                    (color.b * 0.25f).toInt(), 236,
                )
                ctx.text(
                    font, name,
                    (textX + shadowOffset).roundToInt(), (posY + 1f + shadowOffset).roundToInt(),
                    shadowColor.argb, false,
                )
            }
            // 主文字 (baseline = 顶部 + 1, 使文字顶部对齐条目)
            ctx.text(font, name, textX.roundToInt(), (posY + 1f).roundToInt(), color.argb, false)

            // 悬停高亮
            val mx = mouseGuiX(ctx.guiWidth())
            val my = mouseGuiY(ctx.guiHeight())
            if (mx in rectX..rectZ && my in posY..(posY + textH)) {
                ctx.drawQuad(rectX, posY, rectZ, posY + textH, Color4b(255, 255, 255, 26))
            }

            entries += EntryRect(mod, rectX, posY, rectZ - rectX, textH)
            posY += textH * anim
        }

        // Outline 连线 (简化版: 每条左右竖线 + 首条顶线 + 末条底线)
        if (display == Display.OUTLINE && bgRects.isNotEmpty()) {
            bgRects.forEachIndexed { i, r ->
                val color = bgColors[i]
                ctx.drawVerticalLine(r[2], r[1], r[3], 2f, color)
                ctx.drawVerticalLine(r[0], r[1], r[3], 2f, color)
                if (i == 0) ctx.drawHorizontalLine(r[0], r[2], r[1], 2f, color)
                if (i == bgRects.lastIndex) ctx.drawHorizontalLine(r[0], r[2], r[3], 2f, color)
            }
        }

        // 列表背景
        if (listBackground && entries.isNotEmpty()) {
            val minX = entries.minOf { it.x } - 6f
            val maxX = guiWidth - rightOffset + 6f
            val topY = entries.minOf { it.y } - 2f
            val bottomY = entries.maxOf { it.y + it.h } + 2f
            val bg = Color4b(0, 0, 0, backgroundAlpha)
            when (backgroundStyle) {
                BackgroundStyle.OPACITY -> ctx.drawRoundedRect(minX, topY, maxX, bottomY, 4f, bg)
                BackgroundStyle.SHADOW -> ctx.drawGlowRect(minX, topY, maxX, bottomY, Color4b(0, 0, 0, 80), 8f, 3)
                BackgroundStyle.BOTH -> {
                    ctx.drawGlowRect(minX, topY, maxX, bottomY, Color4b(0, 0, 0, 80), 8f, 3)
                    ctx.drawRoundedRect(minX, topY, maxX, bottomY, 4f, bg)
                }
            }
        }

        entryRects.clear()
        entryRects.addAll(entries)
    }

    /* ============================= 工具 ============================= */

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
