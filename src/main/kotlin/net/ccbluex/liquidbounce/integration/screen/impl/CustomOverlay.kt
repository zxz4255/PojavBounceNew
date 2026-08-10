package net.ccbluex.liquidbounce.integration.screen.impl

import net.ccbluex.liquidbounce.integration.backend.browser.Browser
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.ScreenManager

class CustomOverlay(
    private val screenType: CustomScreenType,
    var browserSettings: BrowserSettings = ScreenManager.browserSettings
) {

    var browser: Browser? = null
        private set

    var visible: Boolean
        set(value) {
            // 完全禁用，不执行任何操作
        }
        get() = false   // 始终返回 false，表示不显示

    fun open() {
        // 禁用，不执行任何操作
    }

    fun close() {
        // 禁用，不执行任何操作
    }
}
