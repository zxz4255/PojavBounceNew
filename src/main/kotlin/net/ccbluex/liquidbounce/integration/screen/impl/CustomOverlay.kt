package net.ccbluex.liquidbounce.integration.screen.impl

import net.ccbluex.liquidbounce.LiquidBounce.logger
import net.ccbluex.liquidbounce.integration.backend.BrowserBackendManager
import net.ccbluex.liquidbounce.integration.backend.browser.Browser
import net.ccbluex.liquidbounce.integration.backend.browser.BrowserSettings
import net.ccbluex.liquidbounce.integration.backend.isBrowserDisabled
import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.integration.screen.ScreenManager
import net.ccbluex.liquidbounce.integration.theme.ThemeManager

class CustomOverlay(
    private val screenType: CustomScreenType,
    var browserSettings: BrowserSettings = ScreenManager.browserSettings
) {

    /**
     * This [browser] might be null.
     */
    var browser: Browser? = null
        private set

    var visible: Boolean
        set(value) {
            // ===== 禁用：完全忽略所有设置请求 =====
            // 原逻辑会调用 open()，现在什么都不做
        }
        get() = false   // 始终返回 false，确保外界认为它不可见

    fun open() {
        // ===== 禁用：不打开任何浏览器 =====
        // 原逻辑会检查后端并创建 browser，现在直接返回
        // 可选：可以添加一条日志便于调试，但为了干净就不加了
    }

    fun close() {
        // ===== 禁用：不关闭任何浏览器 =====
        // 原逻辑会调用 browser?.close()，现在什么都不做
    }
}
