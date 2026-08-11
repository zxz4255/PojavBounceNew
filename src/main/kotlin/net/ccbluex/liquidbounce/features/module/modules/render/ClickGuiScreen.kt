package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import java.io.File

/**
 * LiquidBounce-style ClickGUI — Multi-panel layout, each category is an independent floating panel.
 */
class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    // ==================== Colors (降低透明度) ====================
    private val ACCENT = 0xFF56B4E9.toInt()
    private val ACCENT_DARK = 0xFF3A7CA5.toInt()
    private val BG = 0x80101014.toInt()          // 面板背景降低至 0x80 (50%)
    private val PANEL_BG = 0x9016161A.toInt()     // 面板内部降低至 0x90 (56%)
    private val TAB_BG = 0x8025252E.toInt()
    private val TAB_ACTIVE = 0xFF33333D.toInt()
    private val TEXT = 0xFFE8E8E8.toInt()
    private val TEXT_DIM = 0xFF8E8E8E.toInt()
    private val TEXT_BRIGHT = 0xFFFFFFFF.toInt()
    private val BORDER = 0x1CFFFFFF.toInt()
    private val HOVER = 0x10FFFFFF.toInt()
    private val SCROLL_TRACK = 0x1CFFFFFF.toInt()
    private val SCROLL_THUMB = 0x44FFFFFF.toInt()
    private val SCROLL_THUMB_HOVER = 0x80FFFFFF.toInt()
    private val EXPANDED_BG = 0x0A56B4E9.toInt()
    private val OVERLAY = 0x20000000
    private val SETTING_BG = 0x60080810.toInt()

    // ==================== Layout ====================
    private val CORNER = 4f
    private val ITEM_H = 18f
    private val SETTING_H = 18f
    private val SCROLL_W = 4f
    private val PADDING = 5f
    private val SETTING_INDENT = 8f
    private val PANEL_GAP = 0f
    private val PANEL_MIN_W = 125
    private val PANEL_MAX_H = 400
    private val HEADER_H = 24f

    // ==================== Slider Drag State ====================
    private data class SliderContext(
        val value: Value<*>,
        val panel: PanelData,
        val sliderX: Int,
        val sliderY: Int,
        val sliderW: Int,
        val min: Float,
        val max: Float
    )
    private var sliderContext: SliderContext? = null

    // ==================== State ====================
    private var expandedModule: ClientModule? = null
    private var searchText = ""
    private var searchFocused = false
    private var listeningValue: Value<*>? = null
    private val collapsedGroups = mutableSetOf<Value<*>>()

    private var fadeAnim = 0f
    private var isFirstLoad = true   // 标记首次加载，用于读取缓存

    private val categories = ModuleCategories.entries.toList()

    // ==================== 模式值缓存 ====================
    private val modeValueCache = IdentityHashMap<ClientModule, Value<*>?>()

    private data class PanelData(
        val category: ModuleCategory?,
        var x: Float, var y: Float, var w: Float, var h: Float,
        var scrollOffset: Float = 0f, var targetScroll: Float = 0f,
        var draggingScroll: Boolean = false,
        var draggingPanel: Boolean = false,
        var dragOffsetX: Float = 0f,
        var dragOffsetY: Float = 0f,
        var collapsed: Boolean = false
    )

    private var panels = mutableListOf<PanelData>()
    private var searchPanel: PanelData? = null

    private var isLeftMouseDownCached = false

    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = true

    // ==================== Drawing utilities ====================
    private fun fillRect(ctx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        if (x2 <= x1 || y2 <= y1) return
        ctx.fill(x1, y1, x2, y2, color)
    }

    private fun fillRect(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        if (x2 <= x1 || y2 <= y1) return
        ctx.fill(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), color)
    }

    private fun drawText(ctx: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, color: Int) {
        ctx.text(font, text, x, y, color)
    }

    private fun drawRoundedRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        if (r <= 0.5f) {
            fillRect(ctx, x, y, x + w, y + h, color)
            return
        }
        val x1 = x; val y1 = y; val x2 = x + w; val y2 = y + h
        fillRect(ctx, x1 + r, y1, x2 - r, y2, color)
        fillRect(ctx, x1, y1 + r, x1 + r, y2 - r, color)
        fillRect(ctx, x2 - r, y1 + r, x2, y2 - r, color)
        drawCorner(ctx, x1 + r, y1 + r, r, 180f, 270f, color)
        drawCorner(ctx, x2 - r, y1 + r, r, 270f, 360f, color)
        drawCorner(ctx, x2 - r, y2 - r, r, 0f, 90f, color)
        drawCorner(ctx, x1 + r, y2 - r, r, 90f, 180f, color)
    }

    private fun drawCorner(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, start: Float, end: Float, color: Int) {
        var a = start
        while (a < end) {
            val rad1 = Math.toRadians(a.toDouble())
            val rad2 = Math.toRadians((a + 6f).coerceAtMost(end).toDouble())
            val px1 = cx + (cos(rad1) * r).toFloat()
            val py1 = cy + (sin(rad1) * r).toFloat()
            val px2 = cx + (cos(rad2) * r).toFloat()
            val py2 = cy + (sin(rad2) * r).toFloat()
            val minX = cx.coerceAtMost(px1).coerceAtMost(px2).toInt()
            val maxX = cx.coerceAtLeast(px1).coerceAtLeast(px2).toInt()
            val minY = cy.coerceAtMost(py1).coerceAtMost(py2).toInt()
            val maxY = cy.coerceAtLeast(py1).coerceAtLeast(py2).toInt()
            fillRect(ctx, minX, minY, max(minX + 1, maxX), max(minY + 1, maxY), color)
            a += 6f
        }
    }

    private fun trimText(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var str = text
        while (str.isNotEmpty() && font.width("$str...") > maxWidth) {
            str = str.substring(0, str.length - 1)
        }
        return if (str.isEmpty()) "..." else "$str..."
    }

    private fun getCategoryModules(category: ModuleCategory): List<ClientModule> {
        return try {
            ModuleManager.getModules().toList()
                .filter { it.category == category && it.name != "ClickGUI" }
                .filter { searchText.isEmpty() || it.name.contains(searchText, ignoreCase = true) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getExpandedHeight(mod: ClientModule): Float {
        if (expandedModule != mod) return 0f
        return getVisibleValues(mod).size * SETTING_H
    }

    private fun getContentHeight(modules: List<ClientModule>): Float {
        var h = 0f
        modules.forEach { mod ->
            h += ITEM_H
            h += getExpandedHeight(mod)
        }
        return h
    }

    // ==================== Background ====================
    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight
        fillRect(ctx, 0, 0, sc, sh, OVERLAY)
    }

    // ==================== Main render ====================
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        fadeAnim += (1f - fadeAnim) * 0.25f
        if (fadeAnim < 0.01f) return

        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight
        val font = minecraft!!.font

        val isSearching = searchText.isNotEmpty()

        // 首次加载布局
        if (isFirstLoad) {
            val saved = loadLayout()
            // 先创建面板，再应用保存的位置（在面板创建后再应用，因为此时 panels 可能还未填充）
            // 我们将在面板列表构建完成后应用，所以这里先保存到临时变量，后面使用
            // 这里只标记，实际应用在 panels 构建之后
        }

        // 计算面板布局
        val targetPanels = mutableListOf<PanelData>()

        if (isSearching) {
            val w = (sc * 0.6f).coerceAtLeast(PANEL_MIN_W.toFloat()).coerceAtMost(sc.toFloat())
            val h = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val x = (sc - w) / 2f
            val y = (sh - h) / 2f
            searchPanel = searchPanel ?: PanelData(null, x, y, w, h, 0f, 0f)
            searchPanel?.let { it.x = x; it.y = y; it.w = w; it.h = h }
            if (searchPanel != null) targetPanels.add(searchPanel!!)
        } else {
            searchPanel = null
            val count = categories.size
            val totalGap = PANEL_GAP * (count - 1)
            val widthPerPanel = ((sc - totalGap) / count).coerceAtMost(PANEL_MIN_W * 1.6f)
            val panelW = widthPerPanel.coerceAtLeast(PANEL_MIN_W.toFloat())
            val panelH = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val totalW = panelW * count + PANEL_GAP * (count - 1)
            val startX = (sc - totalW) / 2f
            val panelY = (sh - panelH) / 2f

            for ((idx, cat) in categories.withIndex()) {
                val panelX = startX + (panelW + PANEL_GAP) * idx
                val existingPanel = panels.find { it.category == cat }
                if (existingPanel != null) {
                    existingPanel.w = panelW
                    existingPanel.h = panelH
                    targetPanels.add(existingPanel)
                } else {
                    // 创建新面板，位置稍后会被缓存覆盖
                    val newPanel = PanelData(
                        cat,
                        panelX,
                        panelY,
                        panelW, panelH,
                        collapsed = false
                    )
                    targetPanels.add(newPanel)
                }
            }
            panels.removeAll { targetPanels.contains(it).not() && it.category != null }
            panels = targetPanels
        }

        // 加载缓存到已创建的面板（首次加载）
        if (isFirstLoad) {
            val saved = loadLayout()
            for (panel in panels) {
                val tag = panel.category?.tag
                if (tag != null) {
                    saved[tag]?.let { (x, y, collapsed) ->
                        panel.x = x.toFloat()
                        panel.y = y.toFloat()
                        panel.collapsed = collapsed
                    }
                }
            }
            isFirstLoad = false
        }

        // 绘制面板循环
        for (panel in panels) {
            val px = panel.x; val py = panel.y; val pw = panel.w; val ph = panel.h

            val actualHeight = if (panel.collapsed) HEADER_H + 2f else ph

            drawRoundedRect(ctx, px, py, pw, actualHeight, CORNER, BG)
            drawRoundedRect(ctx, px, py, pw, 1f, CORNER, BORDER)
            drawRoundedRect(ctx, px, py + actualHeight - 1f, pw, 1f, CORNER, BORDER)

            var panelModules: List<ClientModule>
            if (isSearching) {
                panelModules = categories.flatMap { getCategoryModules(it) }.distinct()
                drawText(ctx, font, "§lSearch Results", (px + 8f).toInt(), (py + 5f).toInt(), ACCENT)
            } else {
                val category = panel.category ?: continue
                panelModules = getCategoryModules(category)
                val arrow = if (panel.collapsed) "▶ " else "▼ "
                drawText(ctx, font, "§l$arrow${category.tag}", (px + 8f).toInt(), (py + 5f).toInt(), ACCENT)
                val lineWidth = font.width(category.tag) + 10f
                fillRect(ctx, px + 8f, py + 18f, px + 8f + lineWidth, py + 19f, ACCENT_DARK)
            }

            if (panel.collapsed) continue

            val headerH = 24f
            val listAreaX = px + PADDING
            val listAreaW = pw - PADDING * 2 - SCROLL_W
            val listAreaY = py + headerH + 4f
            val listAreaH = ph - headerH - 8f

            val contentH = getContentHeight(panelModules)
            val maxScroll = max(0f, contentH - listAreaH)
            panel.targetScroll = panel.targetScroll.coerceIn(0f, maxScroll)
            panel.scrollOffset += (panel.targetScroll - panel.scrollOffset) * 0.3f

            var curY = listAreaY - panel.scrollOffset

            for (mod in panelModules) {
                val isExpanded = expandedModule == mod
                val modEndY = curY + ITEM_H

                if (modEndY >= listAreaY && curY <= listAreaY + listAreaH) {
                    val isHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                            mouseY in curY.toInt()..modEndY.toInt()

                    if (isHover) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, modEndY, HOVER)
                    if (isExpanded) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, modEndY, EXPANDED_BG)

                    val nameColor = if (mod.enabled) TEXT else TEXT_DIM
                    val nameMaxW = (listAreaW - 16).toInt()
                    drawText(ctx, font, trimText(font, mod.name, nameMaxW),
                        (listAreaX + 4f).toInt(), (curY + 5f).toInt(), nameColor)

                    val dotX = (listAreaX + listAreaW - 4f).toInt()
                    val dotY = curY.toInt() + 7
                    fillRect(ctx, dotX, dotY, dotX + 4, dotY + 4,
                        if (mod.enabled) ACCENT else 0x40808080.toInt())
                }

                curY += ITEM_H

                if (isExpanded) {
                    val values = getVisibleValues(mod)
                    val settingBgH = values.size * SETTING_H

                    val bgStart = curY.coerceAtLeast(listAreaY)
                    val bgEnd = (curY + settingBgH).coerceAtMost(listAreaY + listAreaH)
                    if (bgEnd > bgStart && bgStart < listAreaY + listAreaH) {
                        fillRect(ctx, listAreaX, bgStart, listAreaX + listAreaW, bgEnd, SETTING_BG)
                    }

                    for ((v, depth) in values) {
                        val settingEndY = curY + SETTING_H
                        if (settingEndY >= listAreaY && curY <= listAreaY + listAreaH) {
                            val isSettingHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                                    mouseY in curY.toInt()..settingEndY.toInt()
                            if (isSettingHover) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, settingEndY, HOVER)
                            renderSetting(ctx, v, depth, listAreaX, curY, listAreaW, mouseX, mouseY, mod)
                        }
                        curY += SETTING_H
                    }
                }
            }

            if (contentH > listAreaH) {
                fillRect(ctx, listAreaX + listAreaW, listAreaY, listAreaX + listAreaW + SCROLL_W, listAreaY + listAreaH, SCROLL_TRACK)
                val thumbH = (listAreaH * listAreaH / contentH).coerceAtLeast(12f)
                val thumbY = listAreaY + if (maxScroll > 0f) (panel.scrollOffset / maxScroll) * (listAreaH - thumbH) else 0f
                val isScrollHover = mouseX in (listAreaX + listAreaW).toInt()..(listAreaX + listAreaW + SCROLL_W).toInt() &&
                        mouseY in thumbY.toInt()..(thumbY + thumbH).toInt()
                val thumbColor = if (isScrollHover || panel.draggingScroll) SCROLL_THUMB_HOVER else SCROLL_THUMB
                fillRect(ctx, listAreaX + listAreaW, thumbY, listAreaX + listAreaW + SCROLL_W, thumbY + thumbH, thumbColor)
            }
        }

        // 底部搜索输入框
        val searchY = sh - 30f
        val searchX = (sc - 160f) / 2f
        val searchW = 160f
        fillRect(ctx, searchX, searchY, searchX + searchW, searchY + 16f, TAB_BG)
        drawRoundedRect(ctx, searchX, searchY, searchW, 16f, 2f, BORDER)

        if (searchText.isEmpty()) {
            drawText(ctx, font, "§7Search modules...", (searchX + 4f).toInt(), (searchY + 3f).toInt(), TEXT_DIM)
        } else {
            drawText(ctx, font, trimText(font, searchText, (searchW - 20).toInt()),
                (searchX + 4f).toInt(), (searchY + 3f).toInt(), TEXT)
        }

        if (searchFocused) {
            val cursorX = searchX.toInt() + 4 + font.width(searchText)
            if (cursorX < searchX + searchW - 4) {
                val blink = System.currentTimeMillis() / 500 % 2 == 0L
                if (blink) fillRect(ctx, cursorX, searchY.toInt() + 2, cursorX + 1, searchY.toInt() + 14, TEXT_BRIGHT)
            }
        }
    }

    // ==================== Setting row renderer ====================
    private fun renderSetting(
        ctx: GuiGraphicsExtractor, v: Value<*>, depth: Int,
        x: Float, y: Float, w: Float, mouseX: Int, mouseY: Int,
        mod: ClientModule
    ) {
        val font = minecraft!!.font
        val indent = depth * SETTING_INDENT
        val actual = getActualValue(v)
        val isGroup = isGroupValue(v)
        val labelX = (x + 6 + indent).toInt()
        val valueX = (x + w - 44).toInt()
        val labelMaxW = (valueX - labelX - 4).coerceAtLeast(10)

        val isEnum = actual is Enum<*>
        val enumConstants: List<Any> = if (isEnum) {
            try { (actual!!.javaClass.enumConstants?.toList() ?: emptyList()) as List<Any> } catch (_: Exception) { emptyList() }
        } else if (actual != null) {
            try {
                val ec = actual.javaClass.enumConstants
                if (ec != null && ec.size >= 2) ec.toList() as List<Any> else emptyList()
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val actualEnum = if (isEnum) actual as Enum<*> else null

        when {
            isEnum && enumConstants.size >= 2 -> {
                renderModeList(ctx, font, v, enumConstants, actualEnum!!, x, y, w, indent, mouseX, mouseY)
            }
            !isEnum && enumConstants.size >= 2 -> {
                val currentName = actual?.toString() ?: ""
                val currentEnum = enumConstants.find { it.toString() == currentName } as? Enum<*>
                if (currentEnum != null) {
                    renderModeList(ctx, font, v, enumConstants, currentEnum, x, y, w, indent, mouseX, mouseY)
                } else {
                    drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 5f).toInt(), TEXT_DIM)
                    val valMaxW = (x + w - valueX - 2).toInt().coerceAtLeast(10)
                    drawText(ctx, font, "§7${trimText(font, getDisplayValue(v), valMaxW)}", valueX, (y + 5f).toInt(), TEXT_DIM)
                }
            }
            isGroup -> {
                val isCollapsed = collapsedGroups.contains(v)
                val arrow = if (isCollapsed) "▶" else "▼"
                drawText(ctx, font, "$arrow ${trimText(font, v.name, labelMaxW)}",
                    labelX, (y + 5f).toInt(), TEXT)
            }
            actual is Boolean -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_DIM)
                val status = if (actual) "§aON" else "§cOFF"
                drawText(ctx, font, status, valueX, (y + 5f).toInt(), if (actual) ACCENT else TEXT_DIM)
            }
            isBindValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_DIM)
                val isListening = listeningValue == v
                val bindStr = trimText(font, formatBindValue(v), (w - 60 - indent).toInt())
                val display = if (isListening) "§e[...]" else "§7$bindStr"
                drawText(ctx, font, display, valueX, (y + 5f).toInt(), if (isListening) ACCENT else TEXT_DIM)
            }
            isSliderValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 3f).toInt(), TEXT_DIM)

                var fv = 0f; var minV = 0f; var maxV = 100f
                if (actual is Number) {
                    fv = actual.toFloat()
                    if (v is RangedValue<*>) {
                        minV = (v.range.start as? Number)?.toFloat() ?: 0f
                        maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                    }
                }

                val sliderW = 36
                val sliderX = valueX
                val sliderY = y.toInt() + 8
                val progress = if (maxV > minV) ((fv - minV) / (maxV - minV)).coerceIn(0f, 1f) else 0f

                fillRect(ctx, sliderX, sliderY, sliderX + sliderW, sliderY + 2, 0x30FFFFFF.toInt())
                fillRect(ctx, sliderX, sliderY, sliderX + (sliderW * progress).toInt(), sliderY + 2, ACCENT)
                fillRect(ctx, sliderX + (sliderW * progress).toInt() - 1, sliderY - 1,
                    sliderX + (sliderW * progress).toInt() + 1, sliderY + 3, TEXT_BRIGHT)

                val valText = trimText(font, "%.1f".format(fv), (w - 70 - indent).toInt())
                drawText(ctx, font, valText, sliderX + sliderW + 3, (y + 3f).toInt(), TEXT_DIM)
            }
            isColorValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_DIM)
                val color = extractColor(v)
                fillRect(ctx, valueX, y.toInt() + 4, valueX + 10, y.toInt() + 14, color.rgb)
            }
            else -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_DIM)
                val valMaxW = (x + w - valueX - 2).toInt().coerceAtLeast(10)
                drawText(ctx, font, "§7${trimText(font, getDisplayValue(v), valMaxW)}",
                    valueX, (y + 5f).toInt(), TEXT_DIM)
            }
        }
    }

    // ==================== 模式选择器渲染 ====================
    private fun renderModeList(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, constants: List<Any>, current: Enum<*>,
        x: Float, y: Float, w: Float, indent: Float,
        mouseX: Int, mouseY: Int
    ) {
        val labelX = (x + 6 + indent).toInt()
        val nameMaxW = 40
        drawText(ctx, font, trimText(font, v.name, nameMaxW), labelX, (y + 5f).toInt(), TEXT)

        val dotSize = 4
        val dotGap = 2
        val nameGap = 4
        val nameW = font.width(v.name)
        var drawX = labelX + nameW + 8
        val drawY = y.toInt() + 7
        val maxX = (x + w - 2).toInt()

        for (const in constants) {
            val displayName = const.toString()
            val isActive = displayName == current.name
            val cNameW = font.width(displayName)

            val neededW = dotSize + dotGap + cNameW + nameGap + 4
            if (drawX + neededW > maxX) {
                if (drawX + dotSize <= maxX) {
                    fillRect(ctx, drawX, drawY, drawX + dotSize, drawY + dotSize,
                        if (isActive) ACCENT else 0x40808080.toInt())
                }
                break
            }

            fillRect(ctx, drawX, drawY, drawX + dotSize, drawY + dotSize,
                if (isActive) ACCENT else 0x40808080.toInt())
            drawX += dotSize + dotGap

            drawText(ctx, font, displayName, drawX, (y + 5f).toInt(),
                if (isActive) TEXT_BRIGHT else TEXT_DIM)
            drawX += cNameW + nameGap + 4
        }
    }

    // ==================== Click handling ====================
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val btn = event.button()
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight

        // 搜索框点击
        val searchY = sh - 30f
        val searchX = (sc - 160f) / 2f
        val searchW = 160f
        if (mx in searchX.toInt()..(searchX + searchW).toInt() &&
            my in searchY.toInt()..(searchY + 16f).toInt()) {
            searchFocused = true
            return true
        }
        searchFocused = false

        var targetPanel: PanelData? = null
        for (panel in panels) {
            if (mx in panel.x.toInt()..(panel.x + panel.w).toInt() &&
                my in panel.y.toInt()..(panel.y + panel.h).toInt()) {
                targetPanel = panel
                break
            }
        }

        if (targetPanel != null) {
            val headerY = targetPanel.y
            val headerH = 24f
            if (my in headerY.toInt()..(headerY + headerH).toInt()) {
                if (btn == 1) {
                    targetPanel.collapsed = !targetPanel.collapsed
                    saveLayout()
                    return true
                } else if (btn == 0) {
                    targetPanel.draggingPanel = true
                    targetPanel.dragOffsetX = mx - targetPanel.x
                    targetPanel.dragOffsetY = my - targetPanel.y
                    return true
                }
            }
        }

        if (targetPanel == null) return true

        val panel = targetPanel!!
        if (panel.collapsed) return true

        val listAreaX = panel.x + PADDING
        val listAreaW = panel.w - PADDING * 2 - SCROLL_W
        val listAreaY = panel.y + 28f
        val listAreaH = panel.h - 32f

        if (mx in (listAreaX + listAreaW).toInt()..(listAreaX + listAreaW + SCROLL_W).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {
            val modules = getModulesForPanel(panel)
            val contentH = getContentHeight(modules)
            if (contentH > listAreaH) {
                panel.draggingScroll = true
                return true
            }
        }

        if (mx in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {

            val modules = getModulesForPanel(panel)
            var curY = listAreaY - panel.scrollOffset

            for (mod in modules) {
                val isExpanded = expandedModule == mod
                val modEndY = curY + ITEM_H

                if (my in curY.toInt()..modEndY.toInt()) {
                    when (btn) {
                        0 -> {
                            if (mod.name != "ClickGUI") {
                                try { mod.enabled = !mod.enabled } catch (_: Exception) {}
                            }
                        }
                        1 -> { expandedModule = if (expandedModule == mod) null else mod }
                    }
                    return true
                }

                curY += ITEM_H

                if (isExpanded) {
                    val values = getVisibleValues(mod)
                    for ((v, depth) in values) {
                        val settingEndY = curY + SETTING_H
                        if (my in curY.toInt()..settingEndY.toInt()) {
                            val actual = getActualValue(v)
                            if (actual is Enum<*> && btn == 0) {
                                val constants: List<Any> = try {
                                    (actual.javaClass.enumConstants?.toList() ?: emptyList()) as List<Any>
                                } catch (_: Exception) { emptyList() }
                                if (constants.size >= 2) {
                                    handleModeClick(v, mx.toFloat(), listAreaX, listAreaW, 8f, constants, actual)
                                    return true
                                }
                            }
                            handleSettingClick(v, btn, mx.toFloat(), curY, listAreaW, listAreaX, panel)
                            return true
                        }
                        curY += SETTING_H
                    }
                }
            }
        }

        return true
    }

    // ==================== 模式点击处理（新版，与 renderModeList 坐标一致） ====================
    private fun handleModeClick(
        v: Value<*>, mx: Float,
        x: Float, w: Float, indent: Float,
        constants: List<Any>, current: Enum<*>
    ) {
        val font = minecraft!!.font
        val dotSize = 4
        val dotGap = 2
        val nameGap = 4

        val labelX = (x + 6 + indent).toInt()
        val nameW = font.width(v.name)
        var drawX = labelX + nameW + 8
        val maxX = (x + w - 2).toInt()

        for (const in constants) {
            val cName = const.toString()
            val cNameW = font.width(cName)
            val neededW = dotSize + dotGap + cNameW + nameGap + 4

            if (drawX + neededW > maxX) {
                if (drawX + dotSize <= maxX && mx.toInt() in drawX..(drawX + dotSize)) {
                    trySetValue(v, const)
                    return
                }
                break
            }

            if (mx.toInt() in drawX..(drawX + dotSize)) {
                trySetValue(v, const)
                return
            }
            if (mx.toInt() in (drawX + dotSize + dotGap)..(drawX + dotSize + dotGap + cNameW)) {
                trySetValue(v, const)
                return
            }
            drawX += dotSize + dotGap + cNameW + nameGap + 4
        }

        // 循环到下一个
        val currentIdx = constants.indexOfFirst { it.toString() == current.name }
        if (currentIdx >= 0) {
            val nextIdx = (currentIdx + 1) % constants.size
            trySetValue(v, constants[nextIdx])
        }
    }

    // ==================== 旧版模式点击（保留但不使用） ====================
    @Suppress("UNUSED_PARAMETER")
    private fun handleModeClick(v: Value<*>, mx: Float, rightEdge: Float,
                                constants: List<Any>, current: Enum<*>) {
        // 此重载已废弃，保留仅为兼容
    }

    // ==================== 鼠标拖拽 ====================
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val mx = event.x().toFloat()
        val my = event.y().toFloat()

        // 滑块拖动
        val context = sliderContext
        if (context != null) {
            val valueX = context.sliderX
            val sliderW = context.sliderW
            val minV = context.min
            val maxV = context.max

            val progress = ((mx.toInt() - valueX).toFloat() / sliderW).coerceIn(0f, 1f)
            val newValue = minV + (maxV - minV) * progress

            val actual = getActualValue(context.value)
            when (actual) {
                is Float -> trySetValue(context.value, newValue)
                is Double -> trySetValue(context.value, newValue.toDouble())
                is Int -> trySetValue(context.value, newValue.toInt())
                is Long -> trySetValue(context.value, newValue.toLong())
            }
            return true
        }

        for (panel in panels) {
            if (panel.draggingPanel) {
                panel.x = mx - panel.dragOffsetX
                panel.y = my - panel.dragOffsetY
                val idx = panels.indexOf(panel)
                if (idx >= 0 && idx < panels.size - 1) {
                    panels.removeAt(idx)
                    panels.add(panel)
                }
                return true
            }

            if (panel.draggingScroll) {
                val modules = getModulesForPanel(panel)
                val contentH = getContentHeight(modules)
                val listAreaH = panel.h - 32f
                if (contentH > listAreaH) {
                    val maxScroll = contentH - listAreaH
                    panel.targetScroll = (panel.targetScroll - dy.toFloat() * 1.2f).coerceIn(0f, maxScroll)
                }
                return true
            }
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        for (panel in panels) {
            panel.draggingScroll = false
            panel.draggingPanel = false
        }
        sliderContext = null
        saveLayout()
        return true
    }

    private fun getModulesForPanel(panel: PanelData): List<ClientModule> {
        return if (searchText.isNotEmpty()) {
            categories.flatMap { getCategoryModules(it) }.distinct()
        } else {
            panel.category?.let { getCategoryModules(it) } ?: emptyList()
        }
    }

    // ==================== 设置点击处理 ====================
    private fun handleSettingClick(v: Value<*>, btn: Int, mx: Float, y: Float, w: Float, x: Float, panel: PanelData) {
        if (btn != 0) return
        val actual = getActualValue(v) ?: return

        if (isGroupValue(v)) {
            if (collapsedGroups.contains(v)) collapsedGroups.remove(v)
            else collapsedGroups.add(v)
            return
        }

        if (actual is Boolean) {
            trySetValue(v, !actual)
            return
        }

        if (isBindValue(v)) {
            listeningValue = if (listeningValue == v) null else v
            return
        }

        if (isSliderValue(v)) {
            val valueX = (x + w - 44).toInt()
            val sliderW = 36
            val hitStart = valueX - 4
            val hitEnd = valueX + sliderW + 4
            if (mx.toInt() in hitStart..hitEnd) {
                var minV = 0f; var maxV = 100f
                if (actual is Number && v is RangedValue<*>) {
                    minV = (v.range.start as? Number)?.toFloat() ?: 0f
                    maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                }
                val progress = ((mx.toInt() - valueX).toFloat() / sliderW).coerceIn(0f, 1f)
                val newValue = minV + (maxV - minV) * progress

                when (actual) {
                    is Float -> trySetValue(v, newValue)
                    is Double -> trySetValue(v, newValue.toDouble())
                    is Int -> trySetValue(v, newValue.toInt())
                    is Long -> trySetValue(v, newValue.toLong())
                }

                sliderContext = SliderContext(
                    value = v,
                    panel = panel,
                    sliderX = valueX,
                    sliderY = y.toInt() + 8,
                    sliderW = sliderW,
                    min = minV,
                    max = maxV
                )
            }
        }
    }

    private fun trySetValue(v: Value<*>, value: Any) {
        try {
            val setMethod = v.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterCount == 1
            }
            setMethod?.invoke(v, value)
        } catch (_: Exception) {}
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        for (panel in panels) {
            if (panel.collapsed) continue
            if (mouseX in panel.x.toDouble()..(panel.x + panel.w).toDouble() &&
                mouseY in panel.y.toDouble()..(panel.y + panel.h).toDouble()) {
                panel.targetScroll = (panel.targetScroll - vertical.toFloat() * 20f).coerceAtLeast(0f)
                return true
            }
        }
        return true
    }

    // ==================== Keyboard ====================
    override fun keyPressed(event: KeyEvent): Boolean {
        if (listeningValue != null) {
            listeningValue = null
            return true
        }

        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            if (searchFocused) {
                searchFocused = false
                return true
            }
            onClose()
            return true
        }

        if (searchFocused) {
            when (event.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    if (searchText.isNotEmpty()) searchText = searchText.dropLast(1)
                    return true
                }
                GLFW.GLFW_KEY_SPACE -> {
                    if (searchText.length < 50) searchText += " "
                    return true
                }
                else -> {
                    val name = GLFW.glfwGetKeyName(event.key(), 0)
                    if (name != null && name.length == 1 && searchText.length < 50) {
                        searchText += name
                        return true
                    }
                }
            }
        }

        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (searchFocused && searchText.length < 50) {
            try {
                val cls = event.javaClass
                var codepoint = 0

                try {
                    val m = cls.getMethod("codepoint")
                    codepoint = m.invoke(event) as? Int ?: 0
                } catch (_: NoSuchMethodException) {
                    try {
                        val m = cls.getMethod("getCodepoint")
                        codepoint = m.invoke(event) as? Int ?: 0
                    } catch (_: NoSuchMethodException) {
                        try {
                            val f = cls.getDeclaredField("codePoint")
                            f.isAccessible = true
                            codepoint = f.get(event) as? Int ?: 0
                        } catch (_: NoSuchFieldException) {
                            try {
                                val f = cls.getDeclaredField("character")
                                f.isAccessible = true
                                val ch = f.get(event) as? Char
                                codepoint = ch?.code ?: 0
                            } catch (_: NoSuchFieldException) {
                                try {
                                    val m = cls.getMethod("getCodePoint")
                                    codepoint = m.invoke(event) as? Int ?: 0
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }

                if (codepoint > 31) {
                    searchText += codepoint.toChar()
                    return true
                }
            } catch (_: Exception) { }
        }
        return true
    }

    override fun onClose() {
        saveLayout()
        setScreenCompat(null)
        fadeAnim = 0f
        isFirstLoad = true   // 下次打开重新加载缓存
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================
    private fun setScreenCompat(screen: Screen?) {
        val mc = minecraft ?: return
        try {
            mc.javaClass.getMethod("setScreen", Screen::class.java)?.invoke(mc, screen)
            return
        } catch (_: NoSuchMethodException) { }
        try {
            mc.javaClass.getMethod("openScreen", Screen::class.java)?.invoke(mc, screen)
            return
        } catch (_: NoSuchMethodException) { }
        try {
            mc.javaClass.getMethod("displayGuiScreen", Screen::class.java)?.invoke(mc, screen)
        } catch (_: Exception) { }
    }

    // ==================== Value helpers ====================
    private fun getModeValue(mod: ClientModule): Value<*>? {
        modeValueCache[mod]?.let { return it }
        val topValues = try { mod.collectValuesRecursively() } catch (_: Exception) { return null }
        for (v in topValues) {
            val actual = getActualValue(v) ?: continue
            if (actual is Enum<*>) {
                val constants = try { actual.javaClass.enumConstants?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
                if (constants.size >= 2) {
                    modeValueCache[mod] = v
                    return v
                }
            }
        }
        modeValueCache[mod] = null
        return null
    }

    private fun getVisibleValues(module: ClientModule): List<Pair<Value<*>, Int>> {
        val result = mutableListOf<Pair<Value<*>, Int>>()
        val topValues = try {
            module.collectValuesRecursively()
        } catch (_: Exception) {
            return emptyList()
        }

        val visited = IdentityHashMap<Value<*>, Boolean>()

        val modeVal = getModeValue(module)
        val currentModeName = if (modeVal != null) {
            (getActualValue(modeVal) as? Enum<*>)?.name ?: ""
        } else ""

        val allModeNames = if (modeVal != null && currentModeName.isNotEmpty()) {
            val actual = getActualValue(modeVal)
            val constants = try { actual!!.javaClass.enumConstants?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
            constants.map { it.toString() }.filter { it != currentModeName }
        } else emptyList()

        fun isHiddenForCurrentMode(v: Value<*>): Boolean {
            if (allModeNames.isEmpty()) return false
            if (v == modeVal) return false
            val vName = v.name
            for (modeName in allModeNames) {
                if (vName.contains(modeName, ignoreCase = true) &&
                    !vName.contains(currentModeName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun process(v: Value<*>, depth: Int) {
            if (visited.containsKey(v)) return
            if (isHiddenForCurrentMode(v)) {
                visited[v] = true
                return
            }
            visited[v] = true
            result.add(Pair(v, depth))
            if (isGroupValue(v) && !collapsedGroups.contains(v)) {
                getGroupChildren(v).forEach { child ->
                    if (!isHiddenForCurrentMode(child)) {
                        process(child, depth + 1)
                    }
                }
            }
        }

        topValues.forEach { v ->
            var isChild = false
            topValues.forEach { other ->
                if (other != v && isGroupValue(other) && getGroupChildren(other).contains(v)) {
                    isChild = true
                }
            }
            if (!isChild) process(v, 0)
        }
        return result
    }

    private fun isGroupValue(v: Value<*>): Boolean {
        return try {
            v.javaClass.simpleName.contains("Group", true) ||
            v.javaClass.simpleName.contains("Container", true)
        } catch (_: Exception) {
            false
        }
    }

    private fun getGroupChildren(v: Value<*>): List<Value<*>> {
        val list = mutableListOf<Value<*>>()
        val visited = IdentityHashMap<Value<*>, Boolean>()
        try {
            for (m in v.javaClass.methods) {
                if ((m.name.equals("getValues", true) || m.name.equals("getSubValues", true)) && m.parameterCount == 0) {
                    val res = m.invoke(v)
                    if (res is Collection<*>) {
                        for (child in res) {
                            if (child is Value<*> && !visited.containsKey(child)) {
                                visited[child] = true
                                list.add(child)
                            }
                        }
                    }
                }
            }
            for (f in v.javaClass.declaredFields) {
                f.isAccessible = true
                val valObj = f.get(v)
                if (valObj is Value<*> && !visited.containsKey(valObj)) {
                    visited[valObj] = true
                    list.add(valObj)
                } else if (valObj is Collection<*>) {
                    for (child in valObj) {
                        if (child is Value<*> && !visited.containsKey(child)) {
                            visited[child] = true
                            list.add(child)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    private fun getActualValue(v: Value<*>): Any? {
        var obj: Any? = try { v.get() } catch (_: Exception) { null }
        var depth = 0
        while (obj is Value<*> && depth < 5) {
            obj = try { obj.get() } catch (_: Exception) { null }
            depth++
        }
        return obj
    }

    private fun isBindValue(v: Value<*>): Boolean {
        val name = v.name.lowercase()
        if (name.contains("key") || name.contains("bind")) return true
        val actual = getActualValue(v) ?: return false
        return actual.javaClass.simpleName.lowercase().contains("key") ||
               actual.javaClass.simpleName.lowercase().contains("bind")
    }

    private fun isSliderValue(v: Value<*>): Boolean {
        val actual = getActualValue(v) ?: return false
        return actual is Number || actual is ClosedRange<*> || v is RangedValue<*>
    }

    private fun isColorValue(v: Value<*>): Boolean {
        val actual = getActualValue(v) ?: return false
        return actual is Color || actual.javaClass.simpleName.contains("Color", true)
    }

    private fun extractColor(v: Value<*>): Color {
        val actual = getActualValue(v) ?: return Color.WHITE
        if (actual is Color) return actual
        if (actual is Number) return Color(actual.toInt(), true)
        return Color.WHITE
    }

    private fun formatBindValue(v: Value<*>): String {
        val actual = getActualValue(v) ?: return "NONE"
        try {
            val keyField = actual.javaClass.declaredFields.find {
                it.name.equals("boundKey", true) || it.name.equals("key", true)
            }
            if (keyField != null) {
                keyField.isAccessible = true
                val key = keyField.get(actual)
                if (key != null) {
                    return key.toString().replace("key.keyboard.", "").uppercase()
                }
            }
        } catch (_: Exception) {}
        return actual.toString().replace("key.keyboard.", "").take(10).uppercase()
    }

    private fun getDisplayValue(v: Value<*>): String {
        val actual = getActualValue(v) ?: return "NONE"
        if (actual is Enum<*>) return actual.name
        if (actual is Boolean) return if (actual) "ON" else "OFF"
        return actual.toString().take(15)
    }

    // ==================== 面板位置缓存 ====================
    private fun getLayoutFile(): File {
        return File(minecraft?.gameDirectory ?: File("."), "config/liquidbounce_clickgui_panels.json")
    }

    private fun saveLayout() {
        try {
            val sb = StringBuilder()
            sb.append("[")
            val parts = panels.filter { it.category != null }.map { p ->
                val tag = p.category?.tag ?: ""
                "{\"tag\":\"$tag\",\"x\":${p.x.toInt()},\"y\":${p.y.toInt()},\"collapsed\":${p.collapsed}}"
            }
            sb.append(parts.joinToString(","))
            sb.append("]")
            val file = getLayoutFile()
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    private fun loadLayout(): Map<String, Triple<Int, Int, Boolean>> {
        return try {
            val file = getLayoutFile()
            if (!file.exists()) return emptyMap()
            val content = file.readText()
            val result = mutableMapOf<String, Triple<Int, Int, Boolean>>()
            val regex = Regex("""\{"tag":"([^"]+)","x":(-?[0-9]+),"y":(-?[0-9]+),"collapsed":(true|false)\}""")
            for (match in regex.findAll(content)) {
                val (tag, x, y, collapsed) = match.destructured
                result[tag] = Triple(x.toInt(), y.toInt(), collapsed.toBoolean())
            }
            result
        } catch (_: Exception) { emptyMap() }
    }
}