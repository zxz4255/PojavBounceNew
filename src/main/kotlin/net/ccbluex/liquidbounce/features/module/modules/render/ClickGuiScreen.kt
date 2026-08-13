package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import java.io.File

/**
 * LiquidBounce-style ClickGUI — Multi-panel layout, each category is an independent floating panel.
 * Dark glass theme matching reference screenshot.
 */
class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    // ==================== Colors (紫色主题, 匹配参考图) ====================
    // 开关/激活文字/蓝点 — 紫色
    private val ACCENT = 0xFF9B59D6.toInt()
    private val ACCENT_DARK = 0x669B59D6.toInt()
    // 面板背景 — 深灰色
    private val BG = 0xE01A1A22.toInt()
    private val PANEL_BG = 0xD81C1C24.toInt()
    // 未激活模块名 浅灰白
    private val TEXT get() = ModuleClickGui.getTextColor()
    // 激活文字 — 紫色(与开关一致)
    private val TEXT_BRIGHT = 0xFF9B59D6.toInt()
    // 次要文字: 纯白不透明度
    private val TEXT_DIM get() = 0xFF000000.toInt() or (ModuleClickGui.getTextColor() and 0x00FFFFFF)
    // 分类标题 — 纯白色
    private val CATEGORY_TITLE = 0xFFFFFFFF.toInt()
    private val TAB_BG = 0x8025252E.toInt()
    private val TAB_ACTIVE = 0xFF33333D.toInt()
    private val BORDER = 0x20FFFFFF.toInt()
    private val HOVER = 0x15FFFFFF.toInt()
    // 滚动条 — 紫色
    private val SCROLL_TRACK = 0x189B59D6.toInt()
    private val SCROLL_THUMB = 0x509B59D6.toInt()
    private val SCROLL_THUMB_HOVER = 0x789B59D6.toInt()
    private val EXPANDED_BG = 0x0A9B59D6.toInt()
    private val GROUP_BG = 0x0C9B59D6.toInt()
    private val GROUP_LINE = 0x149B59D6.toInt()
    private val SETTING_CHILD_BG = 0x06FFFFFF.toInt()
    // 遮罩 — 黑色 alpha=30%
    private val OVERLAY = 0x4D000000.toInt()
    private val SETTING_BG = 0x50080810.toInt()

    // ==================== Layout ====================
    private val CORNER = 8f                           // 圆角更大, 更圆润
    private val ITEM_H = 17f                          // 行高稍紧凑
    private val SETTING_H = 17f
    private val SCROLL_W = 4f                         // 滚动条宽度
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
        val max: Float,
        val rangeWidth: Float = 0f,   // 范围值宽度 (如 12~14 → 2), 拖动时保持
        val rangePoint: Int = -1      // 【修复】范围双滑块: -1=普通, 0=下限点, 1=上限点
    )
    private var sliderContext: SliderContext? = null

    // ==================== State ====================
    private var expandedModule: ClientModule? = null
    private var searchText = ""
    private var searchFocused = false
    private var listeningValue: Value<*>? = null
    private val collapsedGroups = mutableSetOf<Value<*>>()

    // ==================== 调色板 (color 选项) ====================
    private var activeColorValue: Value<*>? = null
    private var colorPickerX = 0f
    private var colorPickerY = 0f
    private var colorPickerAlpha = 255               // 当前调色板 Alpha (0..255)
    private var colorPickerAlphaDragging = false     // 正在拖动 Alpha 条
    private val PALETTE_ROWS = 9
    private val PALETTE_COLS = 5
    private val PALETTE_CELL = 10f
    private val PALETTE_GAP = 2f
    private val PALETTE_PAD = 4f
    private val ALPHA_BAR_H = 10f
    private val ALPHA_BAR_OFFSET = 6f
    private val PALETTE_W = PALETTE_COLS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP + PALETTE_PAD * 2
    private val PALETTE_H = PALETTE_ROWS * (PALETTE_CELL + PALETTE_GAP) - PALETTE_GAP + PALETTE_PAD * 2 +
        ALPHA_BAR_H + ALPHA_BAR_OFFSET

    /** 预生成调色板 */
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
    private var isFirstLoad = true

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
        // 字体缩放: 使用 pose 矩阵 translate+scale, 失败时回退到原始尺寸
        val pose = try { ctx.pose() } catch (_: Exception) { null }
        if (pose != null) {
            try {
                pose.pushMatrix()
                pose.translate(x.toFloat(), y.toFloat())
                pose.scale(TEXT_SCALE, TEXT_SCALE)
                ctx.text(font, text, 0, 0, color)
                pose.popMatrix()
                return
            } catch (_: Exception) {
                try { pose.popMatrix() } catch (_: Exception) {}
            }
        }
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

    // 字体缩放比例 (0.8 = 比默认小一点)
    private val TEXT_SCALE = 0.8f

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
                .filter { it.category == category }
                .filter { searchText.isEmpty() || it.name.contains(searchText, ignoreCase = true) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 计算展开后的总高度，Mode 竖排时高度 = (1 + constants数) * SETTING_H */
    private fun getExpandedHeight(mod: ClientModule): Float {
        if (expandedModule != mod) return 0f
        var h = 0f
        for ((v, _) in getVisibleValues(mod)) {
            val actual = getActualValue(v)
            if (v is ModeValueGroup<*>) {
                // 【修复】Mode 组: 标题行 + 每个 mode 一行
                h += max(1f, (1 + v.modes.size).toFloat()) * SETTING_H
            } else if (isEnumWithMultiple(actual)) {
                val constants = getEnumConstants(actual)
                h += max(1f, constants.size.toFloat()) * SETTING_H
            } else {
                h += SETTING_H
            }
        }
        return h
    }

    private fun getContentHeight(modules: List<ClientModule>): Float {
        var h = 0f
        modules.forEach { mod ->
            h += ITEM_H
            h += getExpandedHeight(mod)
        }
        return h
    }

    // ==================== Background (无面板外渲染层) ====================
    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // 不再绘制面板以外的全屏遮罩层
    }

    // ==================== Main render ====================
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        fadeAnim += (1f - fadeAnim) * 0.25f
        if (fadeAnim < 0.01f) return

        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight
        val font = minecraft!!.font

        val isSearching = searchText.isNotEmpty()

        val savedLayout = if (isFirstLoad) loadLayout() else LayoutState(emptyMap(), null, emptyList())
        val targetPanels = mutableListOf<PanelData>()

        if (isSearching) {
            val w = (sc * 0.6f).coerceAtLeast(PANEL_MIN_W.toFloat()).coerceAtMost(sc.toFloat())
            val h = (sh * 0.7f).coerceAtMost(PANEL_MAX_H.toFloat())
            val x = (sc - w) / 2f
            val y = (sh - h) / 2f
            searchPanel = searchPanel ?: PanelData(null, x, y, w, h, 0f, 0f)
            searchPanel?.let { it.x = x; it.y = y; it.w = w; it.h = h }
            if (searchPanel != null) targetPanels.add(searchPanel!!)
            // 【修复】搜索时不再替换 panels: 分类面板保留(位置/状态不丢),
            // 绘制/交互时只使用 searchPanel (见 currentPanels)
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
                    if (isFirstLoad) {
                        savedLayout.panels[cat.tag]?.let {
                            existingPanel.x = it.x.toFloat()
                            existingPanel.y = it.y.toFloat()
                            existingPanel.collapsed = it.collapsed
                            existingPanel.targetScroll = it.scroll
                            existingPanel.scrollOffset = it.scroll
                        }
                    }
                    existingPanel.w = panelW
                    existingPanel.h = panelH
                    targetPanels.add(existingPanel)
                } else {
                    val saved = savedLayout.panels[cat.tag]
                    val savedX = saved?.x?.toFloat() ?: panelX
                    val savedY = saved?.y?.toFloat() ?: panelY
                    val savedCollapsed = saved?.collapsed ?: false
                    val newPanel = PanelData(cat, savedX, savedY, panelW, panelH, collapsed = savedCollapsed)
                    newPanel.targetScroll = saved?.scroll ?: 0f
                    newPanel.scrollOffset = newPanel.targetScroll
                    targetPanels.add(newPanel)
                }
            }
            panels.removeAll { targetPanels.contains(it).not() && it.category != null }
            panels = targetPanels
            if (isFirstLoad) {
                savedLayout.expandedModule?.let { name ->
                    val mod = ModuleManager.getModuleByName(name)
                    // 【建议】OFF 模块不默认展开 (仅启用模块恢复展开状态)
                    expandedModule = if (mod != null && mod.enabled) mod else null
                }
                for (key in savedLayout.collapsedGroups) {
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
                isFirstLoad = false
            }
        }

        // 绘制面板循环 (【修复】搜索时只绘制 searchPanel, 分类面板保留在 panels 中)
        for (panel in if (isSearching) targetPanels else panels) {
            val px = panel.x; val py = panel.y; val pw = panel.w; val ph = panel.h
            val actualHeight = if (panel.collapsed) HEADER_H + 2f else ph

            // 面板背景 — 深色半透明
            drawRoundedRect(ctx, px, py, pw, actualHeight, CORNER, BG)

            var panelModules: List<ClientModule>
            if (isSearching) {
                panelModules = categories.flatMap { getCategoryModules(it) }.distinct()
                drawText(ctx, font, "§lSearch Results", (px + 8f).toInt(), (py + 5f).toInt(), ACCENT)
            } else {
                val category = panel.category ?: continue
                panelModules = getCategoryModules(category)
                // 分类标题 — 保持原天蓝色不变
                val arrow = if (panel.collapsed) "▶ " else "▼ "
                drawText(ctx, font, "§l$arrow${category.tag}", (px + 8f).toInt(), (py + 5f).toInt(), CATEGORY_TITLE)
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

            // 【修复】裁剪区域：防止文字超出面板上下方
            val clipTop = listAreaY.toInt().coerceAtLeast(0)
            val clipBottom = (listAreaY + listAreaH).toInt().coerceAtMost(sh.toInt())

            // 【修复】列表区域 scissor 裁剪: 部分可见的模块/设置也能渲染, 超出部分被裁剪
            // 不再"整行必须在可视区才渲染"(那会隐藏列表底部最后一个模块)
            ctx.enableScissor(listAreaX.toInt(), listAreaY.toInt(),
                (listAreaX + listAreaW).toInt(), (listAreaY + listAreaH).toInt())

            var curY = listAreaY - panel.scrollOffset

            for (mod in panelModules) {
                val isExpanded = expandedModule == mod
                val modEndY = curY + ITEM_H

                // 【修复】部分可见即渲染 (由 scissor 裁剪超出部分)
                if (curY < listAreaY + listAreaH && modEndY > listAreaY) {
                    val isHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                            mouseY in curY.toInt()..modEndY.toInt()

                    if (isHover) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, modEndY, HOVER)
                    if (isExpanded) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, modEndY, EXPANDED_BG)

                    // 【修改】激活文字=天蓝(TEXT_BRIGHT), 未激活=浅灰白(TEXT)
                    val nameColor = if (mod.enabled) TEXT_BRIGHT else TEXT
                    val nameMaxW = (listAreaW - 16).toInt()
                    drawText(ctx, font, trimText(font, mod.name, nameMaxW),
                        (listAreaX + 4f).toInt(), (curY + 4f).toInt(), nameColor)

                    // 开关 — 原蓝点样式 (开启=紫色)
                    val dotX = (listAreaX + listAreaW - 4f).toInt()
                    val dotY = curY.toInt() + 7
                    fillRect(ctx, dotX, dotY, dotX + 4, dotY + 4,
                        if (mod.enabled) ACCENT else 0x40808080.toInt())
                }

                curY += ITEM_H

                if (isExpanded) {
                    val values = getVisibleValues(mod)
                    // 计算总背景高度（含 Mode 竖排增高）
                    var totalSettingH = 0f
                    for ((v, _) in values) {
                        val actual = getActualValue(v)
                        if (v is ModeValueGroup<*>) {
                            totalSettingH += max(1f, (1 + v.modes.size).toFloat()) * SETTING_H
                        } else if (isEnumWithMultiple(actual)) {
                            totalSettingH += max(1f, getEnumConstants(actual).size.toFloat()) * SETTING_H
                        } else {
                            totalSettingH += SETTING_H
                        }
                    }

                    // 【修复】设置区域背景严格限制在列表区域内
                    val bgStart = curY.coerceAtLeast(listAreaY)
                    val bgEnd = (curY + totalSettingH).coerceAtMost(listAreaY + listAreaH)
                    if (bgEnd > bgStart) {
                        fillRect(ctx, listAreaX, bgStart, listAreaX + listAreaW, bgEnd, SETTING_BG)
                    }

                    for ((v, depth) in values) {
                        val actual = getActualValue(v)
                        if (v is ModeValueGroup<*>) {
                            // 【修复】Mode 选择器: 标题 + 每个 mode 一行
                            val rowCount = renderModeListForModeGroup(ctx, font, v,
                                listAreaX, curY, listAreaW, depth, mouseX, mouseY)
                            curY += rowCount * SETTING_H
                        } else if (isEnumWithMultiple(actual)) {
                            val rowCount = renderModeListVertical(ctx, font, v, getEnumConstants(actual),
                                actual as Enum<*>, listAreaX, curY, listAreaW, depth, mouseX, mouseY,
                                listAreaY, listAreaH)
                            curY += rowCount * SETTING_H
                        } else {
                            val settingEndY = curY + SETTING_H
                            // 【修复】部分可见即渲染 (由 scissor 裁剪)
                            if (curY < listAreaY + listAreaH && settingEndY > listAreaY) {
                                val isSettingHover = mouseX in listAreaX.toInt()..(listAreaX + listAreaW).toInt() &&
                                        mouseY in curY.toInt()..settingEndY.toInt()
                                if (isSettingHover) fillRect(ctx, listAreaX, curY, listAreaX + listAreaW, settingEndY, HOVER)
                                renderSetting(ctx, v, depth, listAreaX, curY, listAreaW, mouseX, mouseY, mod)
                            }
                            curY += SETTING_H
                        }
                    }
                }
            }

            // 关闭列表裁剪 (滚动条/后续内容不受影响)
            ctx.disableScissor()

            // 滚动条
            if (contentH > listAreaH) {
                fillRect(ctx, listAreaX + listAreaW, listAreaY, listAreaX + listAreaW + SCROLL_W, listAreaY + listAreaH, SCROLL_TRACK)
                val thumbH = (listAreaH * listAreaH / contentH).coerceAtLeast(12f)
                val thumbY = listAreaY + if (maxScroll > 0f) (panel.scrollOffset / maxScroll) * (listAreaH - thumbH) else 0f
                // 【修复】扩大滚动条命中区域到整个轨道
                val isScrollHover = mouseX in (listAreaX + listAreaW - 4).toInt()..(listAreaX + listAreaW + SCROLL_W + 4).toInt() &&
                        mouseY in listAreaY.toInt()..(listAreaY + listAreaH).toInt()
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

        // 调色板
        val colorVal = activeColorValue
        if (colorVal != null) {
            val px = colorPickerX.coerceIn(0f, sc - PALETTE_W)
            val py = colorPickerY.coerceIn(0f, sh - PALETTE_H)
            drawRoundedRect(ctx, px, py, PALETTE_W, PALETTE_H, 4f, BG)
            fillRect(ctx, px.toInt(), py.toInt(), (px + PALETTE_W).toInt(), py.toInt() + 1, SCROLL_THUMB)
            fillRect(ctx, px.toInt(), (py + PALETTE_H - 1).toInt(), (px + PALETTE_W).toInt(), (py + PALETTE_H).toInt(), SCROLL_THUMB)
            fillRect(ctx, px.toInt(), py.toInt(), px.toInt() + 1, (py + PALETTE_H).toInt(), SCROLL_THUMB)
            fillRect(ctx, (px + PALETTE_W - 1).toInt(), py.toInt(), (px + PALETTE_W).toInt(), (py + PALETTE_H).toInt(), SCROLL_THUMB)

            val currentRgb = extractColor(colorVal).rgb and 0xFFFFFF
            for (index in paletteColors.indices) {
                val row = index / PALETTE_COLS
                val col = index % PALETTE_COLS
                val cx = px + PALETTE_PAD + col * (PALETTE_CELL + PALETTE_GAP)
                val cy = py + PALETTE_PAD + row * (PALETTE_CELL + PALETTE_GAP)
                val color4b = paletteColors[index]
                fillRect(ctx, cx.toInt(), cy.toInt(), (cx + PALETTE_CELL).toInt(), (cy + PALETTE_CELL).toInt(), color4b.argb)
                if ((color4b.argb and 0xFFFFFF) == currentRgb) {
                    fillRect(ctx, (cx - 1).toInt(), (cy - 1).toInt(), (cx + PALETTE_CELL + 1).toInt(), cy.toInt(), TEXT_BRIGHT)
                    fillRect(ctx, (cx - 1).toInt(), (cy + PALETTE_CELL).toInt(), (cx + PALETTE_CELL + 1).toInt(), (cy + PALETTE_CELL + 1).toInt(), TEXT_BRIGHT)
                    fillRect(ctx, (cx - 1).toInt(), (cy - 1).toInt(), cx.toInt(), (cy + PALETTE_CELL + 1).toInt(), TEXT_BRIGHT)
                    fillRect(ctx, (cx + PALETTE_CELL).toInt(), (cy - 1).toInt(), (cx + PALETTE_CELL + 1).toInt(), (cy + PALETTE_CELL + 1).toInt(), TEXT_BRIGHT)
                }
            }

            // —— 【新增】Alpha 透明度条 (底部): 透明 → 当前色, 拖动调节 ——
            val barY = py + PALETTE_H - ALPHA_BAR_H
            val barX = px + PALETTE_PAD
            val barW = PALETTE_W - PALETTE_PAD * 2
            val (cr, cg, cb) = currentColorRgb(colorVal)
            // 渐变: 8 段, 每段当前色 alpha 递增
            for (s in 0 until 8) {
                val a = (s * 255 / 7).coerceIn(0, 255)
                val sx = barX + barW * s / 8
                val ex = barX + barW * (s + 1) / 8
                fillRect(ctx, sx.toInt(), barY.toInt(), ex.toInt(), (barY + ALPHA_BAR_H).toInt(),
                    (a shl 24) or (cr shl 16) or (cg shl 8) or cb)
            }
            // Alpha 滑块
            val knobX = barX + barW * colorPickerAlpha / 255
            fillRect(ctx, (knobX - 2).toInt(), (barY - 2).toInt(), (knobX + 2).toInt(), (barY + ALPHA_BAR_H + 2).toInt(), TEXT_BRIGHT)
            // Alpha 数值
            drawText(ctx, font, "$colorPickerAlpha", (barX + barW + 3f).roundToInt(), (barY + 1f).toInt(), TEXT_DIM)
        }
    }

    // ==================== 滑条布局计算 ====================
    private data class SliderLayout(
        val sliderX: Int,
        val sliderW: Int,
        val valText: String,
        val valX: Int,
        val fv: Float,
        val minV: Float,
        val maxV: Float,
        val rangeWidth: Float = 0f,
        // 【修复】范围值双滑块: 下限点/上限点在轨道内的位置 (仅 Range 使用)
        val lowerPointX: Int = 0,
        val upperPointX: Int = 0,
        val rangeEnd: Float = 0f,
    )

    /** 范围值显示文本 (如 12..14 → "12~14") */
    private fun formatRange(r: ClosedRange<*>): String {
        val s = r.start
        return when (s) {
            is Int -> "${s}~${r.endInclusive}"
            is Long -> "$s~${r.endInclusive}"
            is Float -> String.format(java.util.Locale.US, "%.1f~%.1f", s, r.endInclusive)
            is Double -> String.format(java.util.Locale.US, "%.1f~%.1f", s, r.endInclusive)
            else -> "$s~${r.endInclusive}"
        }
    }

    private fun computeSliderLayout(
        font: Font,
        v: Value<*>,
        actual: Any?,
        x: Float, w: Float, indent: Float
    ): SliderLayout {
        var fv = 0f
        var minV = 0f
        var maxV = 100f
        var rangeText: String? = null
        var rangeWidth = 0f
        var rangeEnd = 0f

        // 【修复】范围值支持: actual 可能是 ClosedRange (如 12..14)
        if (actual is ClosedRange<*>) {
            val start = (actual.start as? Number)?.toFloat() ?: 0f
            val endIncl = (actual.endInclusive as? Number)?.toFloat() ?: start
            fv = start
            rangeEnd = endIncl
            rangeWidth = (endIncl - start).coerceAtLeast(0f)
            rangeText = formatRange(actual)
        } else if (actual is Number) {
            fv = actual.toFloat()
        }
        if (v is RangedValue<*>) {
            minV = (v.range.start as? Number)?.toFloat() ?: 0f
            maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
        }

        // 【修复】滑条固定最右, 数值文本固定在滑条左侧, 不再随数值长度改变滑条坐标
        val rightEdge = (x + w - 2).toInt()
        val sliderW = 36
        val sliderX = rightEdge - 3 - sliderW
        val minSliderX = (x + 6 + indent).toInt() + 24
        val maxValW = (sliderX - 3 - minSliderX).coerceIn(12, 80)
        val valText = trimText(font, rangeText ?: String.format(java.util.Locale.US, "%.1f", fv), maxValW)
        val valW = font.width(valText).coerceAtMost(maxValW)
        val valX = sliderX - 3 - valW

        // 【修复】范围值: 计算轨道内下限点/上限点的 x 位置 (双滑块)
        var lowerPointX = 0
        var upperPointX = 0
        if (rangeWidth > 0f || actual is ClosedRange<*>) {
            val span = (maxV - minV).takeIf { it > 0f } ?: 1f
            lowerPointX = sliderX + ((fv - minV) / span * sliderW).toInt().coerceIn(0, sliderW)
            upperPointX = sliderX + ((rangeEnd - minV) / span * sliderW).toInt().coerceIn(0, sliderW)
        }
        return SliderLayout(sliderX, sliderW, valText, valX, fv, minV, maxV, rangeWidth, lowerPointX, upperPointX, rangeEnd)
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
            fillRect(ctx, x, y, x + w, y + SETTING_H, SETTING_CHILD_BG)
            val lineX = (x + 4f + indent).toInt()
            fillRect(ctx, lineX, y.toInt() + 1, lineX + 1, y.toInt() + SETTING_H.toInt() - 1, GROUP_LINE)
        }

        val actual = getActualValue(v)
        val isGroup = isGroupValue(v)
        val labelX = (x + 6 + indent).toInt()
        val toggleX = (x + w - 16).toInt()
        val valueX = (x + w - 44).toInt()
        val labelMaxW = (valueX - labelX - 4).coerceAtLeast(10)

        when {
            isGroup -> {
                val isCollapsed = collapsedGroups.contains(v)
                val arrow = if (isCollapsed) "▶" else "▼"
                fillRect(ctx, x, y, x + w, y + SETTING_H, GROUP_BG)
                val groupMaxW = (w - 16 - indent).toInt().coerceAtLeast(10)
                drawText(ctx, font, "$arrow ${trimText(font, v.name, groupMaxW)}",
                    labelX, (y + 4f).toInt(), if (isCollapsed) TEXT_DIM else ACCENT)
            }
            actual is Boolean -> {
                // 【修复】label 充分利用到 ON/OFF 前方 (省略号贴近开关)
                val nameMaxW = (toggleX - labelX - 2).coerceAtLeast(10)
                drawText(ctx, font, trimText(font, v.name, nameMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val status = if (actual) "§aON" else "§cOFF"
                drawText(ctx, font, status, toggleX, (y + 4f).toInt(), if (actual) ACCENT else TEXT_DIM)
            }
            isBindValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val isListening = listeningValue == v
                val bindStr = trimText(font, formatBindValue(v), (w - 60 - indent).toInt())
                val display = if (isListening) "§e[...]" else "§7$bindStr"
                drawText(ctx, font, display, valueX, (y + 4f).toInt(), if (isListening) ACCENT else TEXT_DIM)
            }
            isSliderValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 3f).toInt(), TEXT_DIM)
                val layout = computeSliderLayout(font, v, actual, x, w, indent)
                val sliderY = y.toInt() + 8
                val isRange = layout.rangeWidth > 0f || layout.upperPointX != 0

                // 轨道 (固定)
                fillRect(ctx, layout.sliderX, sliderY, layout.sliderX + layout.sliderW, sliderY + 2, 0x30FFFFFF.toInt())

                if (isRange) {
                    // 【修复】范围值双滑块: 下限点↔上限点之间填充, 两个可拖动的点
                    val lx = layout.lowerPointX
                    val ux = layout.upperPointX.coerceAtLeast(lx + 2)
                    // 区间填充
                    fillRect(ctx, lx, sliderY, ux, sliderY + 2, ACCENT)
                    // 下限点
                    fillRect(ctx, lx - 2, sliderY - 3, lx + 2, sliderY + 5, TEXT_BRIGHT)
                    // 上限点
                    fillRect(ctx, ux - 2, sliderY - 3, ux + 2, sliderY + 5, TEXT_BRIGHT)
                } else {
                    val progress = if (layout.maxV > layout.minV) {
                        ((layout.fv - layout.minV) / (layout.maxV - layout.minV)).coerceIn(0f, 1f)
                    } else 0f
                    fillRect(ctx, layout.sliderX, sliderY, layout.sliderX + (layout.sliderW * progress).toInt(), sliderY + 2, ACCENT)
                    fillRect(ctx, layout.sliderX + (layout.sliderW * progress).toInt() - 1, sliderY - 1,
                        layout.sliderX + (layout.sliderW * progress).toInt() + 1, sliderY + 3, TEXT_BRIGHT)
                }
                drawText(ctx, font, layout.valText, layout.valX, (y + 3f).toInt(), TEXT_DIM)
            }
            isColorValue(v) -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val color = extractColor(v)
                fillRect(ctx, valueX, y.toInt() + 4, valueX + 10, y.toInt() + 14, color.rgb)
                if (activeColorValue == v) {
                    val bx = valueX - 1; val by = y.toInt() + 3
                    fillRect(ctx, bx, by, bx + 12, by + 1, TEXT_BRIGHT)
                    fillRect(ctx, bx, by + 11, bx + 12, by + 12, TEXT_BRIGHT)
                    fillRect(ctx, bx, by, bx + 1, by + 12, TEXT_BRIGHT)
                    fillRect(ctx, bx + 11, by, bx + 12, by + 12, TEXT_BRIGHT)
                }
            }
            else -> {
                drawText(ctx, font, trimText(font, v.name, labelMaxW), labelX, (y + 4f).toInt(), TEXT_DIM)
                val valMaxW = (x + w - valueX - 2).toInt().coerceAtLeast(10)
                drawText(ctx, font, "§7${trimText(font, getDisplayValue(v), valMaxW)}", valueX, (y + 4f).toInt(), TEXT_DIM)
            }
        }

        // 子模块开关 — 原蓝点样式 (开启=紫色)
        if (depth > 0 && getActualValue(v) !is Boolean && !isEnumWithMultiple(getActualValue(v)) &&
            !isGroup && !isSliderValue(v) && !isColorValue(v) && !isBindValue(v)) {
            val va = getActualValue(v)
            if (va is Enum<*>) {
                val dotX2 = (x + w - 4f).toInt()
                val dotY2 = y.toInt() + 6
                val isActive = getDisplayValue(v) == va.name
                fillRect(ctx, dotX2, dotY2, dotX2 + 4, dotY2 + 4,
                    if (isActive) ACCENT else 0x40808080.toInt())
            }
        }
    }

    // ==================== Mode 组 (ModeValueGroup) 渲染 ====================
    /** 【修复】ModeValueGroup 选择器: 标题行 + 每个 mode 一行(点+名称), 当前 mode 高亮 */
    private fun renderModeListForModeGroup(
        ctx: GuiGraphicsExtractor, font: Font,
        v: ModeValueGroup<*>, x: Float, curY: Float, w: Float, depth: Int,
        mouseX: Int, mouseY: Int
    ): Int {
        val indent = depth * SETTING_INDENT
        val labelX = (x + 6 + indent).toInt()
        val collapsed = collapsedGroups.contains(v)

        // 标题行
        fillRect(ctx, x, curY, x + w, curY + SETTING_H, GROUP_BG)
        drawText(ctx, font, "${if (collapsed) "▶" else "▼"} ${v.name}",
            labelX, (curY + 4f).toInt(), ACCENT)
        if (collapsed) return 1

        // 每个 mode 一行
        var yOff = curY + SETTING_H
        for (mode in v.modes) {
            val isActive = mode === v.activeMode
            val dotX = labelX + 4
            val dotY = yOff.toInt() + 6
            fillRect(ctx, dotX, dotY, dotX + 4, dotY + 4,
                if (isActive) ACCENT else 0x40808080.toInt())
            val textX = dotX + 6
            val maxTextW = (x + w - 8 - textX).toInt().coerceAtLeast(10)
            drawText(ctx, font, trimText(font, mode.name, maxTextW), textX, (yOff + 4f).toInt(),
                if (isActive) TEXT_BRIGHT else TEXT_DIM)
            yOff += SETTING_H
        }
        return 1 + v.modes.size
    }

    // ==================== Mode 竖排渲染 ====================
    private fun renderModeListVertical(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, constants: List<Any>, current: Enum<*>,
        x: Float, curY: Float, w: Float, depth: Int,
        mouseX: Int, mouseY: Int,
        listAreaY: Float, listAreaH: Float
    ): Int {
        val indent = depth * SETTING_INDENT
        val labelX = (x + 6 + indent).toInt()
        val nameMaxW = 50
        // 标题行：部分可见即渲染 (由 scissor 裁剪)
        if (curY < listAreaY + listAreaH && curY + SETTING_H > listAreaY) {
            drawText(ctx, font, trimText(font, v.name, nameMaxW), labelX, (curY + 4f).toInt(), TEXT)
        }
        var yOff = curY + SETTING_H

        val dotSize = 4
        val dotGap = 2
        val nameX = labelX
        for (const in constants) {
            // 每行 Mode：部分可见即渲染 (由 scissor 裁剪)
            if (yOff < listAreaY + listAreaH && yOff + SETTING_H > listAreaY) {
                val displayName = const.toString()
                val isActive = displayName == current.name
                val dotX = nameX + 4
                val dotY = yOff.toInt() + 6

                // 开关 — 原蓝点样式 (开启=紫色)
                fillRect(ctx, dotX, dotY, dotX + dotSize, dotY + dotSize,
                    if (isActive) ACCENT else 0x40808080.toInt())
                val textX = dotX + dotSize + dotGap
                val maxTextW = (x + w - 8 - textX).toInt().coerceAtLeast(10)
                drawText(ctx, font, trimText(font, displayName, maxTextW), textX, (yOff + 4f).toInt(),
                    if (isActive) TEXT_BRIGHT else TEXT_DIM)
            }
            yOff += SETTING_H
        }
        return 1 + constants.size
    }

    // ==================== Enum 辅助 ====================
    private fun isEnumWithMultiple(actual: Any?): Boolean {
        if (actual !is Enum<*>) return false
        return getEnumConstants(actual).size >= 2
    }

    private fun getEnumConstants(actual: Any?): List<Any> {
        if (actual == null) return emptyList()
        return try {
            (actual.javaClass.enumConstants?.toList() ?: emptyList()) as List<Any>
        } catch (_: Exception) { emptyList() }
    }

    // ==================== Click handling ====================
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val btn = event.button()
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val sc = minecraft!!.window.guiScaledWidth
        val sh = minecraft!!.window.guiScaledHeight

        // 搜索框
        val searchY = sh - 30f
        val searchX = (sc - 160f) / 2f
        val searchW = 160f
        if (mx in searchX.toInt()..(searchX + searchW).toInt() &&
            my in searchY.toInt()..(searchY + 16f).toInt()) {
            searchFocused = true
            return true
        }
        searchFocused = false

        // 调色板
        val colorVal = activeColorValue
        if (colorVal != null) {
            val px = colorPickerX.coerceIn(0f, sc - PALETTE_W)
            val py = colorPickerY.coerceIn(0f, sh - PALETTE_H)
            if (mx in px.toInt()..(px + PALETTE_W).toInt() &&
                my in py.toInt()..(py + PALETTE_H).toInt()) {
                // 【新增】Alpha 条区域 → 设置透明度并开始拖动
                val barY = py + PALETTE_H - ALPHA_BAR_H
                if (my >= barY.toInt() && my <= (barY + ALPHA_BAR_H).toInt()) {
                    val barX = px + PALETTE_PAD
                    val barW = PALETTE_W - PALETTE_PAD * 2
                    val alpha = (((mx - barX) / barW) * 255).toInt().coerceIn(0, 255)
                    colorPickerAlpha = alpha
                    colorPickerAlphaDragging = true
                    // 实时应用到当前颜色
                    val (r, g, b) = currentColorRgb(colorVal)
                    trySetValue(colorVal, Color4b(r, g, b, alpha))
                    return true
                }
                // 色块 → 选色 (应用当前 Alpha)
                val col = ((mx - px - PALETTE_PAD) / (PALETTE_CELL + PALETTE_GAP)).toInt()
                val row = ((my - py - PALETTE_PAD) / (PALETTE_CELL + PALETTE_GAP)).toInt()
                val index = row * PALETTE_COLS + col
                if (index in paletteColors.indices) {
                    val picked = paletteColors[index]
                    trySetValue(colorVal, Color4b(picked.r, picked.g, picked.b, colorPickerAlpha))
                    activeColorValue = null
                }
                return true
            }
            activeColorValue = null
        }

        var targetPanel: PanelData? = null
        for (panel in currentPanels()) {
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

        // 【修复】滚动条点击 — 扩大命中范围到整个轨道区域
        if (mx in (listAreaX + listAreaW - 6).toInt()..(listAreaX + listAreaW + SCROLL_W + 6).toInt() &&
            my in listAreaY.toInt()..(listAreaY + listAreaH).toInt()) {
            val modules = getModulesForPanel(panel)
            val contentH = getContentHeight(modules)
            if (contentH > listAreaH) {
                // 【修复】点击时直接跳转到对应位置
                val clickRatio = (my - listAreaY) / listAreaH
                val maxScroll = contentH - listAreaH
                panel.targetScroll = clickRatio * maxScroll
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
                                // 【修复】模块关闭(OFF)后自动收起其展开的设置
                                if (!mod.enabled && expandedModule == mod) {
                                    expandedModule = null
                                }
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
                        val actual = getActualValue(v)

                        if (v is ModeValueGroup<*>) {
                            // 【修复】Mode 组点击: 标题行折叠/展开, mode 行切换
                            val titleEndY = curY + SETTING_H
                            if (my in curY.toInt()..titleEndY.toInt()) {
                                if (btn == 0) {
                                    if (collapsedGroups.contains(v)) collapsedGroups.remove(v)
                                    else collapsedGroups.add(v)
                                }
                                return true
                            }
                            curY += SETTING_H
                            for (mode in v.modes) {
                                val settingEndY = curY + SETTING_H
                                if (my in curY.toInt()..settingEndY.toInt()) {
                                    if (btn == 0) {
                                        try { v.setByString(mode.name) } catch (_: Exception) {}
                                    }
                                    return true
                                }
                                curY += SETTING_H
                            }
                        } else if (isEnumWithMultiple(actual)) {
                            val constants = getEnumConstants(actual)
                            curY += SETTING_H
                            for (const in constants) {
                                val settingEndY = curY + SETTING_H
                                if (my in curY.toInt()..settingEndY.toInt()) {
                                    if (btn == 0) {
                                        trySetValue(v, const)
                                    }
                                    return true
                                }
                                curY += SETTING_H
                            }
                        } else {
                            val settingEndY = curY + SETTING_H
                            if (my in curY.toInt()..settingEndY.toInt()) {
                                handleSettingClick(v, btn, mx.toFloat(), curY, listAreaW, listAreaX, panel, depth * SETTING_INDENT)
                                return true
                            }
                            curY += SETTING_H
                        }
                    }
                }
            }
        }

        return true
    }

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
                // 【新增】打开调色板时同步当前颜色的 Alpha
                colorPickerAlpha = currentColorAlpha(v)
                colorPickerAlphaDragging = false
                colorPickerX = x + w - PALETTE_W - 2f
                colorPickerY = y + SETTING_H + 2f
            }
            return
        }

        if (isSliderValue(v)) {
            val font = minecraft!!.font
            val layout = computeSliderLayout(font, v, actual, x, w, indent)
            val hitStart = layout.sliderX - 6
            val hitEnd = layout.sliderX + layout.sliderW + 6
            if (mx.toInt() in hitStart..hitEnd) {
                val progress = ((mx.toInt() - layout.sliderX).toFloat() / layout.sliderW).coerceIn(0f, 1f)
                val newValue = layout.minV + (layout.maxV - layout.minV) * progress

                // 【修复】范围双滑块: 判断点击离下限点/上限点哪个近
                var rangePoint = -1
                if (layout.upperPointX != 0) {
                    val mxInt = mx.toInt()
                    val dLower = abs(mxInt - layout.lowerPointX)
                    val dUpper = abs(mxInt - layout.upperPointX)
                    rangePoint = if (dLower <= dUpper) 0 else 1
                }
                if (rangePoint == -1) {
                    applySliderValue(v, actual, newValue, layout.rangeWidth)
                } else {
                    applyRangePoint(v, actual, newValue, rangePoint)
                }

                sliderContext = SliderContext(
                    value = v, panel = panel,
                    sliderX = layout.sliderX, sliderY = y.toInt() + 8,
                    sliderW = layout.sliderW, min = layout.minV, max = layout.maxV,
                    rangeWidth = layout.rangeWidth, rangePoint = rangePoint
                )
            }
        }
    }

    /** 应用范围值双滑块: 只移动下限点(point=0)或上限点(point=1), 另一端保持不动 */
    private fun applyRangePoint(v: Value<*>, actual: Any?, newValue: Float, point: Int) {
        if (actual !is ClosedRange<*>) return
        val start = (actual.start as? Number)?.toFloat() ?: return
        val end = (actual.endInclusive as? Number)?.toFloat() ?: return
        val min = ((v as? RangedValue<*>)?.range?.start as? Number)?.toFloat() ?: 0f
        val max = ((v as? RangedValue<*>)?.range?.endInclusive as? Number)?.toFloat() ?: 100f
        val newStart: Float
        val newEnd: Float
        if (point == 0) {
            // 拖动下限点: 下限 ≤ 上限
            newStart = newValue.coerceIn(min, end)
            newEnd = end
        } else {
            // 拖动上限点: 上限 ≥ 下限
            newStart = start
            newEnd = newValue.coerceIn(start, max)
        }
        when (actual.start) {
            is Int -> trySetValue(v, newStart.toInt()..newEnd.toInt())
            is Long -> trySetValue(v, newStart.toLong()..newEnd.toLong())
            is Float -> trySetValue(v, newStart..newEnd)
            is Double -> trySetValue(v, newStart.toDouble()..newEnd.toDouble())
        }
    }

    /** 应用滑块值: 支持普通数值和范围值 (ClosedRange, 拖动时保持范围宽度) */
    private fun applySliderValue(v: Value<*>, actual: Any?, newValue: Float, rangeWidth: Float) {
        when (actual) {
            is ClosedRange<*> -> {
                val start = actual.start
                val newStart: Any = when (start) {
                    is Int -> newValue.toInt()
                    is Long -> newValue.toLong()
                    is Float -> newValue
                    is Double -> newValue.toDouble()
                    else -> return
                }
                val newRange: Any = when (start) {
                    is Int -> (newStart as Int)..(newStart + rangeWidth.toInt())
                    is Long -> (newStart as Long)..(newStart + rangeWidth.toLong())
                    is Float -> (newStart as Float)..(newStart + rangeWidth)
                    is Double -> (newStart as Double)..(newStart + rangeWidth.toDouble())
                    else -> return
                }
                trySetValue(v, newRange)
            }
            is Float -> trySetValue(v, newValue)
            is Double -> trySetValue(v, newValue.toDouble())
            is Int -> trySetValue(v, newValue.toInt())
            is Long -> trySetValue(v, newValue.toLong())
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleModeClick(v: Value<*>, mx: Float, x: Float, w: Float, indent: Float,
                                constants: List<Any>, current: Enum<*>) {}

    @Suppress("UNUSED_PARAMETER")
    private fun handleModeClick(v: Value<*>, mx: Float, rightEdge: Float,
                                constants: List<Any>, current: Enum<*>) {}

    private fun trySetValue(v: Value<*>, value: Any) {
        try {
            val setMethod = v.javaClass.methods.firstOrNull {
                it.name == "set" && it.parameterCount == 1
            }
            setMethod?.invoke(v, value)
        } catch (_: Exception) {}
    }

    // ==================== 鼠标拖拽 ====================
    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val mx = event.x().toFloat()
        val my = event.y().toFloat()

        // 【新增】Alpha 条拖动: 拖动时实时更新当前颜色的透明度
        if (colorPickerAlphaDragging) {
            val colorVal = activeColorValue
            if (colorVal != null) {
                val px = colorPickerX.coerceIn(0f, minecraft!!.window.guiScaledWidth - PALETTE_W)
                val py = colorPickerY.coerceIn(0f, minecraft!!.window.guiScaledHeight - PALETTE_H)
                val barX = px + PALETTE_PAD
                val barW = PALETTE_W - PALETTE_PAD * 2
                val alpha = (((mx - barX) / barW) * 255).toInt().coerceIn(0, 255)
                colorPickerAlpha = alpha
                val (r, g, b) = currentColorRgb(colorVal)
                trySetValue(colorVal, Color4b(r, g, b, alpha))
            }
            return true
        }

        // 滑块拖动
        val context = sliderContext
        if (context != null) {
            val progress = ((mx.toInt() - context.sliderX).toFloat() / context.sliderW).coerceIn(0f, 1f)
            val newValue = context.min + (context.max - context.min) * progress
            val actual = getActualValue(context.value)
            // 【修复】范围双滑块: 按 rangePoint 分别拖动下限/上限点
            if (context.rangePoint == -1) {
                applySliderValue(context.value, actual, newValue, context.rangeWidth)
            } else {
                applyRangePoint(context.value, actual, newValue, context.rangePoint)
            }
            return true
        }

        for (panel in currentPanels()) {
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
            // 【修复】滚动条拖拽方向: 鼠标向下(dy>0) → 内容向上滚(targetScroll 增大)
            if (panel.draggingScroll) {
                val modules = getModulesForPanel(panel)
                val contentH = getContentHeight(modules)
                val listAreaH = panel.h - 32f
                if (contentH > listAreaH) {
                    val maxScroll = contentH - listAreaH
                    panel.targetScroll = (panel.targetScroll + dy.toFloat() * 1.5f).coerceIn(0f, maxScroll)
                }
                return true
            }
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        for (panel in currentPanels()) {
            panel.draggingScroll = false
            panel.draggingPanel = false
        }
        sliderContext = null
        colorPickerAlphaDragging = false
        saveLayout()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        for (panel in currentPanels()) {
            if (panel.collapsed) continue
            if (mouseX in panel.x.toDouble()..(panel.x + panel.w).toDouble() &&
                mouseY in panel.y.toDouble()..(panel.y + panel.h).toDouble()) {
                panel.targetScroll = (panel.targetScroll - vertical.toFloat() * 22f).coerceAtLeast(0f)
                return true
            }
        }
        return true
    }

    /** 【修复】当前生效的面板列表: 搜索时只显示 searchPanel, 否则为分类面板 */
    private fun currentPanels(): List<PanelData> =
        if (searchText.isNotEmpty()) listOfNotNull(searchPanel) else panels

    private fun getModulesForPanel(panel: PanelData): List<ClientModule> {
        return if (searchText.isNotEmpty()) {
            categories.flatMap { getCategoryModules(it) }.distinct()
        } else {
            panel.category?.let { getCategoryModules(it) } ?: emptyList()
        }
    }

    // ==================== Keyboard ====================
    override fun keyPressed(event: KeyEvent): Boolean {
        if (listeningValue != null) {
            listeningValue = null
            return true
        }
        // ESC / 右 Shift 关闭: Android 环境 key() 可能是 scancode, 同时用 key() 和 scancode() 兜底
        // (ESC scancode=1, 右 Shift scancode=54)
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.scancode() == 1 ||
            event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT || event.scancode() == 54) {
            if (searchFocused) { searchFocused = false; return true }
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
                            val f = cls.getDeclaredField("codePoint"); f.isAccessible = true
                            codepoint = f.get(event) as? Int ?: 0
                        } catch (_: NoSuchFieldException) {
                            try {
                                val f = cls.getDeclaredField("character"); f.isAccessible = true
                                codepoint = (f.get(event) as? Char)?.code ?: 0
                            } catch (_: NoSuchFieldException) {
                                try {
                                    val m = cls.getMethod("getCodePoint")
                                    codepoint = m.invoke(event) as? Int ?: 0
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
                if (codepoint > 31) { searchText += codepoint.toChar(); return true }
            } catch (_: Exception) { }
        }
        return true
    }

    override fun onClose() {
        saveLayout()
        setScreenCompat(null)
        fadeAnim = 0f
        isFirstLoad = true
    }

    override fun removed() {
        saveLayout()
    }

    private fun setScreenCompat(screen: Screen?) {
        val mc = minecraft ?: return
        try {
            mc.javaClass.getMethod("setScreen", Screen::class.java)?.invoke(mc, screen); return
        } catch (_: NoSuchMethodException) { }
        try {
            mc.javaClass.getMethod("openScreen", Screen::class.java)?.invoke(mc, screen); return
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
        val topValues = try { module.collectValuesRecursively() } catch (_: Exception) { return emptyList() }
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
            // ModeValueGroup 不走按名过滤(由 activeMode 决定显示), 其余值正常过滤
            if (v !is ModeValueGroup<*> && isHiddenForCurrentMode(v)) { visited[v] = true; return }
            visited[v] = true
            result.add(Pair(v, depth))
            when {
                // 【修复】ModeValueGroup: 只展开当前激活的 Mode 的设置, 其他 mode 的设置不再混入
                v is ModeValueGroup<*> -> {
                    if (!collapsedGroups.contains(v)) {
                        getGroupChildren(v.activeMode).forEach { child ->
                            if (!isHiddenForCurrentMode(child)) process(child, depth + 1)
                        }
                    }
                }
                isGroupValue(v) -> {
                    if (!collapsedGroups.contains(v)) {
                        getGroupChildren(v).forEach { child ->
                            if (!isHiddenForCurrentMode(child)) process(child, depth + 1)
                        }
                    }
                }
            }
        }

        topValues.forEach { v ->
            var isChild = false
            topValues.forEach { other ->
                if (other != v && isGroupValue(other) && getGroupChildren(other).contains(v)) isChild = true
            }
            if (!isChild) process(v, 0)
        }
        return result
    }

    private fun isGroupValue(v: Value<*>): Boolean {
        return try {
            v.javaClass.simpleName.contains("Group", true) ||
            v.javaClass.simpleName.contains("Container", true)
        } catch (_: Exception) { false }
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
                                visited[child] = true; list.add(child)
                            }
                        }
                    }
                }
            }
            for (f in v.javaClass.declaredFields) {
                f.isAccessible = true
                val valObj = f.get(v)
                if (valObj is Value<*> && !visited.containsKey(valObj)) {
                    visited[valObj] = true; list.add(valObj)
                } else if (valObj is Collection<*>) {
                    for (child in valObj) {
                        if (child is Value<*> && !visited.containsKey(child)) {
                            visited[child] = true; list.add(child)
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
            obj = try { obj.get() } catch (_: Exception) { null }; depth++
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

    /** 当前颜色 Alpha (Color4b.a) */
    private fun currentColorAlpha(v: Value<*>): Int {
        val actual = getActualValue(v)
        if (actual is Color4b) return actual.a
        return 255
    }

    /** 当前颜色 RGB 分量 */
    private fun currentColorRgb(v: Value<*>): Triple<Int, Int, Int> {
        val actual = getActualValue(v)
        if (actual is Color4b) return Triple(actual.r, actual.g, actual.b)
        val c = extractColor(v)
        return Triple(c.red, c.green, c.blue)
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
                if (key != null) return key.toString().replace("key.keyboard.", "").uppercase()
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
                        if (collapsedGroups.contains(v)) groups += "${mod.name}:${v.name}"
                    }
                }
            } catch (_: Exception) { }
            val groupsJson = groups.joinToString(",") { "\"$it\"" }
            val sb = StringBuilder()
            sb.append("{\"panels\":[").append(panelsJson).append("],")
            sb.append("\"expanded\":\"").append(expandedName).append("\",")
            sb.append("\"groups\":[").append(groupsJson).append("]}")
            val file = getLayoutFile()
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
        } catch (e: Exception) {
            println("[ClickGui] saveLayout failed: ${e.message}")
        }
    }

    private fun loadLayout(): LayoutState {
        return try {
            val file = getLayoutFile()
            if (!file.exists()) return LayoutState(emptyMap(), null, emptyList())
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
            val expanded = Regex(""""expanded":"([^"]*)"""").find(content)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            val groups = mutableListOf<String>()
            Regex(""""groups":\[(.*?)\]""").find(content)?.groupValues?.get(1)?.let { gs ->
                Regex(""""([^"]+)"""").findAll(gs).forEach { groups.add(it.groupValues[1]) }
            }
            LayoutState(panels, expanded, groups)
        } catch (_: Exception) {
            LayoutState(emptyMap(), null, emptyList())
        }
    }
}
