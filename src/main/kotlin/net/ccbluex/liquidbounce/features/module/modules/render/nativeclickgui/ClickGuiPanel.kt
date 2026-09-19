/*
 * Native ClickGUI Panel — layout & style 1:1 with web Panel.svelte
 *
 * Width 250 · Header 40 · Module row 32 · Max body 545
 * Settings area: left 4px accent border + base-50 bg (matches Module.svelte)
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui.setting.ClickGuiSettingRenderer
import net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui.setting.collectModuleSettings
import net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui.ClickGuiIconRenderer
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import kotlin.math.max
import kotlin.math.min

class ClickGuiPanel(
    val category: ModuleCategory,
    val modules: List<ClientModule>,
    initialX: Float,
    initialY: Float,
    var zIndex: Int = 0
) {
    companion object {
        const val WIDTH = 250f
        const val HEADER_HEIGHT = 40f
        const val MODULE_HEIGHT = 32f
        const val MAX_BODY_HEIGHT = 545f
        const val CORNER_RADIUS = 5f
        const val ANIM_SPEED = 0.22f
    }

    var x = initialX
    var y = initialY
    var expanded = false
    var scrollOffset = 0f

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var expandProgress = 0f

    private val openSettings = mutableSetOf<String>()
    private var draggingValue: Value<*>? = null
    private var draggingValueY = 0f
    private var draggingModuleName: String = ""

    var hoveredModule: ClientModule? = null
        private set

    private fun moduleBlockHeight(mod: ClientModule): Float {
        var h = MODULE_HEIGHT
        if (mod.name in openSettings) {
            collectModuleSettings(mod).forEach { h += ClickGuiSettingRenderer.measureHeight(it, mod.name) }
        }
        return h
    }

    private val contentHeight: Float
        get() = modules.sumOf { moduleBlockHeight(it).toDouble() }.toFloat()

    private val bodyTargetHeight: Float
        get() = if (expanded) min(contentHeight, MAX_BODY_HEIGHT) else 0f

    val currentBodyHeight: Float
        get() = bodyTargetHeight * expandProgress

    val totalHeight: Float
        get() = HEADER_HEIGHT + currentBodyHeight

    fun update(delta: Float) {
        val target = if (expanded) 1f else 0f
        val t = (ANIM_SPEED * (delta * 20f).coerceIn(0.4f, 4f))
        expandProgress = Mth.lerp(t, expandProgress, target)
        if (kotlin.math.abs(expandProgress - target) < 0.002f) expandProgress = target
        val maxScroll = max(0f, contentHeight - currentBodyHeight)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
    }

    fun mouseClicked(mx: Float, my: Float, button: Int): Boolean {
        if (!contains(mx, my)) return false
        if (button != 0) return true
        if (isInExpandButton(mx, my)) {
            expanded = !expanded
            return true
        }
        if (isInHeader(mx, my)) {
            dragging = true
            dragOffsetX = mx - x
            dragOffsetY = my - y
            return true
        }
        if (expanded && isInBody(mx, my)) return handleBodyClick(mx, my)
        return true
    }

    private fun handleBodyClick(mx: Float, my: Float): Boolean {
        var cursor = y + HEADER_HEIGHT - scrollOffset
        for (mod in modules) {
            val blockH = moduleBlockHeight(mod)
            val nameBottom = cursor + MODULE_HEIGHT
            if (my in cursor..nameBottom) {
                if (mx >= x + WIDTH - 40f) {
                    if (mod.name in openSettings) openSettings.remove(mod.name)
                    else openSettings.add(mod.name)
                } else {
                    mod.enabled = !mod.enabled
                }
                return true
            }
            if (mod.name in openSettings && my in nameBottom..(cursor + blockH)) {
                var sy = nameBottom
                for (value in collectModuleSettings(mod)) {
                    val vh = ClickGuiSettingRenderer.measureHeight(value, mod.name)
                    if (my in sy..(sy + vh)) {
                        if (ClickGuiSettingRenderer.mouseClicked(value, x + 4f, sy, WIDTH - 4f, mx, my, 0, mod.name)) {
                            draggingValue = value
                            draggingValueY = sy
                            draggingModuleName = mod.name
                        }
                        return true
                    }
                    sy += vh
                }
            }
            cursor += blockH
        }
        return true
    }

    fun mouseReleased(button: Int) {
        if (button == 0) {
            dragging = false
            draggingValue = null
        }
    }

    fun mouseDragged(mx: Float, my: Float) {
        if (dragging) {
            x = mx - dragOffsetX
            y = my - dragOffsetY
        }
        draggingValue?.let { value ->
            ClickGuiSettingRenderer.mouseDragged(value, x + 4f, draggingValueY, WIDTH - 4f, mx, my, draggingModuleName)
        }
    }

    fun mouseScrolled(mx: Float, my: Float, amount: Double): Boolean {
        if (!expanded || !isInBody(mx, my)) return false
        scrollOffset = (scrollOffset - amount.toFloat() * 22f).coerceAtLeast(0f)
        val maxScroll = max(0f, contentHeight - currentBodyHeight)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        return true
    }

    fun mouseMoved(mx: Float, my: Float) {
        hoveredModule = null
        if (!expanded || !isInBody(mx, my)) return
        var cursor = y + HEADER_HEIGHT - scrollOffset
        for (mod in modules) {
            val blockH = moduleBlockHeight(mod)
            if (my in cursor..(cursor + MODULE_HEIGHT)) {
                hoveredModule = mod
                return
            }
            cursor += blockH
        }
    }

    fun contains(mx: Float, my: Float) =
        mx in x..(x + WIDTH) && my in y..(y + totalHeight)

    private fun isInHeader(mx: Float, my: Float) =
        mx in x..(x + WIDTH) && my in y..(y + HEADER_HEIGHT)

    private fun isInExpandButton(mx: Float, my: Float): Boolean {
        val s = 22f
        val bx = x + WIDTH - 14f - s
        val by = y + (HEADER_HEIGHT - s) / 2f
        return mx in bx..(bx + s) && my in by..(by + s)
    }

    private fun isInBody(mx: Float, my: Float) =
        mx in x..(x + WIDTH) && my in (y + HEADER_HEIGHT)..(y + totalHeight)

    fun render(ctx: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val c = ClickGuiColors
        val font = mc.font

        ctx.drawRoundedRect(
            x + 1.5f, y + 2.5f, x + WIDTH + 1.5f, y + totalHeight + 2.5f,
            CORNER_RADIUS, fillColor = Color4b(0, 0, 0, 100)
        )

        if (currentBodyHeight > 0.5f) {
            ctx.drawRoundedRect(
                x, y + HEADER_HEIGHT - 3f, x + WIDTH, y + totalHeight,
                CORNER_RADIUS, fillColor = c.PANEL_BODY_BG
            )
            ctx.drawQuadXYWH(x, y + HEADER_HEIGHT - 3f, WIDTH, 10f, c.PANEL_BODY_BG)
        }

        ctx.drawRoundedRect(
            x, y, x + WIDTH, y + HEADER_HEIGHT,
            CORNER_RADIUS, fillColor = c.PANEL_HEADER_BG
        )
        ctx.drawQuadXYWH(x, y + HEADER_HEIGHT - 8f, WIDTH, 8f, c.PANEL_HEADER_BG)
        ctx.drawQuadXYWH(x, y + HEADER_HEIGHT - 2f, WIDTH, 2f, c.PANEL_HEADER_BORDER)

        ClickGuiIconRenderer.drawCategoryIcon(ctx, category, x + 12f, y + (HEADER_HEIGHT - 16f) / 2f, 16f)
        ctx.text(
            font, category.tag.asPlainText(),
            (x + 38f).toInt(), (y + (HEADER_HEIGHT - 8f) / 2f).toInt(),
            c.TEXT.argb, false
        )
        renderPlusIcon(ctx, x + WIDTH - 22f, y + HEADER_HEIGHT / 2f, expandProgress)

        if (currentBodyHeight > 1f) {
            val bodyTop = y + HEADER_HEIGHT
            val bodyBottom = y + totalHeight
            var cursor = bodyTop - scrollOffset

            for (mod in modules) {
                val blockH = moduleBlockHeight(mod)
                if (cursor + blockH < bodyTop - 1f || cursor > bodyBottom + 1f) {
                    cursor += blockH
                    continue
                }

                if (cursor + MODULE_HEIGHT >= bodyTop && cursor <= bodyBottom) {
                    renderModuleName(ctx, mod, x, cursor, mouseX, mouseY)
                }

                if (mod.name in openSettings) {
                    val settingsTop = cursor + MODULE_HEIGHT
                    val settingsH = blockH - MODULE_HEIGHT
                    if (settingsH > 0 && settingsTop < bodyBottom && settingsTop + settingsH > bodyTop) {
                        ctx.drawQuadXYWH(x, settingsTop, WIDTH, settingsH, c.MODULE_SETTINGS_BG)
                        ctx.drawQuadXYWH(x, settingsTop, 4f, settingsH, c.MODULE_SETTINGS_BORDER)

                        var sy = settingsTop
                        for (value in collectModuleSettings(mod)) {
                            val vh = ClickGuiSettingRenderer.measureHeight(value, mod.name)
                            if (sy + vh >= bodyTop && sy <= bodyBottom) {
                                ClickGuiSettingRenderer.render(
                                    ctx, value, x + 4f, sy, WIDTH - 4f, mouseX, mouseY, mod.name
                                )
                            }
                            sy += vh
                        }
                    }
                }
                cursor += blockH
            }
        }
    }

    private fun renderModuleName(
        ctx: GuiGraphicsExtractor,
        mod: ClientModule,
        px: Float, py: Float,
        mouseX: Float, mouseY: Float
    ) {
        val c = ClickGuiColors
        val font = mc.font
        val hovered = hoveredModule == mod
        val enabled = mod.enabled
        val settingsOpen = mod.name in openSettings

        if (hovered) ctx.drawQuadXYWH(px, py, WIDTH, MODULE_HEIGHT, c.MODULE_HOVER_BG)

        val color = if (enabled) c.MODULE_ENABLED else c.TEXT_DIMMED
        val name = mod.name
        val tw = font.width(name)
        ctx.text(
            font, name.asPlainText(),
            (px + (WIDTH - tw) / 2f).toInt(),
            (py + (MODULE_HEIGHT - 8f) / 2f).toInt(),
            color.argb, false
        )

        val ax = px + WIDTH - 18f
        val ay = py + MODULE_HEIGHT / 2f
        val ac = when {
            settingsOpen -> c.ACCENT
            enabled -> Color4b(c.ACCENT.r, c.ACCENT.g, c.ACCENT.b, 180)
            else -> Color4b(255, 255, 255, 110)
        }
        if (settingsOpen) {
            ctx.drawQuadXYWH(ax - 5f, ay - 2f, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax - 3f, ay, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax - 1f, ay + 2f, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax + 1f, ay, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax + 3f, ay - 2f, 2f, 2f, ac)
        } else {
            ctx.drawQuadXYWH(ax - 4f, ay - 5f, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax - 2f, ay - 3f, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax, ay - 1f, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax - 2f, ay + 1f, 2f, 2f, ac)
            ctx.drawQuadXYWH(ax - 4f, ay + 3f, 2f, 2f, ac)
        }
    }

    private fun renderPlusIcon(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, progress: Float) {
        val col = ClickGuiColors.PANEL_TOGGLE_ICON
        val len = 5.5f
        ctx.drawQuadXYWH(cx - len, cy - 1f, len * 2f, 2f, col)
        val vAlpha = (1f - progress).coerceIn(0f, 1f)
        if (vAlpha > 0.05f) {
            val a = (col.a * vAlpha).toInt().coerceIn(0, 255)
            ctx.drawQuadXYWH(cx - 1f, cy - len, 2f, len * 2f, Color4b(col.r, col.g, col.b, a))
        }
    }

    data class PanelState(
        val x: Float, val y: Float, val expanded: Boolean, val scroll: Float, val z: Int
    )

    fun saveState() = PanelState(x, y, expanded, scrollOffset, zIndex)

    fun loadState(state: PanelState) {
        x = state.x; y = state.y; expanded = state.expanded
        scrollOffset = state.scroll; zIndex = state.z
        expandProgress = if (expanded) 1f else 0f
    }
}
