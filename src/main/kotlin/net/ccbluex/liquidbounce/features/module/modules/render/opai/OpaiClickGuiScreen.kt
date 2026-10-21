/*
 * OpaiClickGuiScreen —— Samsara OpaiClickGuiScreen 的 1:1 移植。
 *
 * 逐项保留：视口布局、列拖拽、滚轮/Shift 横向滚动、行展开裁剪动画、模式下拉浮层、
 * 滑块拖拽、开关、按压水波纹、右键绑定、滚动条拖拽、配置面板、开/关动画与缩放变换。
 *
 * 唯一替换：Samsara Feature/Setting → LiquidBounce ClientModule/Value。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/opai/OpaiClickGuiScreen.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.opai

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.BOOLEAN_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.FIELD_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.FIELD_Y
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.MODE_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.OPTION_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.SLIDER_Y
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Entry
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Kind
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Rect
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiInteraction.Action
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.COL_GAP
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.COL_W
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.FOOTER_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.HEADER_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.RADIUS
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.ROW_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.TEXT_PAD
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* ===================== 列位置持久化（Samsara ClickGuiLayouts 等价） ===================== */

object OpaiLayouts {

    private val file = ConfigSystem.rootFolder.resolve("opai-clickgui.json")
    private val data = com.google.gson.JsonObject()

    init {
        runCatching {
            if (file.isFile) {
                com.google.gson.JsonParser.parseString(file.readText()).asJsonObject.entrySet().forEach { (key, value) ->
                    val obj = value.asJsonObject
                    val point = com.google.gson.JsonObject()
                    point.addProperty("x", obj.get("x").asFloat)
                    point.addProperty("y", obj.get("y").asFloat)
                    data.add(key, point)
                }
            }
        }
    }

    fun x(id: String, fallback: Float): Float =
        if (data.has(id)) data.getAsJsonObject(id).get("x").asFloat else fallback

    fun y(id: String, fallback: Float): Float =
        if (data.has(id)) data.getAsJsonObject(id).get("y").asFloat else fallback

    fun put(id: String, x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        val point = com.google.gson.JsonObject()
        point.addProperty("x", x)
        point.addProperty("y", y)
        data.add(id, point)
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(data.toString())
        }
    }
}

/* ============================== 设置适配层 ============================== */

/** Samsara Setting → LiquidBounce Value。只映射 opai GUI 认识的四种控件。 */
sealed class OpaiSetting(val label: String, val raw: Value<*>) {

    val visible: Boolean get() = raw.visibleCondition.asBoolean

    class Toggle(val value: Value<Boolean>) : OpaiSetting(value.name, value) {
        var state: Boolean
            get() = value.get()
            set(v) = value.set(v)
    }

    class Number(val value: RangedValue<*>) : OpaiSetting(value.name, value) {

        val isInt: Boolean get() = value.get() is Int

        val minimum: Double get() = when (val range = value.range) {
            is IntRange -> range.first.toDouble()
            is ClosedFloatingPointRange<*> -> (range.start as kotlin.Number).toDouble()
            else -> 0.0
        }

        val maximum: Double get() = when (val range = value.range) {
            is IntRange -> range.last.toDouble()
            is ClosedFloatingPointRange<*> -> (range.endInclusive as kotlin.Number).toDouble()
            else -> 1.0
        }

        val step: Double get() = if (isInt) 1.0 else 0.01

        val current: Double get() = (value.get() as? kotlin.Number)?.toDouble() ?: 0.0

        fun set(raw: Double) {
            val clamped = raw.coerceIn(minimum, maximum)
            val stepped = Math.round(clamped / step) * step
            val scale = if (isInt) 0 else BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceIn(0, 6)
            val rounded = BigDecimal.valueOf(stepped).setScale(scale, RoundingMode.HALF_UP).toDouble()
            if (isInt) {
                @Suppress("UNCHECKED_CAST")
                (value as RangedValue<Int>).set(rounded.roundToInt())
            } else {
                @Suppress("UNCHECKED_CAST")
                (value as RangedValue<Float>).set(rounded.toFloat())
            }
        }
    }

    class Mode(val value: ChoiceListValue<*>) : OpaiSetting(value.name, value) {

        val options: List<String> get() = value.choices.map { it.tag }

        val selection: Int
            get() {
                val current = (value.get() as? Tagged)?.tag ?: return -1
                return value.choices.indexOfFirst { it.tag == current }
            }

        val selectionLabel: String get() = options.getOrElse(selection) { "" }

        fun select(index: Int) {
            val option = value.choices.toList().getOrNull(index) ?: return
            runCatching { value.setByString(option.tag) }
        }
    }

    class Multi(val value: MultiChoiceListValue<*>) : OpaiSetting(value.name, value) {

        val options: List<String> get() = value.choices.map { it.tag }

        val selectionLabel: String get() = "${value.get().size} Selected"

        @Suppress("UNCHECKED_CAST")
        private fun typed(): MultiChoiceListValue<Tagged> = value as MultiChoiceListValue<Tagged>

        fun selected(index: Int): Boolean {
            val option = value.choices.toList().getOrNull(index) ?: return false
            return option in typed()
        }

        fun toggle(index: Int) {
            val option = value.choices.toList().getOrNull(index) ?: return
            typed().toggle(option)
        }
    }

    companion object {
        fun of(value: Value<*>): OpaiSetting? = when (value) {
            is MultiChoiceListValue<*> -> Multi(value)
            is ChoiceListValue<*> -> Mode(value)
            is RangedValue<*> -> if (value.get() is Int || value.get() is Float) Number(value) else null
            else -> if (value.get() is Boolean) Toggle(value as Value<Boolean>) else null
        }
    }
}

/* ================================== 主界面 ================================== */

class OpaiClickGuiScreen(
    private val paletteSource: () -> OpaiStyle.Palette,
) : Screen(Component.literal("ClickGUI")) {

    private companion object {
        val CATEGORY_NAMES = arrayOf("Combat", "Movement", "Player", "Visual", "Misc")

        /** 与 Samsara 五列一致：Combat/Movement/Player/Visual(Render)/Misc(其余全部)。 */
        fun columnOf(module: ClientModule): Int = when (module.category) {
            ModuleCategories.COMBAT -> 0
            ModuleCategories.MOVEMENT -> 1
            ModuleCategories.PLAYER -> 2
            ModuleCategories.RENDER -> 3
            else -> 4
        }

        /** 26.3 的鼠标 id 是 SDL 语义（1=左 2=中 3=右），内部统一成 GLFW 语义。 */
        fun normalizeMouseButton(button: Int): Int = when (button) {
            1 -> 0
            3 -> 1
            4 -> 3
            5 -> 4
            else -> button
        }

        fun formatNumber(setting: OpaiSetting.Number): String {
            val precision = BigDecimal.valueOf(setting.step).stripTrailingZeros().scale().coerceIn(0, 6)
            return BigDecimal.valueOf(setting.current).setScale(precision, RoundingMode.HALF_UP).toPlainString()
        }

        fun applyAlpha(color: Int, alpha: Float): Int {
            val a = (alpha.coerceIn(0f, 1f) * ((color ushr 24) and 0xFF)).toInt()
            return (a shl 24) or (color and 0x00FFFFFF)
        }
    }

    private var palette: OpaiStyle.Palette = OpaiStyle.LAVENDER
    private val columns = ArrayList<OpaiColumn>()
    private val menuAnimation = OpaiMotion(0f, 30.0)
    private val configs = OpaiConfigPanel(OpaiConfigRepository())
    private var configName: EditBox? = null
    private var openedAt = 0.0
    private var menuScale = 1f
    private var menuOpacity = 0f
    private var closeStarted = 0.0
    private var closingAlpha = 0f
    private var closingScale = 1f
    private var transformCenterY = 0f
    private var configFront = false
    private var colW = COL_W
    private var maxBodyH = 0f
    private var horizontalScroll = 0f
    private var maxHorizontalScroll = 0f
    private var shiftDown = false
    private var draggedColumn: OpaiColumn? = null
    private var dragX = 0f
    private var dragY = 0f
    private var draggedScrollbar: OpaiColumn? = null
    private var scrollbarGrab = 0f
    private var draggingSlider: OpaiSetting.Number? = null
    private var sliderRow: OpaiRow? = null
    private var sliderColumn: OpaiColumn? = null
    private var bindingRow: OpaiRow? = null
    private val toggleAnims = HashMap<OpaiSetting.Toggle, OpaiMotion>()
    private val sliderAnims = HashMap<OpaiSetting.Number, OpaiMotion>()
    private val controlFeedback = HashMap<OpaiSetting, OpaiFeedback>()
    private var closing = false
    private var closeDestination: Screen? = null
    private var initialized = false
    private var detached = true
    private var layoutWidth = 0
    private var layoutHeight = 0
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var lastFrameTime = 0.0

    private var hudEditOpened = System.nanoTime()
    private var hudEditAlpha = 0f
    private var hudEditBox: Rect? = null

    /* ------------------------------ 生命周期 ------------------------------ */

    override fun init() {
        super.init()
        closeDestination = null
        configs.targets = OpaiTargetsPanel(CombatTargets.combatGroup, CombatTargets.visualGroup)

        if (initialized) {
            val layout = OpaiLayout.viewport(width, height, CATEGORY_NAMES.size)
            val previous = OpaiLayout.viewport(layoutWidth, layoutHeight, CATEGORY_NAMES.size)
            val previousScroll = horizontalScroll
            colW = layout.columnWidth
            maxBodyH = layout.maxBodyHeight
            transformCenterY = layout.top + (HEADER_H + layout.maxBodyHeight) / 2
            maxHorizontalScroll = OpaiLayout.horizontalOverflow(width, CATEGORY_NAMES.size, layout)
            horizontalScroll = horizontalScroll.coerceIn(0f, maxHorizontalScroll)
            if (width != layoutWidth || height != layoutHeight) {
                for (column in columns) {
                    column.x = (column.x + layout.left - previous.left + previousScroll - horizontalScroll)
                        .coerceIn(4 - maxHorizontalScroll, max(4f, width - colW - 4 + maxHorizontalScroll))
                    column.y = (column.y + layout.top - previous.top)
                        .coerceIn(4f, max(4f, height - HEADER_H - ROW_H - 8f))
                }
            }
            configs.fitViewport(width, height)
            configs.dock(width.toFloat(), layout.top - 2)
            configs.refresh()
            if (detached) {
                configs.collapse()
                configFront = true
                openedAt = OpaiMotion.now()
                menuAnimation.snap(0f, openedAt)
                menuAnimation.approach(1f, openedAt)
            }
            detached = false
            closing = false
            lastFrameTime = OpaiMotion.now()
            layoutWidth = width
            layoutHeight = height
            refreshLayouts()
            return
        }

        columns.clear()
        bindingRow = null
        clearDrag()
        val layout = OpaiLayout.viewport(width, height, CATEGORY_NAMES.size)
        colW = layout.columnWidth
        maxBodyH = layout.maxBodyHeight
        horizontalScroll = 0f
        maxHorizontalScroll = OpaiLayout.horizontalOverflow(width, CATEGORY_NAMES.size, layout)
        shiftDown = false
        for (i in CATEGORY_NAMES.indices) {
            val column = OpaiColumn(CATEGORY_NAMES[i])
            for (module in ModuleManager) {
                if (columnOf(module) == i) column.rows.add(OpaiRow(module))
            }
            column.rows.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            column.x = OpaiLayouts.x("opai:${column.name}", layout.left + i * (colW + COL_GAP))
            column.y = OpaiLayouts.y("opai:${column.name}", layout.top)
                .coerceIn(4f, max(4f, height - HEADER_H - ROW_H - 8f))
            columns.add(column)
        }
        transformCenterY = layout.top + (HEADER_H + layout.maxBodyHeight) / 2
        configName = EditBox(mc.font, 0, 0, 120, 20, Component.literal("Configuration name")).apply {
            setMaxLength(69)
            setValue(configs.input())
        }
        initialized = true
        openedAt = OpaiMotion.now()
        menuAnimation.snap(0f, openedAt)
        configs.position(max(4f, width - colW - layout.left), 8f, colW)
        configs.refresh()
        configs.fitViewport(width, height)
        configs.dock(width.toFloat(), layout.top - 2)
        configFront = true
        lastFrameTime = OpaiMotion.now()
        closing = false
        detached = false
        layoutWidth = width
        layoutHeight = height
        refreshLayouts()
    }

    private fun settingKind(setting: OpaiSetting): Kind = when (setting) {
        is OpaiSetting.Toggle -> Kind.BOOLEAN
        is OpaiSetting.Number -> Kind.NUMBER
        is OpaiSetting.Mode, is OpaiSetting.Multi -> Kind.MODE
    }

    private fun refreshLayouts() {
        val now = OpaiMotion.now()
        for (column in columns) {
            val columnFactor = column.expand.approach(if (column.expanded) 1f else 0f, now)
            val content = OpaiContentLayout<Target>()
            for (row in column.rows) {
                if (row.module.hidden) continue
                content.add(Target(row, null), Kind.MODULE)
                val rowFactor = row.expand.approach(if (row.expanded) 1f else 0f, now)
                for (setting in row.settings) {
                    if (setting is OpaiSetting.Mode || setting is OpaiSetting.Multi) {
                        row.modes.getOrPut(setting) { OpaiMotion(0f, 40.0) }.approach(
                            if (row.expanded && column.expanded && setting.visible && row.openMode === setting) 1f else 0f,
                            now,
                        )
                    }
                }
                if (rowFactor <= 0f) continue
                val openMode = row.openMode
                if (openMode != null && !openMode.visible) row.openMode = null
                val section = OpaiContentLayout<Target>()
                for (setting in row.settings) {
                    if (!setting.visible) continue
                    section.add(Target(row, setting), settingKind(setting))
                }
                content.reveal(section, rowFactor)
            }
            column.content = content
            val available = max(ROW_H + FOOTER_H, height - column.y - HEADER_H - 8f)
            column.targetBodyH = content.bodyHeight(min(maxBodyH, available))
            column.bodyH = column.targetBodyH * columnFactor
            column.maxScroll = content.maxScroll(column.targetBodyH)
            if (columnFactor <= 0.01f && !column.expanded) {
                column.scroll = 0f
                column.scrollTarget = 0f
            }
            column.scrollTarget = column.scrollTarget.coerceIn(0f, column.maxScroll)
            column.dropdowns = layoutDropdowns(column)
        }
        configs.update(now)
        val slider = draggingSlider
        if (slider != null && (sliderRow?.expanded != true || sliderRow?.module?.hidden == true || !slider.visible)) {
            clearDrag()
        }
    }

    /* -------------------------------- 渲染 -------------------------------- */

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (mc.level == null) extractPanorama(graphics, delta)
        extractBlurredBackground(graphics)
        graphics.fill(0, 0, width, height, 0x00000000)
        mc.gui.hud.extractDeferredSubtitles()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val now = OpaiMotion.now()
        val dt = max(0.0, now - lastFrameTime).toFloat()
        lastFrameTime = now
        val smoothing = 1 - exp(-dt * 0.014).toFloat()
        val alpha = if (closing) {
            closingAlpha * exp(-30 * (now - closeStarted) / 1000).toFloat()
        } else {
            menuAnimation.approach(1f, now)
        }
        menuOpacity = alpha
        menuScale = if (closing) {
            closingScale + .6f * (1 - alpha / max(.001f, closingAlpha))
        } else {
            OpaiMotion.openingScale(now - openedAt)
        }
        if (alpha <= 0.01f) {
            if (closing) mc.gui.setScreen(closeDestination)
            return
        }
        for (column in columns) column.scroll += (column.scrollTarget - column.scroll) * smoothing
        setMouse(mouseX.toDouble(), mouseY.toDouble())
        refreshLayouts()
        configName?.setFocused(configs.focused())
        syncConfigEditor()
        palette = paletteSource()
        drawHudEditButton(graphics)
        val canvas = OpaiCanvas(graphics, alpha)
        canvas.scale(menuScale, width / 2f, transformCenterY) {
            if (!configFront) configs.paint(canvas, palette, this@OpaiClickGuiScreen.mouseX, this@OpaiClickGuiScreen.mouseY, now)
            drawColumns(canvas, now)
            if (configFront) configs.paint(canvas, palette, this@OpaiClickGuiScreen.mouseX, this@OpaiClickGuiScreen.mouseY, now)
        }
    }

    private fun drawColumns(canvas: OpaiSurface, now: Double) {
        for (column in columns) {
            val bodyY = column.y + HEADER_H
            if (column.bodyH <= 0.5f) {
                canvas.rounded(column.x, column.y, colW, HEADER_H, RADIUS, palette.header)
                canvas.text(
                    column.name, column.x + TEXT_PAD, column.y + HEADER_H / 2,
                    OpaiStyle.HEADER_TEXT_SIZE, colW - TEXT_PAD * 2, palette.text,
                )
                continue
            }
            canvas.panel(column.x, bodyY, colW, column.bodyH, false, palette.body)
            canvas.panel(column.x, column.y, colW, HEADER_H, true, palette.header)
            canvas.text(
                column.name, column.x + TEXT_PAD, column.y + HEADER_H / 2,
                OpaiStyle.HEADER_TEXT_SIZE, colW - TEXT_PAD * 2, palette.text,
            )
            if (column.bodyH > FOOTER_H + 0.5f) {
                canvas.clip(column.x + 1, bodyY, colW - 2, column.bodyH - FOOTER_H) {
                    for (entry in column.content.entries()) {
                        val y = bodyY + entry.top - column.scroll
                        if (entry.visible() && bodyY + entry.visibleBottom() - column.scroll > bodyY &&
                            bodyY + entry.visibleTop() - column.scroll < bodyY + column.bodyH - FOOTER_H
                        ) {
                            val clipH = entry.visibleBottom() - entry.visibleTop()
                            if (clipH > 0.5f && colW > 0.5f) {
                                canvas.clip(
                                    column.x, bodyY + entry.visibleTop() - column.scroll, colW, clipH,
                                ) { drawEntry(canvas, column, entry, y, now) }
                            }
                        }
                    }
                }
            }
            if (column.maxScroll > 0) {
                val bar = OpaiContentLayout.scrollbar(column.bodyH, column.content.height(), column.scroll)
                canvas.rounded(
                    column.x + colW - 3.5f, bodyY + bar.top, 2.5f, bar.height, 1.25f, palette.scrollbar,
                )
            }
            drawDropdowns(canvas, column, now)
        }
    }

    private fun layoutDropdowns(column: OpaiColumn): List<Dropdown> {
        val dropdowns = ArrayList<Dropdown>()
        for (entry in column.content.entries()) {
            if (entry.kind != Kind.MODE) continue
            val setting = entry.target.setting ?: continue
            val options = optionsOf(setting)
            if (options.isEmpty()) continue
            val row = entry.target.row
            val motion = row.modes[setting] ?: continue
            if (motion.value() <= 0f) continue
            val y = column.y + HEADER_H + entry.top - column.scroll
            val field = OpaiContentLayout.field(column.x, y, colW)
            if (entry.visibleBottom() < entry.top + FIELD_Y + FIELD_H - .5f ||
                field.y < column.y + HEADER_H ||
                field.y + FIELD_H > column.y + HEADER_H + column.bodyH - FOOTER_H
            ) continue
            val fullHeight = min(options.size * OPTION_H, max(0f, height - 4 - field.y - FIELD_H))
            if (fullHeight <= 0f) continue
            val scroll = (row.modeScroll[setting] ?: 0f).coerceIn(0f, options.size * OPTION_H - fullHeight)
            dropdowns.add(
                Dropdown(
                    row, setting, Rect(field.x, field.y + FIELD_H, field.width, fullHeight * motion.value()),
                    scroll, fullHeight, options,
                )
            )
        }
        return dropdowns
    }

    private fun optionsOf(setting: OpaiSetting): List<String> = when (setting) {
        is OpaiSetting.Mode -> setting.options
        is OpaiSetting.Multi -> setting.options
        else -> emptyList()
    }

    private fun dropdownAt(column: OpaiColumn): Dropdown? {
        val menus = column.dropdowns
        for (i in menus.indices.reversed()) {
            if (menus[i].bounds.contains(mouseX, mouseY)) return menus[i]
        }
        return null
    }

    private fun drawDropdowns(canvas: OpaiSurface, column: OpaiColumn, now: Double) {
        for (menu in column.dropdowns) {
            val bounds = menu.bounds
            canvas.rounded(bounds.x, bounds.y, bounds.width, bounds.height, 2f, choiceField(menu.setting))
            canvas.clip(bounds.x, bounds.y, bounds.width, bounds.height) {
                for (option in menu.options.indices) {
                    val y = bounds.y + option * OPTION_H - menu.scroll
                    if (y + OPTION_H <= bounds.y || y >= bounds.y + bounds.height) continue
                    val entry = Entry(Target(menu.row, menu.setting), Kind.OPTION, 0f, OPTION_H, option, 0f, OPTION_H)
                    drawEntry(canvas, column, entry, y, now)
                }
            }
        }
    }

    private fun drawEntry(canvas: OpaiSurface, column: OpaiColumn, entry: Entry<Target>, y: Float, now: Double) {
        val row = entry.target.row
        val setting = entry.target.setting
        val x = column.x
        val labelW = colW - TEXT_PAD * 2
        val overlay = dropdownAt(column)
        val hovered = !(configFront && configs.contains(mouseX, mouseY)) && columnAt() === column &&
            if (entry.kind == Kind.OPTION) {
                overlay != null && overlay.setting === setting &&
                    Rect(column.x + TEXT_PAD - 2, y, labelW + 4, OPTION_H).contains(mouseX, mouseY)
            } else {
                overlay == null &&
                    column.content.hit(mouseY - column.y - HEADER_H, column.scroll, column.bodyH) === entry
            }

        if (entry.kind == Kind.MODULE) {
            val enabled = row.enabled.approach(if (row.module.enabled) 1f else 0f, now)
            val hover = row.hover.approach(if (hovered && draggedColumn == null) 1f else 0f, now)
            if (hover > 0.01f && enabled < 0.99f) {
                canvas.rect(x, y, colW, ROW_H, applyAlpha(palette.hover, hover * (1 - enabled)))
            }
            if (enabled > 0.01f) {
                canvas.rect(
                    x, y, colW, ROW_H,
                    applyAlpha(OpaiSurface.mix(palette.enabled, palette.enabledHover, hover), enabled),
                )
            }
            row.feedback.paint(canvas, x, y, colW, ROW_H, 0f, palette.text, now)
            canvas.text(
                if (row === bindingRow) "Press a key" else row.name,
                x + TEXT_PAD, y + ROW_H / 2, OpaiStyle.ROW_TEXT_SIZE, labelW,
                OpaiSurface.mix(palette.text, palette.enabledText, enabled),
            )
            return
        }

        // 设置项共用列的半透明底面，二次填充会改变色值，故不额外画背景。
        when (entry.kind) {
            Kind.BOOLEAN -> {
                val toggle = setting as? OpaiSetting.Toggle ?: return
                canvas.text(
                    OpaiLayout.displayName(toggle.label), x + TEXT_PAD, y + BOOLEAN_H / 2,
                    8.5f, labelW - 30, palette.text,
                )
                drawToggle(canvas, x + colW - TEXT_PAD - 26, y + (BOOLEAN_H - 16) / 2, toggle, now)
            }

            Kind.NUMBER -> {
                val number = setting as? OpaiSetting.Number ?: return
                val value = formatNumber(number)
                val valueWidth = min(labelW * 0.45f, canvas.textWidth(value, 8.5f))
                canvas.text(
                    OpaiLayout.displayName(number.label), x + TEXT_PAD, y + 6.5f, 8.5f,
                    labelW - valueWidth - 4, palette.text,
                )
                canvas.text(value, x + colW - TEXT_PAD - valueWidth, y + 6.5f, 8.5f, valueWidth, palette.text)
                val trackW = colW - TEXT_PAD * 2
                val trackY = y + SLIDER_Y
                val range = number.maximum - number.minimum
                val fraction = if (range <= 0) 0f
                else ((number.current - number.minimum) / range).toFloat().coerceIn(0f, 1f)
                val slider = sliderAnims.getOrPut(number) { OpaiMotion(fraction, 35.0) }
                if (draggingSlider === number) slider.snap(fraction, OpaiMotion.now())
                val displayed = slider.approach(fraction, OpaiMotion.now())
                canvas.rounded(x + TEXT_PAD, trackY - 1.25f, trackW, 2.5f, 1.25f, palette.track)
                canvas.rounded(x + TEXT_PAD, trackY - 1.25f, trackW * displayed, 2.5f, 1.25f, palette.accent)
                val knobX = x + TEXT_PAD + trackW * displayed
                val pulse = feedback(number).press(OpaiMotion.now())
                if (pulse > 0) {
                    canvas.rounded(knobX - 7, trackY - 7, 14f, 14f, 7f, applyAlpha(palette.accent, .25f * pulse))
                }
                canvas.rounded(knobX - 4.5f, trackY - 3.5f, 9f, 9f, 4.5f, 0x33000000)
                canvas.rounded(knobX - 4, trackY - 4, 8f, 8f, 4f, palette.accent)
            }

            Kind.MODE -> {
                val mode = setting ?: return
                canvas.text(OpaiLayout.displayName(mode.label), x + TEXT_PAD, y + 8, 8.5f, labelW, palette.text)
                val field = OpaiContentLayout.field(x, y, colW)
                val open = row.openMode === mode || (row.modes[mode]?.value() ?: 0f) > 0f
                val fieldColor = choiceField(mode)
                canvas.rounded(field.x, field.y, field.width, field.height, 2f, fieldColor)
                if (open) canvas.rect(field.x, field.y + 3, field.width, MODE_H - FIELD_Y - 3, fieldColor)
                canvas.text(
                    OpaiLayout.displayName(selectionLabel(mode)), field.x + 6, field.y + FIELD_H / 2,
                    8.5f, field.width - 12, palette.text,
                )
                canvas.rect(
                    field.x + 1, field.y + FIELD_H - 1, field.width - 2, 1f,
                    if (open) palette.enabled else palette.fieldLine,
                )
                feedback(mode).paint(
                    canvas, field.x, field.y, field.width, field.height, 2f, palette.accent, OpaiMotion.now(),
                )
            }

            Kind.OPTION -> {
                if (setting == null) return
                val option = entry.option
                val options = optionsOf(setting)
                if (option !in options.indices) return
                val multiple = setting is OpaiSetting.Multi
                val selected = if (setting is OpaiSetting.Multi) setting.selected(option)
                else (setting as? OpaiSetting.Mode)?.selection == option
                val left = x + TEXT_PAD - 2
                val width = labelW + 4
                if (selected || hovered) {
                    val highlight = if (multiple) OpaiSurface.mix(choiceField(setting), palette.accent, .10f)
                    else palette.selected
                    canvas.rect(left + 1, y, width - 2, OPTION_H, if (selected) highlight else palette.hover)
                }
                if (selected) {
                    canvasCheck(
                        canvas, left + (if (multiple) 5.5f else 5f), y + (if (multiple) 6f else 7f),
                        if (multiple) 8f else 6f, palette.text,
                    )
                }
                val indent = selected || multiple
                canvas.text(
                    OpaiLayout.displayName(options[option]), left + (if (indent) 17f else 6f),
                    y + OPTION_H / 2, 8.5f, width - (if (indent) 23f else 12f), palette.text,
                )
                row.optionFeedback.getOrPut(Option(setting, option)) { OpaiFeedback() }
                    .paint(canvas, left, y, width, OPTION_H, 0f, palette.accent, OpaiMotion.now())
            }

            Kind.PADDING -> Unit
        }
    }

    private fun canvasCheck(canvas: OpaiSurface, x: Float, y: Float, size: Float, color: Int) {
        if (canvas is OpaiCanvas) canvas.check(x, y, size, color)
        else canvas.icon(OpaiIcons.Icon.CHECK, x, y, size, color)
    }

    private fun choiceField(setting: OpaiSetting): Int =
        if (setting is OpaiSetting.Multi) 0xFF2A2A2D.toInt() else palette.field

    private fun selectionLabel(setting: OpaiSetting): String = when (setting) {
        is OpaiSetting.Mode -> setting.selectionLabel
        is OpaiSetting.Multi -> setting.selectionLabel
        else -> ""
    }

    private fun drawToggle(canvas: OpaiSurface, x: Float, y: Float, toggle: OpaiSetting.Toggle, now: Double) {
        val anim = toggleAnims.getOrPut(toggle) { OpaiMotion(if (toggle.state) 1f else 0f, 30.0) }
        val t = anim.approach(if (toggle.state) 1f else 0f, now)
        canvas.rounded(x, y, 26f, 16f, 8f, OpaiSurface.mix(palette.toggleOutline, palette.accent, t))
        if (t < 1f) canvas.rounded(x + 1, y + 1, 24f, 14f, 7f, applyAlpha(palette.field, 1 - t))
        val ink = feedback(toggle)
        val stretch = 3 * ink.press(now)
        val knobX = x + 2 + (10 - stretch) * t
        canvas.rounded(
            knobX, y + 2, 12 + stretch, 12f, 6f,
            OpaiSurface.mix(palette.fieldLine, palette.enabledText, t),
        )
        ink.paint(canvas, x, y, 26f, 16f, 8f, palette.accent, now)
        if (t > 0.01f) canvasCheck(canvas, knobX + 3, y + 5, 6f, applyAlpha(palette.accent, t))
    }

    private fun feedback(setting: OpaiSetting): OpaiFeedback = controlFeedback.getOrPut(setting) { OpaiFeedback() }

    /* ------------------------------ 命中与输入 ------------------------------ */

    private fun columnAt(): OpaiColumn? {
        for (i in columns.indices.reversed()) {
            val column = columns[i]
            if (dropdownAt(column) != null ||
                Rect(column.x, column.y, colW, HEADER_H + column.bodyH).contains(mouseX, mouseY)
            ) return column
        }
        return null
    }

    private fun setMouse(x: Double, y: Double) {
        mouseX = width / 2.0 + (x - width / 2.0) / menuScale
        mouseY = transformCenterY + (y - transformCenterY) / menuScale
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (!closing && hudEditClicked(event.x(), event.y(), event.button())) return true
        if (closing) return true
        setMouse(event.x(), event.y())
        shiftDown = event.hasShiftDown()
        val column = columnAt()
        val button = normalizeMouseButton(event.button())
        if ((configFront || column == null) && configs.click(mouseX, mouseY, button)) {
            bindingRow = null
            draggedColumn = null
            draggedScrollbar = null
            draggingSlider = null
            sliderRow = null
            sliderColumn = null
            configFront = true
            configName?.setFocused(configs.focused())
            if (button == 0 && configs.focused()) {
                val cursor = configs.cursorAt(measureSurface, mouseX)
                configName?.moveCursorTo(cursor, event.hasShiftDown())
                syncConfigEditor()
            }
            return true
        }
        configName?.setFocused(false)
        configs.blur()
        if (column == null) {
            closeDropdowns()
            refreshLayouts()
            return super.mouseClicked(event, doubleClick)
        }
        columns.remove(column)
        columns.add(column)
        configFront = false
        val overlay = dropdownAt(column)
        if (overlay != null) {
            if (button == 0 && overlay.row.openMode === overlay.setting) {
                val option = ((mouseY - overlay.bounds.y + overlay.scroll) / OPTION_H).toInt()
                if (option in overlay.options.indices) {
                    when (val setting = overlay.setting) {
                        is OpaiSetting.Mode -> setting.select(option)
                        is OpaiSetting.Multi -> setting.toggle(option)
                        else -> Unit
                    }
                    overlay.row.optionFeedback.getOrPut(Option(overlay.setting, option)) { OpaiFeedback() }.click(
                        (mouseX - overlay.bounds.x).toFloat(),
                        (mouseY - overlay.bounds.y + overlay.scroll - option * OPTION_H).toFloat(),
                        overlay.bounds.width, OPTION_H, OpaiMotion.now(),
                    )
                    if (overlay.setting !is OpaiSetting.Multi) overlay.row.openMode = null
                    refreshLayouts()
                }
            } else if (button == 1) {
                closeDropdowns()
                refreshLayouts()
            }
            return true
        }
        if (mouseY < column.y + HEADER_H) {
            when (OpaiInteraction.header(button)) {
                Action.EXPAND -> {
                    clearDrag()
                    column.expanded = !column.expanded
                    for (row in column.rows) if (bindingRow === row) bindingRow = null
                    refreshLayouts()
                }
                Action.DRAG -> {
                    clearDrag()
                    draggedColumn = column
                    dragX = mouseX.toFloat() - column.x
                    dragY = mouseY.toFloat() - column.y
                }
                else -> Unit
            }
            return true
        }
        val localY = mouseY.toFloat() - column.y - HEADER_H
        if (!column.expanded) return true
        if (button == 0 && column.maxScroll > 0 &&
            Rect(column.x + colW - 6, column.y + HEADER_H, 6f, column.bodyH - FOOTER_H).contains(mouseX, mouseY)
        ) {
            clearDrag()
            draggedScrollbar = column
            val bar = OpaiContentLayout.scrollbar(column.bodyH, column.content.height(), column.scroll)
            scrollbarGrab = if (localY >= bar.top && localY < bar.top + bar.height) localY - bar.top
            else bar.height / 2
            updateScrollbar()
            return true
        }
        val entry = column.content.hit(localY.toDouble(), column.scroll, column.bodyH) ?: return true
        val row = entry.target.row
        val setting = entry.target.setting
        val action = OpaiInteraction.content(entry.kind, button)
        if (entry.kind == Kind.MODULE) {
            when (action) {
                Action.TOGGLE -> {
                    row.feedback.click(
                        (mouseX - column.x).toFloat(),
                        (mouseY - column.y - HEADER_H - entry.top + column.scroll).toFloat(),
                        colW, ROW_H, OpaiMotion.now(),
                    )
                    row.enabled.approach(if (row.module.enabled) 1f else 0f, OpaiMotion.now())
                    row.module.enabled = !row.module.enabled
                    row.enabled.approach(if (row.module.enabled) 1f else 0f, OpaiMotion.now())
                }
                Action.EXPAND -> {
                    clearDrag()
                    row.expanded = !row.expanded
                    if (bindingRow === row && !row.expanded) bindingRow = null
                    refreshLayouts()
                    if (row.expanded) {
                        val visible = column.targetBodyH - FOOTER_H
                        val reveal = min(column.content.height(), entry.top + ROW_H + MODE_H)
                        column.scrollTarget = max(column.scroll, min(entry.top, reveal - visible))
                            .coerceIn(0f, column.maxScroll)
                    }
                }
                Action.BIND -> bindingRow = row
                else -> Unit
            }
        } else if (action != Action.NONE && row.expanded && setting != null && setting.visible) {
            val y = column.y + HEADER_H + entry.top - column.scroll
            when (action) {
                Action.TOGGLE -> {
                    val toggle = setting as? OpaiSetting.Toggle
                    if (toggle != null) {
                        val anim = toggleAnims.getOrPut(toggle) { OpaiMotion(if (toggle.state) 1f else 0f, 30.0) }
                        anim.approach(if (toggle.state) 1f else 0f, OpaiMotion.now())
                        feedback(toggle).click(
                            (mouseX - column.x - colW + TEXT_PAD + 26).toFloat(),
                            (mouseY - y - (BOOLEAN_H - 16) / 2).toFloat(), 26f, 16f, OpaiMotion.now(),
                        )
                        toggle.state = !toggle.state
                        anim.approach(if (toggle.state) 1f else 0f, OpaiMotion.now())
                    }
                }
                Action.SLIDE -> {
                    val number = setting as? OpaiSetting.Number
                    if (number != null && OpaiContentLayout.sliderHit(column.x, y, colW).contains(mouseX, mouseY)) {
                        feedback(number).click(
                            (mouseX - column.x - TEXT_PAD).toFloat(), 4f,
                            colW - TEXT_PAD * 2, 8f, OpaiMotion.now(),
                        )
                        clearDrag()
                        draggingSlider = number
                        sliderRow = row
                        sliderColumn = column
                        column.scrollTarget = column.scroll
                        updateSlider()
                    }
                }
                Action.EXPAND -> {
                    val field = OpaiContentLayout.field(column.x, y, colW)
                    if (field.contains(mouseX, mouseY)) {
                        feedback(setting).click(
                            (mouseX - field.x).toFloat(), (mouseY - field.y).toFloat(),
                            field.width, field.height, OpaiMotion.now(),
                        )
                        val wasOpen = row.openMode === setting
                        closeDropdowns()
                        row.openMode = if (wasOpen) null else setting
                    }
                }
                else -> Unit
            }
        }
        refreshLayouts()
        return true
    }

    private fun updateSlider() {
        val slider = draggingSlider ?: return
        val column = sliderColumn ?: return
        val fraction = OpaiContentLayout.sliderFraction(mouseX, column.x, colW)
        slider.set(slider.minimum + fraction * (slider.maximum - slider.minimum))
    }

    private fun updateScrollbar() {
        val column = draggedScrollbar ?: return
        val thumb = mouseY.toFloat() - column.y - HEADER_H - scrollbarGrab
        column.scroll = OpaiContentLayout.scrollFromThumb(thumb, column.bodyH, column.content.height())
        column.scrollTarget = column.scroll
    }

    private fun clearDrag() {
        configs.release()
        draggedColumn = null
        draggedScrollbar = null
        draggingSlider = null
        sliderRow = null
        sliderColumn = null
    }

    private fun closeDropdowns(): Boolean {
        var closed = false
        for (column in columns) {
            for (row in column.rows) {
                closed = closed or (row.openMode != null)
                row.openMode = null
            }
        }
        return closed
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (normalizeMouseButton(event.button()) == 0) {
            val captured = configs.captured() || draggedColumn != null ||
                draggingSlider != null || draggedScrollbar != null
            clearDrag()
            if (captured) return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        if (closing) return true
        setMouse(event.x(), event.y())
        if (normalizeMouseButton(event.button()) == 0) {
            if (configs.captured()) {
                configs.drag(mouseX, mouseY, width, height)
                return true
            }
            if (configs.focused()) {
                val cursor = configs.cursorAt(measureSurface, mouseX)
                configName?.moveCursorTo(cursor, true)
                syncConfigEditor()
                return true
            }
            val column = draggedColumn
            if (column != null) {
                column.x = (mouseX.toFloat() - dragX).coerceIn(4f, width - colW - 4f)
                column.y = (mouseY.toFloat() - dragY).coerceIn(
                    4f, max(4f, height - HEADER_H - column.bodyH - 8f),
                )
                OpaiLayouts.put("opai:${column.name}", column.x, column.y)
                refreshLayouts()
                return true
            }
            if (draggedScrollbar != null) {
                updateScrollbar()
                return true
            }
            val slider = draggingSlider
            if (slider != null) {
                if (sliderRow?.expanded == true && slider.visible) {
                    updateSlider()
                    refreshLayouts()
                } else {
                    clearDrag()
                }
                return true
            }
        }
        return super.mouseDragged(event, deltaX, deltaY)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        setMouse(mouseX, mouseY)
        if (closing || draggingSlider != null || draggedScrollbar != null || draggedColumn != null) return true
        val column = columnAt()
        if (configs.contains(this.mouseX, this.mouseY) && (configFront || column == null)) {
            return configs.wheel(verticalAmount)
        }
        val overlay = column?.let { dropdownAt(it) }
        if (overlay != null && column != null) {
            overlay.row.modeScroll[overlay.setting] =
                (overlay.scroll - verticalAmount.toFloat() * OPTION_H * 2)
                    .coerceIn(0f, overlay.options.size * OPTION_H - overlay.fullHeight)
            column.dropdowns = layoutDropdowns(column)
            return true
        }
        if (horizontalAmount != 0.0 || shiftDown || column == null) {
            val amount = (if (horizontalAmount != 0.0) horizontalAmount else verticalAmount).toFloat()
            val next = (horizontalScroll - amount * ROW_H * 2).coerceIn(0f, maxHorizontalScroll)
            val delta = next - horizontalScroll
            for (item in columns) item.x -= delta
            horizontalScroll = next
            return true
        }
        column.scrollTarget = (column.scrollTarget - verticalAmount.toFloat() * ROW_H * 2)
            .coerceIn(0f, column.maxScroll)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (closing) return true
        val key = event.key()
        shiftDown = event.hasShiftDown() || key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT
        val binding = bindingRow
        if (binding != null) {
            binding.module.bindValue.set(
                InputBind(
                    InputConstants.Type.KEYBOARD.getOrCreate(
                        if (event.isEscape() || key == InputConstants.KEY_BACKSPACE) 0 else key,
                    ),
                    InputBind.BindAction.TOGGLE,
                    emptySet(),
                )
            )
            bindingRow = null
            return true
        }
        if (event.isEscape()) {
            if (configs.escape()) {
                configName?.setFocused(false)
            } else if (closeDropdowns()) {
                refreshLayouts()
            } else {
                onClose()
            }
            return true
        }
        if (configs.focused()) {
            configName?.setFocused(true)
            if (event.isConfirmation()) {
                syncConfigEditor()
                configs.create(false)
                return true
            }
            val handled = configName?.keyPressed(event) ?: false
            syncConfigEditor()
            return handled
        }
        return super.keyPressed(event)
    }

    private fun syncConfigEditor() {
        val box = configName ?: return
        val cursor = box.cursorPosition
        val highlighted = box.highlighted
        var selection = cursor
        if (highlighted.isNotEmpty()) {
            val value = box.value
            selection = if (value.startsWith(highlighted, cursor)) cursor + highlighted.length
            else cursor - highlighted.length
        }
        configs.editor(box.value, cursor, selection)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (closing) return true
        if (configs.focused()) {
            configName?.setFocused(true)
            val handled = configName?.charTyped(event) ?: false
            syncConfigEditor()
            return handled
        }
        return super.charTyped(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        shiftDown = event.key() != InputConstants.KEY_LSHIFT && event.key() != InputConstants.KEY_RSHIFT &&
            event.hasShiftDown()
        return super.keyReleased(event)
    }

    override fun onClose() {
        if (closing) return
        clearDrag()
        bindingRow = null
        closing = true
        closeStarted = OpaiMotion.now()
        closingAlpha = menuOpacity
        closingScale = menuScale
    }

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        super.removed()
        for (column in columns) OpaiLayouts.put("opai:${column.name}", column.x, column.y)
        OpaiLayouts.save()
        detached = true
        bindingRow = null
        configs.blur()
        clearDrag()
    }

    /* ---------------------- HUD 编辑按钮（Samsara HudEditButton 等价） ---------------------- */

    private fun drawHudEditButton(graphics: GuiGraphicsExtractor) {
        if (mc.player == null) return
        val size = 26.7f
        val inset = 8f
        val seconds = max(0.0, (System.nanoTime() - hudEditOpened) / 1_000_000_000.0)
        val rise = exp(-seconds * 30).toFloat()
        val y = height - inset - size + (size + inset) * (if (closing) 1f - menuOpacity else rise)
        val box = Rect(width - inset - size, y, size, size)
        hudEditBox = box
        hudEditAlpha = menuOpacity
        val canvas = OpaiCanvas(graphics, menuOpacity)
        canvas.rounded(box.x, box.y, size, size, 5f, palette.accent)
        val ink = 0xFF101014.toInt()
        // 画笔图标：笔身 + 笔尖 + 尾部 + 底部横线（对应原 nvgPath）。
        canvas.rounded(box.x + 6.2f, box.y + 20.2f, 1.6f, 1.6f, .8f, ink)
        canvas.rounded(box.x + 6.5f, box.y + 16.4f, 9.2f, 3.8f, 1.9f, ink)
        canvas.rounded(box.x + 15.7f, box.y + 7.2f, 3.8f, 3.8f, 1.9f, ink)
        canvas.rounded(box.x + 16.6f, box.y + 5.5f, 4.6f, 4.6f, 2.3f, ink)
        canvas.rounded(box.x + 6f, box.y + 22f, 16f, 1.6f, .5f, ink)
    }

    private fun hudEditClicked(x: Double, y: Double, button: Int): Boolean {
        // 内部统一 GLFW 语义：1 = 右键。
        if (button != 1) return false
        if (hudEditAlpha <= .15f) return false
        val box = hudEditBox ?: return false
        if (!box.contains(x, y)) return false
        ModuleHud.hudEditorSelected = !ModuleHud.hudEditorSelected
        return true
    }

    /* -------------------------------- 数据类型 -------------------------------- */

    private data class Target(val row: OpaiRow, val setting: OpaiSetting?)

    private data class Option(val setting: OpaiSetting, val index: Int)

    private data class Dropdown(
        val row: OpaiRow,
        val setting: OpaiSetting,
        val bounds: Rect,
        val scroll: Float,
        val fullHeight: Float,
        val options: List<String>,
    )

    private class OpaiColumn(val name: String) {
        val rows = ArrayList<OpaiRow>()
        val expand = OpaiMotion(1f, 40.0)
        var content = OpaiContentLayout<Target>()
        var dropdowns: List<Dropdown> = emptyList()
        var x = 0f
        var y = 0f
        var scroll = 0f
        var scrollTarget = 0f
        var maxScroll = 0f
        var bodyH = 0f
        var targetBodyH = 0f
        var expanded = true
    }

    private class OpaiRow(val module: ClientModule) {
        val name: String = OpaiLayout.displayName(module.name)
        val settings: List<OpaiSetting> = module.inner.mapNotNull { OpaiSetting.of(it) }
        val hover = OpaiMotion(0f, 30.0)
        val expand = OpaiMotion(0f, 40.0)
        val enabled = OpaiMotion(if (module.enabled) 1f else 0f, 30.0)
        val feedback = OpaiFeedback()
        val modes = HashMap<OpaiSetting, OpaiMotion>()
        val modeScroll = HashMap<OpaiSetting, Float>()
        val optionFeedback = HashMap<Option, OpaiFeedback>()
        var expanded = false
        var openMode: OpaiSetting? = null
    }

    /** configs.cursorAt 只需要文本度量；给出只测量、不绘制的实现。 */
    private val measureSurface: OpaiSurface = object : OpaiSurface {
        private val renderer get() = net.ccbluex.liquidbounce.render.FontManager.FONT_RENDERER

        override fun textWidth(text: String, size: Float): Float =
            renderer.getStringWidth(text.asPlainText(), size / renderer.height, false) + OpaiStyle.TEXT_WEIGHT_OFFSET

        override fun rect(x: Float, y: Float, width: Float, height: Float, color: Int) = Unit
        override fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) = Unit
        override fun panel(x: Float, y: Float, width: Float, height: Float, header: Boolean, color: Int) = Unit
        override fun text(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int) = Unit
        override fun clip(x: Float, y: Float, width: Float, height: Float, content: () -> Unit) = Unit
        override fun opacity(factor: Float, content: () -> Unit) = Unit
        override fun icon(icon: OpaiIcons.Icon, x: Float, y: Float, size: Float, color: Int) = Unit
        override fun scale(factor: Float, centerX: Float, centerY: Float, content: () -> Unit) = Unit
        override fun ripple(
            x: Float, y: Float, width: Float, height: Float, corner: Float,
            centerX: Float, centerY: Float, radius: Float, color: Int,
        ) = Unit
    }
}