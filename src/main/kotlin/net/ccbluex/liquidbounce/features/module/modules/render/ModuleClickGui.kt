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
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import org.lwjgl.glfw.GLFW

object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = GLFW.GLFW_KEY_RIGHT_SHIFT) {

    override val running get() = true

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.action == 1) {
            val key = event.keyCode

            // Right Shift 开/关 ClickGUI
            if (key == GLFW.GLFW_KEY_RIGHT_SHIFT || key == 54) {
                val currentScreen = mc.gui.screen()
                if (currentScreen == null) {
                    openGui()
                } else if (currentScreen is ClickGuiScreen) {
                    closeGui()
                }
            }
            // 【新增】ESC 键关闭 ClickGUI（仅当 GUI 已打开时）
            else if (key == GLFW.GLFW_KEY_ESCAPE) {
                val currentScreen = mc.gui.screen()
                if (currentScreen is ClickGuiScreen) {
                    closeGui()
                }
            }
        }
    }

    override fun onEnabled() {
        openGui()
        super.onEnabled()
    }

    private fun openGui() {
        try {
            mc.gui.setScreen(ClickGuiScreen())
            return
        } catch (_: NoSuchMethodError) { }
        try {
            mc.javaClass.getMethod("setScreen", net.minecraft.client.gui.screens.Screen::class.java)
                ?.invoke(mc, ClickGuiScreen())
            return
        } catch (_: Exception) { }
        mc.execute {
            mc.gui.setScreen(ClickGuiScreen())
        }
    }

    /**
     * 【新增】与 openGui 采用完全相同的反射兜底逻辑，保证能安全关屏。
     */
    private fun closeGui() {
        try {
            mc.gui.setScreen(null)
            return
        } catch (_: NoSuchMethodError) { }
        try {
            mc.javaClass.getMethod("setScreen", net.minecraft.client.gui.screens.Screen::class.java)
                ?.invoke(mc, null)
            return
        } catch (_: Exception) { }
        mc.execute {
            mc.gui.setScreen(null)
        }
    }

    fun sync() {}
    fun invalidate() {}
    val isInSearchBar: Boolean get() = false
    fun updateStandaloneScreen(): Boolean = false
    }
