/*
 * Native ClickGUI color palette — 1:1 mapped from src-theme/src/colors.scss
 * LiquidBounce nextgen web ClickGUI style.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import net.ccbluex.liquidbounce.render.engine.type.Color4b

object ClickGuiColors {

    // Foundation
    val SURFACE = Color4b(0, 0, 0, 255)                    // #000000
    val TEXT = Color4b(255, 255, 255, 255)                 // #ffffff
    val TEXT_DIMMED = Color4b(211, 211, 211, 255)          // #d3d3d3
    val ACCENT = Color4b(70, 119, 255, 255)                // #4677ff
    val ACCENT_HOVER = Color4b(57, 97, 209, 255)           // ~82% mix black
    val GRID = Color4b(128, 128, 128, 64)                  // 25% gray

    // ClickGUI derived (base = black with alpha)
    val BASE_30 = Color4b(0, 0, 0, 77)
    val BASE_36 = Color4b(0, 0, 0, 92)
    val BASE_50 = Color4b(0, 0, 0, 128)
    val BASE_60 = Color4b(0, 0, 0, 153)
    val BASE_70 = Color4b(0, 0, 0, 179)
    val BASE_80 = Color4b(0, 0, 0, 204)
    val BASE_85 = Color4b(0, 0, 0, 217)
    val BASE_90 = Color4b(0, 0, 0, 230)

    // Panel
    val PANEL_SHADOW = BASE_50
    val PANEL_HEADER_BG = BASE_90
    val PANEL_HEADER_BORDER = ACCENT
    val PANEL_BODY_BG = BASE_80
    val PANEL_TOGGLE_ICON = TEXT

    // Module
    val MODULE_HOVER_BG = BASE_85
    val MODULE_HIGHLIGHT = ACCENT
    val MODULE_ENABLED = ACCENT
    val MODULE_SETTINGS_BG = BASE_50
    val MODULE_SETTINGS_BORDER = ACCENT

    // Search / Description
    val SEARCH_BG = BASE_90
    val DESCRIPTION_BG = BASE_90
    val DESCRIPTION_SHADOW = BASE_50
    val OVERLAY_BG = Color4b(0, 0, 0, 153) // black 60%

    // Scrollbar
    val SCROLLBAR = Color4b(255, 255, 255, 80)
}
