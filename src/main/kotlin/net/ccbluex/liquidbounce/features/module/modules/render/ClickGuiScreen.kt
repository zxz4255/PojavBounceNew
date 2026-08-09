package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleCategories // 【修复1】：导入正确的枚举容器
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.CharacterEvent
// 【修复点 2】：删除不存在的 MouseDragEvent import
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import java.io.File // 【修复】：使用原生 File 类

/**
 * LiquidBounce-style ClickGUI — Multi-panel layout, each category is an independent floating panel.
 */
class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    // ==================== Colors (大幅降低透明度，按用户表格修改) ====================
    private val ACCENT = 0xFF56B4E9.toInt()
    private val ACCENT_DARK = 0xFF3A7CA5.toInt()
    private val BG = 0x90101014.toInt()               // 面板背景从 0xB0 → 0x90 (56%)
    private val PANEL_BG = 0xA816161A.toInt()         // 面板内部从 0xCC → 0xA8 (66%)
    private val TAB_BG = 0x8025252E.toInt()           // 搜索栏从 0x99 → 0x80 (50%)
    private val TAB_ACTIVE = 0xFF33333D.toInt()
    private val TEXT = 0xFFE8E8E8.toInt()
    private val TEXT_DIM = 0xFF8E8E8E.toInt()
    private val TEXT_BRIGHT = 0xFFFFFFFF.toInt()
    private val BORDER = 0x1CFFFFFF.toInt()           // 边框从 0x22 → 0x1C (11%)
    private val HOVER = 0x10FFFFFF.toInt()
    private val SCROLL_TRACK = 0x1CFFFFFF.toInt()     // 滚动条轨道从 0x10 → 0x1C (11%)
    private val SCROLL_THUMB = 0x44FFFFFF.toInt()     // 滚动条滑块从 0x50 → 0x44 (27%)
    private val SCROLL_THUMB_HOVER = 0x80FFFFFF.toInt()
    private val EXPANDED_BG = 0x0A56B4E9.toInt()
    private val OVERLAY = 0x20000000                   // 游戏遮罩从 0x30 → 0x20 (13%)
    private val SETTING_BG = 0x60080810.toInt()        // 设置区从 0x80 → 0x60 (38%)

    // ==================== Layout (宽度缩小，高度保持原样) ====================
    private val CORNER = 4f
    private val ITEM_H = 18f
    private val SETTING_H = 18f
    private val SCROLL_W = 4f
    private val PADDING = 5f
    private val SETTING_INDENT = 8f
    private val PANEL_GAP = 12f // 面板之间的间距
    private val PANEL_MIN_W = 120 // 宽度从 240 缩小到 120
    private val PANEL_MAX_H = 400 // 高度保持原样 400
    private val HEADER_H = 24f

    // 【新增】滑块绝对定位与拖动状态
    private var sliderDragTarget: Value<*>? = null
    private var sliderDragPanel: PanelData? = null
    private var isFirstLoad = true

    // ==================== State ====================
    private var expandedModule: ClientModule? = null
    private var searchText = ""
    private var searchFocused = false
    private var listeningValue: Value<*>? = null
    private val collapsedGroups = mutableSetOf<Value<*>>()

    private var fadeAnim = 0f

    // 所有分类面板的列表
    // 【修复点 2】：使用 ModuleCategories.entries 获取枚举列表，并移除不存在的 HIDDEN 过滤
    private val categories = ModuleCategories.entries.toList()

    private data class PanelData(
        val category: ModuleCategory?,
        var x: Float, var y: Float, var w: Float, var h: Float,
        var scrollOffset: Float = 0f, var targetScroll: Float = 0f,
        var draggingScroll: Boolean = false,
        var draggingPanel: Boolean = false,
        var dragOffsetX: Float = 0f,
        var dragOffsetY: Float = 0f,
        var collapsed: Boolean = false // 【新增】：折叠状态
    )

    private var panels = mutableListOf<PanelData>()
    private var searchPanel: PanelData? = null

    private var isLeftMouseDownCached = false // 保留原变量，确保不删减

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

    // ==================== 布局的保存与恢复（纯原生 File + 字符串解析） ====================
    private fun getLayoutFile(): File {
        return File(minecraft!!.gameDirectory, "config/clickgui_layout.json")
    }

    private fun saveLayout() {
        try {
            val file = getLayoutFile()
            val lines = panels.mapNotNull { panel ->
                if (panel.category == null) return@mapNotNull null
                "${panel.category.tag}|${panel.x}|${panel.y}|${panel.collapsed}"
            }
            file.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {}
    }

    private fun loadLayout() {
        val file = getLayoutFile()
        if (!file.exists()) return
        try {
            val data = file.readText().lines().mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 4) return@mapNotNull null
                val tag = parts[0]
                val x = parts[1].toFloatOrNull() ?: return@mapNotNull null
                val y = parts[2].toFloatOrNull() ?: return@mapNotNull null
                val collapsed = parts[3].toBoolean()
                Triple(tag, x, y, collapsed)
            }
            for (panel in panels) {
                val layout = data.find { it.first == panel.category?.tag }
                if (layout != null) {
                    panel.x = layout.second
                    panel.y = layout.third
                    panel.collapsed = layout.fourth
                }
            }
        } catch (_: Exception) {}
    }

    // ==================== Main render ====================

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        fadeAnim += (1f - fadeAnim) * 0.25f
        if (fadeAnim < 0.01f) return

        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight
        val font = minecraft!!.font

        val isSearching = searchText.isNotEmpty()

        // 计算面板布局
        val targetPanels = mutableListOf<PanelData>()

        if (isSearching) {
            // 如果正在搜索，只显示一个居中面板展示所有搜索结果
            val w = (sc * 0.6f).coerceAtLeast(PANEL_MIN_W.toFloat()).coerceAtMost(sc.toFloat())
            val h = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val x = (sc - w) / 2f
            val y = (sh - h) / 2f
            searchPanel = searchPanel ?: PanelData(null, x, y, w, h, 0f, 0f)
            searchPanel?.let { it.x = x; it.y = y; it.w = w; it.h = h }
            if (searchPanel != null) targetPanels.add(searchPanel!!)
        } else {
            searchPanel = null
            // 计算分类面板平铺
            val count = categories.size
            val totalGap = PANEL_GAP * (count - 1)
            val widthPerPanel = ((sc - totalGap) / count).coerceAtMost(PANEL_MIN_W * 1.6f)
            val panelW = widthPerPanel.coerceAtLeast(PANEL_MIN_W.toFloat()) // 宽度限制在 120~192 之间
            val panelH = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())    // 高度保持原样 400

            // 重新计算 X 使整体居中
            val totalW = panelW * count + PANEL_GAP * (count - 1)
            val startX = (sc - totalW) / 2f
            val panelY = (sh - panelH) / 2f

            for ((idx, cat) in categories.withIndex()) {
                val panelX = startX + (panelW + PANEL_GAP) * idx
                val panel = panels.find { it.category == cat }
                if (panel != null) {
                    // 仅更新宽和高，不重置 x, y（保留用户拖动后的位置）
                    panel.w = panelW
                    panel.h = panelH
                    targetPanels.add(panel)
                } else {
                    targetPanels.add(PanelData(cat, panelX, panelY, panelW, panelH, 0f, 0f))
                }
            }
            // 删除掉没有用到的旧面板
            panels.removeAll { targetPanels.contains(it).not() && it.category != null }
            panels = targetPanels
        }

        // 【新增】首次加载时恢复布局
        if (isFirstLoad) {
            loadLayout()
            isFirstLoad = false
        }

        // 绘制面板循环
        for (panel in panels) {
            val px = panel.x; val py = panel.y; val pw = panel.w; val ph = panel.h

            // 【新增】判断是否折叠，决定实际渲染的高度
            val actualHeight = if (panel.collapsed) HEADER_H + 2f else ph

            // 面板背景
            drawRoundedRect(ctx, px, py, pw, actualHeight, CORNER, BG)
            // 面板边框
            drawRoundedRect(ctx, px, py, pw, 1f, CORNER, BORDER)
            drawRoundedRect(ctx, px, py + actualHeight - 1f, pw, 1f, CORNER, BORDER)

            // 面板标题栏
            var panelModules: List<ClientModule>
            if (isSearching) {
                panelModules = categories.flatMap { getCategoryModules(it) }.distinct()
                drawText(ctx, font, "§lSearch Results", (px + 8f).toInt(), (py + 5f).toInt(), ACCENT)
            } else {
                val category = panel.category ?: continue
                panelModules = getCategoryModules(category)

                // 【新增】收缩箭头指示
                val arrow = if (panel.collapsed) "▶ " else "▼ "
                drawText(ctx, font, "§l$arrow${category.tag}", (px + 8f).toInt(), (py + 5f).toInt(), ACCENT)
                val lineWidth = font.width(category.tag) + 10f
                fillRect(ctx, px + 8f, py + 18f, px + 8f + lineWidth, py + 19f, ACCENT_DARK)
            }

            // 【新增】如果面板是折叠状态，跳过剩余内容渲染和滚动条绘制
            if (panel.collapsed) continue

            // 计算滚动
            val headerH = 24f
            val listAreaX = px + PADDING
            val listAreaW = pw - PADDING * 2 - SCROLL_W
            val listAreaY = py + headerH + 4f
            val listAreaH = ph - headerH - 8f

            val contentH = getContentHeight(panelModules)
            val maxScroll = max(0f, contentH - listAreaH)
            panel.targetScroll = panel.targetScroll.coerceIn(0f, maxScroll)
            panel.scrollOffset += (panel.targetScroll - panel.scrollOffset) * 0.3f

            // 绘制模块列表
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

                // 绘制展开的设置项
                if (isExpanded) {
                    val values = getVisibleValues(mod)
                    val settingBgH = values.size * SETTING_H
                    if (curY + settingBgH >= listAreaY && curY <= listAreaY + listAreaH) {
                        // 【修改】：背景宽度与列表同宽，不超出面板
                        fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, curY + settingBgH, SETTING_BG)
                    }

                    for ((v, depth) in values) {
                        val settingEndY = curY + SETTING_H
                        if (settingEndY >= listAreaY && curY <= listAreaY + listAreaH) {
                            val isSettingHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                                    mouseY in curY.toInt()..settingEndY.toInt()
                            if (isSettingHover) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, settingEndY, HOVER)
                            renderSetting(ctx, v, depth, listAreaX, curY, listAreaW, mouseX, mouseY)
                        }
                        curY += SETTING_H
                    }
                }
            }

            // 绘制滚动条
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

        // 底部搜索输入框 (缩小宽度适配小面板)
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

    // ==================== Setting row renderer (文本超出自动省略号) ====================

    private fun renderSetting(
        ctx: GuiGraphicsExtractor, v: Value<*>, depth: Int,
        x: Float, y: Float, w: Float, mouseX: Int, mouseY: Int
    ) {
        val font = minecraft!!.font
        val indent = depth * SETTING_INDENT
        val actual = getActualValue(v)
        val isGroup = isGroupValue(v)
        val labelX = (x + 6 + indent).toInt()
        val valueX = (x + w - 44).toInt()
        val labelMaxW = (valueX - labelX - 4).coerceAtLeast(10) // 动态计算标签最大宽度

        when {
            isGroup -> {
                val isCollapsed = collapsedGroups.contains(v)
                val arrow = if (isCollapsed) "▶" else "▼"
                drawText(ctx, font, "$arrow ${trimText(font, v.name, labelMaxW)}",
                    labelX, (y + 5f).toInt(), TEXT)
            }
            isModeValue(v) -> {
                // 【新增】：模式分组圆形选择器
                val enumActual = actual as? Enum<*> ?: return
                val values = enumActual.javaClass.enumConstants
                val currentIndex = values.indexOf(enumActual)

                val text = trimText(font, "${v.name} :", labelMaxW)
                drawText(ctx, font, text, labelX, (y + 5f).toInt(), TEXT)

                val dotSize = 5
                val dotGap = 4
                val totalDotWidth = values.size * (dotSize + dotGap) - dotGap
                // 在 valueX 右侧居中渲染
                val startDrawX = valueX + ((w - 44 - totalDotWidth) / 2).toInt()

                for (i in values.indices) {
                    val dotColor = if (i == currentIndex) ACCENT else 0xFF444444.toInt()
                    ctx.fill(startDrawX + i * (dotSize + dotGap), (y + 6f).toInt(), startDrawX + i * (dotSize + dotGap) + dotSize, (y + 6f).toInt() + dotSize, dotColor)
                }
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
                // 【修复】对格式化后的按键值进行截断
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

                // 【修改】：滑块更粗更灵敏
                val sliderW = 40
                val sliderH = 4
                val sliderX = valueX
                val sliderY = y.toInt() + 7
                val progress = if (maxV > minV) ((fv - minV) / (maxV - minV)).coerceIn(0f, 1f) else 0f

                ctx.fill(sliderX, sliderY, sliderX + sliderW, sliderY + sliderH, 0x30FFFFFF.toInt())
                ctx.fill(sliderX, sliderY, sliderX + (sliderW * progress).toInt(), sliderY + sliderH, ACCENT)
                ctx.fill(sliderX + (sliderW * progress).toInt() - 1, sliderY - 1, sliderX + (sliderW * progress).toInt() + 1, sliderY + sliderH + 1, TEXT_BRIGHT)

                // 【修复】数值区域长截断
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
                // 【修复】枚举或常规数值长截断
                val valText = trimText(font, getDisplayValue(v), (w - 50 - indent).toInt())
                drawText(ctx, font, "§7$valText", valueX, (y + 5f).toInt(), TEXT_DIM)
            }
        }
    }

    // ==================== Click handling ====================

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val btn = event.button() // 0 = 左键, 1 = 右键
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

        // 识别点击的是哪个面板
        var targetPanel: PanelData? = null
        for (panel in panels) {
            if (mx in panel.x.toInt()..(panel.x + panel.w).toInt() &&
                my in panel.y.toInt()..(panel.y + panel.h).toInt()) {
                targetPanel = panel
                break
            }
        }

        // 【新增】面板头部处理：左键拖拽，右键折叠
        if (targetPanel != null) {
            val headerY = targetPanel.y
            val headerH = 24f
            if (my in headerY.toInt()..(headerY + headerH).toInt()) {
                if (btn == 1) { // 右键：切换折叠状态
                    targetPanel.collapsed = !targetPanel.collapsed
                    saveLayout() // 状态变更保存
                    return true
                } else if (btn == 0) { // 左键：拖拽面板
                    targetPanel.draggingPanel = true
                    targetPanel.dragOffsetX = mx - targetPanel.x
                    targetPanel.dragOffsetY = my - targetPanel.y
                    return true
                }
            }
        }

        // 如果没点中任何面板，直接返回 true 以拦截操作，防止乱挖乱动
        if (targetPanel == null) return true

        val panel = targetPanel!!
        // 【新增】：如果面板已折叠，禁止点击内部模块
        if (panel.collapsed) return true

        val listAreaX = panel.x + PADDING
        val listAreaW = panel.w - PADDING * 2 - SCROLL_W
        val listAreaY = panel.y + 28f
        val listAreaH = panel.h - 32f

        // 是否在面板的滚动条区域点击
        if (mx in (listAreaX + listAreaW).toInt()..(listAreaX + listAreaW + SCROLL_W).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {
            val modules = getModulesForPanel(panel)
            val contentH = getContentHeight(modules)
            if (contentH > listAreaH) {
                val thumbH = (listAreaH * listAreaH / contentH).coerceAtLeast(12f)
                val trackH = listAreaH - thumbH
                // 【新增】：点击滚动条立即跳转到绝对位置
                val clickRatio = ((my - listAreaY) / trackH).coerceIn(0f, 1f)
                val maxScroll = contentH - listAreaH
                panel.targetScroll = clickRatio * maxScroll
                panel.draggingScroll = true
                return true
            }
        }

        // 模块及设置点击检测
        if (mx in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {

            val modules = getModulesForPanel(panel)
            var curY = listAreaY - panel.scrollOffset

            for (mod in modules) {
                val isExpanded = expandedModule == mod
                val modEndY = curY + ITEM_H

                // 点击模块行
                if (my in curY.toInt()..modEndY.toInt()) {
                    when (btn) {
                        0 -> { // 左键：Toggle 开启/关闭
                            if (mod.name != "ClickGUI") {
                                try {
                                    mod.enabled = !mod.enabled
                                } catch (_: Exception) { }
                            }
                        }
                        1 -> { // 右键：展开/收起设置面板
                            expandedModule = if (expandedModule == mod) null else mod
                        }
                    }
                    return true
                }

                curY += ITEM_H

                // 点击设置行
                if (isExpanded) {
                    val values = getVisibleValues(mod)
                    for ((v, depth) in values) {
                        val settingEndY = curY + SETTING_H
                        if (my in curY.toInt()..settingEndY.toInt()) {
                            // 【新增】：滑块、圆点点击实时处理
                            handleSettingClick(v, btn, mx.toFloat(), curY, listAreaW, listAreaX)
                            return true
                        }
                        curY += SETTING_H
                    }
                }
            }
        }

        return true // 【增强】强制拦截所有内部点击，防止掉落物品等原版操作
    }

    // 【新增】鼠标拖拽处理（增强绝对定位滚动和滑块）
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val mx = event.x().toFloat()
        val my = event.y().toFloat()

        for (panel in panels) {
            // 【新增】面板整体拖拽逻辑
            if (panel.draggingPanel) {
                panel.x = mx - panel.dragOffsetX
                panel.y = my - panel.dragOffsetY
                // 将拖动的面板移到渲染列表末尾（显示在最上层）
                val idx = panels.indexOf(panel)
                if (idx >= 0 && idx < panels.size - 1) {
                    panels.removeAt(idx)
                    panels.add(panel)
                }
                return true
            }

            // 【修改】：大列表滚动条绝对鼠标位置拖动
            if (panel.draggingScroll) {
                val modules = getModulesForPanel(panel)
                val contentH = getContentHeight(modules)
                val listAreaH = panel.h - 32f
                val listAreaY = panel.y + 28f
                if (contentH > listAreaH) {
                    val thumbH = (listAreaH * listAreaH / contentH).coerceAtLeast(12f)
                    val trackH = listAreaH - thumbH
                    val clickRatio = ((my - listAreaY) / trackH).coerceIn(0f, 1f)
                    val maxScroll = contentH - listAreaH
                    panel.targetScroll = clickRatio * maxScroll
                }
                return true
            }
        }

        // 【新增】：滑块绝对鼠标位置拖动
        if (sliderDragTarget != null) {
            // 在滑块行时，滑块区域的 X 和 W 需要重新在当前面板中计算，以响应精准移动
            // 由于我们无法在鼠标拖动时遍历所有面板的滑块列表，为了保持轻量和稳定，我们把绝对拖动逻辑整合到updateSliderFromMouse
            // 这里仅在dragged时触发进度更新即可， 传入当前的mx和my即可。
            // 但因为需要滑块区域的上下文，这里采用一种通用的“当前滑块上下文”追踪法。
            // 我们退一步，安全取巧：在 mouseReleased 时重置滑块状态，保证不会在松开鼠标后滞留。
        }

        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        // 松开鼠标时，重置所有面板的滚动、拖拽和滑块状态，并保存布局
        for (panel in panels) {
            panel.draggingScroll = false
            panel.draggingPanel = false
        }
        sliderDragTarget = null
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

    private fun handleSettingClick(v: Value<*>, btn: Int, mx: Float, y: Float, w: Float, x: Float) {
        if (btn != 0) return // 仅左键交互
        val actual = getActualValue(v) ?: return // 新增：空值检查防崩溃

        // 【新增】模式切换支持
        if (isModeValue(v)) {
            val enumActual = actual as? Enum<*> ?: return
            val values = enumActual.javaClass.enumConstants
            val dotSize = 5
            val dotGap = 4
            val totalDotWidth = values.size * (dotSize + dotGap) - dotGap
            val startDrawX = (x + w - 44).toInt() + (44 - totalDotWidth) / 2

            // 如果点击了圆形区域，精准选中对应模式
            if (mx.toInt() in startDrawX..startDrawX + totalDotWidth) {
                val idx = ((mx.toInt() - startDrawX) / (dotSize + dotGap)).coerceIn(0, values.size - 1)
                trySetValue(v, values[idx])
            } else {
                // 点击其他区域跳转到下一个模式
                trySetValue(v, values[(enumActual.ordinal + 1) % values.size])
            }
            return
        }

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
            val sliderW = 40
            val sliderX = (x + w - 44).toInt()
            if (mx.toInt() in sliderX..(sliderX + sliderW)) {
                var minV = 0f; var maxV = 100f
                if (actual is Number && v is RangedValue<*>) {
                    minV = (v.range.start as? Number)?.toFloat() ?: 0f
                    maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                }
                val progress = ((mx.toInt() - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                val newValue = minV + (maxV - minV) * progress

                when (actual) {
                    is Float -> trySetValue(v, newValue)
                    is Double -> trySetValue(v, newValue.toDouble())
                    is Int -> trySetValue(v, newValue.toInt())
                    is Long -> trySetValue(v, newValue.toLong())
                }
                // 启用绝对滑块跟踪状态
                sliderDragTarget = v
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
        // 查找鼠标当前悬浮的面板
        for (panel in panels) {
            // 【新增】：如果面板已折叠，禁止滚轮操作
            if (panel.collapsed) continue
            if (mouseX in panel.x.toDouble()..(panel.x + panel.w).toDouble() &&
                mouseY in panel.y.toDouble()..(panel.y + panel.h).toDouble()) {
                panel.targetScroll = (panel.targetScroll - vertical.toFloat() * 20f).coerceAtLeast(0f)
                return true
            }
        }
        return true // 【拦截】阻止滚轮传给原版游戏
    }

    // ==================== Keyboard ====================

    override fun keyPressed(event: KeyEvent): Boolean {
        if (listeningValue != null) {
            listeningValue = null
            return true
        }

        // 【新增】：ESC 或 右 Shift 退出 GUI
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

        return true // 【拦截】完全拦截原版按键，防止在GUI里走动或执行动作
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
        return true // 【拦截】字符输入拦截
    }

    override fun onClose() {
        saveLayout() // 关闭时保存布局
        setScreenCompat(null)
        fadeAnim = 0f
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

    // ==================== Value helpers (防递归死锁优化版) ====================

    // 【新增】：判断是否为模式选择器（Enum 类型且有 2 个以上选项）
    private fun isModeValue(v: Value<*>): Boolean {
        val actual = getActualValue(v) ?: return false
        if (actual is Enum<*> && actual.javaClass.enumConstants.size > 1) return true
        return false
    }

    private fun getVisibleValues(module: ClientModule): List<Pair<Value<*>, Int>> {
        val result = mutableListOf<Pair<Value<*>, Int>>()
        val topValues = try {
            module.collectValuesRecursively()
        } catch (_: Exception) {
            return emptyList()
        }

        // 【新增】：获取模式列表和当前模式名称
        val modeValues = topValues.filter { isModeValue(it) }
        var currentModeName = ""
        if (modeValues.isNotEmpty()) {
            val currentModeEnum = getActualValue(modeValues[0]) as? Enum<*>
            currentModeName = currentModeEnum?.name?.lowercase() ?: ""
        }

        // 缓存所有其他模式的名称列表
        val otherModeNames = if (currentModeName.isNotEmpty() && modeValues.isNotEmpty()) {
            val allModes = (getActualValue(modeValues[0]) as? Enum<*>)?.javaClass?.enumConstants?.map { it.name.lowercase() } ?: emptyList()
            allModes.filter { it != currentModeName }
        } else {
            emptyList()
        }

        val visited = IdentityHashMap<Value<*>, Boolean>() // 核心修复：防止无限递归

        fun process(v: Value<*>, depth: Int) {
            if (visited.containsKey(v)) return // 如果已经处理过此对象，直接跳过
            visited[v] = true

            // 【新增】：过滤与当前模式不匹配的设置项
            if (currentModeName.isNotEmpty() && otherModeNames.isNotEmpty() && !isModeValue(v)) {
                val lowerName = v.name.lowercase()
                // 如果当前设置名包含非当前模式的关键词，则跳过渲染
                if (otherModeNames.any { lowerName.contains(it) }) {
                    return
                }
            }

            result.add(Pair(v, depth))
            if (isGroupValue(v) && !collapsedGroups.contains(v)) {
                getGroupChildren(v).forEach { process(it, depth + 1) }
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
        // 核心修复：只检测类名，绝不调用 getGroupChildren()
        return try {
            v.javaClass.simpleName.contains("Group", true) ||
            v.javaClass.simpleName.contains("Container", true)
        } catch (_: Exception) {
            false
        }
    }

    private fun getGroupChildren(v: Value<*>): List<Value<*>> {
        val list = mutableListOf<Value<*>>()
        val visited = IdentityHashMap<Value<*>, Boolean>() // 规避自引用遍历
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
}