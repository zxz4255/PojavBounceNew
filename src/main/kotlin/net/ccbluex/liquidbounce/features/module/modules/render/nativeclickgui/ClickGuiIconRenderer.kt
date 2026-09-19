/*
 * Draws category / UI icons.
 *
 * Place rasterized PNGs (from the official SVGs) at:
 *   assets/liquidbounce/textures/clickgui/<name>.png
 * Identifiers: liquidbounce:clickgui/<name>
 *
 * If the texture is missing, falls back to a colored initial letter.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.client.renderer.texture.AbstractTexture

object ClickGuiIconRenderer {

    private val missing = mutableSetOf<Identifier>()

    fun drawCategoryIcon(
        ctx: GuiGraphicsExtractor,
        category: ModuleCategory,
        x: Float,
        y: Float,
        size: Float = 16f,
        color: Color4b = Color4b(255, 255, 255, 255)
    ) {
        val id = ClickGuiIcons.forCategory(category)
        if (!drawTexture(ctx, id, x, y, size, size, color)) {
            // fallback: rounded square + first letter
            ctx.drawRoundedRect(x, y, x + size, y + size, 3f, fillColor = ClickGuiColors.ACCENT)
            val letter = category.tag.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val font = mc.font
            val tw = font.width(letter)
            ctx.text(
                font, letter.asPlainText(),
                (x + (size - tw) / 2f).toInt(),
                (y + (size - 8f) / 2f).toInt(),
                ClickGuiColors.TEXT.argb, false
            )
        }
    }

    fun drawIcon(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        size: Float = 12f,
        color: Color4b = Color4b(255, 255, 255, 255)
    ): Boolean = drawTexture(ctx, id, x, y, size, size, color)

    private fun drawTexture(
        ctx: GuiGraphicsExtractor,
        id: Identifier,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Color4b
    ): Boolean {
        if (id in missing) return false
        return runCatching {
            val tm = mc.textureManager
            // Ensure texture is loaded (registers if present in resource packs)
            tm.getTexture(id)
            val setup = textureSetupOf(id) ?: run {
                missing += id
                return false
            }
            ctx.drawTexQuad(
                setup,
                x, y, x + w, y + h,
                0f, 0f, 1f, 1f,
                color.argb,
                RenderPipelines.GUI_TEXTURED
            )
            true
        }.getOrElse {
            missing += id
            false
        }
    }

    /**
     * Build TextureSetup for an Identifier.
     * Adapts to the client's TextureSetup factory; if the signature differs
     * at compile time, replace the body with the project-local helper.
     */
    private fun textureSetupOf(id: Identifier): TextureSetup? {
        return runCatching {
            // Common pattern on recent Yarn/Mojmap: TextureSetup.singleTexture / of
            val clazz = TextureSetup::class.java
            // try static of(Identifier) or single(AbstractTexture)
            val methods = clazz.methods
            methods.firstOrNull { it.name == "singleTexture" && it.parameterCount == 1 }?.let { m ->
                val tex: AbstractTexture = mc.textureManager.getTexture(id)
                return m.invoke(null, tex) as TextureSetup
            }
            methods.firstOrNull { it.name == "of" && it.parameterCount == 1 }?.let { m ->
                return m.invoke(null, id) as TextureSetup
            }
            // last resort: noTexture means we cannot draw
            null
        }.getOrNull()
    }
}
