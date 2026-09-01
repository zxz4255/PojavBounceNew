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
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.util.ARGB
import kotlin.math.roundToInt

/**
 * NativeClickGUI module
 *
 * A ClickGUI rendered entirely with vanilla GuiGraphics/Screen APIs - no browser/theme involved.
 * Lists every registered module, grouped by category, with an inline settings panel supporting
 * boolean toggles and ranged (float/int) sliders.
 */
object ModuleNativeClickGui : ClientModule(
    "NativeClickGUI",
    ModuleCategories.RENDER,
    bind = InputConstants.KEY_RIGHT_BRACKET,
    disableActivation = true
) {

    override val running get() = true

    // Module-level appearance settings, adjustable like any other module
    val panelOpacity by float("PanelOpacity", 0.85f, 0.2f..1f)
    val rowHeight by int("RowHeight", 18, 14..28)
    val sidebarWidth by int("SidebarWidth", 90, 60..150)

    // ClientModule finalizes onToggled(), so - like the real ModuleClickGui - we hook onEnabled()
    // to open the screen, then immediately flip back to disabled since this "module" only
    // represents a momentary keybind action, not a persistent running state.
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

private val PANEL_BG = ARGB.color(220, 18, 18, 20)
private val SIDEBAR_BG = ARGB.color(235, 14, 14, 16)
private val ROW_BG = ARGB.color(160, 30, 30, 34)
private val ROW_BG_HOVER = ARGB.color(200, 42, 42, 48)
private val ROW_ENABLED = ARGB.color(220, 46, 120, 220)
private val ACCENT = ARGB.color(255, 90, 160, 255)
private val TEXT_MAIN = -1
private val TEXT_DIM = ARGB.color(255, 170, 170, 175)

/**
 * A native, vanilla-rendered replacement for the browser-based ClickGUI.
 */
class NativeClickGuiScreen : Screen("NativeClickGUI".asPlainText()) {

    private var selectedCategory: ModuleCategory = ModuleCategories.entries.first()
    private var expandedModule: ClientModule? = null

    /** value currently being dragged (for sliders), or null */
    private var draggingValue: RangedValue<*>? = null

    private var scrollOffset = 0

    private val panelX = 40
    private val panelY = 30
    private val panelWidth = 460
    private val panelHeight get() = (height - panelY * 2).coerceAtLeast(200)

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val sidebarWidth = ModuleNativeClickGui.sidebarWidth
        val rowHeight = ModuleNativeClickGui.rowHeight

        // --- Backgrounds ---
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG)
        context.fill(panelX, panelY, panelX + sidebarWidth, panelY + panelHeight, SIDEBAR_BG)

        // --- Category tabs ---
        var tabY = panelY + 6
        for (category in ModuleCategories.entries) {
            val hovered = mouseX in panelX..(panelX + sidebarWidth) && mouseY in tabY..(tabY + rowHeight)
            val selected = category == selectedCategory

            if (selected) {
                context.fill(panelX, tabY, panelX + sidebarWidth, tabY + rowHeight, ROW_ENABLED)
            } else if (hovered) {
                context.fill(panelX, tabY, panelX + sidebarWidth, tabY + rowHeight, ROW_BG_HOVER)
            }

            context.text(
                font,
                category.tag.asPlainText(),
                panelX + 8,
                tabY + (rowHeight - font.lineHeight) / 2,
                if (selected) TEXT_MAIN else TEXT_DIM,
                false
            )

            tabY += rowHeight
        }

        // --- Module list for selected category ---
        val listX = panelX + sidebarWidth + 6
        val listWidth = panelWidth - sidebarWidth - 12
        var rowY = panelY + 6 - scrollOffset

        val modulesInCategory = ModuleManager.filter { it.category == selectedCategory }

        for (module in modulesInCategory) {
            if (rowY + rowHeight >= panelY && rowY <= panelY + panelHeight) {
                drawModuleRow(context, module, listX, rowY, listWidth, rowHeight, mouseX, mouseY)
            }
            rowY += rowHeight

            if (module === expandedModule) {
                val settingsHeight = drawSettingsPanel(context, module, listX, rowY, listWidth, mouseX, mouseY)
                rowY += settingsHeight
            }
        }
    }

    private fun drawModuleRow(
        context: GuiGraphicsExtractor,
        module: ClientModule,
        x: Int,
        y: Int,
        width: Int,
        rowHeight: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + rowHeight)
        val bg = when {
            module.enabled -> ROW_ENABLED
            hovered -> ROW_BG_HOVER
            else -> ROW_BG
        }

        context.fill(x, y, x + width, y + rowHeight, bg)
        context.text(
            font,
            module.name.asPlainText(),
            x + 6,
            y + (rowHeight - font.lineHeight) / 2,
            TEXT_MAIN,
            false
        )

        // Expand indicator on the right
        val indicator = if (module === expandedModule) "-" else "+"
        context.text(
            font,
            indicator.asPlainText(),
            x + width - 12,
            y + (rowHeight - font.lineHeight) / 2,
            TEXT_DIM,
            false
        )
    }

    /**
     * Draws the inline settings panel for an expanded module and returns its total height in pixels.
     */
    private fun drawSettingsPanel(
        context: GuiGraphicsExtractor,
        module: ClientModule,
        x: Int,
        startY: Int,
        width: Int,
        mouseX: Int,
        mouseY: Int
    ): Int {
        val settings = module.settings.values.filter {
            it.name != "Enabled" && it.name != "Bind" && it.name != "Hidden"
        }

        var y = startY
        val rowH = 16

        for (value in settings) {
            context.fill(x, y, x + width, y + rowH, ARGB.color(120, 24, 24, 28))

            context.text(
                font,
                value.name.asPlainText(),
                x + 6,
                y + (rowH - font.lineHeight) / 2,
                TEXT_DIM,
                false
            )

            when (value.valueType) {
                ValueType.BOOLEAN -> drawBooleanWidget(context, value, x, y, width, rowH)
                ValueType.FLOAT, ValueType.INT -> {
                    if (value is RangedValue<*>) {
                        drawSliderWidget(context, value, x, y, width, rowH, mouseX, mouseY)
                    }
                }
                else -> {
                    // Other value types (text, color, enum, etc.) fall back to a plain text readout.
                    val text = value.get().toString()
                    context.text(
                        font,
                        text.asPlainText(),
                        x + width - font.width(text) - 6,
                        y + (rowH - font.lineHeight) / 2,
                        TEXT_MAIN,
                        false
                    )
                }
            }

            y += rowH
        }

        return y - startY
    }

    @Suppress("UNCHECKED_CAST")
    private fun drawBooleanWidget(
        context: GuiGraphicsExtractor,
        value: Value<*>,
        x: Int,
        y: Int,
        width: Int,
        rowH: Int
    ) {
        val boolValue = value as Value<Boolean>
        val boxSize = rowH - 6
        val boxX = x + width - boxSize - 6
        val boxY = y + 3

        context.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, ARGB.color(255, 60, 60, 66))
        if (boolValue.get()) {
            context.fill(boxX + 2, boxY + 2, boxX + boxSize - 2, boxY + boxSize - 2, ACCENT)
        }
    }

    private fun drawSliderWidget(
        context: GuiGraphicsExtractor,
        value: RangedValue<*>,
        x: Int,
        y: Int,
        width: Int,
        rowH: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val sliderWidth = 120
        val sliderX = x + width - sliderWidth - 6
        val sliderY = y + rowH / 2 - 1

        context.fill(sliderX, sliderY, sliderX + sliderWidth, sliderY + 2, ARGB.color(255, 70, 70, 76))

        val fraction = sliderFraction(value)
        val knobX = sliderX + (sliderWidth * fraction).roundToInt()

        context.fill(knobX - 3, y + 3, knobX + 3, y + rowH - 3, ACCENT)

        val valueText = value.get().toString()
        context.text(
            font,
            valueText.asPlainText(),
            sliderX - font.width(valueText) - 6,
            y + (rowH - font.lineHeight) / 2,
            TEXT_MAIN,
            false
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun sliderFraction(value: RangedValue<*>): Float {
        val range = value.range
        return when (val current = value.get()) {
            is Float -> {
                val r = range as ClosedRange<Float>
                ((current - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
            }
            is Int -> {
                val r = range as ClosedRange<Int>
                ((current - r.start).toFloat() / (r.endInclusive - r.start)).coerceIn(0f, 1f)
            }
            else -> 0f
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySliderFraction(value: RangedValue<*>, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val range = value.range

        when (value.get()) {
            is Float -> {
                val r = range as ClosedRange<Float>
                (value as Value<Float>).set(r.start + (r.endInclusive - r.start) * clamped)
            }
            is Int -> {
                val r = range as ClosedRange<Int>
                val newValue = (r.start + ((r.endInclusive - r.start) * clamped).roundToInt())
                (value as Value<Int>).set(newValue)
            }
        }
    }

    // ---- Input handling ----

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val sidebarWidth = ModuleNativeClickGui.sidebarWidth
        val rowHeight = ModuleNativeClickGui.rowHeight

        val mouseX = click.x.toInt()
        val mouseY = click.y.toInt()

        // Category tab click
        var tabY = panelY + 6
        for (category in ModuleCategories.entries) {
            if (mouseX in panelX..(panelX + sidebarWidth) && mouseY in tabY..(tabY + rowHeight)) {
                selectedCategory = category
                expandedModule = null
                scrollOffset = 0
                return true
            }
            tabY += rowHeight
        }

        // Module row / settings click
        val listX = panelX + sidebarWidth + 6
        val listWidth = panelWidth - sidebarWidth - 12
        var rowY = panelY + 6 - scrollOffset

        val modulesInCategory = ModuleManager.filter { it.category == selectedCategory }

        for (module in modulesInCategory) {
            if (mouseX in listX..(listX + listWidth) && mouseY in rowY..(rowY + rowHeight)) {
                val expandArea = listX + listWidth - 20
                if (mouseX >= expandArea) {
                    expandedModule = if (expandedModule === module) null else module
                } else {
                    module.enabled = !module.enabled
                }
                return true
            }
            rowY += rowHeight

            if (module === expandedModule) {
                val settings = module.settings.values.filter {
                    it.name != "Enabled" && it.name != "Bind" && it.name != "Hidden"
                }
                for (value in settings) {
                    if (mouseY in rowY..(rowY + 16)) {
                        handleSettingClick(value, mouseX, listX, listWidth, rowY)
                        return true
                    }
                    rowY += 16
                }
            }
        }

        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSettingClick(value: Value<*>, mouseX: Int, listX: Int, listWidth: Int, rowY: Int) {
        when (value.valueType) {
            ValueType.BOOLEAN -> (value as Value<Boolean>).set(!value.get())
            ValueType.FLOAT, ValueType.INT -> {
                if (value is RangedValue<*>) {
                    val sliderWidth = 120
                    val sliderX = listX + listWidth - sliderWidth - 6
                    val fraction = (mouseX - sliderX).toFloat() / sliderWidth
                    applySliderFraction(value, fraction)
                    draggingValue = value
                }
            }
            else -> {}
        }
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        val value = draggingValue ?: return false
        val sidebarWidth = ModuleNativeClickGui.sidebarWidth
        val listX = panelX + sidebarWidth + 6
        val listWidth = panelWidth - sidebarWidth - 12
        val sliderWidth = 120
        val sliderX = listX + listWidth - sliderWidth - 6

        val fraction = (click.x.toFloat() - sliderX) / sliderWidth
        applySliderFraction(value, fraction)
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        draggingValue = null
        return true
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        scrollOffset = (scrollOffset - (verticalAmount * ModuleNativeClickGui.rowHeight).toInt())
            .coerceAtLeast(0)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key == InputConstants.KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(input)
    }

    override fun onClose() {
        super.onClose()
    }

    override fun isPauseScreen() = false
}
