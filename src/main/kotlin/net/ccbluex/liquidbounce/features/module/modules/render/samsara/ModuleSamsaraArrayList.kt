/*
 * Samsara ArrayList 移植到 LiquidBounce。
 *
 * 两种风格，视觉逐值还原 Samsara 的 Hud.java：
 *   Default —— 右对齐、彩虹左边条/右边条、淡背景、带阴影的彩虹文字
 *   Opai    —— 右对齐圆角胶囊、右侧 accent 强调线、后缀用 accent 色、外发光阴影
 *
 * 动画用 Samsara 的解析临界阻尼弹簧（ArraylistMotion），
 * 快速开关时位置连续；退出的行先滑出、再收拢间距（65ms 窗口）。
 */
package net.ccbluex.liquidbounce.features.module.modules.render.samsara

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object ModuleSamsaraArrayList : ClientModule(
    name = "SamsaraArrayList",
    category = ModuleCategories.RENDER,
    state = true,
) {

    // ---------------------------------------------------------------- 设置

    private enum class Style(override val tag: String) : Tagged {
        Default("Default"),
        Opai("Opai"),
    }

    private enum class BarMode(override val tag: String) : Tagged {
        Left("Left"),
        Right("Right"),
        None("None"),
    }

    private val style by enumChoice("Style", Style.Opai)
    private val barMode by enumChoice("BarMode", BarMode.Left)
    private val lowercase by boolean("Lowercase", false)
    private val showSuffix by boolean("ShowSuffix", true)
    private val background by boolean("Background", true)
    private val shadow by boolean("Shadow", true)
    private val rightLine by boolean("RightLine", true)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val xOffset by int("XOffset", 0, -2000..2000)
    private val yOffset by int("YOffset", 0, -2000..2000)

    private val showCombat by boolean("Combat", true)
    private val showMovement by boolean("Movement", true)
    private val showPlayer by boolean("Player", true)
    private val showRender by boolean("Render", true)
    private val showMisc by boolean("Misc", true)

    // ------------------------------------------------------------ 版式常量

    /** Opai 版式，对应 OpaiArraylistLayout。 */
    private const val OPAI_FONT_SIZE = 9.08f
    private const val OPAI_ROW_HEIGHT = 12f
    private const val OPAI_LEFT_PAD = 3f
    private const val OPAI_RIGHT_PAD = 2f
    private const val OPAI_EDGE_WIDTH = 1f
    private const val OPAI_EDGE_INSET = 0.5f
    private const val OPAI_TOP_INSET = 0.25f
    private const val OPAI_RADIUS = 5f
    private const val OPAI_BASELINE = 8.5f

    /** Default 版式，对应 ArraylistEntry。 */
    private const val DEFAULT_FONT_SIZE = 8f
    private const val DEFAULT_OFFSET = 12f

    private val OPAI_BACKGROUND = Color4b(0xCC101010.toInt())
    private val OPAI_TEXT = Color4b(0xFFFFFFFF.toInt())
    private val DEFAULT_BACKGROUND = Color4b(0x80090909.toInt())
    private val OPAI_SHADOW = Color4b(0x65000000)
    /** Samsara Opai lavender accent. */
    private val OPAI_ACCENT = Color4b(0xFFBBC3FF.toInt())

    private val font get() = FontManager.FONT_RENDERER

    private val opaiScale: Float get() = font.scaleToVanillaFont * (OPAI_FONT_SIZE / 9f)
    private val defaultScale: Float get() = font.scaleToVanillaFont * (DEFAULT_FONT_SIZE / 9f)

    private fun measureOpai(text: String): Float =
        font.getStringWidth(Component.literal(text), opaiScale)

    private fun measureDefault(text: String): Float =
        font.getStringWidth(Component.literal(text), defaultScale)

    // ---------------------------------------------------------------- 条目

    private class Entry(val module: ClientModule) : Comparable<Entry> {
        val motion = ArraylistMotion()

        var name = ""
        var suffix = ""

        var nameWidth = 0f
        var gapWidth = 0f
        var suffixWidth = 0f
        var width = 0f

        /** Default 风格只用文字宽度 + 阴影边距。 */
        var defaultWidth = 0f

        fun updateText(lower: Boolean, showSuffix: Boolean, opai: Boolean) {
            val raw = module.name
            name = if (lower) raw.lowercase(Locale.ROOT) else raw

            suffix = if (showSuffix) suffixOf(module) else ""
            if (lower) suffix = suffix.lowercase(Locale.ROOT)

            if (opai) {
                nameWidth = measureOpai(name)
                gapWidth = if (suffix.isEmpty()) 0f else measureOpai(" ")
                suffixWidth = if (suffix.isEmpty()) 0f else measureOpai(suffix)
                width = OPAI_LEFT_PAD + nameWidth + gapWidth + suffixWidth +
                    OPAI_RIGHT_PAD + OPAI_EDGE_WIDTH
            } else {
                defaultWidth = measureDefault(name)
                width = defaultWidth
            }
        }

        fun visibility(visible: Boolean, now: Long) = motion.visibility(visible, now)
        fun position(y: Float, now: Long) = motion.position(y, now)
        fun contribution() = motion.contribution()

        /** 未完全进入时向右偏移的量。 */
        fun slide(rowWidth: Float) = (rowWidth + 8f) * (1f - motion.progress())

        fun isModuleVisible() = module.enabled && !module.hidden

        /** 宽度大的排上面。 */
        override fun compareTo(other: Entry) = other.width.compareTo(width)
    }

    /**
     * 后缀取模块第一个可见的 ChoiceListValue（对应 Samsara 的 suffixSetting）。
     * HUD / ClickGUI 这类 UI 模块不显示后缀。
     */
    private fun suffixOf(module: ClientModule): String {
        if (module.name == "HUD" || module.name == "ClickGUI") return ""
        val choice = module.collectValuesRecursively()
            .filterIsInstance<net.ccbluex.liquidbounce.config.types.list.ChoiceListValue<*>>()
            .firstOrNull { it.visibleCondition.asBoolean } ?: return ""
        return (choice.get() as? Tagged)?.tag ?: ""
    }

    // ---------------------------------------------------------------- 状态

    private val entries = ArrayList<Entry>()
    private val visible = ArrayList<Entry>()
    private var initialized = false
    private var lastOpai = false

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        for (module in ModuleManager) {
            if (module === this) continue
            entries += Entry(module)
        }
    }

    private fun categoryAllowed(category: ModuleCategory): Boolean = when (category) {
        ModuleCategories.COMBAT -> showCombat
        ModuleCategories.MOVEMENT -> showMovement
        ModuleCategories.PLAYER -> showPlayer
        ModuleCategories.RENDER -> showRender
        ModuleCategories.MISC -> showMisc
        else -> false
    }

    // ---------------------------------------------------------------- 渲染

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (!running || mc.player == null) return@handler

        ensureInitialized()

        val opai = style == Style.Opai
        if (lastOpai != opai) {
            entries.forEach { it.motion.reset() }
            lastOpai = opai
        }

        entries.forEach { it.updateText(lowercase, showSuffix, opai) }
        entries.sort()

        val now = System.currentTimeMillis()
        val rowOffset = if (opai) OPAI_ROW_HEIGHT else DEFAULT_OFFSET

        visible.clear()
        var occupied = 0f
        for (entry in entries) {
            val show = entry.isModuleVisible() && categoryAllowed(entry.module.category)
            entry.visibility(show, now)
            entry.position(occupied, now)
            if (entry.motion.visible()) {
                visible += entry
                occupied += entry.contribution() * rowOffset
            }
        }

        if (visible.isEmpty()) return@handler

        val g = event.context
        val screenW = mc.window.guiScaledWidth.toFloat()

        if (opai) {
            drawOpai(g, screenW)
        } else {
            drawDefault(g, screenW)
        }
    }

    /** Opai 风格：右对齐圆角胶囊。 */
    private fun drawOpai(g: GuiGraphicsExtractor, screenW: Float) {
        g.pose().pushMatrix()
        try {
            g.pose().translate(screenW + xOffset, yOffset.toFloat())
            g.pose().scale(scale, scale)

            // 阴影先全部铺完，避免相邻行把上一行文字压暗
            if (shadow) {
                for (i in visible.indices) {
                    val e = visible[i]
                    val radius = radiusFor(i)
                    val right = -OPAI_EDGE_INSET + e.slide(e.width)
                    val x = right - e.width
                    val y = OPAI_TOP_INSET + e.motion.y()
                    g.drawRoundedRect(
                        x - 1.5f, y - 1f,
                        x + e.width + 1.5f, y + OPAI_ROW_HEIGHT + 1.5f,
                        radius + 1f, fillColor = OPAI_SHADOW,
                    )
                }
            }

            if (background) {
                for (i in visible.indices) {
                    val e = visible[i]
                    val radius = radiusFor(i)
                    val right = -OPAI_EDGE_INSET + e.slide(e.width)
                    val x = right - e.width
                    val y = OPAI_TOP_INSET + e.motion.y()
                    // 只有右下角是圆角：上部用矩形、底部用圆角矩形
                    g.drawQuad(x, y, x + e.width, y + OPAI_ROW_HEIGHT - radius, OPAI_BACKGROUND)
                    g.drawRoundedRect(
                        x, y + OPAI_ROW_HEIGHT - radius * 2f,
                        x + e.width, y + OPAI_ROW_HEIGHT,
                        radius, fillColor = OPAI_BACKGROUND,
                    )
                }
            }

            for (e in visible) {
                val right = -OPAI_EDGE_INSET + e.slide(e.width)
                val x = right - e.width
                val y = OPAI_TOP_INSET + e.motion.y()
                val baseline = y + OPAI_BASELINE - font.height * opaiScale / 2f

                font.draw(
                    g, Component.literal(e.name),
                    x + OPAI_LEFT_PAD, baseline,
                    OPAI_TEXT, shadow = false, scale = opaiScale,
                )
                if (e.suffix.isNotEmpty()) {
                    font.draw(
                        g, Component.literal(e.suffix),
                        x + OPAI_LEFT_PAD + e.nameWidth + e.gapWidth, baseline,
                        OPAI_ACCENT, shadow = false, scale = opaiScale,
                    )
                }
            }

            if (rightLine) {
                for (e in visible) {
                    val right = -OPAI_EDGE_INSET + e.slide(e.width)
                    val y = OPAI_TOP_INSET + e.motion.y()
                    g.drawQuad(
                        right - OPAI_EDGE_WIDTH, y, right, y + OPAI_ROW_HEIGHT,
                        OPAI_ACCENT,
                    )
                }
            }
        } finally {
            g.pose().popMatrix()
        }
    }

    /** 圆角随下一行宽度收缩，形成阶梯式外轮廓。 */
    private fun radiusFor(index: Int): Float {
        val e = visible[index]
        val nextWidth = if (index + 1 < visible.size) visible[index + 1].width else 0f
        return min(OPAI_RADIUS, max(0f, e.width - nextWidth))
    }

    /** Default 风格：彩虹条 + 淡背景 + 带阴影的彩虹文字。 */
    private fun drawDefault(g: GuiGraphicsExtractor, screenW: Float) {
        g.pose().pushMatrix()
        try {
            g.pose().translate(screenW + xOffset, yOffset.toFloat())
            g.pose().scale(scale, scale)

            for (index in visible.indices) {
                val e = visible[index]
                val color = colorAt(index)
                val x = -e.defaultWidth + e.slide(e.defaultWidth)
                val y = e.motion.y()

                if (background) {
                    g.drawQuad(
                        x - 6.5f, y, x + e.defaultWidth + 6.5f, y + DEFAULT_OFFSET,
                        DEFAULT_BACKGROUND,
                    )
                }

                if (barMode != BarMode.None) {
                    val bx = if (barMode == BarMode.Left) x - 4.5f else x + e.defaultWidth - 2.5f
                    g.drawRoundedRect(
                        bx, y + 2f, bx + 1f, y + 10f, 1f,
                        fillColor = color.fade(0.35f),
                    )
                    g.drawRoundedRect(
                        bx + 0.5f, y + 2.5f, bx + 1.5f, y + 9.5f, 1f,
                        fillColor = color,
                    )
                }

                val textOffset = when (barMode) {
                    BarMode.Left -> 2f
                    BarMode.None -> 3.5f
                    BarMode.Right -> 4.25f
                }
                font.draw(
                    g, Component.literal(e.name),
                    x - textOffset, y + 9f - font.height * defaultScale / 2f,
                    color, shadow = true, scale = defaultScale,
                )
            }
        } finally {
            g.pose().popMatrix()
        }
    }

    /** 对应 Samsara 的 ClientColors：按行号循环彩虹。 */
    private fun colorAt(index: Int): Color4b {
        val hue = ((index * 20) % 360) / 360f
        val rgb = java.awt.Color.HSBtoRGB(hue, 0.55f, 1f)
        return Color4b(rgb or 0xFF000000.toInt())
    }
}

/**
 * Samsara 的 ArraylistMotion：解析临界阻尼弹簧。
 *
 * 与普通线性插值不同，位置和速度都是解析解，
 * 因此在动画中途反向时不会出现速度突变。
 */
internal class ArraylistMotion {

    private class Spring(
        value: Double,
        private val rate: Double,
        private val immediateDeparture: Boolean = false,
    ) {
        private var current = value
        private var target = value
        private var velocity = 0.0
        private var last = -1L

        fun to(newTarget: Double, now: Long): Double {
            if (last >= 0 && now > last) {
                val dt = (now - last) / 1000.0
                val offset = current - target
                val linear = velocity + rate * offset
                val decay = Math.exp(-rate * dt)
                current = target + (offset + linear * dt) * decay
                velocity = (velocity - rate * linear * dt) * decay
            }
            if (newTarget != target && immediateDeparture &&
                Math.abs(current - target) < 0.002 && Math.abs(velocity) < 0.02
            ) {
                velocity = rate * (newTarget - current)
            }
            last = now
            target = newTarget
            return current
        }

        fun snap(v: Double, now: Long) {
            current = v
            target = v
            velocity = 0.0
            last = now
        }

        fun value(): Double = current
    }

    private var horizontal = Spring(0.0, 25.0)
    private var occupied = Spring(0.0, 12.7, immediateDeparture = true)
    private var enabled = false
    private var y = 0f
    private var changedAt = Long.MIN_VALUE / 2

    fun visibility(visible: Boolean, now: Long) {
        if (enabled != visible) {
            enabled = visible
            changedAt = now
        }
        horizontal.to(if (visible) 1.0 else 0.0, now)
        // 退出的行先滑出，间距晚 65ms 再收拢
        occupied.to(if (visible || now - changedAt < 65) 1.0 else 0.0, now)
    }

    fun position(newY: Float, now: Long) {
        y = newY
    }

    fun progress(): Float = horizontal.value().coerceIn(0.0, 1.0).toFloat()

    fun contribution(): Float = occupied.value().coerceIn(0.0, 1.0).toFloat()

    fun y(): Float = y

    fun visible(): Boolean = enabled || progress() > 0.001f || contribution() > 0.001f

    fun reset() {
        horizontal = Spring(0.0, 25.0)
        occupied = Spring(0.0, 12.7, immediateDeparture = true)
        enabled = false
        y = 0f
        changedAt = Long.MIN_VALUE / 2
    }
}