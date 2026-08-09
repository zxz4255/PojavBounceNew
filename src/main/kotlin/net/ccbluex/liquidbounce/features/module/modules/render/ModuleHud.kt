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
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.events.SpaceSeperatedNamesChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isDestructed
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isHidingNow
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
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
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.Screen

// ================= 【事件导入】 =================
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.features.module.ModuleManager
// =================================================

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

    // ==========================================================
    // 【配置组】ArrayList 设置（最大宽高 + 整体缩放）
    // ==========================================================
    private val hudSettings = ValueGroup("ArrayList Settings")
    private enum class Position { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CUSTOM }
    val position by enum("Position", Position.TOP_RIGHT)
    val posX by float("Offset X", 10f, 0f..1000f)
    val posY by float("Offset Y", 10f, 0f..1000f)
    val bgAlpha by int("Background Alpha", 100, 0..255)
    val scale by float("Scale", 1f, 0.5f..2f)
    // 【新增】极限宽度与极限高度
    val maxWidth by float("Max Width", 150f, 50f..500f)
    val maxHeight by float("Max Height", 300f, 50f..800f)
    // ==========================================================

    init {
        tree(Blur)
        tree(hudSettings)
    }

    object Blur : ToggleableValueGroup(ModuleHud, "Blur", enabled = true) {
        val sigma by float("Sigma", 5.0F, 1.0F..15.0F)
        val alphaBlendRange by floatRange("AlphaBlendRange", 0.0F..0.75F, 0.0F..1.0F)
    }

    @Suppress("unused")
    private val spaceSeperatedNames by boolean("SpaceSeperatedNames", true).onChange { state ->
        EventManager.callEvent(SpaceSeperatedNamesChangeEvent(state))
        state
    }

    val isBlurEffectActive get() = Blur.enabled && !(mc.gui.hud.isHidden && mc.gui.screen() == null)

    val themes = tree(ValueGroup("Themes"))
    val components = tree(ValueGroup("AdditionalComponents")).apply {
        tree(MinimapHudComponent)
    }

    fun updateThemes() {
        themes.inner.filterIsInstance<ValueGroup>().forEach { themes.drop(it) }
        for (theme in ThemeManager.themes) {
            themes.tree(theme.settings)
        }
        themes.walkInit()
        themes.walkKeyPath()
    }

    override fun onEnabled() {
        if (isHidingNow) chat(markAsError(message("hidingAppearance")))
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

    fun reopen() {
        overlay.close()
        updateOverlayVisibility(mc.gui.screen())
    }

    // ==========================================================
    // 【原生渲染】纯视觉展示，无点击交互
    // ==========================================================
    private fun trimText(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var str = text
        while (str.isNotEmpty() && font.width("$str...") > maxWidth) {
            str = str.substring(0, str.length - 1)
        }
        return if (str.isEmpty()) "..." else "$str..."
    }

    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> { event ->
        if (mc.player == null || mc.world == null || !enabled) return@handler

        val ctx = event.ctx
        val font = mc.font
        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight

        // 【修改】按文本长度从长到短进行排序（降序）
        val enabledModules = ModuleManager.getModules()
            .filter { it.enabled && it.name != "HUD" && it.name != "ClickGUI" }
            .sortedByDescending { font.width(it.name) } // 越长，排得越靠上

        if (enabledModules.isEmpty()) return@handler

        var totalHeight = 0f
        var maxTextWidth = 0f
        val itemData = mutableListOf<Pair<ClientModule, String>>()

        // 1. 计算自然尺寸与宽度截断
        val limitWidthPx = maxWidth.toInt()
        for (mod in enabledModules) {
            val displayName = trimText(font, mod.name, limitWidthPx) // 超出极限宽度直接带省略号截断
            val textWidth = font.width(displayName).toFloat()
            val rowHeight = font.lineHeight + 4
            totalHeight += rowHeight
            maxTextWidth = maxTextWidth.coerceAtLeast(textWidth)
            itemData.add(Pair(mod, displayName))
        }

        if (itemData.isEmpty()) return@handler

        // 2. 计算宽度高度限制与缩放适配
        val rawScale = this.scale
        val limitHeightPx = maxHeight

        // 已限制截断后的自然宽度，乘以用户设定的缩放比例
        var finalRenderWidth = maxTextWidth * rawScale
        var finalRenderHeight = totalHeight * rawScale

        // 如果超出了设定的极限高度，计算一个“自动适应缩放”的比例
        var finalGlobalScale = rawScale
        if (finalRenderHeight > limitHeightPx) {
            val fitScale = limitHeightPx / finalRenderHeight
            finalGlobalScale = rawScale * fitScale
            finalRenderWidth = maxTextWidth * finalGlobalScale
            finalRenderHeight = totalHeight * finalGlobalScale
        }

        // 3. 计算绝对坐标
        var baseX = 0f
        var baseY = 0f
        when (position) {
            Position.TOP_LEFT -> { baseX = posX; baseY = posY }
            Position.TOP_RIGHT -> { baseX = screenWidth - finalRenderWidth - posX; baseY = posY }
            Position.BOTTOM_LEFT -> { baseX = posX; baseY = screenHeight - finalRenderHeight - posY }
            Position.BOTTOM_RIGHT -> { baseX = screenWidth - finalRenderWidth - posX; baseY = screenHeight - finalRenderHeight - posY }
            Position.CUSTOM -> { baseX = posX; baseY = posY }
        }

        // 4. 矩阵变换与渲染
        ctx.pose().pushPose()
        ctx.pose().translate(baseX.toDouble(), baseY.toDouble(), 0.0)
        ctx.pose().scale(finalGlobalScale, finalGlobalScale, 1f)

        var yCursor = 0f

        for ((mod, displayName) in itemData) {
            val textWidth = font.width(displayName).toFloat()
            val rowHeight = font.lineHeight + 4
            val color = if (mod.enabled) 0xFFFFFFFF.toInt() else 0xFFA0A0A0.toInt()

            // 绘制背景条
            if (bgAlpha > 0) {
                val bgColor = (bgAlpha shl 24) or 0x000000
                ctx.fill(0f, yCursor, textWidth + 4, yCursor + rowHeight, bgColor)
            }

            // 绘制文字
            ctx.text(font, displayName, 2, yCursor + 2, color)

            yCursor += rowHeight
        }
        ctx.pose().popPose()
    }
}
