/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.function.Supplier

object Live2dRenderer {

    private var textureId: Identifier? = null
    private var texture: DynamicTexture? = null
    private var textureW = 0
    private var textureH = 0

    private val pipeline: RenderPipeline get() = RenderPipelines.GUI_TEXTURED

    fun draw(
        context: GuiGraphicsExtractor,
        pixels: IntArray,
        texW: Int,
        texH: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        opacity: Float,
    ) {
        if (texW <= 0 || texH <= 0 || w <= 0 || h <= 0) return
        if (pixels.size < texW * texH) return
        val tex = ensureTexture(texW, texH)
        val image = tex.pixels ?: return
        for (i in 0 until texW * texH) {
            val argb = pixels[i]
            image.setPixel(i % texW, i / texW, argb)
        }
        tex.upload()
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt() shl 24
        val color = alpha or 0x00FFFFFF
        val setup = TextureSetup.singleTexture(tex.textureView, tex.sampler)
        context.drawTexQuad(
            textureSetup = setup,
            x0 = x.toFloat(),
            y0 = y.toFloat(),
            x1 = (x + w).toFloat(),
            y1 = (y + h).toFloat(),
            u1 = 0f, v1 = 0f, u2 = 1f, v2 = 1f,
            argb = color,
            pipeline = pipeline,
        )
    }

    fun drawPlaceholder(
        context: GuiGraphicsExtractor,
        x: Int, y: Int, w: Int, h: Int, color: Color4b,
    ) {
        if (w <= 0 || h <= 0) return
        val argb = color.argb
        context.drawCustomElement(
            pipeline = RenderPipelines.GUI,
            bounds = context.getBounds(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()),
        ) { pose ->
            addVertexWith2DPose(pose, x.toFloat(), y.toFloat()).setColor(argb)
            addVertexWith2DPose(pose, x.toFloat(), (y + h).toFloat()).setColor(argb)
            addVertexWith2DPose(pose, (x + w).toFloat(), (y + h).toFloat()).setColor(argb)
            addVertexWith2DPose(pose, (x + w).toFloat(), y.toFloat()).setColor(argb)
        }
    }

    private fun ensureTexture(w: Int, h: Int): DynamicTexture {
        val existing = texture
        if (existing != null && textureW == w && textureH == h) return existing
        existing?.close()
        val name = "live2d-hud"
        val tex = DynamicTexture(Supplier { name }, w, h, true)
        val id = Identifier.fromNamespaceAndPath("liquidbounce", "live2d/hud")
        Minecraft.getInstance().textureManager.register(id, tex)
        textureId = id
        texture = tex
        textureW = w
        textureH = h
        return tex
    }

    fun dispose() {
        texture?.close()
        texture = null
        textureId = null
        textureW = 0
        textureH = 0
    }
}
