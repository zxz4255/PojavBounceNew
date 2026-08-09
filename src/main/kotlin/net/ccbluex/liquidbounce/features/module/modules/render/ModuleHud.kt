/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.BrowserReadyEvent
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.events.SpaceSeperatedNamesChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isDestructed
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isHidingNow
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud.themes
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.impl.CustomSharedMinecraftScreen
import net.ccbluex.liquidbounce.integration.screen.impl.CustomStandaloneMinecraftScreen
import net.ccbluex.liquidbounce.integration.screen.impl.CustomOverlay
import net.ccbluex.liquidbounce.integration.theme.ThemeManager
import net.ccbluex.liquidbounce.integration.theme.component.components.minimap.MinimapHudComponent
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.Screen

/**
 * Module HUD
 *
 * The client in-game dashboard.
 */

object ModuleHud : ClientModule("HUD", ModuleCategories.RENDER, state = true, hide = true) {

    override val running
        get() = this.enabled && !isDestructed
    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.hud"

    private val isVisible: Boolean
        get() = !isHidingNow && inGame

    var hudEditorSelected = false
        set(value) {
            if (value != field) {
                field = value
                updateOverlayVisibility(mc.gui.screen())
            }
        }

    private fun shouldShowOverlay(screen: Screen?): Boolean =
        screen !is DisconnectedScreen &&
            screen !is LevelLoadingScreen &&
            !(hudEditorSelected && isClickGuiScreen(screen))

    private fun isClickGuiScreen(screen: Screen?): Boolean =
        screen is CustomSharedMinecraftScreen && screen.screenType == CustomScreenType.CLICK_GUI ||
            screen is CustomStandaloneMinecraftScreen && screen.screenType == CustomScreenType.CLICK_GUI

    private fun updateOverlayVisibility(screen: Screen?) {
        if (!enabled || !isVisible) {
            overlay.close()
            return
        }

        overlay.visible = shouldShowOverlay(screen)
    }

    private var overlay = CustomOverlay(
        screenType = CustomScreenType.HUD,
        browserSettings = BrowserSettings(60, ::reopen)
    )

    init {
        tree(Blur)
        tree(ModuleList)
    }

    object Blur : ToggleableValueGroup(ModuleHud, "Blur", enabled = true) {
        val sigma by float("Sigma", 5.0F, 1.0F..15.0F)
        val alphaBlendRange by floatRange("AlphaBlendRange", 0.0F..0.75F, 0.0F..1.0F)
    }

    // ============= 功能列表配置 =============
    object ModuleList : ToggleableValueGroup(ModuleHud, "ModuleList", enabled = true) {
        val backgroundColor by int("BackgroundColor", 0xC915171B.toInt(), Int.MIN_VALUE..Int.MAX_VALUE)
        val backgroundAlpha by int("BackgroundAlpha", 60, 0..255)
        val textColor by int("TextColor", 0xFFE0E0E0.toInt(), Int.MIN_VALUE..Int.MAX_VALUE)
        val cornerRadius by int("CornerRadius", 4, 0..16)
        val padding by int("Padding", 4, 0..12)
        val lineSpacing by int("LineSpacing", 2, 0..8)
        val maxDisplay by int("MaxDisplay", 20, 0..50)
        val position by int("Position", 0, 0..3)
    }
    // ========================================

    @Suppress("unused")
    private val spaceSeperatedNames by boolean("SpaceSeperatedNames", true).onChange { state ->
        EventManager.callEvent(SpaceSeperatedNamesChangeEvent(state))
        state
    }

    val isBlurEffectActive
        get() = Blur.enabled && !(mc.gui.hud.isHidden && mc.gui.screen() == null)

    val themes = tree(ValueGroup("Themes"))

    val components = tree(ValueGroup("AdditionalComponents")).apply {
        tree(MinimapHudComponent)
    }

    fun updateThemes() {
        themes.inner.filterIsInstance<ValueGroup>().forEach {
            themes.drop(it)
        }
        for (theme in ThemeManager.themes) {
            themes.tree(theme.settings)
        }
        themes.walkInit()
        themes.walkKeyPath()
    }

    override fun onEnabled() {
        if (isHidingNow) {
            chat(markAsError(message("hidingAppearance")))
        }
        updateOverlayVisibility(mc.gui.screen())
    }

    override fun onDisabled() {
        overlay.close()
    }

    @Suppress("unused")
    private val browserReadyHandler = handler<BrowserReadyEvent> { event ->
        tree(overlay.browserSettings)
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        updateOverlayVisibility(event.screen)
    }

    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        overlay.close()
    }

    // ============= 功能列表渲染 (完全复制 ClickGuiScreen 的 API) =============
    @Suppress("unused")
    private val gameRenderHandler = handler<GameRenderEvent> { event ->
        renderModuleList(event.GuiGraphicsExtractor)  // 改为 guiGraphics
    }

    private fun getEnabledModules(): List<ClientModule> {
        return ModuleManager.getModules()
            .filter { it.enabled && it !is ModuleHud && it !is ModuleClickGui }
            .sortedByDescending { it.name.length }
    }

    private fun renderModuleList(ctx: GuiGraphicsExtractor) {
        if (!enabled || !isVisible || !ModuleList.enabled) return

        val client = mc
        val modules = getEnabledModules()
        if (modules.isEmpty()) return

        val maxDisplay = ModuleList.maxDisplay
        val displayModules = if (maxDisplay > 0) modules.take(maxDisplay) else modules
        if (displayModules.isEmpty()) return

        val font = client.font
        val scWidth = client.window.guiScaledWidth
        val scHeight = client.window.guiScaledHeight

        val padding = ModuleList.padding
        val lineSpacing = ModuleList.lineSpacing
        val cornerRadius = ModuleList.cornerRadius.toFloat()
        val textColor = ModuleList.textColor
        val bgColorRaw = ModuleList.backgroundColor
        val bgAlpha = ModuleList.backgroundAlpha.coerceIn(0, 255)
        val position = ModuleList.position

        val lines = displayModules.map { mod ->
            val text = mod.name
            val width = font.width(text)
            Pair(text, width)
        }

        if (lines.isEmpty()) return

        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (font.lineHeight + lineSpacing) - lineSpacing

        val boxWidth = maxWidth + padding * 2
        val boxHeight = totalHeight + padding * 2

        val xPos = when (position) {
            2, 3 -> padding
            else -> scWidth - boxWidth - padding
        }
        val yPos = when (position) {
            2, 0 -> padding + 10
            else -> scHeight - boxHeight - padding - 30
        }

        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        // 绘制圆角矩形背景（完全复制 ClickGuiScreen 的 drawRoundedRect）
        drawRoundedRect(ctx, xPos.toFloat(), yPos.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius, bgColor)

        // 绘制所有模块名称 (使用 ctx.text，与 ClickGuiScreen 完全一致)
        var currentY = yPos + padding
        for ((text, _) in lines) {
            ctx.text(font, text, (xPos + padding).toInt(), currentY.toInt(), textColor)
            currentY += font.lineHeight + lineSpacing
        }
    }

    /**
     * 完整复制 ClickGuiScreen 的 drawRoundedRect 实现
     */
    private fun drawRoundedRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        if (r <= 0.5f) {
            ctx.fill(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt(), color)
            return
        }
        val x1 = x; val y1 = y; val x2 = x + w; val y2 = y + h
        ctx.fill((x1 + r).toInt(), y1.toInt(), (x2 - r).toInt(), y2.toInt(), color)
        ctx.fill(x1.toInt(), (y1 + r).toInt(), (x1 + r).toInt(), (y2 - r).toInt(), color)
        ctx.fill((x2 - r).toInt(), (y1 + r).toInt(), x2.toInt(), (y2 - r).toInt(), color)
    }

    fun reopen() {
        overlay.close()
        updateOverlayVisibility(mc.gui.screen())
    }
}
