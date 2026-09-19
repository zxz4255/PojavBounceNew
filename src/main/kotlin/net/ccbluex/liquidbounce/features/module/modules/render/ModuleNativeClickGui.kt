/*
 * Native ClickGUI module — opens NativeClickGuiScreen instead of the web browser GUI.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui.NativeClickGuiScreen
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc

/**
 * Native ClickGUI
 *
 * Opens the native (non-browser) ClickGUI that mirrors the web theme layout.
 * Default bind: Right Shift (same as web ClickGUI; change if both are registered).
 */
object ModuleNativeClickGui : ClientModule(
    name = "NativeClickGUI",
    category = ModuleCategories.RENDER,
    bind = InputConstants.KEY_RSHIFT,
    disableActivation = true,
    aliases = listOf("NClickGUI", "NativeClickGui")
) {

    override val running get() = true

    private val scale by float("Scale", 1f, 0.5f..2f)

    override fun onEnabled() {
        if (!inGame) {
            enabled = false
            return
        }

        mc.execute {
            val screen = NativeClickGuiScreen()
            screen.scaleFactor = scale
            mc.gui.setScreen(screen)
        }
        // notActivatable-style: turn off after opening so the bind acts as a toggle open
        enabled = false
        super.onEnabled()
    }
}
