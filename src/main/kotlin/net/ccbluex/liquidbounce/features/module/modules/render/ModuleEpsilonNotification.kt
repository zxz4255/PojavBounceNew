/*
 * ModuleEpsilonNotification —— 还原 Epsilon Notifications.java / Notification.java / NotificationMode.java
 * LiquidBounce Nextgen · 原生 Overlay · 无 Web
 *
 * 动画阶段 (与 Java 一致):
 *  ENTER_BAR 0–300ms  色条展开
 *  ENTER_CONTENT 300–500ms  内容淡入 + 色条收窄
 *  SHOW 显示
 *  EXIT_CONTENT 0–200ms after display
 *  EXIT_BAR 200–500ms  色条收起
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ServerConnectEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ModuleEpsilonNotification : ClientModule(
    "EpsilonNotification",
    ModuleCategories.RENDER,
    aliases = listOf("Notification", "EpsilonNotif"),
) {
    init { enabled = true }

    private enum class AnchorH(override val tag: String) : Tagged {
        LEFT("Left"), CENTER("Center"), RIGHT("Right"),
    }
    private enum class AnchorV(override val tag: String) : Tagged {
        TOP("Top"), BOTTOM("Bottom"),
    }

    private val scale by float("Scale", 1.0f, 0.5f..2.0f)
    private val fontScale by float("Font Scale", 1.0f, 0.5f..2.0f)
    private val subtitleYOffset by float("Subtitle Y Offset", 0.4f, -10f..20f)
    private val boxWidth by int("Width", 120, 80..300)
    private val boxHeight by int("Height", 30, 24..80)
    private val backgroundAlpha by int("Background Alpha", 145, 0..255)
    private val displayTime by int("Display Time", 2000, 500..5000)
    private val entryGap by float("Entry Gap", 3f, 0f..12f)
    private val accentBarWidth by float("Accent Bar Width", 2.4f, 1f..8f)
    private val textPadding by float("Text Padding", 6f, 2f..16f)
    private val posX by float("Pos X", 8f, 0f..800f)
    private val posY by float("Pos Y", 8f, 0f..600f)
    private val anchorH by enumChoice("Horizontal Anchor", AnchorH.RIGHT)
    private val anchorV by enumChoice("Vertical Anchor", AnchorV.TOP)
    private val preview by boolean("Preview", false)

    private val successColor by color("Success", Color4b(33, 207, 178, 255))
    private val infoColor by color("Info", Color4b(255, 255, 255, 255))
    private val errorColor by color("Error", Color4b(236, 67, 48, 255))

    enum class Mode { SUCCESS, INFO, ERROR }

    private data class Notif(
        val id: Int,
        var title: String,
        var subTitle: String,
        var mode: Mode,
        val replaceable: Boolean,
        var createTime: Long = System.currentTimeMillis(),
        var skipIntro: Boolean = false,
    ) {
        fun elapsed() = System.currentTimeMillis() - createTime
        fun exitTime(displayMs: Int) = elapsed() - displayMs
        fun expired(displayMs: Int) = elapsed() > displayMs + 500L
    }

    private enum class Stage { ENTER_BAR, ENTER_CONTENT, SHOW, EXIT_CONTENT, EXIT_BAR, HIDDEN }
    private data class Frame(val stage: Stage, val progress: Float, val occupied: Float)

    private val queue = CopyOnWriteArrayList<Notif>()
    private var nextId = 1

    /** 对外 API：推送通知 */
    fun post(title: String, subTitle: String = "", mode: Mode = Mode.INFO, replaceableId: Int? = null) {
        if (replaceableId != null) {
            val existing = queue.find { it.replaceable && it.id == replaceableId }
            if (existing != null) {
                existing.title = title
                existing.subTitle = subTitle
                existing.mode = mode
                existing.createTime = System.currentTimeMillis()
                existing.skipIntro = true
                return
            }
            queue += Notif(replaceableId, title, subTitle, mode, true)
            return
        }
        queue += Notif(nextId++, title, subTitle, mode, false)
    }

    fun postToggle(moduleName: String, enabled: Boolean) {
        post(moduleName, if (enabled) "Enabled" else "Disabled", if (enabled) Mode.SUCCESS else Mode.ERROR)
    }

    private fun modeColor(m: Mode, a: Int = 255): Color4b {
        val c = when (m) {
            Mode.SUCCESS -> successColor
            Mode.INFO -> infoColor
            Mode.ERROR -> errorColor
        }
        return Color4b(c.r, c.g, c.b, a.coerceIn(0, 255))
    }

    /** Easing.EASE_OUT_CUBIC */
    private fun easeOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x).pow(3)
    }

    private fun frameOf(n: Notif, occupied: Float): Frame {
        val elapsed = n.elapsed()
        if (!n.skipIntro) {
            if (elapsed <= 300L) {
                val p = easeOutCubic(elapsed / 300f)
                return Frame(Stage.ENTER_BAR, p, occupied * p)
            }
            if (elapsed <= 500L) {
                val p = easeOutCubic((elapsed - 300L) / 200f)
                return Frame(Stage.ENTER_CONTENT, p, occupied)
            }
        }
        val exit = n.exitTime(displayTime)
        if (exit < 0L) return Frame(Stage.SHOW, 1f, occupied)
        if (exit <= 200L) {
            val p = 1f - easeOutCubic(exit / 200f)
            return Frame(Stage.EXIT_CONTENT, p, occupied)
        }
        if (exit <= 500L) {
            val p = 1f - easeOutCubic((exit - 200L) / 300f)
            return Frame(Stage.EXIT_BAR, p, occupied * p)
        }
        return Frame(Stage.HIDDEN, 0f, 0f)
    }

    private fun isLeftDocked() = anchorH == AnchorH.LEFT


    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { ev ->
        if (!enabled) return@handler
        if (ev.hidden) return@handler
        if (ev.moduleName.equals(name, true) || ev.moduleName.contains("Notification", true)) return@handler
        postToggle(ev.moduleName, ev.enabled)
    }

    @Suppress("unused")
    private val notifEventHandler = handler<NotificationEvent> { ev ->
        if (!enabled) return@handler
        val mode = when (ev.severity) {
            NotificationEvent.Severity.ERROR -> Mode.ERROR
            NotificationEvent.Severity.SUCCESS, NotificationEvent.Severity.ENABLED -> Mode.SUCCESS
            NotificationEvent.Severity.DISABLED -> Mode.ERROR
            else -> Mode.INFO
        }
        val title = ev.title.ifBlank { "Notice" }
        val msg = ev.message
        post(title, msg, mode)
    }

    @Suppress("unused")
    private val connectHandler = handler<ServerConnectEvent> { ev ->
        if (!enabled) return@handler
        val addr = try { ev.address.toString() } catch (_: Throwable) {
            try { ev.serverInfo.name } catch (_: Throwable) { "server" }
        }
        post("Connecting", addr.take(40), Mode.INFO)
    }

    override suspend fun enabledEffect() {
        post("Epsilon Notification", "Enabled", Mode.SUCCESS)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled) return@handler
        queue.removeAll { it.expired(displayTime) }

        val ctx = event.context
        val font = mc.font
        val s = scale
        val textScaleBase = fontScale * s
        val bw = boxWidth * s
        val bh = boxHeight * s
        val spacing = bh + entryGap * s
        val bgA = backgroundAlpha

        data class Entry(val n: Notif, val frame: Frame)
        val entries = mutableListOf<Entry>()
        var totalH = 0f
        for (n in queue) {
            val f = frameOf(n, spacing)
            if (f.stage == Stage.HIDDEN) continue
            totalH += f.occupied
            entries += Entry(n, f)
        }
        if (entries.isEmpty() && preview) {
            val p = Notif(0, "Preview", "Notification", Mode.SUCCESS, false, skipIntro = true)
            totalH = spacing
            entries += Entry(p, Frame(Stage.SHOW, 1f, spacing))
        }
        if (entries.isEmpty()) return@handler

        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        val baseX = when (anchorH) {
            AnchorH.LEFT -> posX
            AnchorH.CENTER -> (sw - bw) / 2f
            AnchorH.RIGHT -> sw - bw - posX
        }
        var curY = when (anchorV) {
            AnchorV.TOP -> posY
            AnchorV.BOTTOM -> sh - posY - max(bh, totalH)
        }

        for (e in entries) {
            val n = e.n
            val f = e.frame
            when (f.stage) {
                Stage.ENTER_BAR, Stage.EXIT_BAR -> {
                    val width = if (isLeftDocked()) bw * f.progress else bw * f.progress
                    val rx = if (isLeftDocked()) baseX else baseX + bw - width
                    ctx.drawQuad(rx, curY, rx + width, curY + bh, modeColor(n.mode, 255))
                }
                Stage.ENTER_CONTENT, Stage.EXIT_CONTENT, Stage.SHOW -> {
                    val alpha = (255f * f.progress).roundToInt().coerceIn(0, 255)
                    // 背景
                    ctx.drawQuad(baseX, curY, baseX + bw, curY + bh, Color4b(0, 0, 0, (bgA * f.progress).roundToInt().coerceIn(0, 255)))
                    // 文字
                    val pad = textPadding * s
                    val barW = accentBarWidth * s
                    val title = n.title
                    val sub = n.subTitle
                    val ts = textScaleBase
                    val subTs = ts * 0.92f
                    val titleH = font.lineHeight * ts
                    val subH = if (sub.isNotEmpty()) font.lineHeight * subTs else 0f
                    val lineGap = 1.8f * ts + subtitleYOffset * s
                    val contentH = titleH + subH + if (sub.isNotEmpty()) lineGap else 0f
                    val textX = if (isLeftDocked()) baseX + pad else baseX + barW + pad
                    val titleY = curY + (bh - contentH) / 2f

                    ctx.pose().withPush {
                        translate(textX, titleY)
                        scale(ts, ts)
                        ctx.text(font, title, 0, 0, Color4b(255, 255, 255, alpha).argb, false)
                    }
                    if (sub.isNotEmpty()) {
                        ctx.pose().withPush {
                            translate(textX, titleY + titleH + lineGap)
                            scale(subTs, subTs)
                            ctx.text(font, sub, 0, 0, modeColor(n.mode, (alpha * 0.86f).roundToInt()).argb, false)
                        }
                    }
                    // 色条：progress 小时更宽，完全显示时收为细条
                    val accentW = barW + (bw - barW) * (1f - f.progress)
                    val accentX = if (isLeftDocked()) baseX + bw - accentW else baseX
                    ctx.drawQuad(accentX, curY, accentX + accentW, curY + bh, modeColor(n.mode, 255))
                }
                Stage.HIDDEN -> {}
            }
            curY += f.occupied
        }
    }
}
