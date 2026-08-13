/*
 * ============================================================================
 *  ModuleSolsticeNotifications —— 移植 Solstice 的 Notifications.cpp/hpp (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  原版功能 (Notifications.cpp, Dear ImGui) —— 本版逐行还原:
 *   1. 动画: MathUtils::lerp 纯线性插值
 *      - currentDuration 每帧 lerp 到 (isTimeUp ? 0 : 1), 速率 deltaTime * 5
 *      - CalcSize: x = lerp(screenW+boxW, screenW-boxW-10, currentDuration) 从右滑入
 *                  yOff = lerp(yOff, yOff-boxH, currentDuration) 逐条向上堆叠
 *   2. 进度条: percentDone = timeShown/duration (增长)
 *      - 进度区: (x, y) → (x + boxW*percentDone+6 clamp, y+boxH-10), 主题色 0.7
 *      - 剩余区: (x + boxW*percentDone-6, y) → (x+boxW, y+boxH-10), 黑色 0.7
 *   3. 类型色: Info=主题色(粉蓝白) / Warning=黄 / Error=红
 *   4. 多层阴影 (AddShadowRect, 作用于进度区)
 *   5. 清理: isTimeUp && timeShown > duration + 3
 *   6. maxNotifications 只统计未超时通知 (i++)
 *
 *  移植说明:
 *   - ImGui AddRectFilled → drawRoundedRect (圆角 5)
 *   - AddText → context.text(mc.font, 9px; textH=9 → boxH=39)
 *   - AddShadowRect → 多层描边光晕 (outline 模式)
 *   - AddRectFilledMultiColor (渐变, 默认关) → 8 段颜色插值近似
 *   - getThemedColor → 粉蓝白三色循环插值 (与 Arraylist 移植一致)
 *
 *  可调节项 (20+): 右边缘距/底部距/条间距、限制条数/最大条数/默认时长、
 *        模块开关通知/进服通知/隐藏模块通知、最小宽度、阴影模糊/密度、
 *        进度条开关/颜色渐变/文字阴影、颜色模式 (Theme/Rainbow)、
 *        动画速度 (原版 5, 可调大加快) 等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor, 无 Web 依赖。
 *
 *  安装:
 *    1. 放入 src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleSolsticeNotifications.kt
 *    2. ModuleManager.kt: import + builtin 列表加 ModuleSolsticeNotifications,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

object ModuleSolsticeNotifications : ClientModule(
    "SolsticeNotifications",
    ModuleCategories.RENDER,
    aliases = listOf("SolsticeNotify", "Notifications"),
) {

    /* ============================= 通知类型 ============================= */

    enum class NotificationType { INFO, WARNING, ERROR }

    /** 原版 Notification 结构体逐字段还原 */
    private class Notification(
        val message: String,
        val type: NotificationType,
        val duration: Float,          // 秒
    ) {
        var timeShown = 0f
        var currentDuration = 0f      // 滑入/滑出动画 0..1
        var isTimeUp = false
    }

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val rightMargin by int("Right Margin", 10, 0..100)     // 原版 CalcSize 右距 10
    private val bottomOffset by int("Bottom Offset", 10, 0..200)   // 原版 displaySize.y - 10
    private val spacing by int("Spacing", 0, 0..20)                // 原版无间距(靠 yOff 堆叠)

    // —— 通知 ——
    private val limitNotifications by boolean("Limit Notifications", false)
    private val maxNotifications by int("Max Notifications", 6, 1..10)
    private val defaultDuration by int("Default Duration", 3000, 500..10000)   // 毫秒
    private val showOnToggle by boolean("Show On Toggle", true)
    private val showOnJoin by boolean("Show On Join", true)
    private val notifyHiddenModules by boolean("Notify Hidden Modules", false)

    // —— 外观 ——
    private val minWidth by int("Min Width", 200, 80..400)         // 原版 fmax(200, 50+textW)
    private val shadowBlur by float("Shadow Blur", 10f, 0f..30f)
    private val shadowDensity by int("Shadow Density", 2, 0..8)
    private val showProgressBar by boolean("Show Progress Bar", true)
    private val colorGradient by boolean("Color Gradient", false)
    private val textShadow by boolean("Text Shadow", true)

    // —— 颜色 ——
    private enum class ColorMode(override val tag: String) : Tagged { THEME("Theme"), RAINBOW("Rainbow") }
    private val colorMode by enumChoice("Color Mode", ColorMode.THEME)
    private val rainbowSpeed by float("Rainbow Speed", 1f, 0.1f..10f)

    // —— 动画 ——
    // 原版: deltaTime * 5.0f (纯线性 lerp)。此配置直接替换 5.0f, 默认保持原版。
    private val animationSpeed by float("Animation Speed", 80f, 1f..100f)

    /* ============================= 内部状态 ============================= */

    private val notifications = ArrayList<Notification>()
    private var lastFrameNs = 0L
    private var startupTime = 0L

    // 原版主题色板 (粉蓝白)
    private val themeColors = listOf(
        Color4b(0xE9, 0xA8, 0xBC),
        Color4b(0x6E, 0xC8, 0xF1),
        Color4b(255, 255, 255, 128),
    )

    /* ============================= 工具 ============================= */

    /** 原版 MathUtils::lerp 纯线性 */
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
        return Color4b.ofHSB(hue, 1f, 1f)
    }

    private fun resolveColor(index: Float): Color4b = when (colorMode) {
        ColorMode.THEME -> themedColor(index)
        ColorMode.RAINBOW -> rainbowColor(index)
    }

    /* ============================= 发送 API ============================= */

    fun add(message: String, type: NotificationType = NotificationType.INFO, duration: Float = defaultDuration / 1000f) {
        while (limitNotifications && notifications.size >= maxNotifications) {
            notifications.removeAt(notifications.lastIndex)
        }
        notifications.add(0, Notification(message, type, duration))
    }

    /* ============================= 事件触发 ============================= */

    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { event ->
        if (!showOnToggle) return@handler
        if (event.moduleName == name) return@handler
        if (event.hidden && !notifyHiddenModules) return@handler
        if (System.currentTimeMillis() - startupTime < 3000) return@handler   // 启动加载过滤
        add(
            "${event.moduleName} ${if (event.enabled) "Enabled" else "Disabled"}",
            if (event.enabled) NotificationType.INFO else NotificationType.WARNING,
            defaultDuration / 1000f,
        )
    }

    @Suppress("unused")
    private val joinHandler = handler<WorldChangeEvent> {
        if (!showOnJoin) return@handler
        val server = mc.currentServer?.ip?.takeIf { it.isNotEmpty() } ?: "SinglePlayer"
        add("Joined $server", NotificationType.INFO, defaultDuration / 1000f)
    }

    override suspend fun enabledEffect() {
        startupTime = System.currentTimeMillis()
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

        renderNotifications(context, frameTime)
    }

    private fun renderNotifications(ctx: GuiGraphicsExtractor, dt: Float) {
        // 原版: std::erase_if(isTimeUp && timeShown > duration + 3)
        notifications.removeAll { it.isTimeUp && it.timeShown > it.duration + 3f }
        if (notifications.isEmpty()) return

        val font = mc.font
        val screenW = ctx.guiWidth().toFloat()
        val screenH = ctx.guiHeight().toFloat()

        // 原版: float y = displaySize.y - 10 (逐条被 CalcSize 上移, 形成堆叠)
        var y = screenH - bottomOffset
        var x = 0f
        var i = 0

        for (n in notifications) {
            // 原版: if (i >= mMaxNotifications && mLimitNotifications) break;
            if (i >= maxNotifications && limitNotifications) break

            // 原版: 时间推进 + currentDuration 线性 lerp (deltaTime * 5)
            n.timeShown += dt
            n.isTimeUp = n.timeShown >= n.duration
            n.currentDuration = lerp(
                n.currentDuration,
                if (n.isTimeUp) 0f else 1f,
                (dt * animationSpeed).coerceAtMost(1f),
            )

            val percentDone = (n.timeShown / n.duration).coerceIn(0f, 1f)

            // 原版: textH = 字体高度, boxH = textH + 30 (9px → 39)
            val textW = font.width(n.message).toFloat()
            val boxW = maxOf(minWidth.toFloat(), 50f + textW)
            val boxH = 9f + 30f

            // 原版 CalcSize: 修改 x 和 y (线性插值)
            val beginX = screenW - boxW - rightMargin
            val endX = screenW + boxW
            x = lerp(endX, beginX, n.currentDuration)
            y = lerp(y, y - boxH, n.currentDuration)

            // 原版: 完全移出判定 (x 越界 且 y 越界)
            if (x > screenW + boxW && y > screenH + boxH) continue

            // 原版: themeColor (Info=主题色(y*2) / Warning=黄 / Error=红), alpha 0.7
            var baseColor = resolveColor(y * 2f)
            when (n.type) {
                NotificationType.WARNING -> baseColor = Color4b(255, 204, 0)
                NotificationType.ERROR -> baseColor = Color4b(255, 0, 0)
                NotificationType.INFO -> Unit
            }
            val themeColor = baseColor.alpha(179)

            // 原版: progressW = boxW*percentDone + 6, progMax.x = clamp(x+progressW, x, x+boxW)
            val progressW = boxW * percentDone + 6f
            val progMaxX = (x + progressW).coerceIn(x, x + boxW)

            // 原版: AddShadowRect(x, y, progMax, themeColor, blur, density) —— 阴影作用于进度区
            if (shadowDensity > 0 && shadowBlur > 0f) {
                for (d in 0 until shadowDensity) {
                    val t = d / (shadowDensity - 1).coerceAtLeast(1).toFloat()
                    val w = shadowBlur * (0.25f + 0.75f * t)
                    val a = (0.4f * 255 * (1f - t * 0.6f)).roundToInt().coerceIn(0, 255)
                    ctx.drawRoundedRect(
                        x - w * 0.25f, y - w * 0.25f, progMaxX + w * 0.25f, y + boxH - 10f + w * 0.25f,
                        w * 0.5f, Color4b.TRANSPARENT, Color4b(0, 0, 0, a), w * 0.4f,
                    )
                }
            }

            if (showProgressBar) {
                // 进度区: (x, y) → (progMaxX, y+boxH-10), 主题色, 圆角 5
                if (colorGradient) {
                    // AddRectFilledMultiColor 近似: 8 段水平插值
                    val segments = 8
                    for (s in 0 until segments) {
                        val sx = x + (progMaxX - x) * s / segments
                        val ex = x + (progMaxX - x) * (s + 1) / segments
                        val c = themeColor.interpolateTo(
                            resolveColor(y * 2f + (sx - progMaxX) * 1.2f),
                            (s / (segments - 1).toFloat()).toDouble(),
                        )
                        ctx.drawRoundedRect(sx, y, ex, y + boxH - 10f, 5f, c)
                    }
                } else {
                    ctx.drawRoundedRect(x, y, progMaxX, y + boxH - 10f, 5f, themeColor)
                }

                // 剩余区: (x + boxW*percentDone - 6, y) → (x+boxW, y+boxH-10), 黑色 0.7, 圆角 5
                val bgMinX = x + boxW * percentDone - 6f
                if (bgMinX < x + boxW) {
                    ctx.drawRoundedRect(bgMinX, y, x + boxW, y + boxH - 10f, 5f, Color4b(0, 0, 0, 179))
                }
            } else {
                // 无进度条: 整条背景 (主题色 0.7)
                ctx.drawRoundedRect(x, y, x + boxW, y + boxH - 10f, 5f, themeColor)
            }

            // 文本: AddText(x+10, y+10) 白色
            ctx.text(font, n.message, (x + 10f).roundToInt(), (y + 10f).roundToInt(), Color4b.WHITE.argb, textShadow)

            // 原版: if (!n.isTimeUp) i++ —— 只统计未超时通知
            if (!n.isTimeUp) i++
        }
    }
}
