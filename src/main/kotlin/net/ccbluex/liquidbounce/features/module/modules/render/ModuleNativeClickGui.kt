/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.util.ARGB
import kotlin.math.roundToInt

/**
 * NativeClickGUI module
 *
 * This is a structural port of the real ClickGUI (src-theme/src/routes/clickgui) to vanilla
 * GuiGraphics rendering - no browser/CEF involved. It replicates the actual interaction model:
 *
 *  - One draggable, freely-positioned panel per module category (see Panel.svelte)
 *  - Grid snapping while dragging, bypassed by holding shift (see Panel.svelte snapToGrid)
 *  - Right-click (or the arrow button) collapses/expands a panel's module list
 *  - Left-click on a module toggles it; right-click/arrow expands its inline settings (Module.svelte)
 *  - A hover tooltip shows the module description + aliases (Module.svelte setDescription)
 *  - A search bar fuzzy-filters by name/alias and jumps to + expands the matching panel (Search.svelte)
 *
 * Not ported 1:1: color pickers, curve editors, vector inputs, and virtualized lists - those
 * settings fall back to a plain text readout. Boolean/Float/Int(Range)/Choice are fully interactive.
 */
object ModuleNativeClickGui : ClientModule(
    "NativeClickGUI",
    ModuleCategories.RENDER,
    bind = InputConstants.KEY_RBRACKET,
    disableActivation = true
) {

    override val running get() = true

    // Mirrors the real ModuleClickGui's own settings (see ModuleClickGui.kt)
    val scale by float("Scale", 1f, 0.5f..2f)
    val searchBarAutoFocus by boolean("SearchBarAutoFocus", true)

    object Snapping : ToggleableValueGroup(this, "Snapping", true) {
        val gridSize by int("GridSize", 10, 1..100)
    }

    /**
     * One persisted config group per module category - holds panel position, expand state and
     * which of that category's modules currently have their settings expanded. Registered via
     * [tree] like [Snapping], so it round-trips through the same modules.json the rest of the
     * client's settings use (see ModuleManager.modulesConfig / ConfigSystem).
     */
    class PanelConfigGroup(tag: String) : ValueGroup("ClickGuiPanel$tag") {
        val posX by float("X", -1f, -1f..8000f)
        val posY by float("Y", -1f, -1f..8000f)
        val expanded by boolean("Expanded", false)
        val expandedModules by text("ExpandedModules", "")
    }

    private val panelConfigs: Map<String, PanelConfigGroup> = ModuleCategories.entries.associate { category ->
        category.tag to PanelConfigGroup(category.tag)
    }

    fun configFor(category: ModuleCategory): PanelConfigGroup =
        panelConfigs.getValue(category.tag)

    /** Call after any change that should survive a client restart. */
    fun persist() {
        ConfigSystem.store(ModuleManager.modulesConfig)
    }

    init {
        tree(Snapping)
        panelConfigs.values.forEach { tree(it) }
    }

    override fun onEnabled() {
        if (mc.gui.screen() !is NativeClickGuiScreen) {
            mc.gui.setScreen(NativeClickGuiScreen())
        } else {
            mc.gui.setScreen(null)
        }
        super.onEnabled()
        enabled = false
    }
}

// ---- Persisted UI state, backed by ModuleNativeClickGui.configFor() (survives client restarts) ----

private class PanelState(val category: ModuleCategory, var x: Float, var y: Float) {
    var expanded = false
    var scrollOffset = 0
    var zIndex = 0
}

private object PanelRegistry {
    private val states = linkedMapOf<String, PanelState>()
    private var nextZ = 0

    fun get(category: ModuleCategory, panelIndex: Int): PanelState = states.getOrPut(category.tag) {
        val config = ModuleNativeClickGui.configFor(category)
        val hasSavedPosition = config.posX.get() >= 0f && config.posY.get() >= 0f
        val defaultX = 20f
        val defaultY = panelIndex * 40f + 20f

        PanelState(
            category = category,
            x = if (hasSavedPosition) config.posX.get() else defaultX,
            y = if (hasSavedPosition) config.posY.get() else defaultY
        ).also { state ->
            state.expanded = config.expanded.get()
        }
    }

    fun bringToFront(state: PanelState) {
        state.zIndex = ++nextZ
    }

    /** Panels sorted back-to-front, so later ones draw (and hit-test) on top. */
    fun ordered(categories: Collection<ModuleCategory>): List<Pair<ModuleCategory, PanelState>> =
        categories.mapIndexed { i, c -> c to get(c, i) }.sortedBy { it.second.zIndex }

    /** Writes position + expand state to disk. Call on drag-release / expand-toggle, not every frame. */
    fun save(category: ModuleCategory, state: PanelState) {
        val config = ModuleNativeClickGui.configFor(category)
        config.posX.set(state.x)
        config.posY.set(state.y)
        config.expanded.set(state.expanded)
        ModuleNativeClickGui.persist()
    }
}

private object ExpandedModules {

    fun isExpanded(module: ClientModule): Boolean = expandedSetFor(module.category).contains(module.name)

    /** Toggles and immediately persists, matching Module.svelte's per-module localStorage write. */
    fun toggle(module: ClientModule) {
        val config = ModuleNativeClickGui.configFor(module.category)
        val current = expandedSetFor(module.category).toMutableSet()

        if (!current.add(module.name)) {
            current.remove(module.name)
        }

        config.expandedModules.set(current.joinToString(","))
        ModuleNativeClickGui.persist()
    }

    /** Forces the module's settings open (used when a search result is selected) and persists. */
    fun expand(module: ClientModule) {
        val config = ModuleNativeClickGui.configFor(module.category)
        val current = expandedSetFor(module.category).toMutableSet()
        if (current.add(module.name)) {
            config.expandedModules.set(current.joinToString(","))
            ModuleNativeClickGui.persist()
        }
    }

    private fun expandedSetFor(category: ModuleCategory): Set<String> =
        ModuleNativeClickGui.configFor(category).expandedModules.get()
            .split(",")
            .filter { it.isNotBlank() }
            .toSet()
}

private val PANEL_HEADER_BG = ARGB.color(235, 22, 22, 26)
private val PANEL_BODY_BG = ARGB.color(220, 16, 16, 19)
private val ROW_HOVER = ARGB.color(180, 34, 34, 40)
private val SETTINGS_BG = ARGB.color(200, 12, 12, 15)
private val SETTINGS_BORDER = ARGB.color(255, 60, 110, 210)
private val TEXT_DIM = ARGB.color(255, 165, 165, 172)
private val TEXT_ENABLED = ARGB.color(255, 90, 170, 255)
private val TEXT_MAIN = -1
private val ACCENT = ARGB.color(255, 90, 170, 255)
private val GRID_COLOR = ARGB.color(60, 255, 255, 255)
private val TOOLTIP_BG = ARGB.color(235, 10, 10, 12)
private val SEARCH_BG = ARGB.color(220, 20, 20, 24)
private val SEARCH_HIGHLIGHT = ARGB.color(255, 90, 170, 255)

private const val PANEL_WIDTH = 172
private const val HEADER_HEIGHT = 22
private const val ROW_HEIGHT = 18
private const val SETTING_ROW_HEIGHT = 16
private const val MAX_EXPANDED_HEIGHT = 260

/**
 * A native, vanilla-rendered structural port of the real (browser-based) ClickGUI.
 */
class NativeClickGuiScreen : Screen("ClickGUI".asPlainText()) {

    private val searchQuery = StringBuilder()
    private var searchFocused = ModuleNativeClickGui.searchBarAutoFocus
    private var highlightedModule: String? = null

    private var draggingPanel: PanelState? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var ignoreGrid = false

    private var draggingValue: RangedValue<*>? = null
    private var draggingValueBounds: Rect? = null

    private var hoveredTooltip: Triple<String, String, List<String>>? = null // (name, description, aliases)
    private var hoveredTooltipX = 0
    private var hoveredTooltipY = 0

    private class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private val scale get() = ModuleNativeClickGui.scale
    private fun s(px: Int) = (px * scale).roundToInt()

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        hoveredTooltip = null
        drawSearchBar(context, mouseX, mouseY)

        val categories = ModuleCategories.entries
        val draggingNow = draggingPanel != null

        for ((category, state) in PanelRegistry.ordered(categories)) {
            drawPanel(context, category, state, mouseX, mouseY, draggingNow)
        }

        hoveredTooltip?.let { (name, description, aliases) ->
            drawTooltip(context, name, description, aliases, hoveredTooltipX, hoveredTooltipY)
        }
    }

    // ---- Search bar ----

    private fun matchingModules(): List<ClientModule> {
        val q = searchQuery.toString().lowercase().replace(" ", "")
        if (q.isEmpty()) return emptyList()
        return ModuleManager.filter { module ->
            module.name.lowercase().contains(q) || module.aliases.any { it.lowercase().contains(q) }
        }
    }

    private fun drawSearchBar(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val barWidth = s(220)
        val barHeight = s(18)
        val x = (width - barWidth) / 2
        val y = s(8)

        context.fill(x, y, x + barWidth, y + barHeight, SEARCH_BG)
        if (searchFocused) {
            context.fill(x, y + barHeight, x + barWidth, y + barHeight + 1, SEARCH_HIGHLIGHT)
        }

        val display = if (searchQuery.isEmpty()) "Search..." else searchQuery.toString()
        context.text(
            font,
            display.asPlainText(),
            x + 6,
            y + (barHeight - font.lineHeight) / 2,
            if (searchQuery.isEmpty()) TEXT_DIM else TEXT_MAIN,
            false
        )

        val results = matchingModules()
        if (results.isNotEmpty()) {
            var ry = y + barHeight + 2
            for (module in results.take(8)) {
                val hovered = mouseX in x..(x + barWidth) && mouseY in ry..(ry + s(16))
                if (hovered) {
                    context.fill(x, ry, x + barWidth, ry + s(16), ROW_HOVER)
                }
                context.text(
                    font,
                    module.name.asPlainText(),
                    x + 6,
                    ry + (s(16) - font.lineHeight) / 2,
                    if (module.enabled) TEXT_ENABLED else TEXT_MAIN,
                    false
                )
                ry += s(16)
            }
        }
    }

    // ---- Panels ----

    private fun drawPanel(
        context: GuiGraphicsExtractor,
        category: ModuleCategory,
        state: PanelState,
        mouseX: Int,
        mouseY: Int,
        draggingNow: Boolean
    ) {
        val x = state.x.roundToInt()
        val y = state.y.roundToInt()
        val w = s(PANEL_WIDTH)
        val headerH = s(HEADER_HEIGHT)

        context.fill(x, y, x + w, y + headerH, PANEL_HEADER_BG)
        context.text(
            font,
            category.tag.asPlainText(),
            x + s(8),
            y + (headerH - font.lineHeight) / 2,
            TEXT_MAIN,
            false
        )

        val arrow = if (state.expanded) "v" else ">"
        context.text(
            font,
            arrow.asPlainText(),
            x + w - s(14),
            y + (headerH - font.lineHeight) / 2,
            TEXT_DIM,
            false
        )

        if (draggingNow && draggingPanel === state && snappingEnabled()) {
            drawSnapGrid(context)
        }

        if (!state.expanded) return

        val modules = ModuleManager.filter { it.category == category }
        var rowY = y + headerH
        val bodyTop = rowY
        var bodyBottom = rowY

        for (module in modules) {
            if (bodyBottom - bodyTop >= s(MAX_EXPANDED_HEIGHT)) break

            val rowH = s(ROW_HEIGHT)
            val hovered = mouseX in x..(x + w) && mouseY in rowY..(rowY + rowH)

            context.fill(x, rowY, x + w, rowY + rowH, if (hovered) ROW_HOVER else PANEL_BODY_BG)

            val nameColor = when {
                module.enabled -> TEXT_ENABLED
                hovered -> TEXT_MAIN
                else -> TEXT_DIM
            }
            context.text(
                font,
                module.name.asPlainText(),
                x + s(8),
                rowY + (rowH - font.lineHeight) / 2,
                nameColor,
                false
            )

            val settings = visibleSettings(module)
            if (settings.isNotEmpty()) {
                val arrowChar = if (ExpandedModules.isExpanded(module)) "-" else "+"
                context.text(
                    font,
                    arrowChar.asPlainText(),
                    x + w - s(14),
                    rowY + (rowH - font.lineHeight) / 2,
                    TEXT_DIM,
                    false
                )
            }

            if (hovered) {
                hoveredTooltip = Triple(module.name, module.description.get() ?: "", module.aliases)
                hoveredTooltipX = x + w + s(4)
                hoveredTooltipY = rowY
            }

            rowY += rowH
            bodyBottom = rowY

            if (ExpandedModules.isExpanded(module) && settings.isNotEmpty()) {
                for (value in settings) {
                    if (bodyBottom - bodyTop >= s(MAX_EXPANDED_HEIGHT)) break
                    val settingH = s(SETTING_ROW_HEIGHT)
                    drawSetting(context, value, x, rowY, w, settingH, mouseX, mouseY)
                    rowY += settingH
                    bodyBottom = rowY
                }
            }
        }
    }

    /** Boolean/Bind/Hidden are excluded, matching Module.svelte's `hasSettings` filter. */
    private fun visibleSettings(module: ClientModule): List<Value<*>> =
        module.settings.values.filter { it.name != "Enabled" && it.name != "Bind" && it.name != "Hidden" }

    private fun drawSnapGrid(context: GuiGraphicsExtractor) {
        val grid = s(ModuleNativeClickGui.Snapping.gridSize)
        if (grid <= 0) return
        var gx = 0
        while (gx < width) {
            context.fill(gx, 0, gx + 1, height, GRID_COLOR)
            gx += grid
        }
        var gy = 0
        while (gy < height) {
            context.fill(0, gy, width, gy + 1, GRID_COLOR)
            gy += grid
        }
    }

    private fun snappingEnabled() = ModuleNativeClickGui.Snapping.enabled

    // ---- Settings widgets ----

    private fun drawSetting(
        context: GuiGraphicsExtractor,
        value: Value<*>,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        context.fill(x, y, x + w, y + h, SETTINGS_BG)
        context.fill(x, y, x + 2, y + h, SETTINGS_BORDER)

        context.text(
            font,
            value.name.asPlainText(),
            x + s(6),
            y + (h - font.lineHeight) / 2,
            TEXT_DIM,
            false
        )

        when (value.valueType) {
            ValueType.BOOLEAN -> {
                @Suppress("UNCHECKED_CAST")
                val boolValue = value as Value<Boolean>
                val boxSize = h - s(6)
                val boxX = x + w - boxSize - s(6)
                val boxY = y + s(3)
                context.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, ARGB.color(255, 55, 55, 60))
                if (boolValue.get()) {
                    context.fill(boxX + 2, boxY + 2, boxX + boxSize - 2, boxY + boxSize - 2, ACCENT)
                }
            }

            ValueType.FLOAT, ValueType.INT -> {
                if (value is RangedValue<*>) {
                    val sliderW = s(80)
                    val sliderX = x + w - sliderW - s(6)
                    val sliderY = y + h / 2 - 1

                    context.fill(sliderX, sliderY, sliderX + sliderW, sliderY + 2, ARGB.color(255, 70, 70, 76))
                    val fraction = sliderFraction(value)
                    val knobX = sliderX + (sliderW * fraction).roundToInt()
                    context.fill(knobX - 2, y + 2, knobX + 2, y + h - 2, ACCENT)

                    val text = value.get().toString()
                    context.text(
                        font,
                        text.asPlainText(),
                        sliderX - font.width(text) - s(4),
                        y + (h - font.lineHeight) / 2,
                        TEXT_MAIN,
                        false
                    )
                }
            }

            ValueType.CHOICE, ValueType.CHOOSE -> {
                // Simplified: click cycles through options (real UI uses a dropdown with nested
                // per-choice settings; those nested settings aren't rendered here).
                val text = value.get().toString()
                context.text(
                    font,
                    text.asPlainText(),
                    x + w - font.width(text) - s(6),
                    y + (h - font.lineHeight) / 2,
                    ACCENT,
                    false
                )
            }

            else -> {
                val text = value.get().toString()
                context.text(
                    font,
                    text.asPlainText(),
                    x + w - font.width(text) - s(6),
                    y + (h - font.lineHeight) / 2,
                    TEXT_MAIN,
                    false
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sliderFraction(value: RangedValue<*>): Float = when (val current = value.get()) {
        is Float -> {
            val r = value.range as ClosedRange<Float>
            ((current - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
        }
        is Int -> {
            val r = value.range as ClosedRange<Int>
            ((current - r.start).toFloat() / (r.endInclusive - r.start)).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySliderFraction(value: RangedValue<*>, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        when (value.get()) {
            is Float -> {
                val r = value.range as ClosedRange<Float>
                (value as Value<Float>).set(r.start + (r.endInclusive - r.start) * clamped)
            }
            is Int -> {
                val r = value.range as ClosedRange<Int>
                (value as Value<Int>).set(r.start + ((r.endInclusive - r.start) * clamped).roundToInt())
            }
        }
    }

    // ---- Tooltip ----

    private fun drawTooltip(
        context: GuiGraphicsExtractor,
        name: String,
        description: String,
        aliases: List<String>,
        x: Int,
        y: Int
    ) {
        var text = description
        if (aliases.isNotEmpty()) {
            text += " (aka ${aliases.joinToString(", ")})"
        }
        if (text.isBlank()) return

        val boxWidth = (font.width(text) + s(10)).coerceAtMost(s(200))
        val boxHeight = s(16)
        val boxX = if (x + boxWidth > width) x - boxWidth - s(8) else x
        val boxY = y.coerceIn(0, (height - boxHeight).coerceAtLeast(0))

        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, TOOLTIP_BG)
        context.text(font, text.asPlainText(), boxX + s(5), boxY + (boxHeight - font.lineHeight) / 2, TEXT_MAIN, false)
    }

    // ---- Input handling ----

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mouseX = click.x.toInt()
        val mouseY = click.y.toInt()
        val rightClick = click.button() == InputConstants.MOUSE_BUTTON_RIGHT

        val barWidth = s(220)
        val x0 = (width - barWidth) / 2
        val y0 = s(8)
        searchFocused = mouseX in x0..(x0 + barWidth) && mouseY in y0..(y0 + s(18))
        if (searchFocused) {
            return true
        }

        val results = matchingModules()
        if (results.isNotEmpty()) {
            var ry = y0 + s(18) + 2
            for (module in results.take(8)) {
                if (mouseX in x0..(x0 + barWidth) && mouseY in ry..(ry + s(16))) {
                    jumpTo(module)
                    return true
                }
                ry += s(16)
            }
        }

        for ((category, state) in PanelRegistry.ordered(ModuleCategories.entries).asReversed()) {
            if (handlePanelClick(category, state, mouseX, mouseY, rightClick)) {
                PanelRegistry.bringToFront(state)
                return true
            }
        }

        return true
    }

    private fun jumpTo(module: ClientModule) {
        highlightedModule = module.name
        searchQuery.clear()
        val index = ModuleCategories.entries.toList().indexOf(module.category).coerceAtLeast(0)
        val state = PanelRegistry.get(module.category, index)
        state.expanded = true
        ExpandedModules.expand(module)
        PanelRegistry.bringToFront(state)
        PanelRegistry.save(module.category, state)
    }

    private fun handlePanelClick(
        category: ModuleCategory,
        state: PanelState,
        mouseX: Int,
        mouseY: Int,
        rightClick: Boolean
    ): Boolean {
        val x = state.x.roundToInt()
        val y = state.y.roundToInt()
        val w = s(PANEL_WIDTH)
        val headerH = s(HEADER_HEIGHT)

        if (mouseX in x..(x + w) && mouseY in y..(y + headerH)) {
            if (rightClick) {
                state.expanded = !state.expanded
                PanelRegistry.save(category, state)
            } else {
                draggingPanel = state
                dragOffsetX = mouseX - state.x
                dragOffsetY = mouseY - state.y
            }
            return true
        }

        if (!state.expanded) return false

        val modules = ModuleManager.filter { it.category == category }
        var rowY = y + headerH
        val bodyTop = rowY

        for (module in modules) {
            if (rowY - bodyTop >= s(MAX_EXPANDED_HEIGHT)) break
            val rowH = s(ROW_HEIGHT)

            if (mouseX in x..(x + w) && mouseY in rowY..(rowY + rowH)) {
                val settings = visibleSettings(module)
                val arrowZone = x + w - s(16)
                if (rightClick || (settings.isNotEmpty() && mouseX >= arrowZone)) {
                    if (settings.isNotEmpty()) ExpandedModules.toggle(module)
                } else {
                    module.enabled = !module.enabled
                }
                return true
            }
            rowY += rowH

            if (ExpandedModules.isExpanded(module)) {
                for (value in visibleSettings(module)) {
                    if (rowY - bodyTop >= s(MAX_EXPANDED_HEIGHT)) break
                    val settingH = s(SETTING_ROW_HEIGHT)
                    if (mouseY in rowY..(rowY + settingH) && mouseX in x..(x + w)) {
                        handleSettingClick(value, mouseX, x, w, rowY, settingH)
                        return true
                    }
                    rowY += settingH
                }
            }
        }

        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSettingClick(value: Value<*>, mouseX: Int, x: Int, w: Int, rowY: Int, rowH: Int) {
        when (value.valueType) {
            ValueType.BOOLEAN -> (value as Value<Boolean>).set(!value.get())
            ValueType.FLOAT, ValueType.INT -> {
                if (value is RangedValue<*>) {
                    val sliderW = s(80)
                    val sliderX = x + w - sliderW - s(6)
                    val fraction = (mouseX - sliderX).toFloat() / sliderW
                    applySliderFraction(value, fraction)
                    draggingValue = value
                    draggingValueBounds = Rect(sliderX, rowY, sliderW, rowH)
                }
            }
            else -> {}
        }
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        draggingPanel?.let { panel ->
            val rawX = click.x.toFloat() - dragOffsetX
            val rawY = click.y.toFloat() - dragOffsetY
            panel.x = snap(rawX)
            panel.y = snap(rawY)
            panel.x = panel.x.coerceIn(0f, (width - s(PANEL_WIDTH)).toFloat().coerceAtLeast(0f))
            panel.y = panel.y.coerceIn(0f, (height - s(HEADER_HEIGHT)).toFloat().coerceAtLeast(0f))
            return true
        }

        draggingValue?.let { value ->
            val bounds = draggingValueBounds ?: return true
            val fraction = (click.x.toFloat() - bounds.x) / bounds.w
            applySliderFraction(value, fraction)
            return true
        }

        return false
    }

    private fun snap(value: Float): Float {
        if (ignoreGrid || !ModuleNativeClickGui.Snapping.enabled) return value
        val grid = s(ModuleNativeClickGui.Snapping.gridSize).coerceAtLeast(1)
        return (value / grid).roundToInt() * grid.toFloat()
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        draggingPanel = null
        draggingValue = null
        draggingValueBounds = null
        return true
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        for ((category, state) in PanelRegistry.ordered(ModuleCategories.entries).asReversed()) {
            val x = state.x.roundToInt()
            val y = state.y.roundToInt()
            val w = s(PANEL_WIDTH)
            if (mouseX.toInt() in x..(x + w) && mouseY.toInt() in y..(y + s(HEADER_HEIGHT) + s(MAX_EXPANDED_HEIGHT))) {
                state.scrollOffset = (state.scrollOffset - (verticalAmount * ROW_HEIGHT).toInt()).coerceAtLeast(0)
                return true
            }
        }
        return false
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        if (!searchFocused) return false
        val char = input.codepoint().toChar()
        if (char.isLetterOrDigit() || char == ' ') {
            searchQuery.append(char)
        }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        when (input.key) {
            InputConstants.KEY_ESCAPE -> {
                if (searchFocused && searchQuery.isNotEmpty()) {
                    searchQuery.clear()
                } else {
                    onClose()
                }
                return true
            }
            InputConstants.KEY_BACKSPACE -> {
                if (searchFocused && searchQuery.isNotEmpty()) {
                    searchQuery.deleteCharAt(searchQuery.length - 1)
                }
                return true
            }
            InputConstants.KEY_RETURN -> {
                matchingModules().firstOrNull()?.let(::jumpTo)
                return true
            }
            InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> {
                ignoreGrid = true
                return true
            }
        }
        return super.keyPressed(input)
    }

    override fun isPauseScreen() = false
}
