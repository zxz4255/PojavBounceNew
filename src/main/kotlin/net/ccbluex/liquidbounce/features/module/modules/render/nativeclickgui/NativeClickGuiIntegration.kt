/*
 * Optional integration helper — open NativeClickGuiScreen from ModuleClickGui
 * or bind a key without touching the web browser path.
 *
 * Usage example (inside ModuleClickGui.onEnabled or a command):
 *
 *   mc.execute {
 *       mc.gui.setScreen(NativeClickGuiScreen())
 *   }
 *
 * Or replace the browser open path when a setting "Native" is selected.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import net.ccbluex.liquidbounce.utils.client.mc

object NativeClickGuiIntegration {

    /** Open the native ClickGUI (closes any current screen first). */
    fun open() {
        mc.execute {
            mc.gui.setScreen(NativeClickGuiScreen())
        }
    }

    /** Toggle: if already open, close; otherwise open. */
    fun toggle() {
        mc.execute {
            if (mc.gui.screen() is NativeClickGuiScreen) {
                mc.gui.setScreen(null)
            } else {
                mc.gui.setScreen(NativeClickGuiScreen())
            }
        }
    }
}
