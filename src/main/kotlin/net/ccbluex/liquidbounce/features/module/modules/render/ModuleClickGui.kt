/*
 * ModuleClickGui —— 打开原生 ClickGuiScreen 的轻量模块 (无 Web 依赖)
 *
 * 解决: 原浏览器版需两次按右 Shift 才能打开 ClickGUI 的问题。
 * 本版: 模块始终 running, keyHandler 常驻监听, 按下右 Shift 一次即打开;
 *       再次按下 (当前屏幕是 ClickGuiScreen) 关闭。
 *
 * 兼容 API: sync() / invalidate() / isInSearchBar / updateStandaloneScreen()
 *           满足 AutoConfig / ScreenManager / ThemeManager / CommandBind(s)
 *           / CommandValue / CommandTargets / CommandModels / ModelManager
 *           / ModuleInventoryMove / ScriptManager 等文件的引用。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import org.lwjgl.glfw.GLFW

object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = GLFW.GLFW_KEY_RIGHT_SHIFT) {

    // 模块未启用时也保持事件处理, 保证快捷键始终可用 (无需先启用模块)
    override val running get() = true

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.action == 1 &&
            (event.keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT || event.keyCode == 54)
        ) {
            val currentScreen = mc.gui.screen()
            if (currentScreen == null) {
                openGui()
            } else if (currentScreen is ClickGuiScreen) {
                closeGui()
            }
        }
    }

    // 从模块列表启用 ClickGUI 时立即打开 (用 enabledEffect 替代不存在的 onEnabled)
    override suspend fun enabledEffect() {
        if (mc.gui.screen() !is ClickGuiScreen) {
            openGui()
        }
    }

    private fun openGui() {
        try {
            mc.gui.setScreen(ClickGuiScreen())
            return
        } catch (_: NoSuchMethodError) {
        }
        try {
            mc.javaClass.getMethod("setScreen", net.minecraft.client.gui.screens.Screen::class.java)
                ?.invoke(mc, ClickGuiScreen())
            return
        } catch (_: Exception) {
        }
        mc.execute {
            mc.gui.setScreen(ClickGuiScreen())
        }
    }

    /**
     * 与 openGui 采用完全相同的反射兜底逻辑, 保证能安全关屏。
     */
    private fun closeGui() {
        try {
            mc.gui.setScreen(null)
            return
        } catch (_: NoSuchMethodError) {
        }
        try {
            mc.javaClass.getMethod("setScreen", net.minecraft.client.gui.screens.Screen::class.java)
                ?.invoke(mc, null)
            return
        } catch (_: Exception) {
        }
        mc.execute {
            mc.gui.setScreen(null)
        }
    }

    // ==================== 兼容 API (供其他系统调用) ====================
    fun sync() {}
    fun invalidate() {}
    val isInSearchBar: Boolean get() = false
    fun updateStandaloneScreen(): Boolean = false
}
