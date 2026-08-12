package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.engine.type.Color4b
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
import kotlin.math.min
import kotlin.math.sin
import java.io.File
import kotlin.collections.ArrayList

class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    // ==================== 简洁暗黑风格配色 ====================
    private val PRIMARY = 0xFF6C5CE7.toInt()
    private val PRIMARY_LIGHT = 0xFFA29BFE.toInt()
    private val PRIMARY_DARK = 0xFF4834D4.toInt()
    private val TEXT_WHITE = 0xFFFFFFFF.toInt()
    private val TEXT_SECONDARY = 0xFF888888.toInt()
    private val TEXT_DIM = 0xFF555555.toInt()
    private val BG_PANEL = 0xDD0A0A0A.toInt()
    private val BG_ITEM_HOVER = 0x15FFFFFF.toInt()
    private val BG_ITEM_ACTIVE = 0x0D6C5CE7.toInt()
    private val BORDER_LIGHT = 0x15FFFFFF.toInt()
    private val TOGGLE_ON = 0xFF6C5CE7.toInt()
    private val TOGGLE_OFF = 0xFF2A2A2A.toInt()
    private val TOGGLE_KNOB_ON = 0xFFFFFFFF.toInt()
    private val TOGGLE_KNOB_OFF = 0xFF666666.toInt()

    // ==================== Layout ====================
    private val CORNER = 6f
    private val ITEM_H = 24f
    private val SETTING_H = 20f
    private val SCROLL_W = 3f
    private val PADDING = 8f
    private val SETTING_INDENT = 12f
    private val PANEL_GAP = 8f
    private val PANEL_MIN_W = 120
    private val PANEL_MAX_H = 380
    private val HEADER_H = 28f

    // ==================== 性能优化缓存 ====================
    private data class CachedModuleInfo(
        val name: String,
        val enabled: Boolean,
        val isExpanded: Boolean,
        val settingsCount: Int,
        val totalHeight: Float
    )
    
    // ── 性能缓存（关键：避免每帧反射 + 列表分配）──
    private var cachedPanelData = mutableMapOf<ModuleCategory, List<CachedModuleInfo>>()
    private var lastSearchText = ""
    private var lastUpdateTime = 0L
    private var needsRebuild = true
    private var cacheVersion = 0L
    private val categoryModulesCache = mutableMapOf<ModuleCategory, List<ClientModule>>()
    private var cacheVersionModules = -1L
    private val contentHeightCache = mutableMapOf<PanelData, Float>()

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

    // ==================== 调色板 ====================
    private var activeColorValue: Value<*>? = null
    private var colorPickerX = 0f
    private var colorPickerY = 0f
    private val PALETTE_ROWS = 9
    private val PALETTE_COLS = 5
    private val PALETTE_CELL = 10f
    private val PALETTE_GAP = 2f
    private val PALETTE_PAD = 4f
    private val PALETTE_W = PALETTE_COLS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP + PALETTE_PAD * 2
    private val PALETTE_H = PALETTE_ROWS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP + PALETTE_PAD * 2

    private val paletteColors: List<Color4b> = buildList {
        for (row in 0 until 8) {
            val hue = row * 45f / 360f
            for (col in 0 until 5) {
                val brightness = 0.95f - col * 0.2f
                add(Color4b.ofHSB(hue, 0.9f, brightness))
            }
        }
        for (col in 0 until 5) {
            val brightness = 0.95f - col * 0.2f
            add(Color4b.ofHSB(0f, 0f, brightness))
        }
    }

    private var fadeAnim = 0f
    private var layoutLoaded = false
    private val cachedLayout: LayoutState by lazy { loadLayout() }

    private val categories = ModuleCategories.entries.toList()
    private val modeValueCache = IdentityHashMap<ClientModule, Value<*>?>()
    private val visibleValuesCache = IdentityHashMap<ClientModule, List<Pair<Value<*>, Int>>>()

    // ==================== Panel Data ====================
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

    private val panels = mutableListOf<PanelData>()
    private var searchPanel: PanelData? = null

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
        if (w <= 0 || h <= 0) return
        val r = min(radius, min(w / 2f, h / 2f))
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
            val rad2 = Math.toRadians(min(a + 6f, end).toDouble())
            val px1 = cx + (cos(rad1) * r).toFloat()
            val py1 = cy + (sin(rad1) * r).toFloat()
            val px2 = cx + (cos(rad2) * r).toFloat()
            val py2 = cy + (sin(rad2) * r).toFloat()
            val minX = min(cx, min(px1, px2)).toInt()
            val maxX = max(cx, max(px1, px2)).toInt()
            val minY = min(cy, min(py1, py2)).toInt()
            val maxY = max(cy, max(py1, py2)).toInt()
            fillRect(ctx, minX, minY, max(minX + 1, maxX), max(minY + 1, maxY), color)
            a += 6f
        }
    }

    private fun trimText(font: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        var str = text
        while (str.isNotEmpty() && font.width("$str...") > maxWidth) {
            str = str.substring(0, str.length - 1)
        }
        return if (str.isEmpty()) "..." else "$str..."
    }

    private fun getCategoryModules(category: ModuleCategory): List<ClientModule> {
        val v = cacheVersionModules
        if (v == cacheVersion && searchText.isEmpty()) {
            return categoryModulesCache.getOrPut(category) {
                try {
                    ModuleManager.getModules().toList()
                        .filter { it.category == category && it.name != "ClickGUI" }
                } catch (_: Exception) { emptyList() }
            }
        }
        cacheVersionModules = cacheVersion
        categoryModulesCache.clear()
        val result = try {
            ModuleManager.getModules().toList()
                .filter { it.category == category && it.name != "ClickGUI" }
                .filter { searchText.isEmpty() || it.name.contains(searchText, ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
        categoryModulesCache[category] = result
        return result
    }

    private fun getContentHeightCached(modules: List<ClientModule>, panel: PanelData?): Float {
        if (panel != null) {
            contentHeightCache[panel]?.let { return it }
        }
        var h = 0f
        modules.forEach { mod ->
            h += ITEM_H
            h += getExpandedHeight(mod)
        }
        if (panel != null) contentHeightCache[panel] = h
        return h
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
        fillRect(ctx, 0, 0, sc, sh, 0xCC000000.toInt())
    }

    // ==================== Main render ====================
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // 快速淡入
        if (fadeAnim < 0.01f && fadeAnim > 0f) {
            fadeAnim += (1f - fadeAnim) * 0.25f
            return
        }
        fadeAnim += (1f - fadeAnim) * 0.25f

        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight
        val font = minecraft!!.font

        val isSearching = searchText.isNotEmpty()

        if (!layoutLoaded) {
            applyCachedLayout()
            layoutLoaded = true
        }

        // 检查是否需要重建
        val currentHash = searchText.hashCode() + (expandedModule?.hashCode() ?: 0)
        if (lastSearchText != searchText || needsRebuild) {
            lastSearchText = searchText
            cachedPanelData.clear()
            needsRebuild = false
            cacheVersion++
            contentHeightCache.clear()
            categoryModulesCache.clear()
        }

        // 更新面板列表（只在必要时）
        val targetPanels = mutableListOf<PanelData>()
        
        if (isSearching) {
            val w = (sc * 0.6f).coerceAtLeast(PANEL_MIN_W.toFloat()).coerceAtMost(sc.toFloat())
            val h = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val x = (sc - w) / 2f
            val y = (sh - h) / 2f
            searchPanel = searchPanel ?: PanelData(null, x, y, w, h, 0f, 0f)
            searchPanel?.let { 
                it.x = x; it.y = y; it.w = w; it.h = h 
                targetPanels.add(it)
            }
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
            panels.clear()
            panels.addAll(targetPanels)
        }

        // 绘制面板
        for (panel in panels) {
            val px = panel.x; val py = panel.y; val pw = panel.w; val ph = panel.h
            val actualHeight = if (panel.collapsed) HEADER_H + 2f else ph

            // 面板背景
            drawRoundedRect(ctx, px, py, pw, actualHeight, CORNER, BG_PANEL)
            drawRoundedRect(ctx, px, py, pw, 1f, CORNER, BORDER_LIGHT)
            drawRoundedRect(ctx, px, py + actualHeight - 1f, pw, 1f, CORNER, BORDER_LIGHT)

            var panelModules: List<ClientModule>
            if (isSearching) {
                panelModules = categories.flatMap { getCategoryModules(it) }.distinct()
                drawText(ctx, font, "Search Results", (px + 12f).toInt(), (py + 7f).toInt(), PRIMARY_LIGHT)
            } else {
                val category = panel.category ?: continue
                panelModules = getCategoryModules(category)
                
                val title = category.tag
                drawText(ctx, font, title, (px + 12f).toInt(), (py + 7f).toInt(), TEXT_WHITE)
                
                val countText = "${panelModules.size}"
                val countX = px + pw - font.width(countText) - 12f
                drawText(ctx, font, countText, countX.toInt(), (py + 7f).toInt(), TEXT_SECONDARY)
            }

            if (panel.collapsed) continue

            val headerH = HEADER_H
            val listAreaX = px + PADDING
            val listAreaW = pw - PADDING * 2 - SCROLL_W
            val listAreaY = py + headerH + 2f
            val listAreaH = ph - headerH - 6f

            val contentH = getContentHeightCached(panelModules, panel)
            val maxScroll = max(0f, contentH - listAreaH)
            panel.targetScroll = panel.targetScroll.coerceIn(0f, maxScroll)
            panel.scrollOffset += (panel.targetScroll - panel.scrollOffset) * 0.3f

            var curY = listAreaY - panel.scrollOffset

            // 绘制模块
            for (mod in panelModules) {
                val isExpanded = expandedModule == mod
                val values = if (isExpanded) getVisibleValues(mod) else emptyList()
                val modHeight = ITEM_H + values.size * SETTING_H
                val modEndY = curY + modHeight

                // 裁剪优化
                if (modEndY >= listAreaY && curY <= listAreaY + listAreaH) {
                    val isHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                            mouseY in curY.toInt()..(curY + ITEM_H).toInt()

                    // 模块行背景
                    if (isHover) {
                        fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, curY + ITEM_H, BG_ITEM_HOVER)
                    }
                    if (isExpanded) {
                        fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, curY + ITEM_H, BG_ITEM_ACTIVE)
                    }

                    // 模块名
                    val nameColor = if (mod.enabled) TEXT_WHITE else TEXT_SECONDARY
                    val nameMaxW = (listAreaW - 50).toInt()
                    drawText(ctx, font, trimText(font, mod.name, nameMaxW),
                        (listAreaX + 4f).toInt(), (curY + 6f).toInt(), nameColor)

                    // 圆润开关按钮
                    drawToggle(ctx, mod.enabled, listAreaX + listAreaW, curY)

                    // 设置项
                    if (isExpanded) {
                        var settingY = curY + ITEM_H
                        for ((v, depth) in values) {
                            if (settingY + SETTING_H >= listAreaY && settingY <= listAreaY + listAreaH) {
                                val isSettingHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                                        mouseY in settingY.toInt()..(settingY + SETTING_H).toInt()
                                if (isSettingHover) {
                                    fillRect(ctx, listAreaX, settingY, listAreaX + listAreaW, settingY + SETTING_H, BG_ITEM_HOVER)
                                }
                                renderSetting(ctx, v, depth, listAreaX, settingY, listAreaW, mouseX, mouseY, mod)
                            }
                            settingY += SETTING_H
                        }
                    }
                }

                curY += modHeight
            }

            // 滚动条
            if (contentH > listAreaH) {
                val thumbH = (listAreaH * listAreaH / contentH).coerceAtLeast(10f)
                val thumbY = listAreaY + if (maxScroll > 0f) (panel.scrollOffset / maxScroll) * (listAreaH - thumbH) else 0f
                val isScrollHover = mouseX in (listAreaX + listAreaW).toInt()..(listAreaX + listAreaW + SCROLL_W).toInt() &&
                        mouseY in thumbY.toInt()..(thumbY + thumbH).toInt()
                val thumbColor = if (isScrollHover || panel.draggingScroll) 
                    0x60FFFFFF.toInt() else 0x20FFFFFF.toInt()
                drawRoundedRect(ctx, listAreaX + listAreaW, thumbY, SCROLL_W, thumbH, 2f, thumbColor)
            }
        }

        // 搜索框
        val searchY = sh - 34f
        val searchX = (sc - 180f) / 2f
        val searchW = 180f
        drawRoundedRect(ctx, searchX, searchY, searchW, 24f, 4f, 0xCC0A0A0A.toInt())
        drawRoundedRect(ctx, searchX, searchY, searchW, 1f, 4f, BORDER_LIGHT)

        if (searchText.isEmpty()) {
            drawText(ctx, font, "Search modules...", (searchX + 10f).toInt(), (searchY + 7f).toInt(), TEXT_SECONDARY)
        } else {
            drawText(ctx, font, trimText(font, searchText, (searchW - 24).toInt()),
                (searchX + 10f).toInt(), (searchY + 7f).toInt(), TEXT_WHITE)
        }

        if (searchFocused) {
            val cursorX = searchX.toInt() + 10 + font.width(searchText)
            if (cursorX < searchX + searchW - 10) {
                val blink = System.currentTimeMillis() / 500 % 2 == 0L
                if (blink) {
                    fillRect(ctx, cursorX, searchY.toInt() + 4, cursorX + 1, searchY.toInt() + 20, TEXT_WHITE)
                }
            }
        }

        // 调色板
        val colorVal = activeColorValue
        if (colorVal != null) {
            val px = colorPickerX.coerceIn(0f, sc - PALETTE_W)
            val py = colorPickerY.coerceIn(0f, sh - PALETTE_H)

            drawRoundedRect(ctx, px, py, PALETTE_W, PALETTE_H, 4f, BG_PANEL)
            drawRoundedRect(ctx, px, py, PALETTE_W, 1f, 4f, BORDER_LIGHT)

            val currentRgb = extractColor(colorVal).rgb and 0xFFFFFF

            for (index in paletteColors.indices) {
                val row = index / PALETTE_COLS
                val col = index % PALETTE_COLS
                val cx = px + PALETTE_PAD + col * (PALETTE_CELL + PALETTE_GAP)
                val cy = py + PALETTE_PAD + row * (PALETTE_CELL + PALETTE_GAP)
                val color4b = paletteColors[index]
                drawRoundedRect(ctx, cx, cy, PALETTE_CELL, PALETTE_CELL, 2f, color4b.argb)
                if ((color4b.argb and 0xFFFFFF) == currentRgb) {
                    drawRoundedRect(ctx, cx - 1f, cy - 1f, PALETTE_CELL + 2, PALETTE_CELL + 2, 3f, 0xFFFFFFFF.toInt())
                }
            }
        }
    }

    // ==================== 圆润开关按钮 ====================
    private fun drawToggle(ctx: GuiGraphicsExtractor, enabled: Boolean, rightX: Float, y: Float) {
        val toggleX = rightX - 30f
        val toggleY = y + 4f
        val toggleW = 24f
        val toggleH = 14f
        val radius = toggleH / 2f
        
        // 背景
        val bgColor = if (enabled) TOGGLE_ON else TOGGLE_OFF
        drawRoundedRect(ctx, toggleX, toggleY, toggleW, toggleH, radius, bgColor)
        
        // 滑块 - 完美的圆形
        val knobSize = toggleH - 4f
        val knobX = if (enabled) toggleX + toggleW - knobSize - 2f else toggleX + 2f
        val knobY = toggleY + 2f
        
        // 滑块光晕
        if (enabled) {
            drawRoundedRect(ctx, knobX - 2f, knobY - 2f, knobSize + 4f, knobSize + 4f, (knobSize + 4f) / 2f, 0x206C5CE7.toInt())
        }
        
        // 滑块本身
        val knobColor = if (enabled) TOGGLE_KNOB_ON else TOGGLE_KNOB_OFF
        drawRoundedRect(ctx, knobX, knobY, knobSize, knobSize, knobSize / 2f, knobColor)
    }

    private fun applyCachedLayout() {
        try {
            val saved = cachedLayout
            for ((tag, state) in saved.panels) {
                val category = categories.find { it.tag == tag }
                if (category != null) {
                    val panel = panels.find { it.category == category }
                    if (panel != null) {
                        panel.x = state.x.toFloat()
                        panel.y = state.y.toFloat()
                        panel.collapsed = state.collapsed
                        panel.targetScroll = state.scroll
                        panel.scrollOffset = state.scroll
                    } else {
                        val newPanel = PanelData(
                            category,
                            state.x.toFloat(),
                            state.y.toFloat(),
                            PANEL_MIN_W.toFloat(),
                            PANEL_MAX_H.toFloat(),
                            collapsed = state.collapsed
                        )
                        newPanel.targetScroll = state.scroll
                        newPanel.scrollOffset = state.scroll
                        panels.add(newPanel)
                    }
                }
            }

            saved.expandedModule?.let { name ->
                expandedModule = ModuleManager.getModuleByName(name)
            }

            for (key in saved.collapsedGroups) {
                val idx = key.indexOf(':')
                if (idx <= 0) continue
                val mod = ModuleManager.getModuleByName(key.substring(0, idx)) ?: continue
                val groupName = key.substring(idx + 1)
                for ((v, _) in getVisibleValues(mod)) {
                    if (v.name == groupName && isGroupValue(v)) {
                        collapsedGroups.add(v)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 滑条布局 ====================
    private data class SliderLayout(
        val sliderX: Int,
        val sliderW: Int,
        val valText: String,
        val valX: Int,
        val fv: Float,
        val minV: Float,
        val maxV: Float
    )

    private fun computeSliderLayout(
        font: Font,
        v: Value<*>,
        actual: Any?,
        x: Float, w: Float, indent: Float
    ): SliderLayout {
        var fv = 0f
        var minV = 0f
        var maxV = 100f
        if (actual is Number) {
            fv = actual.toFloat()
            if (v is RangedValue<*>) {
                minV = (v.range.start as? Number)?.toFloat() ?: 0f
                maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
            }
        }

        val rightEdge = (x + w - 2).toInt()
        val sliderW = 36
        val minSliderX = (x + 6 + indent).toInt() + 45
        val maxValW = (rightEdge - minSliderX - 3 - sliderW).coerceIn(16, 60)
        val valText = trimText(font, "%.1f".format(fv), maxValW)
        val valW = font.width(valText).coerceAtMost(maxValW)
        val valX = rightEdge - valW
        val sliderX = valX - 3 - sliderW

        return SliderLayout(sliderX, sliderW, valText, valX, fv, minV, maxV)
    }

    // ==================== Setting row renderer ====================
    private fun renderSetting(
        ctx: GuiGraphicsExtractor, v: Value<*>, depth: Int,
        x: Float, y: Float, w: Float, mouseX: Int, mouseY: Int,
        mod: ClientModule
    ) {
        val font = minecraft!!.font
        val indent = depth * SETTING_INDENT

        if (depth > 0) {
            val lineX = (x + 4f + indent).toInt()
            fillRect(ctx, lineX, y.toInt() + 2, lineX + 1, y.toInt() + SETTING_H.toInt() - 2, 0x206C5CE7.toInt())
        }

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
                    drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 5f).toInt(), TEXT_SECONDARY)
                    val valMaxW = (x + w - valueX - 2).toInt().coerceAtLeast(10)
                    drawText(ctx, font, trimText(font, getDisplayValue(v), valMaxW), valueX, (y + 5f).toInt(), TEXT_DIM)
                }
            }
            isGroup -> {
                val isCollapsed = collapsedGroups.contains(v)
                val arrow = if (isCollapsed) "▶" else "▼"
                val color = if (isCollapsed) TEXT_SECONDARY else PRIMARY_LIGHT
                drawText(ctx, font, "$arrow ${trimText(font, v.name, (w - 20 - indent).toInt())}",
                    labelX, (y + 5f).toInt(), color)
            }
            actual is Boolean -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_SECONDARY)
                val status = if (actual) "ON" else "OFF"
                drawText(ctx, font, status, valueX, (y + 5f).toInt(), if (actual) PRIMARY_LIGHT else TEXT_DIM)
            }
            isBindValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_SECONDARY)
                val isListening = listeningValue == v
                val bindStr = trimText(font, formatBindValue(v), (w - 56 - indent).toInt())
                val display = if (isListening) "[...]" else bindStr
                drawText(ctx, font, display, valueX, (y + 5f).toInt(), if (isListening) PRIMARY_LIGHT else TEXT_DIM)
            }
            isSliderValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 3f).toInt(), TEXT_SECONDARY)

                val layout = computeSliderLayout(font, v, actual, x, w, indent)
                val sliderY = y.toInt() + 8
                val progress = if (layout.maxV > layout.minV) {
                    ((layout.fv - layout.minV) / (layout.maxV - layout.minV)).coerceIn(0f, 1f)
                } else 0f

                drawRoundedRect(ctx, layout.sliderX.toFloat(), sliderY.toFloat(), layout.sliderW.toFloat(), 2f, 1f, 0x30FFFFFF.toInt())
                if (progress > 0) {
                    drawRoundedRect(ctx, layout.sliderX.toFloat(), sliderY.toFloat(), (layout.sliderW * progress).toFloat(), 2f, 1f, PRIMARY)
                }
                val knobX = layout.sliderX + (layout.sliderW * progress)
                drawRoundedRect(ctx, knobX - 2, (sliderY - 2).toFloat(), 4f, 6f, 2f, 0xFFFFFFFF.toInt())

                drawText(ctx, font, layout.valText, layout.valX, (y + 3f).toInt(), TEXT_SECONDARY)
            }
            isColorValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_SECONDARY)
                val color = extractColor(v)
                drawRoundedRect(ctx, valueX.toFloat(), y + 3f, 10f, 10f, 2f, color.rgb)
                if (activeColorValue == v) {
                    drawRoundedRect(ctx, valueX - 0.5f, y + 2.5f, 11f, 11f, 3f, 0xFFFFFFFF.toInt())
                }
            }
            else -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW),
                    labelX, (y + 5f).toInt(), TEXT_SECONDARY)
                val valMaxW = (x + w - valueX - 2).toInt().coerceAtLeast(10)
                drawText(ctx, font, trimText(font, getDisplayValue(v), valMaxW),
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
        drawText(ctx, font, trimText(font, v.name, nameMaxW), labelX, (y + 5f).toInt(), TEXT_SECONDARY)

        val dotSize = 3
        val dotGap = 2
        val nameGap = 3
        val nameW = font.width(v.name)
        var drawX = labelX + nameW + 8
        val drawY = y.toInt() + 8
        val maxX = (x + w - 4).toInt()

        for (const in constants) {
            val displayName = const.toString()
            val isActive = displayName == current.name
            val cNameW = font.width(displayName)

            val neededW = dotSize + dotGap + cNameW + nameGap + 4
            if (drawX + neededW > maxX) {
                if (drawX + dotSize <= maxX) {
                    drawRoundedRect(ctx, drawX.toFloat(), drawY.toFloat(), dotSize.toFloat(), dotSize.toFloat(), 1.5f,
                        if (isActive) PRIMARY else 0x30FFFFFF.toInt())
                }
                break
            }

            drawRoundedRect(ctx, drawX.toFloat(), drawY.toFloat(), dotSize.toFloat(), dotSize.toFloat(), 1.5f,
                if (isActive) PRIMARY else 0x30FFFFFF.toInt())
            drawX += dotSize + dotGap

            drawText(ctx, font, displayName, drawX, (y + 5f).toInt(),
                if (isActive) TEXT_WHITE else TEXT_DIM)
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

        val searchY = sh - 34f
        val searchX = (sc - 180f) / 2f
        val searchW = 180f
        if (mx in searchX.toInt()..(searchX + searchW).toInt() &&
            my in searchY.toInt()..(searchY + 24f).toInt()) {
            searchFocused = true
            return true
        }
        searchFocused = false

        val colorVal = activeColorValue
        if (colorVal != null) {
            val px = colorPickerX.coerceIn(0f, sc - PALETTE_W)
            val py = colorPickerY.coerceIn(0f, sh - PALETTE_H)
            if (mx in px.toInt()..(px + PALETTE_W).toInt() &&
                my in py.toInt()..(py + PALETTE_H).toInt()) {
                val col = ((mx - px - PALETTE_PAD) / (PALETTE_CELL + PALETTE_GAP)).toInt()
                val row = ((my - py - PALETTE_PAD) / (PALETTE_CELL + PALETTE_GAP)).toInt()
                val index = row * PALETTE_COLS + col
                if (index in paletteColors.indices) {
                    trySetValue(colorVal, paletteColors[index])
                    activeColorValue = null
                }
                return true
            }
            activeColorValue = null
        }

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
            val headerH = HEADER_H
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
        val listAreaY = panel.y + HEADER_H + 2f
        val listAreaH = panel.h - HEADER_H - 6f

        // 滚动条点击
        if (mx in (listAreaX + listAreaW).toInt()..(listAreaX + listAreaW + SCROLL_W).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {
            val modules = getModulesForPanel(panel)
            val contentH = getContentHeight(modules)
            if (contentH > listAreaH) {
                panel.draggingScroll = true
                return true
            }
        }

        // 模块列表点击
        if (mx in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {

            val modules = getModulesForPanel(panel)
            var curY = listAreaY - panel.scrollOffset

            for (mod in modules) {
                val isExpanded = expandedModule == mod
                val values = if (isExpanded) getVisibleValues(mod) else emptyList()
                val modHeight = ITEM_H + values.size * SETTING_H

                // 检查是否点击到模块行
                if (my in curY.toInt()..(curY + ITEM_H).toInt()) {
                    // 检查是否点击到开关区域
                    val toggleX = (listAreaX + listAreaW - 30f).toInt()
                    if (mx in toggleX..(toggleX + 24)) {
                        // 点击开关 - 切换模块
                        if (mod.name != "ClickGUI") {
                            try { mod.enabled = !mod.enabled } catch (_: Exception) {}
                            saveLayout()
                            return true
                        }
                    }
                    
                    when (btn) {
                        0 -> {
                            if (mod.name != "ClickGUI") {
                                try { mod.enabled = !mod.enabled } catch (_: Exception) {}
                            }
                        }
                        1 -> { expandedModule = if (expandedModule == mod) null else mod }
                    }
                    saveLayout()
                    return true
                }

                // 检查设置项点击
                if (isExpanded) {
                    var settingY = curY + ITEM_H
                    for ((v, depth) in values) {
                        if (my in settingY.toInt()..(settingY + SETTING_H).toInt()) {
                            val actual = getActualValue(v)
                            if (actual is Enum<*> && btn == 0) {
                                val constants: List<Any> = try {
                                    (actual.javaClass.enumConstants?.toList() ?: emptyList()) as List<Any>
                                } catch (_: Exception) { emptyList() }
                                if (constants.size >= 2) {
                                    handleModeClick(v, mx.toFloat(), listAreaX, listAreaW, 8f, constants, actual)
                                    saveLayout()
                                    return true
                                }
                            }
                            handleSettingClick(v, btn, mx.toFloat(), settingY, listAreaW, listAreaX, panel, depth * SETTING_INDENT)
                            saveLayout()
                            return true
                        }
                        settingY += SETTING_H
                    }
                }

                curY += modHeight
            }
        }

        return true
    }

    // ==================== 模式点击处理 ====================
    private fun handleModeClick(
        v: Value<*>, mx: Float,
        x: Float, w: Float, indent: Float,
        constants: List<Any>, current: Enum<*>
    ) {
        val font = minecraft!!.font
        val dotSize = 3
        val dotGap = 2
        val nameGap = 3

        val labelX = (x + 6 + indent).toInt()
        val nameW = font.width(v.name)
        var drawX = labelX + nameW + 8
        val maxX = (x + w - 4).toInt()

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

        val currentIdx = constants.indexOfFirst { it.toString() == current.name }
        if (currentIdx >= 0) {
            val nextIdx = (currentIdx + 1) % constants.size
            trySetValue(v, constants[nextIdx])
        }
    }

    // ==================== 鼠标拖拽 ====================
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val mx = event.x().toFloat()
        val my = event.y().toFloat()

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
                val listAreaH = panel.h - HEADER_H - 6f
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
    private fun handleSettingClick(
        v: Value<*>, btn: Int, mx: Float, y: Float, w: Float, x: Float, panel: PanelData,
        indent: Float = 0f
    ) {
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

        if (isColorValue(v)) {
            if (activeColorValue == v) {
                activeColorValue = null
            } else {
                activeColorValue = v
                colorPickerX = x + w - PALETTE_W - 2f
                colorPickerY = y + SETTING_H + 2f
            }
            return
        }

        if (isSliderValue(v)) {
            val font = minecraft!!.font
            val layout = computeSliderLayout(font, v, actual, x, w, indent)
            val hitStart = layout.sliderX - 4
            val hitEnd = layout.sliderX + layout.sliderW + 4
            if (mx.toInt() in hitStart..hitEnd) {
                val progress = ((mx.toInt() - layout.sliderX).toFloat() / layout.sliderW).coerceIn(0f, 1f)
                val newValue = layout.minV + (layout.maxV - layout.minV) * progress

                when (actual) {
                    is Float -> trySetValue(v, newValue)
                    is Double -> trySetValue(v, newValue.toDouble())
                    is Int -> trySetValue(v, newValue.toInt())
                    is Long -> trySetValue(v, newValue.toLong())
                }

                sliderContext = SliderContext(
                    value = v,
                    panel = panel,
                    sliderX = layout.sliderX,
                    sliderY = y.toInt() + 8,
                    sliderW = layout.sliderW,
                    min = layout.minV,
                    max = layout.maxV
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
            saveLayout()
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
        layoutLoaded = false
        needsRebuild = true
    }

    override fun removed() {
        saveLayout()
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
        visibleValuesCache[module]?.let { return it }
        
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
        
        visibleValuesCache[module] = result
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
        if (actual is Color4b) return Color(actual.argb, true)
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

    private data class PanelState(val x: Int, val y: Int, val collapsed: Boolean, val scroll: Float)

    private data class LayoutState(
        val panels: Map<String, PanelState>,
        val expandedModule: String?,
        val collapsedGroups: List<String>
    )

    private fun saveLayout() {
        try {
            if (searchText.isNotEmpty()) return
            
            val panelsJson = panels.filter { it.category != null }.joinToString(",") { p ->
                val tag = p.category?.tag ?: ""
                """{"tag":"$tag","x":${p.x.toInt()},"y":${p.y.toInt()},"collapsed":${p.collapsed},"scroll":${p.scrollOffset}}"""
            }

            val expandedName = expandedModule?.name ?: ""
            val groups = mutableListOf<String>()
            try {
                for (mod in ModuleManager.getModules()) {
                    for ((v, _) in getVisibleValues(mod)) {
                        if (collapsedGroups.contains(v)) {
                            groups += "${mod.name}:${v.name}"
                        }
                    }
                }
            } catch (_: Exception) {
            }
            val groupsJson = groups.joinToString(",") { "\"$it\"" }

            val sb = StringBuilder()
            sb.append("{\"panels\":[").append(panelsJson).append("],")
            sb.append("\"expanded\":\"").append(expandedName).append("\",")
            sb.append("\"groups\":[").append(groupsJson).append("]}")

            val file = getLayoutFile()
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadLayout(): LayoutState {
        return try {
            val file = getLayoutFile()
            if (!file.exists()) {
                return LayoutState(emptyMap(), null, emptyList())
            }
            
            val content = file.readText()

            val panels = mutableMapOf<String, PanelState>()
            val panelRegex = Regex(
                """\{"tag":"([^"]+)","x":(-?[0-9]+),"y":(-?[0-9]+),"collapsed":(true|false)(,"scroll":(-?[0-9.]+))?\}"""
            )
            for (match in panelRegex.findAll(content)) {
                val tag = match.groupValues[1]
                val x = match.groupValues[2].toInt()
                val y = match.groupValues[3].toInt()
                val collapsed = match.groupValues[4].toBoolean()
                val scroll = match.groupValues[6].takeIf { it.isNotEmpty() }?.toFloat() ?: 0f
                panels[tag] = PanelState(x, y, collapsed, scroll)
            }

            val expanded = Regex(""""expanded":"([^"]*)"""").find(content)
                ?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

            val groups = mutableListOf<String>()
            Regex(""""groups":\[(.*?)\]""").find(content)?.groupValues?.get(1)?.let { gs ->
                Regex(""""([^"]+)"""").findAll(gs).forEach { groups.add(it.groupValues[1]) }
            }

            LayoutState(panels, expanded, groups)
        } catch (e: Exception) {
            LayoutState(emptyMap(), null, emptyList())
        }
    }
}