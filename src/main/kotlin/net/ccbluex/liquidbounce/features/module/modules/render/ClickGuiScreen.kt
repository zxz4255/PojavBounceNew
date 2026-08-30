package net.ccbluex.liquidbounce.features.module.modules.render

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * 原 LiquidBounce 风格 ClickGUI 已废弃。
 * 打开后仅显示废弃提示，按 ESC 关闭。
 */
class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    private val message = "X该 Clickgui 已废弃X"

    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = true

    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // 半透明黑底
        val w = width
        val h = height
        ctx.fill(0, 0, w, h, 0xCC000000.toInt())
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val font = minecraft?.font ?: return
        val sw = width
        val sh = height

        // 大字居中（放大绘制）
        val scale = 3.5f
        val tw = font.width(message) * scale
        val th = font.lineHeight * scale
        val x = (sw - tw) / 2f
        val y = (sh - th) / 2f

        val pose = try {
            ctx.pose()
        } catch (_: Exception) {
            null
        }
        if (pose != null) {
            try {
                pose.pushMatrix()
                pose.translate(x, y)
                pose.scale(scale, scale)
                ctx.text(font, message, 0, 0, 0xFFFF5555.toInt())
                pose.popMatrix()
            } catch (_: Exception) {
                try {
                    pose.popMatrix()
                } catch (_: Exception) {
                }
                ctx.text(font, message, (sw - font.width(message)) / 2, sh / 2, 0xFFFF5555.toInt())
            }
        } else {
            ctx.text(font, message, (sw - font.width(message)) / 2, sh / 2, 0xFFFF5555.toInt())
        }

        // 副标题提示
        val tip = "Press ESC to close"
        ctx.text(
            font, tip,
            (sw - font.width(tip)) / 2,
            (sh / 2 + th / 2 + 16).toInt(),
            0xFFAAAAAA.toInt(),
        )
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        val mc = minecraft ?: return
        try {
            mc.javaClass.getMethod("setScreen", Screen::class.java).invoke(mc, null as Screen?)
        } catch (_: Exception) {
            try {
                mc.setScreen(null)
            } catch (_: Exception) {
            }
        }
    }
}
