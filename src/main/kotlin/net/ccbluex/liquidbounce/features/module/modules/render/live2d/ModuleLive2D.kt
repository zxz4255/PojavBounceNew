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
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import java.io.File
import kotlin.math.roundToInt

/**
 * Live2D — renders a Live2D Cubism model as an in-game HUD overlay
 * (OverlayRenderEvent), not only when a Screen is open.
 */
object ModuleLive2D : ClientModule("Live2D", ModuleCategories.RENDER) {

    private var modelName by text("Model", "kaguya")
    private var size by int("Size", 260, 40..800, "px")
    private var posX by int("PosX", 16, -2000..2000, "px")
    private var posY by int("PosY", 110, -2000..2000, "px")
    private val opacity by float("Opacity", 1f, 0f..1f)
    private val swayStrength by float("SwayStrength", 3f, 0f..3f)
    private val smoothing by float("Smoothing", 0.1f, 0.02f..0.6f)
    private val followPitch by boolean("FollowPitch", true)
    private val blink by boolean("Blink", true)
    private val chatDrag by boolean("ChatDrag", true)
    private val editMode by boolean("EditMode", false)

    private val animation = Live2dAnimation()
    private val rasterizer = Live2dRasterizer()
    private val loader: Live2dModelLoader by lazy {
        Live2dModelLoader(File(mc.gameDirectory, "config/live2d"))
    }

    private var loaded: Live2dModelLoader.LoadedModel? = null
    private var loadError: String? = null
    private var loadAttempted = false

    private var lastFrameNanos = 0L

    private var dragging = false
    private var dragLastX = 0.0
    private var dragLastY = 0.0
    private var hovered = false

    override fun onEnabled() {
        animation.reset()
        loadAttempted = false
        loadError = null
        lastFrameNanos = 0L
    }

    override fun onDisabled() {
        dragging = false
        hovered = false
        Live2dRenderer.dispose()
    }

    fun availableModels(): List<String> = runCatching { loader.listModels() }.getOrDefault(emptyList())

    fun currentModelName(): String = modelName

    fun selectModel(name: String): Boolean {
        if (name !in availableModels()) {
            return false
        }
        modelName = name
        loadAttempted = false
        loaded = null
        animation.reset()
        return true
    }

    fun status(): String {
        val model = loaded
        return buildString {
            append("model=").append(modelName)
            append(" size=").append(size)
            append(" pos=").append(posX).append(',').append(posY)
            append(" loaded=").append(model != null)
            loadError?.let { append(" error=").append(it) }
        }
    }

    private fun ensureLoaded(): Live2dModelLoader.LoadedModel? {
        loaded?.let { return it }
        if (loadAttempted) {
            return null
        }
        loadAttempted = true
        return try {
            val m = loader.load(modelName)
            loaded = m
            loadError = null
            m
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /** In-world / HUD — must use OverlayRenderEvent so the model is visible without ClickGUI. */
    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        if (!running) {
            return@handler
        }
        renderModel(event.context)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!running) {
            return@handler
        }
        if (editMode) {
            handleEditKeys()
        }
    }

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        if (!running || !chatDrag) {
            return@handler
        }
        val screen = mc.gui.screen() ?: return@handler
        if (screen !is ChatScreen) {
            dragging = false
            return@handler
        }
        if (event.button == 0) {
            if (event.isPressed) {
                val (mx, my) = scaledMouse()
                hovered = isInside(mx, my)
                if (hovered) {
                    dragging = true
                    dragLastX = mx
                    dragLastY = my
                }
            } else if (event.isReleased) {
                dragging = false
            }
        }
    }

    @Suppress("unused")
    private val mouseScrollHandler = handler<MouseScrollEvent> { event ->
        if (!running || !chatDrag) {
            return@handler
        }
        val screen = mc.gui.screen() ?: return@handler
        if (screen !is ChatScreen) {
            return@handler
        }
        val (mx, my) = scaledMouse()
        if (isInside(mx, my) || hovered) {
            size = (size + event.vertical.roundToInt() * 8).coerceIn(40, 800)
        }
    }

    private fun renderModel(context: GuiGraphicsExtractor) {
        val model = ensureLoaded() ?: return

        if (dragging) {
            val (mx, my) = scaledMouse()
            val dx = (mx - dragLastX).roundToInt()
            val dy = (my - dragLastY).roundToInt()
            if (dx != 0 || dy != 0) {
                moveBy(dx, dy)
            }
            dragLastX = mx
            dragLastY = my
        }

        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9).toFloat()
        lastFrameNanos = now

        animation.swayStrength = swayStrength
        animation.smoothing = smoothing
        animation.followPitch = followPitch
        animation.blinkEnabled = blink
        val player = mc.player
        if (player != null) {
            animation.update(model.runtime, player.yRot, player.xRot, dt.coerceIn(0.001f, 0.1f))
        }
        model.runtime.updateTransientAnimations(dt.coerceIn(0.001f, 0.1f))
        model.runtime.update()

        val h = size
        val aspect = model.runtime.bboxAspect.let { if (it.isFinite() && it > 0.01f) it else 1f }
        val w = (h * aspect).roundToInt().coerceAtLeast(1)
        val pixels = rasterizer.rasterize(model, w, h, opacity)
        if (pixels.isEmpty()) {
            return
        }

        Live2dRenderer.draw(
            context = context,
            pixels = pixels,
            texW = w,
            texH = h,
            x = posX,
            y = posY,
            w = w,
            h = h,
            opacity = 1f,
        )

        if (editMode) {
            Live2dRenderer.drawPlaceholder(context, posX - 1, posY - 1, w + 2, 2, editColor)
            Live2dRenderer.drawPlaceholder(context, posX - 1, posY + h - 1, w + 2, 2, editColor)
        }
    }

    private fun handleEditKeys() {
        var moved = false
        if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(262)) {
            posX += 2
            moved = true
        }
        if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(263)) {
            posX -= 2
            moved = true
        }
        if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(265)) {
            posY -= 2
            moved = true
        }
        if (com.mojang.blaze3d.platform.InputConstants.isKeyDown(264)) {
            posY += 2
            moved = true
        }
        if (moved) {
            posX = posX.coerceIn(-2000, 2000)
            posY = posY.coerceIn(-2000, 2000)
        }
    }

    private fun scaledMouse(): Pair<Double, Double> {
        val window = mc.window
        val mouse = mc.mouseHandler
        return mouse.getScaledXPos(window) to mouse.getScaledYPos(window)
    }

    private fun isInside(mx: Double, my: Double): Boolean {
        val h = size
        val model = loaded ?: return false
        val aspect = model.runtime.bboxAspect.let { if (it.isFinite() && it > 0.01f) it else 1f }
        val w = (h * aspect).roundToInt().coerceAtLeast(1)
        return mx >= posX && mx <= posX + w && my >= posY && my <= posY + h
    }

    fun moveBy(dx: Int, dy: Int) {
        posX = (posX + dx).coerceIn(-2000, 2000)
        posY = (posY + dy).coerceIn(-2000, 2000)
    }

    fun reload() {
        loaded = null
        loadAttempted = false
        loadError = null
        animation.reset()
        loader.invalidate(modelName)
    }

    private val editColor = net.ccbluex.liquidbounce.render.engine.type.Color4b(255, 80, 80, 200)
}
