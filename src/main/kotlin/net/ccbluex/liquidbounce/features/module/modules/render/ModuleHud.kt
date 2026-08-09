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
import net.minecraft.client.gui.DrawContext
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
        tree(ModuleList) // 添加功能列表配置
    }

    object Blur : ToggleableValueGroup(ModuleHud, "Blur", enabled = true) {
        /**
         * Gaussian sigma controlling blur strength. Higher values produce stronger blur.
         */
        val sigma by float("Sigma", 5.0F, 1.0F..15.0F)

        /**
         * The range in which the blending from not-blurred to blurred occurs.
         */
        val alphaBlendRange by floatRange("AlphaBlendRange", 0.0F..0.75F, 0.0F..1.0F)
    }

    // ============= 功能列表 (Module List) 配置 =============
    object ModuleList : ToggleableValueGroup(ModuleHud, "ModuleList", enabled = true) {
        /** 背景颜色 (ARGB) */
        val backgroundColor by int("BackgroundColor", 0xC915171B)

        /** 背景透明度 (覆盖 alpha 通道，方便快捷调整) 0-255 */
        val backgroundAlpha by int("BackgroundAlpha", 60, 0..255)

        /** 文字颜色 (ARGB) */
        val textColor by int("TextColor", 0xFFE0E0E0)

        /** 圆角半径 */
        val cornerRadius by int("CornerRadius", 4, 0..16)

        /** 内边距 */
        val padding by int("Padding", 4, 0..12)

        /** 行间距 */
        val lineSpacing by int("LineSpacing", 2, 0..8)

        /** 显示位置 (右上/右下/左上/左下) */
        val position by choice("Position", arrayOf("右上", "右下", "左上", "左下"), "右上")
    }
    // ========================================================

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

    /**
     * Updates [themes] content
     */
    fun updateThemes() {
        // filterIsInstance then forEach to prevent ConcurrentModificationException
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

    // ============= 功能列表渲染 (独立于浏览器) =============
    @Suppress("unused")
    private val gameRenderHandler = handler<GameRenderEvent> { event ->
        renderModuleList(event.context)
    }

    /**
     * 获取当前启用的模块列表（按文本长度从长到短排序）
     */
    private fun getEnabledModules(): List<ClientModule> {
        return ModuleManager.getModules()
            .filter { it.enabled && it !is ModuleHud && it !is ModuleClickGui }
            .sortedByDescending { it.name.length }
    }

    /**
     * 在 HUD 右上角绘制功能列表
     */
    private fun renderModuleList(ctx: DrawContext) {
        if (!enabled || !isVisible || !ModuleList.enabled) return

        val client = mc
        val modules = getEnabledModules()
        if (modules.isEmpty()) return

        val font = client.font
        val scWidth = client.window.guiScaledWidth
        val scHeight = client.window.guiScaledHeight

        // 从配置读取参数
        val padding = ModuleList.padding
        val lineSpacing = ModuleList.lineSpacing
        val cornerRadius = ModuleList.cornerRadius.toFloat()
        val textColor = ModuleList.textColor
        val bgColorRaw = ModuleList.backgroundColor
        val bgAlpha = ModuleList.backgroundAlpha.coerceIn(0, 255)
        val position = ModuleList.position

        // 计算每个模块的文本尺寸
        val lines = modules.map { mod ->
            val text = mod.name
            val width = font.width(text)
            val height = font.lineHeight
            Pair(text, width)
        }

        if (lines.isEmpty()) return

        // 计算最大宽度和总高度
        val maxWidth = lines.maxOfOrNull { it.second } ?: 0
        val totalHeight = lines.size * (font.lineHeight + lineSpacing) - lineSpacing

        // 添加内边距
        val boxWidth = maxWidth + padding * 2
        val boxHeight = totalHeight + padding * 2

        // 计算位置 (右上角默认)
        val xPos = when (position) {
            "左上", "左下" -> padding
            else -> scWidth - boxWidth - padding
        }
        val yPos = when (position) {
            "左上", "右上" -> padding + 10  // 留一点顶部边距
            else -> scHeight - boxHeight - padding - 30
        }

        // 构建背景颜色 (保留原始 RGB，覆盖 Alpha)
        val bgColor = (bgColorRaw and 0x00FFFFFF) or ((bgAlpha shl 24) and 0xFF000000.toInt())

        // 绘制半透明背景 (圆角)
        drawRoundedRect(ctx, xPos.toFloat(), yPos.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), cornerRadius, bgColor)

        // 绘制所有模块名称 (按长度排序)
        var currentY = yPos + padding
        for ((text, _) in lines) {
            val yOffset = currentY + (font.lineHeight - font.lineHeight) / 2
            ctx.drawText(font, text, (xPos + padding).toInt(), yOffset.toInt(), textColor, false)
            currentY += font.lineHeight + lineSpacing
        }
    }

    /**
     * 绘制圆角矩形 (参考 ClickGuiScreen 实现)
     */
    private fun drawRoundedRect(ctx: DrawContext, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
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
