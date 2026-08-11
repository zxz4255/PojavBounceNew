/*
 * ============================================================================
 *  ModuleNotifications —— 仿 Opal v2 NotificationsElement 的通知 HUD (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  功能: 在屏幕右下角堆叠显示通知, 每条通知包含:
 *        - 类型图标 (Info / Success / Warning / Error) + 彩色图标底槽
 *        - 标题 (白色) + 描述 (灰色)
 *        - 底部类型色进度条 (随时间增长)
 *        - 从右边缘滑入 / 过期后滑出动画 (EASE_OUT_EXPO 近似)
 *
 *  LB 0.39 本身没有通知管理器 (只有 WebSocket 的 NotificationEvent),
 *  因此本模块内置轻量通知系统, 其他模块 / 脚本可调用:
 *      ModuleNotifications.notify("标题", "描述", NotificationType.SUCCESS, 3000)
 *
 *  可调节项 (20+): 位置 X/Y、缩放、通知高度、间距、最大同时显示数、
 *        背景、背景透明度、圆角、描边、图标开关、图标底槽开关、
 *        进度条开关与高度、标题颜色、描述颜色、文字阴影、动画速度、
 *        通知默认时长等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor
 *        (drawRoundedRect / drawQuad / fillGradient / mc.font),
 *        不依赖任何 Web / 浏览器组件。
 *
 *  安装:
 *    1. 本文件放入
 *       src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleNotifications.kt
 *    2. ModuleManager.kt 中:
 *       - import 区 (render 模块 import 附近) 添加:
 *           import net.ccbluex.liquidbounce.features.module.modules.render.ModuleNotifications
 *       - builtin 模块列表中添加一行:  ModuleNotifications,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleNotifications : ClientModule("Notifications【skid】", ModuleCategories.RENDER) {

    /* ============================= 通知类型 ============================= */
    // 图标使用 ASCII 字符 (mc.font 保证渲染), 颜色对应 Opal 的类型色

    enum class NotificationType(val icon: String, val color: Color4b) {
        INFO("i", Color4b(0, 170, 255)),
        SUCCESS("+", Color4b(0, 220, 120)),
        WARNING("!", Color4b(255, 200, 40)),
        ERROR("x", Color4b(255, 70, 70)),
    }

    class Notification(
        val title: String,
        val description: String,
        val type: NotificationType,
        val duration: Long,
    ) {
        val startTime: Long = System.currentTimeMillis()
        val elapsed: Long get() = System.currentTimeMillis() - startTime
        val hasExpired: Boolean get() = elapsed >= duration
        val progress: Float get() = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    }

    private class AnimState(var x: Float)

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val offsetX by int("Offset X", 4, 0..500)             // 距右边缘
    private val offsetY by int("Offset Y", 4, 0..500)             // 距底边缘
    private val scaleValue by float("Scale", 1f, 0.5f..2f)        // 整体缩放
    private val notificationHeight by int("Height", 26, 18..40)   // 单条高度
    private val spacing by int("Spacing", 3, 0..10)               // 条间距
    private val maxNotifications by int("Max Notifications", 5, 1..10)

    // —— 外观 ——
    private val background by boolean("Background", true)
    private val backgroundAlpha by int("Background Alpha", 128, 0..255)
    private val backgroundRadius by int("Background Radius", 4, 0..12)
    private val border by boolean("Border", false)
    private val showIcon by boolean("Show Icon", true)
    private val iconBackground by boolean("Icon Background", true)
    private val showProgressBar by boolean("Show Progress Bar", true)
    private val progressBarHeight by int("Progress Bar Height", 4, 1..8)
    private val titleColor by color("Title Color", Color4b.WHITE)
    private val descriptionColor by color("Description Color", Color4b(170, 170, 170))
    private val textShadow by boolean("Text Shadow", true)

    // —— 动画 / 时长 ——
    private val animationSpeed by float("Animation Speed", 8f, 0.5f..30f)
    private val defaultDuration by int("Default Duration", 3000, 500..10000)  // 毫秒

    /* ============================= 内部状态 ============================= */

    private val notifications = ArrayList<Notification>()   // index 0 为最新(显示在底部)
    private val animations = HashMap<Notification, AnimState>()
    private var lastFrameNs = 0L

    /* =========================== 通知发送 API =========================== */

    /**
     * 发送一条通知 (供其他模块 / 脚本调用)。
     * 动画在首次渲染时惰性创建, 初始位置为屏幕右边缘, 自然产生滑入效果。
     */
    fun notify(
        title: String,
        description: String = "",
        type: NotificationType = NotificationType.INFO,
        duration: Long = defaultDuration.toLong(),
    ) {
        // 超过最大数量时丢弃最旧的通知 (列表尾部)
        while (notifications.size >= maxNotifications) {
            val removed = notifications.removeAt(notifications.lastIndex)
            animations.remove(removed)
        }
        notifications.add(0, Notification(title, description, type, duration))
    }

    /** 清空全部通知 */
    fun clearNotifications() {
        notifications.clear()
        animations.clear()
    }

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        // 帧间隔(秒), 保证动画与帧率无关
        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now
        val smooth = (1f - exp(-animationSpeed * frameTime)).coerceIn(0f, 1f)

        val guiWidth = context.guiWidth().toFloat()
        val guiHeight = context.guiHeight().toFloat()

        // 清理已滑出屏幕且过期的通知
        val iterator = notifications.iterator()
        while (iterator.hasNext()) {
            val notification = iterator.next()
            val anim = animations[notification]
            if (notification.hasExpired && anim != null && anim.x >= guiWidth - 1f) {
                iterator.remove()
                animations.remove(notification)
            }
        }
        if (notifications.isEmpty()) {
            return@handler
        }

        val padding = 3f
        val height = notificationHeight.toFloat()
        val iconOffset = 14f + padding   // 图标槽尺寸

        notifications.forEachIndexed { index, notification ->
            val anim = animations.getOrPut(notification) { AnimState(guiWidth) }

            // 宽度由内容决定 (参照 Opal: 至少 100, 图标 + 标题/描述较宽者)
            val titleWidth = font.width(notification.title).toFloat()
            val descWidth = font.width(notification.description).toFloat()
            val width = maxOf(100f, iconOffset + maxOf(titleWidth + padding * 4f, descWidth))
            val endX = guiWidth - width - offsetX

            // 滑入 / 滑出动画 (EASE_OUT_EXPO 的指数平滑近似)
            val targetX = if (notification.hasExpired) guiWidth else endX
            anim.x += (targetX - anim.x) * smooth

            // 自底部向上堆叠: index 0 最新 → 最底部
            val y = guiHeight - offsetY - padding * 2f - (index + 1) * (height + spacing)

            // 完全移出屏幕则跳过
            if (anim.x > guiWidth + width || y + height < 0f || y > guiHeight) {
                return@forEachIndexed
            }

            context.pose().withPush {
                translate(anim.x, y)
                scale(scaleValue, scaleValue)

                // —— 背景 + 边框 ——
                if (background || border) {
                    val fill = if (background) Color4b(0, 0, 0, backgroundAlpha) else Color4b.TRANSPARENT
                    val outline = if (border) Color4b(255, 255, 255, 120) else Color4b.TRANSPARENT
                    if (backgroundRadius > 0) {
                        context.drawRoundedRect(
                            0f, 0f, width, height,
                            backgroundRadius.toFloat(), fill, outline, 1f
                        )
                    } else {
                        context.drawQuad(0f, 0f, width, height, fill, outline)
                    }
                }

                // —— 底部进度条 (类型色, 随时间增长) ——
                if (showProgressBar) {
                    val barW = (width - 0.5f) * notification.progress
                    if (barW > 0.5f) {
                        context.drawRoundedRect(
                            0.5f, height - progressBarHeight.toFloat(),
                            0.5f + barW, height,
                            (progressBarHeight / 2f).coerceAtMost(3f),
                            notification.type.color.alpha(90)
                        )
                    }
                }

                // —— 类型图标 + 底槽 ——
                if (showIcon) {
                    if (iconBackground) {
                        context.drawRoundedRect(
                            padding - 0.5f, padding / 2f + 0.5f,
                            padding - 0.5f + iconOffset, padding / 2f + 0.5f + iconOffset,
                            3f,
                            notification.type.color.darker().alpha(128)
                        )
                    }
                    val icon = notification.type.icon
                    val iconX = padding + (iconOffset - font.width(icon)) / 2f
                    // 字符在图标槽内垂直居中 (9px 字体的 baseline ≈ 中心 + 3.5)
                    val iconY = padding / 2f + 0.5f + iconOffset / 2f - 3.5f
                    context.text(
                        font, icon,
                        iconX.roundToInt(), iconY.roundToInt(),
                        notification.type.color.argb, textShadow
                    )
                }

                // —— 标题 (白色) ——
                context.text(
                    font, notification.title,
                    (padding * 2f + iconOffset).roundToInt(), (padding * 3f).roundToInt(),
                    titleColor.argb, textShadow
                )

                // —— 描述 (灰色, 第二行) ——
                if (notification.description.isNotEmpty()) {
                    context.text(
                        font, notification.description,
                        (padding * 2f + iconOffset).roundToInt(), (padding * 3f + 8.5f).roundToInt(),
                        descriptionColor.argb, textShadow
                    )
                }
            }
        }
    }
}
