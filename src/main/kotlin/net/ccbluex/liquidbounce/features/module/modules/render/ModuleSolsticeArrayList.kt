/*
 * ============================================================================
 *  ModuleSolsticeArrayList —— Arraylist.cpp / Arraylist.hpp 的完整 Kotlin 移植
 *  适配: LiquidBounce For Android 0.39 (Rubbishy-Liquidbounce-Nextgen)
 *
 *  保留原版全部功能:
 *  - 水印 (Solstice V4, 逐字彩虹 + 阴影圆辉光)
 *  - 模块列表按宽度从长到短排序
 *  - 显示模式: Outline / Bar / Split / None
 *  - 背景样式: Opacity / Shadow / Both
 *  - 模块可见性: All / Bound (仅显示绑定按键的模块)
 *  - Render Mode (显示模块当前模式名称)
 *  - 辉光 (Glow / Glow Strength / Glow Density)
 *  - 文字阴影 / 伪粗体 / 自定义字号
 *  - 悬停高亮 + 点击开关模块
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.Value
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
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 对应 C++ 的 `Arraylist` 类。所有可配置项都与 `Arraylist.hpp` 的成员一一对应。
 */
object ModuleSolsticeArrayList : ClientModule("SolsticeArraylist", ModuleCategories.RENDER) {
    init {
        enabled = true
    }

    /* ============================ 枚举 (Arraylist.hpp) ============================ */

    /** 背景样式 */
    enum class BackgroundStyle(override val tag: String) : Tagged {
        OPACITY("Opacity"), SHADOW("Shadow"), BOTH("Both")
    }

    /** 显示模式 */
    enum class DisplayMode(override val tag: String) : Tagged {
        OUTLINE("Outline"), BAR("Bar"), SPLIT("Split"), NONE("None")
    }

    /** 模块可见性 */
    enum class ModuleVisibility(override val tag: String) : Tagged {
        ALL("All"), BOUND("Bound")
    }

    /* ============================ 设置 (Arraylist.hpp 成员) ============================ */

    private val backgroundStyle by enumChoice("Background", BackgroundStyle.SHADOW)
    private var backgroundOpacity: Float = 1.0f
    private val backgroundValue: Float = 0f
    private val blurStrength: Float = 1.0f

    private val displayMode by enumChoice("Display", DisplayMode.SPLIT)
    private val visibility by enumChoice("Visibility", ModuleVisibility.ALL)
    private val renderMode by boolean("Render Mode", true)

    private const val glow: Boolean = false
    private const val glowStrength: Float = 1.9f
    private const val glowDensity: Int = 2

    private val boldText by boolean("Bold Text", true)
    private val fontSize by float("Font Size", 30.0f, 10.0f..70.0f)
    private const val topOffset: Float = 0f
    private const val rightOffset: Float = 0f

    private val textShadow by boolean("Text Shadow", true)
    private const val shadowOffset: Float = 1.85f

    private val watermarkText by text("Watermark Text", "Solstice V4")
    private val watermarkShadowRadius by float("Watermark Shadow Radius", 0f, 0f..120f)
    private val watermarkShadowDensity by int("Watermark Shadow Density", 0, 0..15)

    private val clickToggle by boolean("Click Toggle", true)

    /* ============================ 内部状态 ============================ */

    private class AnimState {
        var arrayListAnim = 0f
    }

    private val animations = HashMap<ClientModule, AnimState>()
    private var watermarkAnim = 0f
    private var lastFrameNs = 0L
    private var wasLeftDown = false

    /** 模块当前模式文本缓存 (Render Mode 用) */
    private val settingDisplayCache = HashMap<ClientModule, String>()
    private var settingDisplayRefresh = 0L

    private class RectInfo(
        val moduleName: String,
        var startX: Float,
        var startY: Float,
        var endX: Float,
        var endY: Float,
        val color: Color4b,
        val mod: ClientModule
    )

    private class LineInfo(
        val moduleName: String,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val color: Color4b,
        val thickness: Float
    )

    /* ============================ 工具函数 ============================ */

    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

    private fun clamp(value: Float, min: Float, max: Float): Float = max(min, min(value, max))

    private fun Color4b.copy(red: Int = this.r, green: Int = this.g, blue: Int = this.b, alpha: Int = this.a): Color4b {
        return Color4b(red, green, blue, alpha)
    }

    /**
     * 主题色, 对应 `ColorUtils::getThemedColor` (Solstice 三色渐变: 粉 -> 蓝 -> 白)。
     */
    private fun getThemedColor(index: Float, ms: Long = System.currentTimeMillis()): Color4b {
        val theme = listOf(
            Color4b(0xE9, 0xA8, 0xBC, 255), // 浅粉
            Color4b(0x6E, 0xC8, 0xF1, 255), // 浅蓝
            Color4b(255, 255, 255, 255)     // 白
        )
        val seconds = 3.0f
        val time = 10000.0f / seconds
        val angle = ((ms + index.toInt()) % time.toInt()).toFloat()
        val segmentTime = time / theme.size
        val segmentIndex = (angle / segmentTime).toInt() % theme.size
        val t = angle / segmentTime - segmentIndex

        val a = theme[segmentIndex]
        val b = theme[(segmentIndex + 1) % theme.size]
        return Color4b(
            lerp(a.r.toFloat(), b.r.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.g.toFloat(), b.g.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.b.toFloat(), b.b.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(a.a.toFloat(), b.a.toFloat(), t).toInt().coerceIn(0, 255)
        )
    }

    /**
     * 按指定缩放绘制文字 (MC 字体基准高度 9px, 缩放后渲染)。
     */
    private fun drawScaledText(
        ctx: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        color: Color4b,
        shadow: Boolean,
        fakeBold: Boolean
    ) {
        if (scale <= 0f || text.isEmpty()) return
        ctx.pose().withPush {
            scale(scale, scale)
            val sx = (x / scale).roundToInt()
            val sy = (y / scale).roundToInt()
            if (fakeBold) {
                ctx.text(font, text, sx + 1, sy, color.argb, shadow)
            }
            ctx.text(font, text, sx, sy, color.argb, shadow)
        }
    }

    /**
     * 对应 `Arraylist::drawShadowRectDense` (多层叠加的软阴影/辉光)。
     */
    private fun drawShadowRectDense(
        ctx: GuiGraphicsExtractor,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        color: Color4b,
        radius: Float,
        density: Int,
        rounding: Float
    ) {
        if (density <= 1) {
            ctx.drawRoundedRect(minX, minY, maxX, maxY, rounding, color, Color4b.TRANSPARENT, 0f)
            return
        }
        for (i in 0 until density) {
            val t = i.toFloat() / (density - 1)
            val r = radius * (0.25f + 0.75f * t)
            val a = (color.a * (0.5f + 0.5f * (1.0f - t))).toInt().coerceIn(0, 255)
            ctx.drawRoundedRect(minX - r, minY - r, maxX + r, maxY + r, rounding, color.copy(alpha = a), Color4b.TRANSPARENT, 0f)
        }
    }

    /**
     * 对应 C++ 的逐字符水印阴影圆 (用稠密圆角矩形近似)。
     */
    private fun drawShadowCircleDense(
        ctx: GuiGraphicsExtractor,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Color4b,
        density: Int
    ) {
        if (radius <= 0f || density <= 0) return
        drawShadowRectDense(ctx, cx, cy, cx, cy, color, radius, density, 12f)
    }

    private fun drawLine(
        ctx: GuiGraphicsExtractor,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Color4b,
        thickness: Float
    ) {
        if (x1 == x2 && y1 == y2) return
        if (y1 == y2) {
            ctx.drawQuad(min(x1, x2), y1 - thickness / 2f, abs(x2 - x1), thickness, color, Color4b.TRANSPARENT)
        } else if (x1 == x2) {
            ctx.drawQuad(x1 - thickness / 2f, min(y1, y2), thickness, abs(y2 - y1), color, Color4b.TRANSPARENT)
        }
    }

    /**
     * 读取模块当前激活的模式名称 (对应 C++ 的 settingDisplay)。每 500ms 刷新一次缓存。
     */
    private fun getSettingDisplay(mod: ClientModule): String {
        val now = System.currentTimeMillis()
        if (now - settingDisplayRefresh > 500L) {
            settingDisplayRefresh = now
            settingDisplayCache.clear()
        }
        return settingDisplayCache.getOrPut(mod) {
            try {
                val values = mod.collectValuesRecursively().toList()
                for (v in values) {
                    val actual = try {
                        v.get()
                    } catch (_: Exception) {
                        null
                    }
                    if (actual is Enum<*>) {
                        val constants = actual.javaClass.enumConstants
                        if (constants != null && constants.size >= 2) {
                            return@getOrPut actual.name
                        }
                    }
                }
                ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * 对应 `Arraylist::toggleModule`。只切换真实存在的模块。
     */
    fun toggleModule(name: String, setting: String = "", addIfMissing: Boolean = true) {
        val mod = ModuleManager.getModuleByName(name)
        if (mod != null) {
            mod.enabled = !mod.enabled
        }
    }

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val font = mc.font
        val guiWidth = ctx.guiWidth().toFloat()

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        } else {
            0.016f
        }
        lastFrameNs = now

        // C++ 默认字体 35, 这里 35 -> 1.0 (MC 默认字号); 水印按 85/35 比例放大
        val textScale = (fontSize / 35.0f).coerceAtLeast(0.2f)
        val watermarkScale = textScale * (85.0f / 35.0f)

        // 对应 C++ mGlowStrength * 100 (缩放后适配 MC 坐标, 收窄使其紧贴文字)
        val glowRadius = glowStrength * 2.0f * textScale

        // ---------------- 点击检测 (悬停高亮 + 点击开关) ----------------
        val mouseX = (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()
        val mouseY = (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()
        val leftDown = mc.window.width > 0 &&
            GLFW.glfwGetMouseButton(mc.window.handle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS
        val clicked = leftDown && !wasLeftDown
        wasLeftDown = leftDown

        /* --- 1. 水印 --- */
        watermarkAnim = clamp(lerp(watermarkAnim, 1.0f, frameTime * 10f), 0f, 1f)

        if (watermarkAnim > 0.01f) {
            var watermarkX = guiWidth - totalWatermarkWidth(font, watermarkText, watermarkScale) - rightOffset
            val watermarkY = topOffset

            for ((i, c) in watermarkText.withIndex()) {
                val charStr = c.toString()
                val charWidth = font.width(charStr) * watermarkScale
                val charHeight = font.lineHeight * watermarkScale
                val charColor = getThemedColor(i * 100f)

                // 逐字阴影圆辉光
                if (watermarkShadowDensity > 0) {
                    val alpha = (charColor.a * watermarkAnim).toInt().coerceIn(0, 255)
                    drawShadowCircleDense(
                        ctx,
                        watermarkX + charWidth / 2f,
                        watermarkY + charHeight / 2f,
                        watermarkShadowRadius * watermarkAnim,
                        charColor.copy(alpha = alpha),
                        watermarkShadowDensity
                    )
                }

                // 阴影文字 (偏移 3.25)
                val shadowColor = Color4b(
                    (charColor.r * 0.25f).toInt(),
                    (charColor.g * 0.25f).toInt(),
                    (charColor.b * 0.25f).toInt(),
                    (255 * 0.925f).toInt()
                )
                drawScaledText(ctx, font, charStr, watermarkX + 3.25f, watermarkY + 3.25f, watermarkScale, shadowColor, false, false)
                drawScaledText(ctx, font, charStr, watermarkX, watermarkY, watermarkScale, charColor, false, false)

                watermarkX += charWidth
            }
        }

        /* --- 2. 模块列表预处理 --- */
        val lineHeight = font.lineHeight.toFloat() * textScale
        val watermarkHeight = if (watermarkText.isNotEmpty()) font.lineHeight * watermarkScale else 0f
        var posY = topOffset + watermarkHeight + 10.0f

        val modules = ModuleManager.getModules().toList()
            .filter { !it.hidden }
            .filter { visibility != ModuleVisibility.BOUND || !it.bind.isUnbound }

        val sortedModules = modules.sortedByDescending { mod ->
            val name = mod.name
            val setting = if (renderMode) getSettingDisplay(mod) else ""
            val fullText = if (setting.isNotEmpty()) "$name $setting" else name
            font.width(fullText) * textScale
        }

        val backgroundRects = ArrayList<RectInfo>()

        /* --- 3. Pass 1: 辉光 + 背景矩形 + 模式装饰 --- */
        for (mod in sortedModules) {
            val animState = animations.getOrPut(mod) { AnimState() }
            animState.arrayListAnim = clamp(
                lerp(animState.arrayListAnim, if (mod.enabled) 1.0f else 0.0f, frameTime * 12.0f),
                0.0f, 1.0f
            )
            if (animState.arrayListAnim < 0.01f) continue

            val color = getThemedColor(posY * 2f)
            val name = mod.name
            val setting = if (renderMode) getSettingDisplay(mod) else ""
            val settingStr = if (setting.isNotEmpty()) " $setting" else ""
            val nameWidth = font.width(name) * textScale
            val settingWidth = if (settingStr.isNotEmpty()) font.width(settingStr) * textScale else 0f
            val totalWidth = nameWidth + settingWidth

            var textPosX = guiWidth - rightOffset
            if (displayMode == DisplayMode.BAR || displayMode == DisplayMode.SPLIT) {
                textPosX -= 7f
            }
            val endPos = textPosX - totalWidth
            val animX = lerp(guiWidth + 14.0f, endPos, animState.arrayListAnim)

            val rectX = animX - 3f
            val rectY = posY
            val rectZ = animX + totalWidth + 4f
            val rectW = posY + lineHeight
            val addedPadding = displayMode == DisplayMode.BAR || displayMode == DisplayMode.SPLIT

            backgroundRects.add(
                RectInfo(
                    name,
                    rectX + (if (addedPadding) 7f else 0f), rectY,
                    rectZ + (if (addedPadding) 7f else 0f), rectW,
                    color, mod
                )
            )

            // 背景样式 (C++ 未实现, 此处补全)
            drawEntryBackground(ctx, rectX, rectY, rectZ + (if (addedPadding) 7f else 0f), rectW, color, animState.arrayListAnim)

            // NONE 文字辉光
            if (glow && displayMode == DisplayMode.NONE) {
                val a = (0.83f * animState.arrayListAnim * 255f).toInt().coerceIn(0, 255)
                drawShadowRectDense(ctx, animX, posY, animX + totalWidth, posY + lineHeight,
                    color.copy(alpha = a), glowRadius * animState.arrayListAnim, glowDensity, 12f)
            }

            // OUTLINE 辉光
            if (displayMode == DisplayMode.OUTLINE && glow) {
                val a = (0.6f * animState.arrayListAnim * 255f).toInt().coerceIn(0, 255)
                drawShadowRectDense(ctx, rectX, rectY, rectZ + (if (addedPadding) 7f else 0f), rectW,
                    color.copy(alpha = a), glowRadius * animState.arrayListAnim, glowDensity, 0f)
            }

            // BAR
            if (displayMode == DisplayMode.BAR) {
                if (glow) {
                    val a1 = (0.83f * animState.arrayListAnim * 255f).toInt().coerceIn(0, 255)
                    val a2 = (0.7f * animState.arrayListAnim * 255f).toInt().coerceIn(0, 255)
                    drawShadowRectDense(ctx, animX, posY, animX + totalWidth, posY + lineHeight,
                        color.copy(alpha = a1), glowRadius * animState.arrayListAnim, glowDensity, 12f)
                    drawShadowRectDense(ctx, animX + totalWidth - 2f, posY - 5f, animX + totalWidth + 4f, posY + lineHeight + 5f,
                        color.copy(alpha = a2), glowRadius * animState.arrayListAnim, glowDensity, 12f)
                }
                ctx.drawQuad(animX + totalWidth, posY, 2f, lineHeight, color, Color4b.TRANSPARENT)
            }

            // SPLIT
            if (displayMode == DisplayMode.SPLIT) {
                if (glow) {
                    val a1 = (0.83f * animState.arrayListAnim * 255f).toInt().coerceIn(0, 255)
                    drawShadowRectDense(ctx, animX, posY, animX + totalWidth, posY + lineHeight,
                        color.copy(alpha = a1), glowRadius * animState.arrayListAnim, glowDensity, 12f)
                }
                val lineStartX = rectZ + 2f
                val lineStartY = posY + 4f
                val lineEndX = lineStartX + 4f
                val lineEndY = lineStartY + lineHeight - 6f
                ctx.drawRoundedRect(lineStartX, lineStartY, lineEndX, lineEndY, 3f, color, Color4b.TRANSPARENT, 0f)

                if (glow) {
                    val a2 = (0.7f * animState.arrayListAnim * 255f).toInt().coerceIn(0, 255)
                    drawShadowRectDense(ctx, lineStartX - 3f, lineStartY - 5f, lineEndX + 3f, lineEndY + 5f,
                        color.copy(alpha = a2), glowRadius * animState.arrayListAnim, glowDensity, 12f)
                }
            }

            posY += lineHeight * animState.arrayListAnim
        }

        /* --- 4. Pass 2: 文字 + 悬停 + 点击 --- */
        posY = topOffset + watermarkHeight + 10.0f
        for (mod in sortedModules) {
            val animState = animations[mod] ?: continue
            if (animState.arrayListAnim < 0.01f) continue

            val color = getThemedColor(posY * 2f)
            val name = mod.name
            val setting = if (renderMode) getSettingDisplay(mod) else ""
            val settingStr = if (setting.isNotEmpty()) " $setting" else ""
            val nameWidth = font.width(name) * textScale
            val settingWidth = if (settingStr.isNotEmpty()) font.width(settingStr) * textScale else 0f
            val totalWidth = nameWidth + settingWidth

            var textPosX = guiWidth - rightOffset
            if (displayMode == DisplayMode.BAR || displayMode == DisplayMode.SPLIT) {
                textPosX -= 7f
            }
            val endPos = textPosX - totalWidth
            val animX = lerp(guiWidth + 14.0f, endPos, animState.arrayListAnim)
            val textY = posY

            val rectX = animX - 3f
            val rectY = posY
            val rectZ = animX + totalWidth + 4f
            val rectW = posY + lineHeight

            // 悬停高亮 + 点击开关
            val hovered = mouseX >= animX && mouseX <= animX + totalWidth &&
                mouseY >= textY && mouseY <= textY + lineHeight
            if (hovered && clickToggle) {
                ctx.drawQuad(rectX, rectY, rectZ, rectW, Color4b(255, 255, 255, 26), Color4b.TRANSPARENT)
                if (clicked) {
                    mod.enabled = !mod.enabled
                }
            }

            // 阴影文字
            if (textShadow) {
                val shadowColor = Color4b(
                    (color.r * 0.25f).toInt(),
                    (color.g * 0.25f).toInt(),
                    (color.b * 0.25f).toInt(),
                    (255 * 0.925f).toInt()
                )
                drawScaledText(ctx, font, name, animX + shadowOffset, textY + shadowOffset, textScale, shadowColor, false, boldText)
                if (settingStr.isNotEmpty()) {
                    drawScaledText(ctx, font, settingStr, animX + nameWidth + shadowOffset, textY + shadowOffset, textScale,
                        Color4b(57, 57, 57, 235), false, boldText)
                }
            }

            // 主文字
            drawScaledText(ctx, font, name, animX, textY, textScale, color, false, boldText)
            if (settingStr.isNotEmpty()) {
                drawScaledText(ctx, font, settingStr, animX + nameWidth, textY, textScale, Color4b(230, 230, 230, 255), false, boldText)
            }

            posY += lineHeight * animState.arrayListAnim
        }

        /* --- 5. Pass 3: Outline 外框线 --- */
        if (displayMode == DisplayMode.NONE) return@handler

        val lines = ArrayList<LineInfo>()
        var bgi = 0
        var startingRectX = 0f
        var startingRectY = 0f

        for ((index, bg) in backgroundRects.withIndex()) {
            bg.startX -= 2.0f
            bg.endX -= 2.0f

            val next = backgroundRects.drop(index + 1).minByOrNull { it.startX }
            val hasNext = next != null

            if (displayMode == DisplayMode.OUTLINE) {
                lines.add(LineInfo(bg.moduleName, bg.endX + 2f, bg.startY, bg.endX + 2f, bg.endY, bg.color, 2f))

                if ((hasNext && next!!.startX >= bg.startX) || !hasNext) {
                    lines.add(LineInfo(bg.moduleName, bg.startX, bg.startY, bg.startX, bg.endY, bg.color, 2f))
                } else if (hasNext) {
                    lines.add(LineInfo(bg.moduleName, next.startX, bg.startY, next.startX, bg.startY, bg.color, 2f))
                }

                if (bgi == 0) {
                    startingRectX = bg.startX
                    startingRectY = bg.startY
                    lines.add(LineInfo(bg.moduleName, bg.startX, bg.startY, bg.endX + 2f, bg.startY, bg.color, 2f))
                }

                if (!hasNext) {
                    lines.add(LineInfo(bg.moduleName, bg.startX, bg.endY, bg.endX + 2f, bg.endY, bg.color, 2f))
                } else if (next!!.startX >= bg.startX) {
                    if (next.startX - bg.startX > 2f) {
                        lines.add(LineInfo(bg.moduleName, bg.startX, bg.endY, next.startX - 1f, bg.endY, bg.color, 2f))
                    }
                }
            }
            bgi++
        }

        if (displayMode == DisplayMode.OUTLINE && backgroundRects.isNotEmpty()) {
            val lowest = backgroundRects.minByOrNull { it.startX }!!
            lines.add(
                LineInfo(lowest.moduleName, lowest.startX, lowest.startY, startingRectX + 2f, lowest.startY, lowest.color, 2f)
            )
        }

        for (line in lines) {
            drawLine(ctx, line.startX, line.startY, line.endX, line.endY, line.color, line.thickness)
        }
    }

    /**
     * 背景样式渲染 (对应 C++ 中声明但未实现的 BackgroundStyle)。
     */
    private fun drawEntryBackground(
        ctx: GuiGraphicsExtractor,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Color4b,
        anim: Float
    ) {
        val intensity = 0.2f + 0.8f * backgroundValue

        if (backgroundStyle == BackgroundStyle.OPACITY || backgroundStyle == BackgroundStyle.BOTH) {
            val alpha = (backgroundOpacity * 255f * 0.35f * intensity * anim).toInt().coerceIn(0, 255)
            if (alpha > 0) {
                ctx.drawQuad(x1, y1, x2, y2, Color4b(0, 0, 0, alpha), Color4b.TRANSPARENT)
            }
        }

        if (backgroundStyle == BackgroundStyle.SHADOW || backgroundStyle == BackgroundStyle.BOTH) {
            val alpha = (color.a * 0.25f * intensity * anim).toInt().coerceIn(0, 255)
            if (alpha > 0) {
                drawShadowRectDense(ctx, x1, y1, x2, y2, color.copy(alpha = alpha), blurStrength * 8f * anim, glowDensity, 6f)
            }
        }
    }

    private fun totalWatermarkWidth(font: Font, text: String, scale: Float): Float {
        var w = 0f
        for (c in text) {
            w += font.width(c.toString()) * scale
        }
        return w
    }
}
