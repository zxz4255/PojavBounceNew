/*
 * ============================================================================
 *  ModuleSolsticeNotifications —— 移植 Solstice 的 Notifications.cpp/hpp (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  原版功能 (Notifications.cpp, Dear ImGui):
 *   1. 右下角堆叠通知 (Solaris 风格)
 *   2. 从右边缘滑入动画 (MathUtils::lerp, currentDuration 0→1)
 *   3. 类型色: Info=主题色(粉蓝白循环) / Warning=黄 / Error=红 (70% 透明度)
 *   4. 增长进度条: percentDone = timeShown/duration, 进度区主题色 + 剩余区黑色 0.7
 *   5. 多层阴影 (mShadowDensity 层 AddShadowRect) + 可选水平渐变
 *   6. 触发: mShowOnToggle (模块开关) / mShowOnJoin (进服)
 *
 *  移植说明:
 *   - AddRectFilled → drawRoundedRect / drawQuad
 *   - AddShadowRect → 多层半透明圆角近似 (drawGlowRect)
 *   - AddText        → context.text(mc.font, 9px)
 *   - 水平渐变 (AddRectFilledMultiColor) → 8 段颜色插值近似
 *   - getThemedColor → 粉蓝白三色循环插值 (与 Arraylist 移植一致)
 *   - 动画: 帧率无关平滑 (原版 deltaTime * 5)
 *
 *  可调节项 (20+): 右边缘距/底部距/条间距、限制条数/最大条数/默认时长、
 *        模块开关通知/进服通知/隐藏模块通知、最小宽度、阴影模糊/密度、
 *        进度条开关/颜色渐变/文字阴影、颜色模式 (Theme/Rainbow)、动画速度等。
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
import net.ccbluex.liquidbounce.render.drawQuad
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

    private class Notification(
        val message: String,
        val type: NotificationType,
        val duration: Float,          // 秒 (原版 float)
    ) {
        var timeShown = 0f
        var currentDuration = 0f      // 滑入/滑出动画 0..1
        var isTimeUp = false
    }

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val rightMargin by int("Right Margin", 10, 0..100)
    private val bottomOffset by int("Bottom Offset", 10, 0..200)
    private val spacing by int("Spacing", 6, 0..20)

    // —— 通知 ——
    private val limitNotifications by boolean("Limit Notifications", false)
    private val maxNotifications by int("Max Notifications", 6, 1..10)
    private val defaultDuration by int("Default Duration", 3000, 500..10000)   // 毫秒
    private val showOnToggle by boolean("Show On Toggle", true)
    private val showOnJoin by boolean("Show On Join", true)
    private val notifyHiddenModules by boolean("Notify Hidden Modules", false)

    // —— 外观 ——
    private val minWidth by int("Min Width", 200, 80..400)
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
    private val animationSpeed by float("Animation Speed", 5f, 0.5f..20f)    // 原版 deltaTime*5

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

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
        // 清理: 已超时且超出 3s 余留
        notifications.removeAll { it.isTimeUp && it.timeShown > it.duration + 3f }
        if (notifications.isEmpty()) return

        val font = mc.font
        val screenW = ctx.guiWidth().toFloat()
        val screenH = ctx.guiHeight().toFloat()

        val boxH = 9f + 30f   // 9px 字体 + 原版 30 内边距
        val smooth = (dt * animationSpeed).coerceAtMost(1f)
        var index = 0

        for (n in notifications) {
            if (limitNotifications && index >= maxNotifications) break

            // 更新动画 (原版: timeShown += deltaTime; currentDuration lerp)
            n.timeShown += dt
            n.isTimeUp = n.timeShown >= n.duration
            n.currentDuration += ((if (n.isTimeUp) 0f else 1f) - n.currentDuration) * smooth
            if (n.currentDuration < 0.01f) continue

            val percentDone = (n.timeShown / n.duration).coerceIn(0f, 1f)
            val textW = font.width(n.message).toFloat()
            val boxW = maxOf(minWidth.toFloat(), 50f + textW)

            // 位置: 右下角向上堆叠, 从右边缘滑入 (原版 CalcSize)
            val x = lerp(screenW + boxW, screenW - boxW - rightMargin, n.currentDuration)
            val y = screenH - bottomOffset - boxH - index * (boxH + spacing)
            if (x > screenW + boxW || y + boxH < 0f) {
                index++
                continue
            }

            // 类型色 (原版: Warning 黄 / Error 红 / Info 主题色, alpha 0.7)
            var baseColor = resolveColor(y * 2f)
            when (n.type) {
                NotificationType.WARNING -> baseColor = Color4b(255, 204, 0)
                NotificationType.ERROR -> baseColor = Color4b(255, 0, 0)
                NotificationType.INFO -> Unit
            }
            val mainColor = baseColor.alpha(179)

            // 进度宽度 (原版: boxW * percentDone + 6, clamp)
            val progressW = boxW * percentDone + 6f
            val progressMaxX = (x + progressW).coerceAtMost(x + boxW)

            // 多层阴影 (原版 drawShadowRectDense)
            if (shadowDensity > 0 && shadowBlur > 0f) {
                for (i in 0 until shadowDensity) {
                    val t = i / (shadowDensity - 1).coerceAtLeast(1).toFloat()
                    val r = shadowBlur * (0.25f + 0.75f * t)
                    val a = (0.4f * (1f - t * 0.5f) * 255).roundToInt().coerceIn(0, 255)
                    ctx.drawRoundedRect(x - r, y - r, x + boxW + r, y + boxH - 10f + r, 5f + r, Color4b(0, 0, 0, a))
                }
            }

            // 进度区 (主题色) — 原版 PushClipRect + AddRectFilled
            if (showProgressBar) {
                if (colorGradient) {
                    // 水平渐变近似: 8 段插值 (原版 AddRectFilledMultiColor)
                    val segments = 8
                    for (s in 0 until segments) {
                        val sx = x + (progressMaxX - x) * s / segments
                        val ex = x + (progressMaxX - x) * (s + 1) / segments
                        val c = mainColor.interpolateTo(
                            resolveColor(y * 2f + (sx - progressMaxX) * 1.2f),
                            (s / (segments - 1).toFloat()).toDouble(),
                        )
                        ctx.drawQuad(sx, y, ex, y + boxH - 10f, c)
                    }
                } else {
                    ctx.drawRoundedRect(x, y, progressMaxX, y + boxH - 10f, 5f, mainColor)
                }

                // 剩余区 (黑色 0.7)
                if (progressMaxX < x + boxW) {
                    ctx.drawRoundedRect(progressMaxX, y, x + boxW, y + boxH - 10f, 5f, Color4b(0, 0, 0, 179))
                }
            } else {
                // 无进度条: 整体背景
                ctx.drawRoundedRect(x, y, x + boxW, y + boxH - 10f, 5f, mainColor)
            }

            // 文本 (原版 AddText x+10, y+10)
            ctx.text(font, n.message, (x + 10f).roundToInt(), (y + 10f).roundToInt(), Color4b.WHITE.argb, textShadow)

            index++
        }
    }
}
