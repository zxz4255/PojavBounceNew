/*
 * ModuleNotifications —— 移植 Solstice (vastrakai) src/Features/Modules/Visual/Notifications.{hpp,cpp}
 *
 * 逐项对照原版 onRenderEvent：
 *  1. 起始 y = DisplaySize.y - 10
 *  2. 每帧先删 isTimeUp && mTimeShown > mDuration + 3.0 的条目
 *  3. mTimeShown += delta；mIsTimeUp = mTimeShown >= mDuration
 *  4. mCurrentDuration = lerp(mCurrentDuration, isTimeUp ? 0 : 1, delta * 5)
 *  5. percentDone = clamp(mTimeShown / mDuration, 0, 1)
 *  6. boxSize = (max(200, 50 + textWidth), textHeight + 30)
 *  7. x = lerp(screen.x + boxW, screen.x - boxW - 10, currentDuration)
 *     yOff = lerp(yOff, yOff - boxH, currentDuration)，y = yOff
 *  8. 主题色 = getThemedColor(y * 2)；Warning = (1,0.8,0,1)，Error = (1,0,0,1)；w = 0.7
 *  9. AddShadowRect((x,y)->progMax, 主题色 alpha 1, 50, offset 0, flags 0, rounding 5)
 *     progMax = (clamp(x + boxW*percentDone + 6, x, x+boxW), y + boxH - 10)
 * 10. clip((x,y)->(x + boxW*percentDone, y + boxH - 10))，AddRectFilled(..., rounding 5)
 * 11. clip((x + boxW*percentDone, y)->(x + boxW, y + boxH - 10))，
 *     AddRectFilled((x + boxW*percentDone - 6, y)->bgMax, black 0.7, rounding 5)
 * 12. AddText(font, 20, (x + 10, y + 10), white, message)
 * 13. 限制：mLimitNotifications 且 i > mMaxNotifications 时 break（原版是 > 不是 >=）
 *
 * 事件接线（原版）：
 *  - onModuleStateChange → "<name> was enabled/disabled"，3s，INFO
 *  - onConnectionRequestEvent → "Connecting to <addr>..."，6s，INFO
 *  - onNotifyEvent → 直接 push 外部 Notification
 *
 * 已知边界（框架差异，不是移植遗漏）：
 *  - LB 的 ClientModule.running 要求 inGame == true，事件分发前会检查 running
 *    （EventManager: if (!eventHook.handlerClass.running)）。ServerConnectEvent 发生在
 *    连接过程中、玩家实体尚未创建，此时 inGame 为 false，因此 connectHandler 实际不会
 *    被调用，进服提示在本框架下无法通过 handler 触发。原版 ConnectionRequestEvent 没有
 *    这个前置条件。
 *  - ModuleToggleEvent 由 ClientModule.onToggled 在 inGame 时发出，开关通知正常。

 *
 * 主题色来源：原版 Notifications 不持有主题，取色走 ColorUtils::getThemedColor →
 * Interface 模块的 mMode / mColorSpeed / mSaturation / mColor1..6。LB 的主题是 Web
 * ThemeManager，没有等价模块，所以把 Solstice Interface 的 ColorThemes 表原样搬进来，
 * 并暴露 Theme / Color Speed / Saturation 三项。原版 Interface 默认 mMode 是
 * MathUtils::random(0, Custom)（每次启动随机），这里取第一个确定项 Trans。
 *
 * 后端映射（无法逐像素复刻的三处）：
 *  - AddShadowRect 用 16px corner + 1px edge 的距离场九宫格纹理，
 *    alpha = (1 - clamp((d_tex + 3.8) / (16 + 3.8), 0, 1)) ^ 4.8，再高斯模糊。
 *    LB 无等价 API，这里用同心圆角矩形按同一公式分层采样，并按叠加结果反解每层 alpha。
 *  - ImGui DisplaySize 是物理像素；LB OverlayRenderEvent 是 GUI 缩放坐标，
 *    统一乘 s = guiScaledHeight / window.height 换算，屏幕占比一致。
 *  - 字体：ImGui 用 Product Sans 20px 矢量；LB 用 mc.font 缩放到 14 物理像素，
 *    字宽由位图字体度量决定，无法与矢量字体逐像素一致。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ServerConnectEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

object ModuleNotifications : ClientModule(
    "Notifications",
    ModuleCategories.RENDER,
    state = true,
    aliases = listOf("SolarisNotifications"),
) {

    private enum class Style(override val tag: String) : Tagged {
        SOLARIS("Solaris"),
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

    @Suppress("unused")
    private val style by enumChoice("Style", Style.SOLARIS)
    private val showOnToggle by boolean("Show on toggle", true)
    private val showOnJoin by boolean("Show on join", true)
    private val limitNotifications by boolean("Limit notifications", false)
    private val maxNotifications by int("Max notifications", 6, 1..25)
        .visibleWhen { limitNotifications }

    private val theme by enumChoice("Theme", Theme.TRANS)
    private val colorSpeed by float("Color Speed", 3f, 0.01f..20f)
    private val saturation by float("Saturation", 1f, 0f..1f)

    private enum class Type { INFO, WARNING, ERROR }

    private class Entry(
        @JvmField val message: String,
        @JvmField val type: Type,
        @JvmField val duration: Float,
    ) {
        @JvmField var currentDuration = 0f
        @JvmField var timeShown = 0f
        @JvmField var isTimeUp = false
    }

    private val entries = ArrayList<Entry>(8)
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

    /** ColorUtils::Rainbow —— hue = ((NOW + index) % (seconds * 1000)) / (seconds * 1000) */
    private fun rainbow(seconds: Float, sat: Float, brightness: Float, index: Float): Color4b {
        val periodMs = (seconds * 1000f).toLong().coerceAtLeast(1L)
        val hue = ((System.currentTimeMillis() + index.toLong()) % periodMs).toFloat() / periodMs
        return Color4b.ofHSB(hue, sat, brightness)
    }

    /** ColorUtils::LerpColors —— time = 10000 / seconds，按 colors.size 分段线性插值 */
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

    /** 原版：Warning = ImColor(1,0.8,0,1)，Error = ImColor(1,0,0,1)，其余 getThemedColor；最后 w = 0.7 */
    private fun typeColor(type: Type, y: Float): Color4b {
        val base = when (type) {
            Type.WARNING -> Color4b(255, 204, 0, 255)
            Type.ERROR -> Color4b(255, 0, 0, 255)
            Type.INFO -> themedColor(y * 2f)
        }
        return base.alpha(178)
    }

    /**
     * ImGui ImDrawList::AddShadowRect(obj_min, obj_max, col, thickness, offset = 0, flags = 0, rounding)
     *
     * flags = 0 → is_filled = true，3x3 九宫格全部绘制，不挖空背景。
     * 纹理参数：TexCornerSize 16 / TexEdgeSize 1 / TexFalloffPower 4.8 /
     *          TexDistanceFieldOffset 3.8 / TexBlur true。
     *
     * 采样：九宫格被拉伸到 thickness，物理距离 d 对应纹理坐标 d * 16 / thickness，
     *       alpha = (1 - clamp((d_tex + 3.8) / (16 + 3.8), 0, 1)) ^ 4.8。
     * 绘制：从外到内画同心圆角矩形；每层 alpha 按「叠加后恰好等于目标值」反解，
     *       避免半透明层反复叠压把辉光画得比原版浓。
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

    private fun push(message: String, type: Type, duration: Float) {
        entries += Entry(message, type, duration)
    }

    /** 原版 onModuleStateChange：只判 isCancelled()，不区分 hidden */
    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { event ->
        if (!showOnToggle) return@handler
        push(
            "${event.moduleName} was ${if (event.enabled) "enabled" else "disabled"}",
            Type.INFO,
            3.0f,
        )
    }

    /** 原版 onNotifyEvent：直接收外部 Notification */
    @Suppress("unused")
    private val notificationHandler = handler<NotificationEvent> { event ->
        // 模块开关已由 ModuleToggleEvent 覆盖，避免同一条重复入队
        if (event.severity == NotificationEvent.Severity.ENABLED ||
            event.severity == NotificationEvent.Severity.DISABLED
        ) {
            return@handler
        }
        val type = if (event.severity == NotificationEvent.Severity.ERROR) Type.ERROR else Type.INFO
        val text = event.message.ifBlank { event.title }
        if (text.isBlank()) return@handler
        push(text, type, 3.0f)
    }

    /** 原版 onConnectionRequestEvent："Connecting to <addr>..."，6s */
    @Suppress("unused")
    private val connectHandler = handler<ServerConnectEvent> { event ->
        if (!showOnJoin) return@handler
        val address = runCatching { event.address.toString() }.getOrDefault("server")
        push("Connecting to $address...", Type.INFO, 6.0f)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        try {
            renderNotifications(event)
        } catch (t: Throwable) {
            // 绝不能让通知模块把整帧打崩
            System.err.println("[Notifications] render failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun renderNotifications(event: OverlayRenderEvent) {
        val ctx = event.context

        val nowNs = System.nanoTime()
        val delta = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs

        entries.removeAll { it.isTimeUp && it.timeShown > it.duration + 3.0f }
        if (entries.isEmpty()) return

        // 物理像素 → GUI 坐标
        val s = mc.window.guiScaledHeight.toFloat() / mc.window.height.toFloat()
        val displayW = ctx.guiWidth().toFloat()
        val displayH = ctx.guiHeight().toFloat()

        val font = mc.font
        // 字体 14 物理像素（略小于原版 20），按位图字体行高求缩放因子
        val textScale = (10f * s) / font.lineHeight

        var yOff = displayH - 10f * s
        var index = 0

        for (entry in entries) {
            if (limitNotifications && index > maxNotifications) break

            entry.timeShown += delta
            entry.isTimeUp = entry.timeShown >= entry.duration
            entry.currentDuration = lerp(
                entry.currentDuration,
                if (entry.isTimeUp) 0f else 1f,
                delta * 5f,
            )

            val percentDone = (entry.timeShown / entry.duration).coerceIn(0f, 1f)

            val textWidth = font.width(entry.message) * textScale
            val textHeight = font.lineHeight * textScale
            val boxW = max(200f * s, 50f * s + textWidth)
            val boxH = textHeight + 30f * s

            val beginX = displayW - boxW - 10f * s
            val endX = displayW + boxW
            val x = lerp(endX, beginX, entry.currentDuration)
            yOff = lerp(yOff, yOff - boxH, entry.currentDuration)
            val y = yOff

            if (x > displayW + boxW && y > displayH + boxH) continue

            // 主题色索引 y * 2 用原版物理像素坐标
            val color = typeColor(entry.type, y / s)
            val radius = 5f * s
            val maxX = x + boxW
            val progMaxX = (x + boxW * percentDone + 6f * s).coerceIn(x, maxX)
            val progMaxY = y + (boxH - 10f * s)

            // MC 26.3：enableScissor 宽/高 <=0 会直接崩帧，这里完全不用 scissor，
            // 用几何裁剪（只画有宽度的矩形）达到同样的进度条效果。
            if (progMaxY <= y + 0.5f || maxX <= x + 0.5f) {
                if (!entry.isTimeUp) index++
                continue
            }

            // 1) 辉光
            drawShadowRect(
                ctx, x, y, progMaxX, progMaxY,
                Color4b(color.r, color.g, color.b, 255),
                50f * s, radius,
            )

            // 2) 进度色块（左→右随 percentDone 变宽）
            val fillRight = (x + boxW * percentDone + 6f * s).coerceIn(x, maxX)
            if (fillRight > x + 0.5f) {
                ctx.drawRoundedRect(x, y, fillRight, progMaxY, radius, color)
            }

            // 3) 剩余背景（进度右侧）
            val bgLeft = (x + boxW * percentDone - 6f * s).coerceIn(x, maxX)
            if (maxX > bgLeft + 0.5f) {
                ctx.drawRoundedRect(bgLeft, y, maxX, progMaxY, radius, Color4b(0, 0, 0, 178))
            }

            // 4) 文字
            ctx.pose().withPush {
                translate(x + 10f * s, y + 10f * s)
                scale(textScale, textScale)
                ctx.text(font, entry.message, 0, 0, Color4b(255, 255, 255, 255).argb, false)
            }

            if (!entry.isTimeUp) index++
        }
    }

    override fun onDisabled() {
        entries.clear()
        lastFrameNs = 0L
    }
}
