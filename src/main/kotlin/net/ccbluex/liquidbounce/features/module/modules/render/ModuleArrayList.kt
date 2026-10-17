/*
 * ModuleArraylist —— 移植 Solstice (vastrakai) src/Features/Modules/Visual/Arraylist.{hpp,cpp}
 *
 * 原版 addSetting 只暴露了这六项（其余在源码里被注释掉）：
 *  - Blur Strength  0..10   默认 0
 *  - Display        Outline / Bar / Split / None   默认 Split
 *  - Visibility     All / Bound                    默认 All
 *  - Render Mode    bool                           默认 true
 *  - Glow           bool                           默认 true
 *  - Glow Strength  0.5..1                         默认 1
 * 被注释掉因而恒为默认值的常量：
 *  - Background = Shadow，Background Opacity = 1，Background Value = 0
 *  - Bold Text = true，Font Size = 25
 *
 * 逐项对照原版 onRenderEvent：
 *  1. 排序：按 (name + " " + settingDisplay) 像素宽度降序；Render Mode 关闭时不拼 settingDisplay
 *  2. 第一趟 pos = (DisplaySize.x - 10, 5)
 *     - 跳过 !Visible、Bound 且 key==0、anim < 0.01
 *     - anim = clamp(lerp(anim, enabled ? 1 : 0, dt * 12), 0, 1)
 *     - color = getThemedColor(pos.y * 2)
 *     - textPos = (pos.x, pos.y)；Bar/Split 时 textPos.x -= 7
 *     - endPos = textPos.x - w(name) - w(settingDisplay)
 *     - textPos.x = lerp(DisplaySize.x + 14, endPos, anim)
 *     - Glow 且 Display 非 Bar/Split：
 *       AddShadowRect((textPos.x, textPos.y) -> (+w(name+setting), +h), color alpha 0.83*anim,
 *                     glowStrength*anim, offset 0, flags 0, rounding 12)
 *     - rect = (textPos.x, textPos.y, textPos.x + w(name) + w(setting), textPos.y + h)
 *       rect.x -= 3；rect.z += 1；rect.z += 3
 *     - addBlur(rect, BlurStrength, 0, backgroundDrawList, true)
 *     - (Background Shadow||Both) 且 Display != Outline：
 *       AddShadowRect(rect, black alpha 1*opacity, 200, offset 0, flags 0, rounding 12)
 *     - Display == Split：lineStart = (rect.z + 2, textPos.y + 4)
 *                        lineEnd = (lineStart.x + 4, lineStart.y + h - 6)
 *                        AddRectFilled(lineStart, lineEnd, color, rounding 3)
 *                        若 Glow：AddShadowRect(line, color alpha 0.7*anim, glowStrength*anim, 0, 0, 12)
 *     - pos.y += h * anim
 *  3. 第二趟 pos = (DisplaySize.x - 10, 10)，textPos.y = pos.y - 5
 *     - 同上跳过与 textPos.x 计算
 *     - isHovered = 鼠标落在 (textPos.x .. +w(name)+w(setting), textPos.y .. +h)
 *     - backgroundRects.push(rect，Bar/Split 时右移 7)
 *     - Display == Outline：AddRectFilled(rect, color*BackgroundValue, alpha 0.4, rounding 0)
 *     - AddText(fontSize, textPos, color, name)
 *     - settingDisplay 非空：textPos.x += w(name)，AddText((0.9,0.9,0.9,1), settingDisplay)
 *     - isHovered：AddRectFilled(rect, white alpha 0.1)；左键按下则 mod->toggle()
 *     - pos.y += h * anim
 *  4. Display == Bar：第三趟 pos = (DisplaySize.x - 10, 10)，textPos = (pos.x, pos.y)
 *     - Bar/Split 时 textPos.x -= 7
 *     - textX = w(name + settingDisplay)，glowPadding = 0
 *     - (Glow && Bar) || Split：AddShadowRect((textPos.x + textX, textPos.y) -> (同 x, +h),
 *                                            color alpha 0.7*anim, glowStrength*anim, 0, 0, 12)
 *     - pos.y += h * anim
 *  5. Display == None：不再画线，直接结束
 *  6. 画线（每条 start.x -= 2，end.x -= 2）
 *     - Outline：右 (end.x+2, start.y)->(end.x+2, end.y)
 *                左 若 (next.start.x >= start.x) 或没有 next：(start.x, start.y)->(start.x, end.y)
 *                   否则退化点 (next.start.x, start.y)->(next.start.x, start.y)
 *                上 bgi==0 时记录 startingRect 并画 (start.x, start.y)->(end.x+2, start.y)
 *                下 没有 next：(start.x, end.y)->(end.x+2, end.y)
 *                   否则 next.start.x >= start.x 且差值 > 2：(start.x, end.y)->(next.start.x-1, end.y)
 *     - Bar：右 (end.x, start.y)->(end.x, end.y)
 *     - 收尾：backgroundRects 按 start.x 升序，取最左项，画 (lowest.start.x, lowest.start.y)
 *             ->(startingRect.x + 2, lowest.start.y)
 *     - 线宽：Outline 2，其余 4
 *
 * 照抄的原版行为（含看似 bug 的分支）：
 *  - 第三趟判定写作 `glow && Bar || Split`，|| 优先级低于 &&，因此 Display == Split 时
 *    无论 Glow 开关都会画那条发光；Split 分支内的发光仍受 Glow 正常约束。
 *  - 第一趟 pos.y 从 5 起，第二/三趟从 10 起且 textPos.y = pos.y - 5，两趟对齐同一行。
 *  - Outline 模式的底色是 color * BackgroundValue(=0)，实际为黑色 alpha 0.4。
 *
 * 主题色：与原版 Notifications 同源，ColorUtils::getThemedColor → Interface 的
 * mMode / mColorSpeed / mSaturation。LB 无等价模块，这里搬入同一张 ColorThemes 表。
 *
 * 后端映射（无法逐像素复刻的地方）：
 *  - ImGui DisplaySize 是物理像素，LB OverlayRenderEvent 是 GUI 缩放坐标，
 *    统一乘 s = guiScaledHeight / window.height。
 *  - ImGui 字体是 Product Sans Bold 42px 基础、按 25px 绘制（pushPrefFont(true, true)）；
 *    LB 用 FontManager.FONT_RENDERER 缩放到 25 物理像素，字宽与字重无法逐像素一致，
 *    排序与换行位置可能随之微移。
 *  - ImRenderUtils::addBlur 走 D2D 离屏模糊，LB 没有 GUI 模糊通道。Blur Strength 默认 0 时
 *    原版直接 return，视觉完全一致；设为 >0 时这里不绘制（GAP）。
 *  - AddShadowRect 用 16px corner + 1px edge 距离场九宫格，
 *    alpha = (1 - clamp((d_tex + 3.8) / 19.8, 0, 1)) ^ 4.8 再高斯模糊；
 *    这里用同心圆角矩形按同式分层采样，并按叠加结果反解每层 alpha。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

object ModuleSolsticeArraylist : ClientModule(
    "SolsticeArraylist",
    ModuleCategories.RENDER,
    state = true,
    aliases = listOf("ArrayList", "SolarisArraylist"),
) {

    private enum class Display(override val tag: String) : Tagged {
        OUTLINE("Outline"), BAR("Bar"), SPLIT("Split"), NONE("None"),
    }

    private enum class ModuleVisibility(override val tag: String) : Tagged {
        ALL("All"), BOUND("Bound"),
    }

    /** 对应原版 Interface::mNamingStyle（默认 NormalSpaced） */
    private enum class NamingStyle(override val tag: String) : Tagged {
        LOWERCASE("Lowercase"),
        LOWERCASE_SPACED("Lowercase Spaced"),
        NORMAL("Normal"),
        NORMAL_SPACED("Normal Spaced"),
    }

    /** 原样搬自 Interface::ColorThemes */
    private enum class Theme(override val tag: String, val colors: List<Color4b>) : Tagged {
        TRANS(
            "Trans",
            listOf(
                Color4b(91, 206, 250, 255),
                Color4b(245, 169, 184, 255),
                Color4b(255, 255, 255, 255),
                Color4b(245, 169, 184, 255),
            ),
        ),
        RAINBOW("Rainbow", emptyList()),
        BUBBLEGUM(
            "Bubblegum",
            listOf(
                Color4b(255, 99, 202, 255),
                Color4b(255, 195, 195, 255),
                Color4b(146, 245, 255, 255),
                Color4b(249, 255, 148, 255),
                Color4b(135, 255, 176, 255),
            ),
        ),
        WATERMELON(
            "Watermelon",
            listOf(
                Color4b(255, 70, 70, 255),
                Color4b(139, 0, 0, 255),
                Color4b(144, 238, 144, 255),
                Color4b(34, 139, 34, 255),
                Color4b(204, 255, 204, 255),
            ),
        ),
        SUNSET(
            "Sunset",
            listOf(
                Color4b(213, 32, 0, 255),
                Color4b(239, 118, 39, 255),
                Color4b(255, 154, 86, 255),
                Color4b(255, 255, 255, 255),
                Color4b(209, 98, 164, 255),
                Color4b(181, 86, 144, 255),
            ),
        ),
        POISON(
            "Poison",
            listOf(
                Color4b(115, 222, 70, 255),
                Color4b(67, 201, 89, 255),
                Color4b(41, 230, 94, 255),
                Color4b(12, 210, 83, 255),
                Color4b(87, 211, 72, 255),
                Color4b(57, 210, 124, 255),
            ),
        ),
    }

    // ===== 原版实际暴露的设置项 =====
    @Suppress("unused")
    private val blurStrength by float("Blur Strength", 0f, 0f..10f)
    private val display by enumChoice("Display", Display.SPLIT)
    private val visibility by enumChoice("Visibility", ModuleVisibility.ALL)
    private val renderMode by boolean("Render Mode", true)
    private val glow by boolean("Glow", true)
    private val glowStrengthSetting by float("Glow Strength", 1f, 0.5f..1f)

    // ===== 主题色（原版取自 Interface 模块，这里内联）=====
    private val theme by enumChoice("Theme", Theme.TRANS)
    private val colorSpeed by float("Color Speed", 3f, 0.01f..20f)
    private val saturation by float("Saturation", 1f, 0f..1f)
    private val namingStyle by enumChoice("Naming", NamingStyle.NORMAL_SPACED)

    // ===== 原版被注释掉、恒为默认值的项 =====
    private const val BACKGROUND_OPACITY = 1f   // mBackgroundOpacity
    private const val BACKGROUND_VALUE = 0f     // mBackgroundValue
    private const val FONT_SIZE = 25f           // mFontSize
    private const val SHADOW_ROUNDING = 12f     // AddShadowRect(..., 12)
    private const val ANIM_SPEED = 12f          // lerp(..., dt * 12)

    private class Row(
        @JvmField val module: ClientModule,
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val endX: Float,
        @JvmField val endY: Float,
        @JvmField val color: Color4b,
    )

    private class Line(
        @JvmField val x1: Float,
        @JvmField val y1: Float,
        @JvmField val x2: Float,
        @JvmField val y2: Float,
        @JvmField val color: Color4b,
    )

    private val animations = HashMap<ClientModule, Float>(64)
    private var hovered: ClientModule? = null
    private var lastFrameNs = 0L

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val tt = t.coerceIn(0f, 1f)
        return Color4b(
            (a.r + (b.r - a.r) * tt).roundToInt().coerceIn(0, 255),
            (a.g + (b.g - a.g) * tt).roundToInt().coerceIn(0, 255),
            (a.b + (b.b - a.b) * tt).roundToInt().coerceIn(0, 255),
            (a.a + (b.a - a.a) * tt).roundToInt().coerceIn(0, 255),
        )
    }

    /** ColorUtils::Rainbow */
    private fun rainbow(seconds: Float, sat: Float, brightness: Float, index: Float): Color4b {
        val periodMs = (seconds * 1000f).toLong().coerceAtLeast(1L)
        val hue = ((System.currentTimeMillis() + index.toLong()) % periodMs).toFloat() / periodMs
        return Color4b.ofHSB(hue, sat, brightness)
    }

    /** ColorUtils::LerpColors */
    private fun lerpColors(seconds: Float, index: Float, colors: List<Color4b>): Color4b {
        if (colors.isEmpty()) return Color4b(255, 255, 255, 255)
        val time = 10000f / seconds
        val angle = ((System.currentTimeMillis() + index.toLong()) % time.toLong()).toFloat()
        val segmentTime = time / colors.size
        val raw = angle / segmentTime
        val segment = raw.toInt().coerceIn(0, colors.size - 1)
        return lerpColor(colors[segment], colors[(segment + 1) % colors.size], raw - segment)
    }

    /** ColorUtils::getThemedColor */
    private fun themedColor(index: Float): Color4b =
        if (theme == Theme.RAINBOW) rainbow(colorSpeed, saturation, 1f, index)
        else lerpColors(colorSpeed, index, theme.colors)

    /**
     * ImGui ImDrawList::AddShadowRect(obj_min, obj_max, col, thickness, offset = 0, flags = 0, rounding)
     *
     * flags = 0 → is_filled = true，3x3 九宫格全部绘制，不挖空背景。
     * 纹理参数：TexCornerSize 16 / TexEdgeSize 1 / TexFalloffPower 4.8 /
     *          TexDistanceFieldOffset 3.8 / TexBlur true。
     */
    private fun drawShadowRect(
        ctx: GuiGraphicsExtractor,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Color4b,
        thickness: Float,
        rounding: Float,
    ) {
        if (thickness <= 0f || color.a <= 0) return
        val corner = 16f
        val distanceOffset = 3.8f
        val falloff = 4.8f
        val steps = 16
        var accumulated = 0f
        for (i in steps downTo 1) {
            val t = i / steps.toFloat()
            val expand = thickness * t
            val sampleDist = corner * t
            val normalized = ((sampleDist + distanceOffset) / (corner + distanceOffset)).coerceIn(0f, 1f)
            val target = (1f - normalized).pow(falloff) * (color.a / 255f)
            if (target <= accumulated + 0.001f) continue
            val layer = ((target - accumulated) / (1f - accumulated)).coerceIn(0f, 1f)
            accumulated = target
            val a = (layer * 255f).roundToInt().coerceIn(0, 255)
            if (a <= 0) continue
            ctx.drawRoundedRect(
                x0 - expand, y0 - expand, x1 + expand, y1 + expand,
                rounding + expand,
                Color4b(color.r, color.g, color.b, a),
            )
        }
    }

    /** ImGui AddLine 是中心线加线宽；原版所有线都轴对齐，直接换算成矩形 */
    private fun drawLineSeg(
        ctx: GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color4b, thickness: Float,
    ) {
        if (color.a <= 0) return
        val half = thickness / 2f
        if (y1 == y2) {
            ctx.drawQuad(minOf(x1, x2), y1 - half, maxOf(x1, x2), y1 + half, color)
        } else if (x1 == x2) {
            ctx.drawQuad(x1 - half, minOf(y1, y2), x1 + half, maxOf(y1, y2), color)
        }
        // 其余情况原版只有零长度退化点，不产生可见像素
    }

    /** 原版 Module::getName()：按 Interface::mNamingStyle 从 mNames 取对应写法 */
    private fun moduleName(module: ClientModule): String {
        val raw = module.name
        return when (namingStyle) {
            NamingStyle.NORMAL -> raw
            NamingStyle.LOWERCASE -> splitCamelCase(raw).joinToString("").lowercase()
            NamingStyle.LOWERCASE_SPACED -> splitCamelCase(raw).joinToString(" ").lowercase()
            NamingStyle.NORMAL_SPACED -> splitCamelCase(raw).joinToString(" ")
        }
    }

    /** "KillAura" → ["Kill", "Aura"]；连续大写按单词处理 */
    private fun splitCamelCase(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val words = ArrayList<String>(4)
        val current = StringBuilder()
        for ((index, ch) in raw.withIndex()) {
            if (index > 0 && ch.isUpperCase() && current.isNotEmpty()) {
                words += current.toString()
                current.setLength(0)
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    /** 原版 Module::getSettingDisplayText()：Lowercase 两种风格会把 setting display 转小写 */
    private fun settingDisplay(module: ClientModule): String {
        if (!renderMode) return ""
        val tag = module.tag
        if (tag.isNullOrEmpty()) return ""
        val text = if (namingStyle == NamingStyle.LOWERCASE || namingStyle == NamingStyle.LOWERCASE_SPACED) {
            tag.lowercase()
        } else {
            tag
        }
        return " $text"
    }

    @Suppress("unused")
    private val clickHandler = handler<MouseButtonEvent> { event ->
        if (!event.isLeftClick) return@handler
        val target = hovered ?: return@handler
        target.enabled = !target.enabled
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context

        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs

        // 物理像素 → GUI 坐标
        val s = mc.window.guiScaledHeight.toFloat() / mc.window.height.toFloat()
        val displayW = ctx.guiWidth().toFloat()

        val renderer = FontManager.FONT_RENDERER
        // 原版以 25 物理像素绘制；LB 用行高求缩放因子
        val textScale = (FONT_SIZE * s) / renderer.height
        val textHeight = FONT_SIZE * s
        // getStringWidth(Component, scale) 内部会乘 scale，不要再手动乘一次
        val widthOf: (String) -> Float = { text ->
            renderer.getStringWidth(text.asPlainText(), textScale)
        }
        val drawText: (String, Float, Float, Color4b) -> Unit = { text, x, y, color ->
            renderer.draw(ctx, text.asPlainText(), x, y, color, false, textScale)
        }

        // 收集可见模块
        val visible = ArrayList<ClientModule>(64)
        for (module in ModuleManager) {
            if (module.hidden) continue
            if (visibility == ModuleVisibility.BOUND && module.bind.isUnbound) continue
            visible += module
        }
        animations.keys.retainAll(visible.toHashSet())

        // 排序：按 (name + " " + settingDisplay) 像素宽度降序
        visible.sortByDescending { module -> widthOf(moduleName(module) + settingDisplay(module)) }

        val glowStrength = glowStrengthSetting * 100f
        val mouseX = MouseHandler.getScaledXPos(mc.window, mc.mouseHandler.xpos()).toFloat()
        val mouseY = MouseHandler.getScaledYPos(mc.window, mc.mouseHandler.ypos()).toFloat()

        // ===== 第一趟：发光 / Split 条 / 背景阴影 =====
        var posX = displayW - 10f * s
        var posY = 5f * s
        val anims = HashMap<ClientModule, Float>(visible.size)

        for (module in visible) {
            var anim = animations[module] ?: 0f
            anim = lerp(anim, if (module.enabled) 1f else 0f, dt * ANIM_SPEED).coerceIn(0f, 1f)
            animations[module] = anim
            anims[module] = anim
            if (anim < 0.01f) continue

            val color = themedColor(posY / s * 2f)
            val name = moduleName(module)
            val setting = settingDisplay(module)
            val textWidth = widthOf(name)
            var textX = posX
            if (display == Display.BAR || display == Display.SPLIT) textX -= 7f * s
            val settingWidth = if (setting.isEmpty()) 0f else widthOf(setting)
            val endPos = textX - textWidth - settingWidth
            textX = lerp(displayW + 14f * s, endPos, anim)

            if (glow && display != Display.BAR && display != Display.SPLIT) {
                drawShadowRect(
                    ctx, textX, posY,
                    textX + widthOf(name + setting), posY + textHeight,
                    color.alpha((0.83f * anim * 255f).roundToInt().coerceIn(0, 255)),
                    glowStrength * s * anim, SHADOW_ROUNDING * s,
                )
            }

            val rectX1 = textX - 3f * s
            val rectX2 = textX + textWidth + settingWidth + 1f * s + 3f * s

            // addBlur(rect, BlurStrength, 0, backgroundDrawList, true)：LB 无 GUI 模糊通道（GAP）
            if (display != Display.OUTLINE) {
                drawShadowRect(
                    ctx, rectX1, posY, rectX2, posY + textHeight,
                    Color4b(0, 0, 0, (255f * BACKGROUND_OPACITY).roundToInt().coerceIn(0, 255)),
                    200f * s, SHADOW_ROUNDING * s,
                )
            }

            if (display == Display.SPLIT) {
                val lineX = rectX2 + 2f * s
                val lineY1 = posY + 4f * s
                val lineY2 = lineY1 + textHeight - 6f * s
                ctx.drawRoundedRect(lineX, lineY1, lineX + 4f * s, lineY2, 3f * s, color)
                if (glow) {
                    drawShadowRect(
                        ctx, lineX, lineY1, lineX + 4f * s, lineY2,
                        color.alpha((0.7f * anim * 255f).roundToInt().coerceIn(0, 255)),
                        glowStrength * s * anim, SHADOW_ROUNDING * s,
                    )
                }
            }

            posY += textHeight * anim
        }

        // ===== 第二趟：文字 / Outline 底色 / 悬停 =====
        posX = displayW - 10f * s
        posY = 10f * s
        hovered = null
        val rows = ArrayList<Row>(visible.size)

        for (module in visible) {
            val anim = anims[module] ?: 0f
            if (anim < 0.01f) continue

            val color = themedColor(posY / s * 2f)
            val name = moduleName(module)
            val setting = settingDisplay(module)
            val textWidth = widthOf(name)
            var textX = posX
            val textY = posY - 5f * s
            var addedPadding = false
            if (display == Display.BAR || display == Display.SPLIT) {
                textX -= 7f * s
                addedPadding = true
            }
            val settingWidth = if (setting.isEmpty()) 0f else widthOf(setting)
            val endPos = textX - textWidth - settingWidth
            textX = lerp(displayW + 14f * s, endPos, anim)

            val isHovered = mouseX >= textX && mouseX <= textX + textWidth + settingWidth &&
                mouseY >= textY && mouseY <= textY + textHeight

            val rectX1 = textX - 3f * s
            val rectX2 = textX + textWidth + settingWidth + 1f * s + 3f * s
            val padOffset = if (addedPadding) 7f * s else 0f

            rows += Row(module, rectX1 + padOffset, textY, rectX2 + padOffset, textY + textHeight, color)

            if (display == Display.OUTLINE) {
                // 原版 color * mBackgroundValue(=0) → 黑色，alpha 0.4
                val bg = Color4b(
                    (color.r * BACKGROUND_VALUE).roundToInt().coerceIn(0, 255),
                    (color.g * BACKGROUND_VALUE).roundToInt().coerceIn(0, 255),
                    (color.b * BACKGROUND_VALUE).roundToInt().coerceIn(0, 255),
                    102,
                )
                ctx.drawQuad(rectX1, textY, rectX2 + padOffset, textY + textHeight, bg)
            }

            drawText(name, textX, textY, color)
            if (setting.isNotEmpty()) {
                drawText(setting, textX + textWidth, textY, Color4b(229, 229, 229, 255))
            }

            if (isHovered) {
                ctx.drawQuad(rectX1, textY, rectX2, textY + textHeight, Color4b(255, 255, 255, 25))
                hovered = module
            }

            posY += textHeight * anim
        }

        // ===== 第三趟：Bar 模式的发光 =====
        if (display == Display.BAR) {
            posX = displayW - 10f * s
            posY = 10f * s
            for (module in visible) {
                val anim = (anims[module] ?: 0f).coerceIn(0f, 1f)
                if (anim < 0.01f) continue

                val color = themedColor(posY / s * 2f)
                val name = moduleName(module)
                val setting = settingDisplay(module)
                var textX = posX
                if (display == Display.BAR || display == Display.SPLIT) textX -= 7f * s
                val settingWidth = if (setting.isEmpty()) 0f else widthOf(setting)
                val endPos = textX - widthOf(name) - settingWidth
                textX = lerp(displayW + 14f * s, endPos, anim)

                // 原版写作 `glow && Bar || Split`，|| 优先级更低 → Split 恒成立
                val drawBarGlow = (glow && display == Display.BAR) || display == Display.SPLIT
                if (drawBarGlow) {
                    val textW = widthOf(name + setting)
                    drawShadowRect(
                        ctx, textX + textW, posY, textX + textW, posY + textHeight,
                        color.alpha((0.7f * anim * 255f).roundToInt().coerceIn(0, 255)),
                        glowStrength * s * anim, SHADOW_ROUNDING * s,
                    )
                }

                posY += textHeight * anim
            }
        }

        if (display == Display.NONE) return@handler

        // ===== 画线 =====
        val lines = ArrayList<Line>(rows.size * 4)

        if (display == Display.OUTLINE) {
            var startingX = 0f
            for ((index, row) in rows.withIndex()) {
                val startX = row.x - 2f * s
                val endX = row.endX - 2f * s
                val startY = row.y
                val endY = row.endY

                // 之后所有背景里最靠左的一条
                var nextStartX = Float.NaN
                for (i in index + 1 until rows.size) {
                    val candidate = rows[i].x - 2f * s
                    if (nextStartX.isNaN() || candidate < nextStartX) nextStartX = candidate
                }
                val hasNext = !nextStartX.isNaN()

                // 右
                lines += Line(endX + 2f * s, startY, endX + 2f * s, endY, row.color)
                // 左
                if (!hasNext || nextStartX >= startX) {
                    lines += Line(startX, startY, startX, endY, row.color)
                } else {
                    lines += Line(nextStartX, startY, nextStartX, startY, row.color)
                }
                // 上
                if (index == 0) {
                    startingX = startX
                    lines += Line(startX, startY, endX + 2f * s, startY, row.color)
                }
                // 下
                if (!hasNext) {
                    lines += Line(startX, endY, endX + 2f * s, endY, row.color)
                } else if (nextStartX >= startX && nextStartX - startX > 2f * s) {
                    lines += Line(startX, endY, nextStartX - 1f * s, endY, row.color)
                }
            }

            // 收尾：最左一条的上边
            val lowest = rows.minByOrNull { it.x }
            if (lowest != null) {
                lines += Line(lowest.x, lowest.y, startingX + 2f * s, lowest.y, lowest.color)
            }
        } else if (display == Display.BAR) {
            for (row in rows) {
                val endX = row.endX - 2f * s
                lines += Line(endX, row.y, endX, row.endY, row.color)
            }
        }

        val thickness = if (display == Display.OUTLINE) 2f * s else 4f * s
        for (line in lines) {
            drawLineSeg(ctx, line.x1, line.y1, line.x2, line.y2, line.color, thickness)
        }
    }

    override fun onDisabled() {
        animations.clear()
        hovered = null
        lastFrameNs = 0L
    }
}