package net.ccbluex.liquidbounce.features.module.modules.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    private val message = "X该clickgui 已废弃X"

    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = true

    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        ctx.fill(0, 0, width, height, 0xCC000000.toInt())
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val font = minecraft?.font ?: return
        val sw = width
        val sh = height

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
        setScreenCompat(null)
    }

    private fun setScreenCompat(screen: Screen?) {
        val mc = minecraft ?: return
        // 兼容不同映射：setScreen / openScreen / displayGuiScreen / gui.setScreen
        val candidates = listOf(
            arrayOf("setScreen", Screen::class.java),
            arrayOf("openScreen", Screen::class.java),
            arrayOf("displayGuiScreen", Screen::class.java),
        )
        for ((name, arg) in candidates) {
            try {
                val m = mc.javaClass.getMethod(name as String, arg as Class<*>)
                m.invoke(mc, screen)
                return
            } catch (_: Exception) {
            }
        }
        try {
            val guiField = mc.javaClass.declaredFields.firstOrNull {
                it.name.equals("gui", true) || it.type.simpleName.contains("Gui", true)
            }
            if (guiField != null) {
                guiField.isAccessible = true
                val gui = guiField.get(mc) ?: return
                val m = gui.javaClass.methods.firstOrNull {
                    it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(Screen::class.java) &&
                        (it.name.equals("setScreen", true) || it.name.contains("Screen", true))
                }
                m?.invoke(gui, screen)
            }
        } catch (_: Exception) {
        }
    }
}
