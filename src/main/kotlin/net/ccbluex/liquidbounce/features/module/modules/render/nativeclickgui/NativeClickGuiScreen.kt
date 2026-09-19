/*
 * Native ClickGUI Screen — full-screen modern recreation of the web ClickGUI.
 *
 * Layout matches ClickGui.svelte:
 *  - full-screen dark overlay
 *  - optional grid
 *  - multiple draggable category panels
 *  - search bar (bottom-center)
 *  - description tooltip on module hover
 *
 * Style 1:1 from colors.scss + Panel/Module Svelte components.
 * Icons: use existing SVG assets (place under assets/liquidbounce/textures/clickgui/).
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

class NativeClickGuiScreen : Screen("ClickGUI".asPlainText()) {

    private val panels = mutableListOf<ClickGuiPanel>()
    private var maxZ = 0

    // search
    private var searchQuery = ""
    private var searchFocused = false
    private val searchResults = mutableListOf<ClientModule>()
    private var searchSelected = 0

    // description tooltip
    private var descText: String? = null
    private var descX = 0f
    private var descY = 0f

    // grid
    private var showGrid = false
    private val gridSize = 10f

    /** Matches web scaleFactor store (1.0 = 100%, web default ~2 → 100% after *50%). */
    var scaleFactor: Float = 1.0f

    private val scaledWidth get() = width / scaleFactor
    private val scaledHeight get() = height / scaleFactor

    // ── Open ────────────────────────────────────────────────────────────────

    override fun init() {
        super.init()
        panels.clear()
        maxZ = 0

        val categories = ModuleCategories.entries.toList()
        // ModuleManager implements Collection<ClientModule>
        val modulesByCat = ModuleManager.groupBy { it.category }

        categories.forEachIndexed { index, cat ->
            val mods = modulesByCat[cat]?.sortedBy { it.name } ?: emptyList()
            if (mods.isEmpty()) return@forEachIndexed
            val panel = ClickGuiPanel(
                category = cat,
                modules = mods,
                initialX = 20f + (index % 4) * 270f,
                initialY = 20f + (index / 4) * 60f,
                zIndex = index
            )
            panels += panel
            maxZ = maxOf(maxZ, index)
        }

        // restore positions
        val saved = ClickGuiPersistence.load()
        panels.forEach { panel ->
            saved[panel.category.tag]?.let { panel.loadState(it) }
            maxZ = maxOf(maxZ, panel.zIndex)
        }
    }

    override fun onClose() {
        ClickGuiPersistence.save(panels)
        super.onClose()
    }

    // ── Tick / Update ───────────────────────────────────────────────────────

    override fun tick() {
        super.tick()
        val delta = 0.05f // approximate; real delta can be wired from partialTick
        panels.forEach { it.update(delta) }
    }

    // ── Render ──────────────────────────────────────────────────────────────

    override fun extractRenderState(
        context: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float
    ) {
        val c = ClickGuiColors

        // dark overlay in screen space (unscaled)
        context.drawQuadXYWH(0f, 0f, width.toFloat(), height.toFloat(), c.OVERLAY_BG)

        // apply scale (web ScaledClickGuiContent) — origin top-left
        val pose = context.pose()
        pose.pushMatrix()
        pose.scale(scaleFactor, scaleFactor)
        val mx = mouseX.toFloat() / scaleFactor
        val my = mouseY.toFloat() / scaleFactor

        // optional grid
        if (showGrid) {
            renderGrid(context)
        }

        // panels sorted by z
        panels.sortedBy { it.zIndex }.forEach { panel ->
            panel.mouseMoved(mx, my)
            panel.render(context, mx, my)
        }

        // description tooltip
        updateDescription(mx, my)
        descText?.let { renderDescription(context, it, descX, descY) }

        // search bar
        renderSearch(context, mx, my)

        pose.popMatrix()
    }

    private fun renderGrid(ctx: GuiGraphicsExtractor) {
        val col = ClickGuiColors.GRID
        var gx = 0f
        while (gx < scaledWidth) {
            ctx.drawQuadXYWH(gx, 0f, 1f, scaledHeight, col)
            gx += gridSize
        }
        var gy = 0f
        while (gy < scaledHeight) {
            ctx.drawQuadXYWH(0f, gy, scaledWidth, 1f, col)
            gy += gridSize
        }
    }

    private fun updateDescription(mx: Float, my: Float) {
        descText = null
        val panel = panels.sortedByDescending { it.zIndex }.firstOrNull { it.contains(mx, my) }
        val mod = panel?.hoveredModule ?: return
        // ClientModule description comes from translation; fallback to name
        val desc = runCatching {
            mod.description?.string?.takeIf { it.isNotBlank() && it != mod.name }
        }.getOrNull() ?: return
        descText = desc
        descX = panel.x + ClickGuiPanel.WIDTH + 12f
        descY = my
        if (descX + 200f > scaledWidth) {
            descX = panel.x - 12f
        }
    }

    private fun renderDescription(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float) {
        val font = mc.font
        val lines = wrap(text, 180)
        val lineH = font.lineHeight + 2
        val boxW = (lines.maxOf { font.width(it) } + 20).toFloat()
        val boxH = (lines.size * lineH + 16).toFloat()
        val boxY = y - boxH / 2f

        // shadow
        ctx.drawRoundedRect(
            x + 1f, boxY + 2f, x + boxW + 1f, boxY + boxH + 2f,
            5f, fillColor = Color4b(0, 0, 0, 120)
        )
        // body
        ctx.drawRoundedRect(
            x, boxY, x + boxW, boxY + boxH,
            5f, fillColor = ClickGuiColors.DESCRIPTION_BG
        )

        var ty = (boxY + 8f).toInt()
        lines.forEach { line ->
            ctx.text(font, line.asPlainText(), (x + 10f).toInt(), ty, ClickGuiColors.TEXT.argb, false)
            ty += lineH
        }
    }

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val font = mc.font
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (font.width(test) > maxWidth && cur.isNotEmpty()) {
                lines += cur
                cur = w
            } else {
                cur = test
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines.ifEmpty { listOf(text) }
    }

    private fun renderSearch(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val c = ClickGuiColors
        val barW = 320f
        val barH = 36f
        val bx = (scaledWidth - barW) / 2f
        val by = scaledHeight - 56f

        ctx.drawRoundedRect(bx, by, bx + barW, by + barH, 8f, fillColor = c.SEARCH_BG)
        // accent left edge
        ctx.drawQuadXYWH(bx, by, 3f, barH, c.ACCENT)

        val font = mc.font
        val placeholder = if (searchQuery.isEmpty() && !searchFocused) "Search modules..." else searchQuery
        val color = if (searchQuery.isEmpty() && !searchFocused) c.TEXT_DIMMED else c.TEXT
        ctx.text(font, placeholder.asPlainText(), (bx + 14f).toInt(), (by + (barH - 8f) / 2f).toInt(), color.argb, false)

        // results dropdown
        if (searchQuery.isNotEmpty() && searchResults.isNotEmpty()) {
            val itemH = 28f
            val dropH = minOf(searchResults.size, 8) * itemH
            val dy = by - dropH - 6f
            ctx.drawRoundedRect(bx, dy, bx + barW, dy + dropH, 6f, fillColor = c.BASE_90)

            searchResults.take(8).forEachIndexed { i, mod ->
                val iy = dy + i * itemH
                if (i == searchSelected) {
                    ctx.drawQuadXYWH(bx, iy, barW, itemH, c.MODULE_HOVER_BG)
                }
                val col = if (mod.enabled) c.MODULE_ENABLED else c.TEXT
                ctx.text(
                    font, mod.name.asPlainText(),
                    (bx + 14f).toInt(), (iy + (itemH - 8f) / 2f).toInt(),
                    col.argb, false
                )
            }
        }
    }

    // ── Input ───────────────────────────────────────────────────────────────

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = click.x().toFloat() / scaleFactor
        val my = click.y().toFloat() / scaleFactor
        val button = click.button()

        // search bar focus
        val barW = 320f
        val barH = 36f
        val bx = (scaledWidth - barW) / 2f
        val by = scaledHeight - 56f
        if (mx in bx..(bx + barW) && my in by..(by + barH)) {
            searchFocused = true
            return true
        }
        searchFocused = false

        // search result click
        if (searchQuery.isNotEmpty() && searchResults.isNotEmpty()) {
            val itemH = 28f
            val dropH = minOf(searchResults.size, 8) * itemH
            val dy = by - dropH - 6f
            if (mx in bx..(bx + barW) && my in dy..(dy + dropH)) {
                val idx = ((my - dy) / itemH).toInt().coerceIn(0, searchResults.lastIndex)
                searchResults[idx].enabled = !searchResults[idx].enabled
                return true
            }
        }

        // panels front-to-back
        val sorted = panels.sortedByDescending { it.zIndex }
        for (panel in sorted) {
            if (panel.contains(mx, my)) {
                // raise z
                maxZ++
                panel.zIndex = maxZ
                if (panel.mouseClicked(mx, my, button)) return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        panels.forEach { it.mouseReleased(click.button()) }
        return super.mouseReleased(click)
    }

    override fun mouseDragged(click: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val mx = click.x().toFloat() / scaleFactor
        val my = click.y().toFloat() / scaleFactor
        panels.forEach { it.mouseDragged(mx, my) }
        return super.mouseDragged(click, dx, dy)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double
    ): Boolean {
        val mx = mouseX.toFloat() / scaleFactor
        val my = mouseY.toFloat() / scaleFactor
        val sorted = panels.sortedByDescending { it.zIndex }
        for (panel in sorted) {
            if (panel.mouseScrolled(mx, my, scrollY)) return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val keyCode = input.key
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (searchQuery.isNotEmpty() || searchFocused) {
                searchQuery = ""
                searchResults.clear()
                searchFocused = false
                return true
            }
            onClose()
            return true
        }
        if (keyCode == InputConstants.KEY_G && input.hasControlDown()) {
            showGrid = !showGrid
            return true
        }

        if (searchFocused || searchQuery.isNotEmpty()) {
            when (keyCode) {
                InputConstants.KEY_BACKSPACE -> {
                    if (searchQuery.isNotEmpty()) {
                        searchQuery = searchQuery.dropLast(1)
                        refreshSearch()
                    }
                    return true
                }
                InputConstants.KEY_DOWN -> {
                    if (searchResults.isNotEmpty()) {
                        searchSelected = (searchSelected + 1) % searchResults.size
                    }
                    return true
                }
                InputConstants.KEY_UP -> {
                    if (searchResults.isNotEmpty()) {
                        searchSelected = (searchSelected - 1 + searchResults.size) % searchResults.size
                    }
                    return true
                }
                InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    searchResults.getOrNull(searchSelected)?.let {
                        it.enabled = !it.enabled
                    }
                    return true
                }
            }
        }
        return super.keyPressed(input)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (searchFocused || searchQuery.isNotEmpty() || codePoint.isLetterOrDigit() || codePoint == ' ') {
            searchFocused = true
            if (!codePoint.isISOControl()) {
                searchQuery += codePoint
                refreshSearch()
                return true
            }
        }
        return super.charTyped(codePoint, modifiers)
    }

    private fun refreshSearch() {
        val q = searchQuery.lowercase().replace(" ", "")
        searchResults.clear()
        if (q.isEmpty()) return
        searchResults += ModuleManager.filter {
            it.name.lowercase().replace(" ", "").contains(q)
        }.sortedBy { it.name }
        searchSelected = 0
    }

    override fun isPauseScreen(): Boolean = false
}
