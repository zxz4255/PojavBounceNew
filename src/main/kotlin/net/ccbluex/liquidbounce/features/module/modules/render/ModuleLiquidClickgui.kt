/*
 * ModuleLiquidClickgui —— LiquidBounce 官方 ClickGUI 的纯 Kotlin 复刻版
 * LiquidBounce Nextgen 0.39 兼容 API
 *
 * 原版 ClickGUI 是 Svelte 网页前端(src-theme/src/routes/clickgui/*)经内嵌浏览器渲染的,
 * 本模块不调用任何浏览器/Web 栈, 全部用原生 GuiGraphicsExtractor 绘制还原:
 *
 *  ── 布局 (ClickGui.svelte / Panel.svelte / Module.svelte) ──
 *  · 每个分类一个独立面板: 默认位置 left=20, top=index*50+20, 可拖动, 点击置顶
 *  · 面板 250px 宽 r5, 投影 10px(黑50%), 标题栏黑90% + 底部 2px 强调色边框,
 *    padding 10x15, 左侧分类图标 + 14px 分类名 + 右侧 +/- 展开钮
 *  · 模块体黑80%, 展开高度 0→545 过渡 300ms ease, 2px 滚动条, 平滑滚动
 *  · 模块行: 居中 12px 文字, padding 10, 灰字 / 悬停黑85%+白字 / 启用强调色,
 *    右键展开设置, 右侧 40px 展开箭头区(rotate -90°→0°)
 *  · 设置区: 黑50% 背景 + 左侧 4px 强调色边框, padding 0 11px 0 7px, slide 500ms quintOut
 *
 *  ── 搜索 (Search.svelte) ──
 *  · 顶部居中 y=70, 600px 宽, 圆角 30(有结果时 10), 黑90%, 投影
 *  · 输入框 padding 15x25 16px 字体; 结果: 顶部 2px 强调色边框, 最高 250px,
 *    每行 16px + 别名, 悬停提示 "Right-click to locate"
 *  · 键盘: ↑↓ 选择, Enter 切换, Tab 定位(面板置顶+展开+滚动+2px 强调色描边高亮)
 *
 *  ── 描述气泡 (Description.svelte) ──
 *  · 悬停模块名显示, 右侧空间>300px 则在右侧否则左侧, 偏移 20px,
 *    箭头三角指向模块, 黑90% r5, 12px 文字 padding 10, fly 200ms
 *
 *  ── 网格吸附 (clickgui_store / Panel) ──
 *  · 拖动面板时显示 gridSize 网格(灰 25%), 吸附 gridSize, Shift 临时禁用
 *
 *  ── 设置组件 (setting/*.svelte) ──
 *  · Switch 22x12: 轨道 r4 (关=白45%混黑 / 开=强调色40%混黑), 圆钮 12px (白/强调色)
 *  · Slider (noUiSlider): 轨道 2px 白20%混黑, 填充+圆钮 12px 强调色,
 *    名称+可编辑数值+后缀 一行, 滑条第二行, 整行高 46
 *  · Dropdown: 强调色背景 r3 padding 6x10, "name • value", 展开时上圆角3下0,
 *    选项列表 黑底 强调色边框, 灰字/悬停白/选中强调色
 *  · 嵌套设置: 左侧 2px 强调色边框 + padding-left 7
 *  · 多选: chips (黑30% 底灰字 / 选中=强调色12%底强调色字)
 *  · 文本: 黑36% 底 强调色边框 r3; 颜色: 色块 + HSV 取色器; 按键: 捕获绑定
 *
 *  ── 配色 (colors.scss) ──
 *  · surface=#000 text=#fff dimmed=#d3d3d3 accent=#4677ff
 *  · 黑色 alpha 混合: 90%=229 85%=217 80%=204 70%=178 60%=153 50%=127 36%=92 30%=77
 *  · 背景 overlay = 黑 60%; 网格 = #808080 @ 25%
 *
 *  面板位置/展开/滚动 持久化(java.util.prefs, 纯 JDK 无文件依赖)。
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.IdentityHashMap
import java.util.prefs.Preferences
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleLiquidClickgui : ClientModule(
    "LiquidClickgui",
    ModuleCategories.RENDER,
    aliases = listOf("LiquidGui", "LiquidClickGui", "VanillaClickGui"),
    bind = InputConstants.KEY_RSHIFT,
    disableActivation = true,
) {

    // ======================== 设置 (原版 Scale/Snapping 均还原为可调项) ========================

    private val scale by float("Scale", 1f, 0.5f..2f)

    private val panelWidth by float("Panel Width", 250f, 180f..420f)
    private val panelMaxHeight by float("Panel Max Height", 545f, 200f..900f)
    private val panelSpacing by float("Panel Spacing", 50f, 20f..140f)
    private val panelRadius by float("Panel Radius", 5f, 0f..14f)
    private val headerPadding by float("Header Padding", 10f, 4f..20f)

    private val snapEnabled by boolean("Snapping", true)
    private val gridSize by int("Grid Size", 10, 1..100, "px")
    private val showGridWhileDrag by boolean("Show Grid While Dragging", true)
    private val dimBackground by boolean("Dim Background", true)
    private val dimAlpha by float("Dim Amount", 0.6f, 0f..1f)

    private val searchEnabled by boolean("Search Bar", true)
    private val searchWidth by float("Search Width", 600f, 300f..900f)
    private val searchY by float("Search Y", 70f, 10f..400f)
    private val searchRadius by float("Search Radius", 30f, 0f..50f)

    private val accentColor by color("Accent", Color4b(0x46, 0x77, 0xFF, 255))
    private val baseColor by color("Base Color", Color4b(0, 0, 0, 255))
    private val textColor by color("Text", Color4b(0xFF, 0xFF, 0xFF, 255))
    private val dimmedColor by color("Dimmed Text", Color4b(0xD3, 0xD3, 0xD3, 255))

    private val panelAnimMs by int("Panel Anim", 300, 0..1000, "ms")
    private val moduleAnimMs by int("Module Anim", 500, 0..1000, "ms")
    private val hoverAnimMs by int("Hover Anim", 200, 0..600, "ms")
    private val fadeMs by int("Fade", 200, 0..800, "ms")

    private val scrollbarWidth by float("Scrollbar Width", 2f, 0f..6f)
    private val descEnabled by boolean("Descriptions", true)
    private val catIcons by boolean("Category Icons", true)

    /** 原版 color-mix(black N%) 的等价 alpha */
    private fun mix(alphaPct: Float, a: Int = 255): Color4b {
        val base = baseColor
        return Color4b(base.r, base.g, base.b, (255 * alphaPct).toInt().coerceIn(0, 255) * a / 255)
    }

    private fun alpha(c: Color4b, a: Int) =
        Color4b(c.r, c.g, c.b, (c.a * a / 255).coerceIn(0, 255))

    // ======================== 常量 (Svelte 设计稿) ========================

    private val HEADER_H get() = headerPadding * 2f + 17f   // 10*2 + 14px 行高
    private val ROW_H = 34f                                  // 10*2 + 14 (12px 字体)
    private val SET_PAD_L = 7f                               // padding: 0 11px 0 7px
    private val SET_PAD_R = 11f
    private val SWITCH_W = 22f
    private val SWITCH_H = 12f
    private val SLIDER_H = 46f
    private val DROPDOWN_H = 26f                             // 6*2 + 14
    private val DROPDOWN_OPT_H = 24f                         // 5*2 + 14
    private val SETTING_ROW_PAD = 7f
    private val DESC_OFFSET = 20f
    private val EXPAND_ZONE_W = 40f

    // 内联取色器
    private val PICKER_SB_H = 130f
    private val PICKER_BAR_H = 12f
    private val PICKER_GAP = 8f
    private val PICKER_TOTAL = PICKER_GAP + PICKER_SB_H + PICKER_GAP + PICKER_BAR_H + PICKER_GAP + PICKER_BAR_H
    private val NESTED_INDENT = 9f                           // 2px 边框 + 7px padding

    private val CAT_ORDER = listOf("Combat", "Player", "Movement", "Render", "World", "Misc", "Exploit", "Fun")

    // ======================== 状态 ========================

    private class Panel(
        val category: String,
        val index: Int,
    ) {
        var x = 20f
        var y = 20f
        var expanded = false
        var extAnim = 0f          // 0..1 展开动画
        var scroll = 0f           // 当前滚动 (平滑)
        var scrollTarget = 0f
        var z = 0
        var hoverAnim = 0f        // 头部悬停(预留)
        var modules: List<ClientModule> = emptyList()
        var contentH = 0f
    }

    private val panels = ArrayList<Panel>()
    private var panelsBuilt = false
    private var maxZ = 0

    private var dragPanel: Panel? = null
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var shiftIgnoreGrid = false

    private val modOpen = IdentityHashMap<ClientModule, Boolean>()
    private val modOpenAnim = IdentityHashMap<ClientModule, Float>()
    private val modHoverAnim = IdentityHashMap<ClientModule, Float>()

    private val groupOpen = IdentityHashMap<Value<*>, Boolean>()
    private val groupOpenAnim = IdentityHashMap<Value<*>, Float>()

    // 搜索
    private var searchQuery = ""
    private var searchFocus = true
    private var searchSelected = 0
    private var searchScroll = 0f
    private var searchResults: List<ClientModule> = emptyList()
    private var searchOpenAnim = 0f

    // 描述气泡
    private var descTarget: ClientModule? = null
    private var descAnchorRight = true
    private var descAnim = 0f

    // 定位高亮 (Tab locate)
    private var highlightMod: ClientModule? = null
    private var highlightAnim = 0f

    // 拖动/交互中的组件状态
    private var sliderDrag: Value<*>? = null
    private var dualWhich = 0
    private val switchAnim = IdentityHashMap<Value<*>, Float>()
    private val sliderRects = IdentityHashMap<Value<*>, FloatArray>()
    private val dropdownOpen = IdentityHashMap<Value<*>, Boolean>()
    private val dropdownAnim = IdentityHashMap<Value<*>, Float>()
    private var dropdownRect: FloatArray? = null        // 当前展开的下拉框(展开区, 供点击)
    private var dropdownOptions = emptyList<Any?>()
    private var dropdownValue: Value<*>? = null
    private var colorOpen: Value<*>? = null
    private var colorDragChannel = -1
    private val colorHue = IdentityHashMap<Value<*>, Float>()
    private val colorSV = IdentityHashMap<Value<*>, Pair<Float, Float>>()
    private var pickerRect: FloatArray? = null
    private var textEdit: Value<*>? = null
    private var textBuf = ""
    private var keyListen: Value<*>? = null

    // 悬停行记录(渲染时写入, 点击时读取)
    private val rowRects = IdentityHashMap<ClientModule, FloatArray>()
    private val headerRects = HashMap<Panel, FloatArray>()
    private val switchRects = IdentityHashMap<Value<*>, FloatArray>()
    private val groupRects = IdentityHashMap<Value<*>, FloatArray>()
    private val dropdownHeadRects = IdentityHashMap<Value<*>, FloatArray>()
    private val chipRects = ArrayList<Triple<Value<*>, Any?, FloatArray>>()
    private val textRects = IdentityHashMap<Value<*>, FloatArray>()
    private val colorRects = IdentityHashMap<Value<*>, FloatArray>()
    private val keyRects = IdentityHashMap<Value<*>, FloatArray>()
    private var expandArrowRect: FloatArray? = null

    private var mouseX = 0f
    private var mouseY = 0f
    private var leftDown = false
    private var frameDt = 0.016f
    private var lastNs = 0L
    private var viewW = 0f
    private var viewH = 0f
    private var uiAlpha = 0f

    // ======================== 值系统 (类型缓存, 同已验证实现) ========================

    private enum class Kind { GROUP, TOGGLE_GROUP, MODE, CHOICE, MULTI, SLIDER, DUAL, BOOL, COLOR, TEXT, KEY, BIND, OTHER }

    private class VInfo(val v: Value<*>) {
        var kind = Kind.OTHER
        var range: Pair<Float, Float>? = null
        var isInt = false
        var suffix = ""
        var choices: List<Any?> = emptyList()
        var children: List<Value<*>>? = null
        var modeRef: Any? = null
    }

    private val infoCache = IdentityHashMap<Value<*>, VInfo>()

    private fun info(v: Value<*>): VInfo = infoCache.getOrPut(v) { resolveInfo(v) }

    private fun resolveInfo(v: Value<*>): VInfo {
        val i = VInfo(v)
        when (v.valueType) {
            ValueType.CONFIGURABLE -> i.kind = Kind.GROUP
            ValueType.TOGGLEABLE -> i.kind = Kind.TOGGLE_GROUP
            ValueType.CHOICE -> {
                i.kind = Kind.MODE
                runCatching { i.choices = (v as ModeValueGroup<*>).modes.toList() }
            }
            ValueType.CHOOSE -> {
                i.kind = Kind.CHOICE
                runCatching { i.choices = (v as ChoiceListValue<*>).choices.toList() }
            }
            ValueType.MULTI_CHOOSE -> {
                i.kind = Kind.MULTI
                runCatching { i.choices = (v as MultiChoiceListValue<*>).choices.toList() }
            }
            ValueType.FLOAT, ValueType.INT -> {
                i.kind = Kind.SLIDER
                i.isInt = v.valueType == ValueType.INT
            }
            ValueType.FLOAT_RANGE, ValueType.INT_RANGE -> {
                i.kind = Kind.DUAL
                i.isInt = v.valueType == ValueType.INT_RANGE
            }
            ValueType.BOOLEAN -> i.kind = Kind.BOOL
            ValueType.COLOR -> i.kind = Kind.COLOR
            ValueType.TEXT -> i.kind = Kind.TEXT
            ValueType.KEY -> i.kind = Kind.KEY
            ValueType.BIND -> i.kind = Kind.BIND
            else -> i.kind = Kind.OTHER
        }
        if (v is RangedValue) {
            val a = (v.range.start as? Number)?.toFloat()
            val b = (v.range.endInclusive as? Number)?.toFloat()
            if (a != null && b != null && b > a) i.range = a to b
            i.suffix = v.suffix
        }
        return i
    }

    private fun childrenOf(v: Value<*>?): List<Value<*>> {
        if (v == null) return emptyList()
        val vi = info(v)
        when (vi.kind) {
            Kind.GROUP, Kind.TOGGLE_GROUP -> {
                vi.children?.let { return it }
                val list = runCatching {
                    (v as ValueGroup).inner.filter { !it.notAnOption }
                }.getOrDefault(emptyList())
                vi.children = list
                return list
            }
            Kind.MODE -> {
                val active = runCatching { (v as ModeValueGroup<*>).activeMode }.getOrNull()
                    ?: return emptyList()
                val cached = vi.children
                if (cached != null && vi.modeRef === active) return cached
                val list = runCatching {
                    active.inner.filter { !it.notAnOption }
                }.getOrDefault(emptyList())
                vi.children = list
                vi.modeRef = active
                return list
            }
            else -> return emptyList()
        }
    }

    private fun childrenOfModule(mod: ClientModule): List<Value<*>> =
        modChildren.getOrPut(mod) {
            runCatching {
                mod.inner.filter { !it.notAnOption && !isRedundantEnabled(it) }
            }.getOrDefault(emptyList())
        }

    private val modChildren = IdentityHashMap<ClientModule, List<Value<*>>>()

    private fun isRedundantEnabled(v: Value<*>) =
        v.valueType == ValueType.BOOLEAN && v.name.equals("Enabled", true)

    private fun numOf(v: Value<*>): Float =
        (v.get() as? Number)?.toFloat() ?: 0f

    private fun dualOf(v: Value<*>): Pair<Float, Float> {
        val cur = v.get() as? ClosedRange<*> ?: return 0f to 0f
        val a = (cur.start as? Number)?.toFloat() ?: 0f
        val b = (cur.endInclusive as? Number)?.toFloat() ?: 0f
        return a to b
    }

    private fun choiceLabel(v: Value<*>): String = runCatching {
        when (info(v).kind) {
            Kind.MODE -> (v as ModeValueGroup<*>).activeMode.name
            else -> taggedLabel(v.get())
        }
    }.getOrDefault("-")

    private fun taggedLabel(o: Any?): String = when (o) {
        null -> "-"
        is Tagged -> o.tag
        is Enum<*> -> o.name
        else -> o.toString()
    }

    private fun multiSelected(v: Value<*>): Set<*> = runCatching {
        (v as MultiChoiceListValue<*>).get()
    }.getOrDefault(emptySet<Any>())

    private fun colorOf(v: Value<*>): Color4b =
        (v.get() as? Color4b) ?: Color4b(255, 255, 255, 255)

    private fun hsvToRgb(h: Float, s: Float, v: Float, a: Int): Color4b {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1 - s)
        val q = v * (1 - f * s)
        val t = v * (1 - (1 - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Color4b((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt(), a)
    }

    private fun rgbToHsv(c: Color4b): Triple<Float, Float, Float> {
        val r = c.r / 255f
        val g = c.g / 255f
        val b = c.b / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d < 1e-5f -> 0f
            mx == r -> ((g - b) / d + 6f) % 6f / 6f
            mx == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        val s = if (mx < 1e-5f) 0f else d / mx
        return Triple(h, s, mx)
    }

    // ======================== 生命周期 ========================

    override fun onEnabled() {
        ensurePanels()
        loadLayout()
        mc.execute { mc.gui.setScreen(LiquidScreen()) }
        super.onEnabled()
    }

    override fun onDisabled() {
        saveLayout()
        textEdit = null
        keyListen = null
        sliderDrag = null
        colorOpen = null
        dropdownValue = null
        descTarget = null
        super.onDisabled()
    }

    private fun closeGui() {
        if (mc.gui.screen() is LiquidScreen) {
            runCatching { mc.gui.setScreen(null) }
        }
        enabled = false
    }

    // ======================== 面板构建与持久化 ========================

    private fun allModules(): List<ClientModule> {
        val raw = runCatching { ModuleManager.getModules() }.getOrNull()
            ?: return runCatching {
                val f = ModuleManager.javaClass.getDeclaredField("modules")
                f.isAccessible = true
                (f.get(ModuleManager) as? Collection<*>)?.filterIsInstance<ClientModule>() ?: emptyList()
            }.getOrDefault(emptyList())
        return raw.filterIsInstance<ClientModule>()
    }

    private fun ensurePanels() {
        val mods = allModules()
        val byCat = LinkedHashMap<String, MutableList<ClientModule>>()
        for (m in mods) {
            val cat = categoryLabel(m)
            byCat.getOrPut(cat) { ArrayList() }.add(m)
        }
        // 按原版图标顺序排列
        val ordered = byCat.entries.sortedBy { (cat, _) ->
            val i = CAT_ORDER.indexOf(cat)
            if (i < 0) CAT_ORDER.size else i
        }
        panels.clear()
        for ((idx, e) in ordered.withIndex()) {
            val p = Panel(e.key, idx)
            p.modules = e.value.sortedBy { it.name }
            p.x = 20f
            p.y = idx * panelSpacing + 20f
            panels.add(p)
        }
        panelsBuilt = true
        modChildren.clear()
        infoCache.clear()
        panels.forEach { p ->
            p.modules.forEach { m ->
                modOpenAnim[m] = if (modOpen[m] == true) 1f else 0f
            }
        }
    }

    private fun categoryLabel(m: ClientModule): String = m.category.name

    private fun prefs(): Preferences? =
        runCatching { Preferences.userNodeForPackage(ModuleLiquidClickgui::class.java) }.getOrNull()

    private fun loadLayout() {
        val p = prefs() ?: return
        for (panel in panels) {
            runCatching {
                val s = p.get("panel.${panel.category}", null) ?: return@runCatching
                val parts = s.split(',')
                if (parts.size >= 4) {
                    panel.x = parts[0].toFloatOrNull() ?: panel.x
                    panel.y = parts[1].toFloatOrNull() ?: panel.y
                    panel.expanded = parts[2] == "1"
                    panel.scrollTarget = parts[3].toFloatOrNull() ?: 0f
                    panel.scroll = panel.scrollTarget
                    panel.extAnim = if (panel.expanded) 1f else 0f
                }
                if (parts.size >= 5) panel.z = parts[4].toIntOrNull() ?: 0
            }
        }
        runCatching {
            maxZ = panels.maxOfOrNull { it.z } ?: 0
        }
    }

    private fun saveLayout() {
        val p = prefs() ?: return
        for (panel in panels) {
            runCatching {
                p.put(
                    "panel.${panel.category}",
                    "${panel.x},${panel.y},${if (panel.expanded) 1 else 0},${panel.scrollTarget},${panel.z}",
                )
            }
        }
        runCatching { p.flush() }
    }

    // ======================== 输入入口 ========================

    private fun guiMX() = (mc.mouseHandler.xpos() * refW() / mc.window.width).toFloat()
    private fun guiMY() = (mc.mouseHandler.ypos() * refH() / mc.window.height).toFloat()
    private fun refW() = if (viewW > 0f) viewW else mc.window.guiScaledWidth.toFloat()
    private fun refH() = if (viewH > 0f) viewH else mc.window.guiScaledHeight.toFloat()

    internal fun onMouse(button: Int, down: Boolean): Boolean {
        if (down) {
            mouseX = guiMX()
            mouseY = guiMY()
            // 逆缩放
            val s = scale
            mouseX = viewW / 2f + (mouseX - viewW / 2f) / s
            mouseY = viewH / 2f + (mouseY - viewH / 2f) / s
            if (button == 0) leftDown = true
            return click(button)
        }
        if (button == 0) leftDown = false
        release(button)
        return true
    }

    private fun over(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    private fun inRect(r: FloatArray?) =
        r != null && over(r[0], r[1], r[2], r[3])

    // ======================== 交互逻辑 ========================

    private fun click(button: Int): Boolean {
        val left = button == 0
        val right = button == 1

        // 取色器(最高层)
        colorOpen?.let { v ->
            val pr = pickerRect
            if (pr != null && over(pr[0], pr[1], pr[3], pr[4])) {
                if (left) {
                    // 通道: SB 面板 / 色相条 / 透明度条
                    val sbBottom = pr[1] + pr[5]
                    val hueBottom = sbBottom + PICKER_GAP + PICKER_BAR_H
                    colorDragChannel = when {
                        mouseY < sbBottom -> 0
                        mouseY < hueBottom -> 1
                        else -> 2
                    }
                    applyColorPicker(v)
                }
                return true
            }
            // 点击取色器/色块行附近以外 → 关闭(点击继续传递, 同原版内联 pickr)
            if (pr == null || !over(pr[0] - pr[3], pr[1] - 30f, pr[3] * 3f, pr[4] + 34f)) {
                colorOpen = null
            }
        }

        // 下拉选项(次高层)
        dropdownValue?.let { dv ->
            val dr = dropdownRect
            if (dr != null && left && over(dr[0], dr[1], dr[2], dr[3])) {
                val idx = ((mouseY - dr[1]) / DROPDOWN_OPT_H).toInt().coerceIn(0, dropdownOptions.size - 1)
                selectOption(dv, dropdownOptions[idx])
                return true
            }
            // 点击其它区域关闭
            val head = dropdownHeadRects[dv]
            val onHead = head != null && over(head[0], head[1], head[2], head[3])
            dropdownValue = null
            dropdownOpen[dv] = false
            // 点击触发器本身: 本次仅关闭(避免 openDropdown 立即重新展开)
            if (onHead) return true
        }

        // 搜索栏
        if (searchEnabled) {
            val sw = searchWidth
            val sx = viewW / 2f - sw / 2f
            val resultsH = if (searchResults.isNotEmpty()) min(searchResults.size * 39f, 250f) + 2f
            else if (searchQuery.isNotEmpty()) 39f else 0f
            if (over(sx, searchY, sw, 49f + resultsH)) {
                if (left) {
                    searchFocus = true
                    // 点击结果行
                    if (searchResults.isNotEmpty() && mouseY > searchY + 49f) {
                        val idx = ((mouseY - searchY - 49f - 2f) / 39f + searchScroll / 39f).toInt()
                        if (idx in searchResults.indices) {
                            val m = searchResults[idx]
                            m.enabled = !m.enabled
                            return true
                        }
                    }
                } else if (right && searchResults.isNotEmpty() && mouseY > searchY + 49f) {
                    val idx = ((mouseY - searchY - 49f - 2f) / 39f + searchScroll / 39f).toInt()
                    if (idx in searchResults.indices) {
                        locateModule(searchResults[idx])
                        return true
                    }
                }
                return true
            }
            if (left) searchFocus = false
        }

        // 按键捕获: 点击原按钮 → 取消监听; 其它按键/鼠标键 → 绑定 (原版 mouseButton listener)
        keyListen?.let { kl ->
            val kr = keyRects[kl]
            if (kr != null && over(kr[0], kr[1], kr[2], kr[3]) && button == 0) {
                setKeyValue(kl, InputConstants.UNKNOWN)
            } else {
                setKeyValue(kl, InputConstants.Type.MOUSE.getOrCreate(button))
            }
            keyListen = null
            return true
        }

        // 面板: 按 z 从高到低
        val sorted = panels.sortedByDescending { it.z }
        for (p in sorted) {
            // 标题栏
            val hr = headerRects[p]
            if (hr != null && over(hr[0], hr[1], hr[2], hr[3])) {
                // 展开按钮 (+/-) 区域: 右侧 15px padding + 12px 图标 → 切换而非拖动
                val btnL = p.x + hr[2] - 15f - 12f - 4f
                val onExpandBtn = mouseX >= btnL
                if (left && !onExpandBtn) {
                    p.z = ++maxZ
                    dragPanel = p
                    dragOffX = mouseX - p.x
                    dragOffY = mouseY - p.y
                } else if (left && onExpandBtn || right) {
                    togglePanel(p)
                }
                return true
            }
            // 模块列表区(展开时)
            if (p.extAnim > 0.05f) {
                val bodyY = p.y + HEADER_H + 2f
                val bodyH = p.extAnim * panelMaxHeight
                if (over(p.x, bodyY, panelWidth, bodyH)) {
                    if (handlePanelContent(p, bodyY, bodyH, left, right)) return true
                }
            }
        }
        return true
    }

    /** 面板内容点击: 先设置组件 → 模块行 */
    private fun handlePanelContent(p: Panel, bodyY: Float, bodyH: Float, left: Boolean, right: Boolean): Boolean {
        // 1) 组件命中(在模块展开设置区)
        for ((v, r) in switchRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) {
                    toggleSwitch(v)
                } else if (right) {
                    toggleGroup(v)
                }
                return true
            }
        }
        for ((v, r) in groupRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left || right) toggleGroup(v)
                return true
            }
        }
        for ((v, r) in dropdownHeadRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) openDropdown(v) else if (right) toggleGroup(v)
                return true
            }
        }
        for ((v, choice, r) in chipRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) {
                    runCatching { (v as MultiChoiceListValue<Any>).toggle(choice) }
                }
                return true
            }
        }
        for ((v, r) in textRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                val k = info(v).kind
                if (left && (k == Kind.TEXT || k == Kind.SLIDER || k == Kind.DUAL)) {
                    textEdit = v
                    textBuf = when (k) {
                        Kind.SLIDER -> fmtNum(numOf(v), info(v).isInt)
                        Kind.DUAL -> {
                            val (lo, hi) = dualOf(v)
                            "${fmtNum(lo, info(v).isInt)}-${fmtNum(hi, info(v).isInt)}"
                        }
                        else -> (v.get() as? String) ?: ""
                    }
                }
                return true
            }
        }
        for ((v, r) in colorRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) {
                    if (colorOpen == v) {
                        colorOpen = null
                    } else {
                        colorOpen = v
                        // 打开时从当前颜色初始化 HSV
                        val cur = colorOf(v)
                        val (h, s, vv) = rgbToHsv(cur)
                        colorHue[v] = h
                        colorSV[v] = s to vv
                    }
                }
                return true
            }
        }
        for ((v, r) in keyRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) keyListen = v
                return true
            }
        }
        for ((v, r) in sliderRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) {
                    sliderDrag = v
                    if (info(v).kind == Kind.DUAL) pickDualHandle(v)
                    applySlider(v)
                }
                return true
            }
        }
        // 2) 模块行
        for ((m, r) in rowRects) {
            if (!samePanel(r, p)) continue
            if (over(r[0], r[1], r[2], r[3])) {
                val inExpandZone = mouseX >= r[0] + r[2] - EXPAND_ZONE_W
                if ((left && inExpandZone) || right) {
                    toggleModule(m)
                    return true
                }
                if (left) {
                    m.enabled = !m.enabled
                    return true
                }
            }
        }
        return false
    }

    private fun inRectRow(r: FloatArray, p: Panel): Boolean = samePanel(r, p)

    private fun samePanel(r: FloatArray, p: Panel): Boolean = r[4].toInt() == p.index

    private fun togglePanel(p: Panel) {
        p.expanded = !p.expanded
        p.z = ++maxZ
        saveLayout()
    }

    private fun toggleModule(m: ClientModule) {
        modOpen[m] = !(modOpen[m] == true)
        saveModuleOpen()
    }

    private fun toggleGroup(v: Value<*>) {
        groupOpen[v] = !(groupOpen[v] == true)
    }

    private fun saveModuleOpen() {
        // 模块展开状态存内存(同原版 localStorage, 会话级足够)
    }

    private fun openDropdown(v: Value<*>) {
        if (dropdownValue == v) {
            dropdownValue = null
            dropdownOpen[v] = false
        } else {
            dropdownValue?.let { dropdownOpen[it] = false }
            dropdownValue = v
            dropdownOpen[v] = true
            dropdownOptions = info(v).choices
        }
    }

    private fun selectOption(v: Value<*>, choice: Any?) {
        runCatching {
            when (info(v).kind) {
                Kind.MODE -> v.setByString((choice as? Tagged)?.tag ?: choice.toString())
                Kind.CHOICE -> (v as Value<Any>).set(choice)
                else -> (v as Value<Any>).set(choice)
            }
        }
        dropdownValue = null
        dropdownOpen[v] = false
        infoCache.remove(v)
    }

    private fun pickDualHandle(v: Value<*>) {
        val rect = sliderRects[v] ?: return
        val bounds = info(v).range ?: return
        val (lo, hi) = dualOf(v)
        val t1 = (lo - bounds.first) / (bounds.second - bounds.first)
        val t2 = (hi - bounds.first) / (bounds.second - bounds.first)
        val x1 = rect[0] + t1 * rect[2]
        val x2 = rect[0] + t2 * rect[2]
        dualWhich = if (abs(mouseX - x1) <= abs(mouseX - x2)) 0 else 1
    }

    private fun applySlider(v: Value<*>) {
        val rect = sliderRects[v] ?: return
        val vi = info(v)
        val bounds = vi.range ?: return
        val (minV, maxV) = bounds
        val t = ((mouseX - rect[0]) / rect[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
        var nv = minV + t * (maxV - minV)
        if (vi.isInt) {
            nv = nv.roundToInt().toFloat()
        } else {
            // noUiSlider step: >100→0.1, ≤0.1→0.0001, ≤1→0.001, 其余 0.01
            val step = when {
                maxV > 100f -> 0.1f
                maxV <= 0.1f -> 0.0001f
                maxV <= 1f -> 0.001f
                else -> 0.01f
            }
            nv = minV + ((nv - minV) / step).roundToInt() * step
        }
        nv = nv.coerceIn(minV, maxV)
        runCatching {
            when (vi.kind) {
                Kind.DUAL -> {
                    val cur = v.get() as ClosedRange<*>
                    val cMin = (cur.start as? Number)?.toFloat() ?: minV
                    val cMax = (cur.endInclusive as? Number)?.toFloat() ?: maxV
                    val lo: Float
                    val hi: Float
                    if (dualWhich == 0) {
                        lo = nv.coerceIn(minV, cMax)
                        hi = cMax
                    } else {
                        lo = cMin
                        hi = nv.coerceIn(cMin, maxV)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val casted = v as Value<Any>
                    when (cur) {
                        is IntRange -> casted.set(lo.roundToInt()..hi.roundToInt())
                        else -> casted.set(lo..hi)
                    }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val casted = v as Value<Any>
                    when (v.get()) {
                        is Int -> casted.set(nv.roundToInt())
                        is Long -> casted.set(nv.roundToInt().toLong())
                        else -> casted.set(nv)
                    }
                }
            }
        }
    }

    private fun applyColorPicker(v: Value<*>) {
        val pr = pickerRect ?: return
        val px = pr[0]
        val py = pr[1]
        val pw = pr[3]
        val sbH = pr[5]
        val hue = colorHue.getOrDefault(v, 0f)
        val (s, vv) = colorSV.getOrDefault(v, 0f to 0f)
        when (colorDragChannel) {
            0 -> {
                val ns = ((mouseX - px) / pw).coerceIn(0f, 1f)
                val nv2 = (1f - (mouseY - py) / sbH).coerceIn(0f, 1f)
                colorSV[v] = ns to nv2
                colorOf(v).let { old ->
                    runCatching {
                        (v as Value<Any>).set(hsvToRgb(hue, ns, nv2, old.a))
                    }
                }
            }
            1 -> {
                val nh = ((mouseX - px) / pw).coerceIn(0f, 1f)
                colorHue[v] = nh
                runCatching {
                    (v as Value<Any>).set(hsvToRgb(nh, s, vv, colorOf(v).a))
                }
            }
            2 -> {
                val na = ((mouseX - px) / pw).coerceIn(0f, 1f)
                val cur = colorOf(v)
                runCatching {
                    (v as Value<Any>).set(Color4b(cur.r, cur.g, cur.b, (na * 255).toInt().coerceIn(0, 255)))
                }
            }
        }
    }

    private fun release(button: Int) {
        if (button == 0) {
            if (dragPanel != null) {
                dragPanel = null
                saveLayout()
            }
            sliderDrag = null
            colorDragChannel = -1
        }
    }

    internal fun onDrag(dx: Double, dy: Double) {
        val p = dragPanel ?: return
        val s = scale
        mouseX = viewW / 2f + (guiMX() - viewW / 2f) / s
        mouseY = viewH / 2f + (guiMY() - viewH / 2f) / s
        val nx = mouseX - dragOffX
        val ny = mouseY - dragOffY
        val g = if (shiftIgnoreGrid || !snapEnabled) 0f else gridSize.toFloat().coerceAtLeast(1f)
        p.x = if (g > 0f) (nx / g).roundToInt() * g else nx
        p.y = if (g > 0f) (ny / g).roundToInt() * g else ny
        p.x = p.x.coerceIn(0f, (viewW / s - panelWidth).coerceAtLeast(0f))
        p.y = p.y.coerceIn(0f, (viewH / s - HEADER_H).coerceAtLeast(0f))
        sliderDrag?.let { applySlider(it) }
        if (colorDragChannel >= 0) colorOpen?.let { applyColorPicker(it) }
    }

    internal fun onScroll(v: Double): Boolean {
        val s = scale
        val mx = viewW / 2f + (guiMX() - viewW / 2f) / s
        val my = viewH / 2f + (guiMY() - viewH / 2f) / s
        // 原版下拉列表在任意滚动时关闭 (on:scroll|capture)
        dropdownValue?.let {
            dropdownValue = null
            dropdownOpen[it] = false
        }
        // 搜索结果滚动
        if (searchEnabled && searchResults.isNotEmpty()) {
            val sw = searchWidth
            val sx = viewW / 2f - sw / 2f
            if (mx >= sx && mx < sx + sw && my >= searchY && my < searchY + 49f + 250f + 2f) {
                searchScroll = (searchScroll - v * 20f).coerceIn(
                    0f,
                    (searchResults.size * 39f - 250f).coerceAtLeast(0f),
                )
                return true
            }
        }
        // 面板体滚动
        val sorted = panels.sortedByDescending { it.z }
        for (p in sorted) {
            if (p.extAnim < 0.9f) continue
            val bodyY = p.y + HEADER_H + 2f
            if (mx >= p.x && mx < p.x + panelWidth && my >= bodyY && my < bodyY + panelMaxHeight) {
                val maxScroll = (p.contentH - panelMaxHeight).coerceAtLeast(0f)
                p.scrollTarget = (p.scrollTarget - v * 20f).coerceIn(0f, maxScroll)
                saveScroll(p)
                return true
            }
        }
        return true
    }

    private fun saveScroll(p: Panel) {
        // 防抖: 关闭时统一保存
    }

    // ======================== 键盘 ========================

    internal fun onKeyPressed(key: Int): Boolean {
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) shiftIgnoreGrid = true

        // 按键绑定捕获 (KEY 存 InputConstants.Key, BIND 存 InputBind)
        keyListen?.let { v ->
            val k = if (key == GLFW.GLFW_KEY_ESCAPE) {
                InputConstants.UNKNOWN
            } else {
                InputConstants.Type.KEYSYM.getOrCreate(key)
            }
            setKeyValue(v, k)
            keyListen = null
            return true
        }

        // 文本编辑
        textEdit?.let {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> textEdit = null
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitText(it)
                GLFW.GLFW_KEY_BACKSPACE -> if (textBuf.isNotEmpty()) textBuf = textBuf.dropLast(1)
            }
            return true
        }

        when (key) {
            GLFW.GLFW_KEY_ESCAPE -> {
                closeGui()
                return true
            }
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (searchEnabled && searchFocus && searchQuery.isNotEmpty()) {
                    searchQuery = searchQuery.dropLast(1)
                    refilterSearch()
                    return true
                }
            }
        }

        // 搜索键盘导航 (原版 Search.svelte: ↓↑ Enter Tab)
        if (searchEnabled && searchResults.isNotEmpty()) {
            when (key) {
                GLFW.GLFW_KEY_DOWN -> {
                    searchSelected = (searchSelected + 1) % searchResults.size
                    return true
                }
                GLFW.GLFW_KEY_UP -> {
                    searchSelected = (searchSelected - 1 + searchResults.size) % searchResults.size
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    searchResults.getOrNull(searchSelected)?.let { m -> m.enabled = !m.enabled }
                    return true
                }
                GLFW.GLFW_KEY_TAB -> {
                    searchResults.getOrNull(searchSelected)?.let { locateModule(it) }
                    return true
                }
            }
        }
        return true
    }

    internal fun onKeyReleased(key: Int) {
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) shiftIgnoreGrid = false
    }

    internal fun onChar(cp: Int): Boolean {
        val ch = runCatching { cp.toChar() }.getOrNull() ?: return true
        // 按键捕获中不输入字符
        if (keyListen != null) return true
        if (textEdit != null) {
            if (!ch.isISOControl() && textBuf.length < 128) {
                // 数值编辑时仅允许数字/小数点/负号/分隔符
                val numeric = when (info(textEdit!!).kind) {
                    Kind.SLIDER, Kind.DUAL -> true
                    else -> false
                }
                if (!numeric || ch.isDigit() || ch == '.' || ch == '-' || ch == ' ') textBuf += ch
            }
            return true
        }
        if (searchEnabled && searchFocus) {
            if (!ch.isISOControl()) {
                searchQuery += ch
                refilterSearch()
            }
            return true
        }
        return true
    }

    private fun commitText(v: Value<*>) {
        val k = info(v).kind
        runCatching {
            when (k) {
                Kind.SLIDER -> {
                    val f = textBuf.trim().toFloatOrNull() ?: return
                    val range = info(v).range
                    val coerced = if (range != null) f.coerceIn(range.first, range.second) else f
                    @Suppress("UNCHECKED_CAST")
                    val casted = v as Value<Any>
                    when (v.get()) {
                        is Int -> casted.set(coerced.roundToInt())
                        is Long -> casted.set(coerced.roundToInt().toLong())
                        else -> casted.set(coerced)
                    }
                }
                Kind.DUAL -> {
                    val parts = textBuf.split('-', ' ')
                    val range = info(v).range
                    if (parts.size >= 2) {
                        var lo = parts[0].trim().toFloatOrNull() ?: return
                        var hi = parts[1].trim().toFloatOrNull() ?: return
                        if (range != null) {
                            lo = lo.coerceIn(range.first, range.second)
                            hi = hi.coerceIn(range.first, range.second)
                        }
                        if (lo > hi) lo = hi.also { hi = lo }
                        @Suppress("UNCHECKED_CAST")
                        val casted = v as Value<Any>
                        when (v.get()) {
                            is IntRange -> casted.set(lo.roundToInt()..hi.roundToInt())
                            else -> casted.set(lo..hi)
                        }
                    }
                }
                else -> when (val cur = v.get()) {
                    is String -> (v as Value<Any>).set(textBuf)
                    is Regex -> (v as Value<Any>).set(Regex(textBuf))
                    else -> (v as Value<Any>).set(textBuf)
                }
            }
        }
        textEdit = null
    }

    private fun refilterSearch() {
        searchSelected = 0
        searchScroll = 0f
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            return
        }
        val q = searchQuery.lowercase().replace(" ", "")
        val all = panels.flatMap { it.modules }
        searchResults = all.filter { m ->
            val n = m.name.lowercase()
            n.contains(q) || m.aliases.any { it.lowercase().contains(q) }
        }
    }

    /** Tab / 右键 locate: 面板置顶 + 展开 + 滚动到模块 + 描边高亮 */
    private fun locateModule(m: ClientModule) {
        highlightMod = m
        highlightAnim = 1f
        for (p in panels) {
            if (p.modules.contains(m)) {
                p.z = ++maxZ
                p.expanded = true
                // 滚动到该模块行
                var y = 0f
                for (mm in p.modules) {
                    if (mm === m) break
                    y += ROW_H + quintOut(modOpenAnim.getOrDefault(mm, 0f)) * settingsHeight(mm)
                }
                val maxScroll = (p.contentH - panelMaxHeight).coerceAtLeast(0f)
                p.scrollTarget = (y - panelMaxHeight / 2f).coerceIn(0f, maxScroll)
                saveLayout()
                break
            }
        }
    }

    // ======================== 工具 ========================

    /** quintOut 缓动 (Svelte: svelte/easing) */
    private fun quintOut(t: Float): Float = 1f - (1f - t).pow(5f)

    private fun approach(cur: Float, target: Float, speed: Float): Float =
        cur + (target - cur) * (1f - (0.001f).pow(frameDt * speed)).coerceIn(0f, 1f)

    /** 模块设置区总高度(不绘制, 仅测量) */
    private fun settingsHeight(m: ClientModule): Float {
        var h = 0f
        walkValues(childrenOfModule(m), 0, 0f, 0f) { _, _, _, rowH, _ -> h += rowH }
        return h
    }

    /** 模块设置区当前显示高度(随展开动画, quintOut 同 svelte slide) */
    private fun settingsHeightNow(m: ClientModule, openF: Float): Float {
        if (openF <= 0.001f) return 0f
        return settingsHeight(m) * quintOut(openF)
    }

    // ======================== Screen ========================

    private class LiquidScreen : Screen(Component.literal("ClickGUI")) {
        override fun isPauseScreen() = false
        override fun shouldCloseOnEsc() = false

        override fun onClose() {
            if (ModuleLiquidClickgui.enabled) ModuleLiquidClickgui.enabled = false
        }

        override fun keyPressed(input: KeyEvent): Boolean {
            ModuleLiquidClickgui.onKeyPressed(input.key())
            return true
        }

        override fun keyReleased(input: KeyEvent): Boolean {
            ModuleLiquidClickgui.onKeyReleased(input.key())
            return true
        }

        override fun charTyped(event: CharacterEvent): Boolean {
            val cp: Int = try {
                event.codepoint()
            } catch (_: Throwable) {
                runCatching {
                    val m = event.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name.lowercase().contains("code")
                    }
                    (m?.invoke(event) as? Number)?.toInt() ?: -1
                }.getOrDefault(-1)
            }
            ModuleLiquidClickgui.onChar(cp)
            return true
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            ModuleLiquidClickgui.onMouse(event.button(), true)
            return true
        }

        override fun mouseReleased(event: MouseButtonEvent): Boolean {
            ModuleLiquidClickgui.onMouse(event.button(), false)
            return true
        }

        override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
            ModuleLiquidClickgui.onDrag(dx, dy)
            return true
        }

        override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
            ModuleLiquidClickgui.onScroll(v)
            return true
        }
    }

    // ======================== 渲染主入口 ========================

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled && uiAlpha <= 0.01f) {
            lastNs = 0L
            return@handler
        }
        if (!panelsBuilt) ensurePanels()

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        frameDt = dt

        uiAlpha = if (enabled) (uiAlpha + dt * 1000f / fadeMs).coerceIn(0f, 1f)
        else (uiAlpha - dt * 1000f / fadeMs).coerceIn(0f, 1f)
        if (uiAlpha <= 0.01f) return@handler

        val ctx = event.context
        val font = mc.font
        viewW = ctx.guiWidth().toFloat()
        viewH = ctx.guiHeight().toFloat()
        val s = scale

        // 鼠标(逆缩放到 GUI 逻辑坐标)
        mouseX = viewW / 2f + (guiMX() - viewW / 2f) / s
        mouseY = viewH / 2f + (guiMY() - viewH / 2f) / s

        // 更新动画(悬停检测使用上一帧的命中矩形)
        updateAnimations(dt)

        // 命中矩形每帧重建
        rowRects.clear()
        headerRects.clear()
        switchRects.clear()
        groupRects.clear()
        dropdownHeadRects.clear()
        chipRects.clear()
        textRects.clear()
        colorRects.clear()
        keyRects.clear()
        sliderRects.clear()
        descTarget = null

        // 背景变暗 (overlay: black 60%)
        if (dimBackground && dimAlpha > 0f) {
            ctx.drawQuad(0f, 0f, viewW, viewH, alpha(Color4b(0, 0, 0, 255), (dimAlpha * 255 * uiAlpha).toInt()))
        }

        // 网格 (拖动时显示)
        val dragging = dragPanel != null

        ctx.pose().withPush {
            translate(viewW / 2f, viewH / 2f)
            scale(s, s)
            translate(-viewW / 2f, -viewH / 2f)

            // 网格: GUI 逻辑坐标下绘制, 与吸附位置精确对齐
            if (dragging && snapEnabled && showGridWhileDrag) {
                drawGrid(ctx, s)
            }

            // 搜索栏(最顶层 UI 之下)
            if (searchEnabled) drawSearch(ctx, font)

            // 面板按 z 升序绘制
            for (p in panels.sortedBy { it.z }) {
                drawPanel(ctx, font, p)
            }

            // 定位高亮描边(最上层)
            drawHighlight(ctx)

            // 描述气泡
            if (descEnabled) drawDescription(ctx, font)

            // 下拉选项浮层(portal 到 body, 不受面板剪裁, 最顶层)
            drawDropdown(ctx, font)
        }
    }

    private fun updateAnimations(dt: Float) {
        for (p in panels) {
            val target = if (p.expanded) 1f else 0f
            val speed = 1000f / panelAnimMs.coerceAtLeast(1)
            p.extAnim = if (target > p.extAnim) {
                (p.extAnim + dt * speed).coerceAtMost(target)
            } else {
                (p.extAnim - dt * speed).coerceAtLeast(target)
            }
            // 平滑滚动
            p.scroll += (p.scrollTarget - p.scroll) * (1f - (0.05f).pow(dt * 14f)).coerceIn(0f, 1f)
            if (abs(p.scroll - p.scrollTarget) < 0.1f) p.scroll = p.scrollTarget
        }
        for (p in panels) {
            for (m in p.modules) {
                val target = if (modOpen[m] == true) 1f else 0f
                val cur = modOpenAnim.getOrDefault(m, target)
                val speed = 1000f / moduleAnimMs.coerceAtLeast(1)
                modOpenAnim[m] = if (target > cur) (cur + dt * speed).coerceAtMost(target)
                else (cur - dt * speed).coerceAtLeast(target)
                val hv = overModuleRow(m)
                val hTarget = if (hv) 1f else 0f
                val hCur = modHoverAnim.getOrDefault(m, 0f)
                modHoverAnim[m] = if (hTarget > hCur) (hCur + dt * 1000f / hoverAnimMs.coerceAtLeast(1)).coerceAtMost(1f)
                else (hCur - dt * 1000f / hoverAnimMs.coerceAtLeast(1)).coerceAtLeast(0f)
            }
        }
        // 搜索圆角过渡 + 描述淡入 (原版 has-results 基于 query 非空)
        searchOpenAnim = approach(searchOpenAnim, if (searchQuery.isNotEmpty()) 1f else 0f, 12f)
        descAnim = approach(descAnim, if (descTarget != null) 1f else 0f, 18f)
        if (highlightAnim > 0f) highlightAnim = (highlightAnim - dt * 0.5f).coerceAtLeast(0f)
    }

    private fun overModuleRow(m: ClientModule): Boolean {
        val r = rowRects[m] ?: return false
        return over(r[0], r[1], r[2], r[3])
    }

    private fun drawGrid(ctx: GuiGraphicsExtractor, s: Float) {
        val g = gridSize.toFloat().coerceAtLeast(1f)
        val gc = Color4b(0x80, 0x80, 0x80, (255 * 0.25f * uiAlpha).toInt())
        // GUI 逻辑坐标(已被 pose 缩放): 可见范围 [-half/s, viewW + half/s]
        val halfW = viewW / 2f / s
        val halfH = viewH / 2f / s
        var x = (viewW / 2f - halfW - g).coerceAtLeast(0f)
        while (x < viewW / 2f + halfW) {
            ctx.drawQuad(x, 0f, x + 1f, viewH, gc)
            x += g
        }
        var y = (viewH / 2f - halfH - g).coerceAtLeast(0f)
        while (y < viewH / 2f + halfH) {
            ctx.drawQuad(0f, y, viewW, y + 1f, gc)
            y += g
        }
    }

    // ======================== 面板渲染 ========================

    private fun drawPanel(ctx: GuiGraphicsExtractor, font: Font, p: Panel) {
        val a = (uiAlpha * 255).toInt()
        val w = panelWidth
        val bodyH = p.extAnim * panelMaxHeight

        // 投影 (box-shadow 0 0 10px 黑50%) — 四向渐变带
        val sh = Color4b(baseColor.r, baseColor.g, baseColor.b, (255 * 0.5f * uiAlpha).toInt())
        val r = 10f
        for (i in 0 until 6) {
            val f = (i + 1) / 6f
            val alphaV = (sh.a * (1f - f)).toInt()
            if (alphaV <= 0) continue
            val c = Color4b(sh.r, sh.g, sh.b, alphaV)
            val off = r * f
            ctx.drawQuad(p.x - off, p.y, p.x + w + off, p.y + off, c)
            ctx.drawQuad(p.x - off, p.y + HEADER_H + bodyH - off, p.x + w + off, p.y + HEADER_H + bodyH, c)
            ctx.drawQuad(p.x - off, p.y, p.x, p.y + HEADER_H + bodyH, c)
            ctx.drawQuad(p.x + w, p.y, p.x + w + off, p.y + HEADER_H + bodyH, c)
        }

        // 标题栏 (黑90% + 底部 2px 强调色)
        ctx.drawRoundedRect(
            p.x, p.y, p.x + w, p.y + HEADER_H + 2f, panelRadius, mix(0.9f, a),
        )
        ctx.drawQuad(p.x, p.y + HEADER_H, p.x + w, p.y + HEADER_H + 2f, mix(0.9f, a))
        ctx.drawQuad(p.x, p.y + HEADER_H, p.x + w, p.y + HEADER_H + 2f, alpha(accentColor, a))

        // 模块体 (黑80%)
        if (bodyH > 0.5f) {
            ctx.drawRoundedRect(
                p.x, p.y + HEADER_H + 2f, p.x + w, p.y + HEADER_H + 2f + bodyH, panelRadius, mix(0.8f, a),
            )
            // 顶部补直角(与标题栏衔接)
            ctx.drawQuad(p.x, p.y + HEADER_H + 2f, p.x + w, p.y + HEADER_H + 8f, mix(0.8f, a))
        }

        // 分类图标 (17x15, 垂直居中)
        if (catIcons) {
            drawCategoryIcon(ctx, p.category, p.x + 15f + 8.5f, p.y + HEADER_H / 2f, a)
        }

        // 分类名 (14px, 500) — 图标(17px) + 间距 12px
        val nameX = p.x + 15f + (if (catIcons) 17f + 12f else 0f)
        drawText(
            ctx, font, p.category, nameX, p.y + (HEADER_H - 17f) / 2f,
            alpha(textColor, a), 14f,
        )

        // 展开钮 (+/-) 12px: 竖线随展开旋转 90°(高度收缩)
        val bx = p.x + w - 15f - 6f
        val by = p.y + HEADER_H / 2f
        val ic = alpha(textColor, a)
        val ext = p.extAnim
        // 横线
        ctx.drawQuad(bx - 6f, by - 1f, bx + 6f, by + 1f, ic)
        // 竖线 (展开时收缩消失)
        if (ext < 0.99f) {
            val hh = (1f - ext).coerceAtLeast(0.08f)
            ctx.drawQuad(bx - hh, by - 6f, bx + hh, by + 6f, alpha(ic, (255 * (1f - ext)).toInt()))
        }

        headerRects[p] = floatArrayOf(p.x, p.y, w, HEADER_H + 2f)

        // 模块列表
        if (bodyH > 0.5f) {
            drawModuleList(ctx, font, p, bodyH, a)
        }
    }

    private fun drawModuleList(ctx: GuiGraphicsExtractor, font: Font, p: Panel, bodyH: Float, a: Int) {
        val clipTop = p.y + HEADER_H + 2f
        val clipBot = clipTop + bodyH
        val x = p.x
        val w = panelWidth

        // 内容高度
        var contentH = 0f
        for (m in p.modules) {
            contentH += ROW_H
            contentH += settingsHeightNow(m, modOpenAnim.getOrDefault(m, 0f))
        }
        p.contentH = contentH
        val maxScroll = (contentH - bodyH).coerceAtLeast(0f)
        p.scrollTarget = p.scrollTarget.coerceIn(0f, maxScroll)

        ctx.scissorStack.withPush(ctx.getBounds(x, clipTop, x + w, clipBot)) {
            var y = clipTop - p.scroll
            for (m in p.modules) {
                val openF = modOpenAnim.getOrDefault(m, 0f)
                // 行(可见剔除)
                if (y + ROW_H > clipTop && y < clipBot) {
                    drawModuleRow(ctx, font, m, x, y, w, a)
                }
                rowRects[m] = floatArrayOf(x, y, w, ROW_H, p.index.toFloat())
                y += ROW_H

                // 设置区
                if (openF > 0.001f) {
                    val setH = settingsHeight(m) * quintOut(openF)
                    if (y + setH > clipTop && y < clipBot) {
                        // 设置区背景: 黑50% + 左侧 4px 强调色
                        ctx.drawQuad(x, y, x + w, y + setH, mix(0.5f, a))
                        ctx.drawQuad(x, y, x + 4f, y + setH, alpha(accentColor, a))
                        // 剪裁设置内容
                        val innerBottom = min(y + setH, clipBot)
                        ctx.scissorStack.withPush(ctx.getBounds(x, y, x + w, innerBottom)) {
                            walkValues(childrenOfModule(m), 0, x + 4f + SET_PAD_L, y) { v, vx, vy, vh, indent ->
                                if (vy + vh > y && vy < y + setH) {
                                    drawSetting(ctx, font, v, vx, vy, vh, indent, p, a)
                                }
                            }
                        }
                    }
                    y += setH
                }
            }

            // 滚动条 (2px, 右缘)
            if (scrollbarWidth > 0f && contentH > bodyH) {
                val thumbH = (bodyH * bodyH / contentH).coerceAtLeast(20f)
                val track = bodyH - thumbH
                val t = (p.scroll / maxScroll).coerceIn(0f, 1f)
                val ty = clipTop + track * t
                ctx.drawQuad(
                    x + w - 3f - scrollbarWidth, ty, x + w - 3f, ty + thumbH,
                    alpha(dimmedColor, (a * 0.7f).toInt()),
                )
            }
        }
    }

    private fun drawModuleRow(
        ctx: GuiGraphicsExtractor,
        font: Font,
        m: ClientModule,
        x: Float,
        y: Float,
        w: Float,
        a: Int,
    ) {
        val hoverF = modHoverAnim.getOrDefault(m, 0f)
        val enabled = m.enabled

        // 悬停背景 (黑85%)
        if (hoverF > 0.01f) {
            ctx.drawQuad(x, y, x + w, y + ROW_H, mix(0.85f, (a * hoverF).toInt()))
        }

        // 名称 (居中 12px, 超宽截断)
        val hasSettings = childrenOfModule(m).isNotEmpty()
        val name = shortenText(font, m.name, w - 8f, 12f)
        val nameW = font.width(name) * (12f / 9f)
        var nx = x + (w - nameW) / 2f
        if (nx < x + 4f) nx = x + 4f

        // 颜色: 灰(未启用) / 白(悬停) / 强调色(启用)
        val col = when {
            enabled -> alpha(accentColor, a)
            hoverF > 0.01f -> {
                val h1 = alpha(dimmedColor, a)
                val h2 = alpha(textColor, a)
                Color4b(
                    (h1.r + (h2.r - h1.r) * hoverF).toInt(),
                    (h1.g + (h2.g - h1.g) * hoverF).toInt(),
                    (h1.b + (h2.b - h1.b) * hoverF).toInt(),
                    (h1.a + (h2.a - h1.a) * hoverF).toInt(),
                )
            }
            else -> alpha(dimmedColor, a)
        }
        drawText(ctx, font, name, nx, y + (ROW_H - 14f) / 2f, col, 12f)

        // 展开箭头 (右侧 40px 区, ▼ rotate -90°→0°)
        if (hasSettings) {
            val openF = modOpenAnim.getOrDefault(m, 0f)
            val ax = x + w - EXPAND_ZONE_W / 2f - 3f
            val ay = y + ROW_H / 2f
            val c = alpha(textColor, (a * (0.5f + 0.5f * openF)).toInt())
            // 竖杆 + 斜翼(向下箭头)
            ctx.drawQuad(ax - 1f, ay - 4f, ax + 1f, ay + 1f, c)
            ctx.drawQuad(ax - 3f, ay - 2f, ax - 1f, ay, c)
            ctx.drawQuad(ax + 1f, ay - 2f, ax + 3f, ay, c)
            ctx.drawQuad(ax - 3f, ay - 0.5f, ax + 3f, ay + 1f, c)
        }

        // 描述气泡目标检测(悬停行)
        if (descEnabled && over(x, y, w, ROW_H)) {
            updateDescTarget(m, x, y, w)
        }
    }

    private fun updateDescTarget(m: ClientModule, x: Float, y: Float, w: Float) {
        if (descTarget !== m) {
            descTarget = m
            descAnim = 0f
        }
        // 右侧可视空间 > 300 则气泡在右侧 (GUI 逻辑坐标可视右缘)
        val guiRight = viewW / 2f + viewW / (2f * scale)
        descAnchorRight = (guiRight - (x + w)) > 300f
    }

    // ======================== 定位高亮 ========================

    private fun drawHighlight(ctx: GuiGraphicsExtractor) {
        val m = highlightMod ?: return
        if (highlightAnim <= 0.01f) return
        val r = rowRects[m] ?: return
        val a = (255 * highlightAnim * uiAlpha).toInt()
        val b = alpha(accentColor, a)
        ctx.drawQuad(r[0], r[1], r[0] + r[2], r[1] + 2f, b)
        ctx.drawQuad(r[0], r[1] + r[3] - 2f, r[0] + r[2], r[1] + r[3], b)
        ctx.drawQuad(r[0], r[1], r[0] + 2f, r[1] + r[3], b)
        ctx.drawQuad(r[0] + r[2] - 2f, r[1], r[0] + r[2], r[1] + r[3], b)
    }

    // ======================== 描述气泡 ========================

    /** 模块描述 (Value.description 是 Supplier<String?>, 兼容取值) */
    private fun modDesc(m: ClientModule): String {
        val d: Any? = m.description
        val str = if (d is java.util.function.Supplier<*>) {
            d.get()?.toString()
        } else {
            d?.toString()
        }
        return str?.takeIf { it.isNotBlank() } ?: m.name
    }

    private fun drawDescription(ctx: GuiGraphicsExtractor, font: Font) {
        if (descAnim <= 0.01f) return
        val m = descTarget ?: return
        val r = rowRects[m] ?: return
        val a = (255 * descAnim * uiAlpha).toInt()

        var text = modDesc(m)
        if (m.aliases.isNotEmpty()) {
            text += " (aka ${m.aliases.joinToString(", ")})"
        }

        val size = 12f
        val sc = size / 9f
        val textW = font.width(text) * sc
        val bw = textW + 20f
        val bh = 34f
        val cy = r[1] + r[3] / 2f

        // fly 动画偏移 (±15)
        val slide = 15f * (1f - descAnim)

        val bx: Float
        val arrowLeft: Boolean
        if (descAnchorRight) {
            bx = r[0] + r[2] + DESC_OFFSET + slide
            arrowLeft = true   // 箭头在左侧指向模块
        } else {
            bx = r[0] - DESC_OFFSET - bw - slide
            arrowLeft = false
        }
        val by = cy - bh / 2f

        // 气泡
        ctx.drawRoundedRect(bx, by, bx + bw, by + bh, 5f, mix(0.9f, a))
        drawText(ctx, font, text, bx + 10f, by + (bh - 14f) / 2f, alpha(textColor, a), size)

        // 箭头 (8px 三角指向模块)
        val ac = mix(0.9f, a)
        val ay = cy
        if (arrowLeft) {
            var i = 0
            while (i < 8) {
                ctx.drawQuad(bx - 8f + i, ay - (8 - i), bx - 8f + i + 1f, ay + (8 - i), ac)
                i++
            }
        } else {
            var i = 0
            while (i < 8) {
                ctx.drawQuad(bx + bw + 7f - i, ay - (8 - i), bx + bw + 8f - i, ay + (8 - i), ac)
                i++
            }
        }
    }

    // ======================== 搜索栏 ========================

    private fun drawSearch(ctx: GuiGraphicsExtractor, font: Font) {
        val a = (uiAlpha * 255).toInt()
        val sw = searchWidth
        val sx = viewW / 2f - sw / 2f
        val sy = searchY
        val inputH = 49f

        // 投影
        val sh = Color4b(baseColor.r, baseColor.g, baseColor.b, (255 * 0.5f * uiAlpha).toInt())
        for (i in 0 until 4) {
            val f = (i + 1) / 4f
            val c = Color4b(sh.r, sh.g, sh.b, (sh.a * (1f - f)).toInt())
            if (c.a <= 0) continue
            val off = 10f * f
            ctx.drawQuad(sx - off, sy - off, sx + sw + off, sy + off, c)
            ctx.drawQuad(sx - off, sy, sx + off, sy + inputH, c)
            ctx.drawQuad(sx + sw - off, sy, sx + sw + off, sy + inputH, c)
        }

        val hasResults = searchResults.isNotEmpty()
        val r = searchRadius + (10f - searchRadius) * searchOpenAnim
        val resultsH = if (hasResults) min(searchResults.size * 39f, 250f) else if (searchQuery.isNotEmpty()) 39f else 0f

        // 背景
        if (resultsH > 0.5f) {
            ctx.drawRoundedRect(sx, sy, sx + sw, sy + inputH + 2f + resultsH, r, mix(0.9f, a))
            ctx.drawQuad(sx + 2f, sy + inputH, sx + sw - 2f, sy + inputH + resultsH + 2f, mix(0.9f, a))
        } else {
            ctx.drawRoundedRect(sx, sy, sx + sw, sy + inputH, r, mix(0.9f, a))
        }

        // 输入文字 / 占位符
        val showText = if (searchQuery.isEmpty()) "Search" else searchQuery
        val col = if (searchQuery.isEmpty()) alpha(dimmedColor, (a * 0.6f).toInt()) else alpha(textColor, a)
        drawText(ctx, font, showText, sx + 25f, sy + (inputH - 19f) / 2f, col, 16f)
        // 光标(聚焦时闪烁)
        if (searchFocus && (System.currentTimeMillis() / 500) % 2 == 0L) {
            val cw = font.width(searchQuery) * (16f / 9f)
            ctx.drawQuad(sx + 25f + cw + 1f, sy + 16f, sx + 25f + cw + 2f, sy + inputH - 16f, alpha(textColor, a))
        }

        if (resultsH <= 0.5f) return

        // 顶部 2px 强调色边框
        ctx.drawQuad(sx, sy + inputH, sx + sw, sy + inputH + 2f, alpha(accentColor, a))

        val listH = resultsH
        ctx.scissorStack.withPush(ctx.getBounds(sx, sy + inputH + 2f, sx + sw, sy + inputH + 2f + listH)) {
            // 无结果占位行
            if (!hasResults && searchQuery.isNotEmpty()) {
                drawText(
                    ctx, font, "No modules found", sx + 25f, sy + inputH + 2f + 10f,
                    alpha(dimmedColor, a), 16f,
                )
                return@withPush
            }
            var y = sy + inputH + 2f - searchScroll
            for ((idx, m) in searchResults.withIndex()) {
                if (y + 39f < sy + inputH || y > sy + inputH + 2f + listH) {
                    y += 39f
                    continue
                }
                val selected = idx == searchSelected
                // 选中项 padding-left 10
                val padL = if (selected) 10f else 0f
                // 名称 (启用=强调色)
                val nameCol = if (m.enabled) alpha(accentColor, a) else alpha(dimmedColor, a)
                drawText(ctx, font, m.name, sx + 25f + padL, y + (39f - 19f) / 2f, nameCol, 16f)
                val nameW = font.width(m.name) * (16f / 9f)
                // 别名
                if (m.aliases.isNotEmpty()) {
                    val alias = "(aka ${m.aliases.joinToString(", ")})"
                    val aw = font.width(alias) * (16f / 9f)
                    val ax = sx + 25f + padL + nameW + 10f
                    if (ax + aw < sx + sw - 25f) {
                        drawText(ctx, font, alias, ax, y + (39f - 19f) / 2f, alpha(dimmedColor, (a * 0.6f).toInt()), 16f)
                    }
                }
                // 悬停提示 "Right-click to locate"
                if (over(sx, y, sw, 39f)) {
                    val hint = "Right-click to locate"
                    val hw = font.width(hint) * (12f / 9f)
                    drawText(ctx, font, hint, sx + sw - 25f - hw, y + (39f - 14f) / 2f, alpha(textColor, (a * 0.4f).toInt()), 12f)
                }
                y += 39f
            }
        }
    }

    // ======================== 设置区布局 (setting/*.svelte 还原) ========================

    /**
     * 设置布局遍历: 从 (originX, originY) 起逐行排列, emit(v, x, y, h, indent), 返回结束 y。
     * GROUP / TOGGLE_GROUP / MODE 展开时递归子项(嵌套缩进 2px 边框 + 7px padding)。
     */
    private fun walkValues(
        values: List<Value<*>>,
        indent: Int,
        originX: Float,
        originY: Float,
        emit: (v: Value<*>, x: Float, y: Float, h: Float, indent: Int) -> Unit,
    ): Float {
        var y = originY
        for (v in values) {
            val vi = info(v)
            val rowH = settingRowHeight(v, vi, indent)
            emit(v, originX, y, rowH, indent)
            y += rowH
            if (vi.kind == Kind.GROUP || vi.kind == Kind.TOGGLE_GROUP || vi.kind == Kind.MODE) {
                if (groupOpen[v] == true) {
                    val children = when (vi.kind) {
                        Kind.TOGGLE_GROUP -> groupNested(v)
                        else -> childrenOf(v)
                    }
                    if (children.isNotEmpty()) {
                        y += 10f // .head.expanded { margin-bottom: 10px }
                        y = walkValues(children, indent + 1, originX + NESTED_INDENT, y, emit)
                    }
                }
            }
        }
        return y
    }

    /** 各类型设置行高 (与 Svelte 组件 padding/最小高一致) */
    private fun settingRowHeight(v: Value<*>, vi: VInfo, indent: Int): Float = when (vi.kind) {
        Kind.BOOL, Kind.TOGGLE_GROUP, Kind.GROUP -> SETTING_ROW_PAD * 2f + 14f
        Kind.SLIDER, Kind.DUAL -> SLIDER_H
        Kind.MODE, Kind.CHOICE -> SETTING_ROW_PAD * 2f + DROPDOWN_H
        Kind.MULTI -> {
            var h = SETTING_ROW_PAD * 2f + 14f
            if (groupOpen[v] == true) h += 10f + chipsHeight(v, indent)
            h
        }
        Kind.TEXT -> SETTING_ROW_PAD + 14f + 5f + 26f + SETTING_ROW_PAD
        Kind.COLOR -> SETTING_ROW_PAD * 2f + 16f + (if (colorOpen == v) PICKER_TOTAL else 0f)
        Kind.KEY, Kind.BIND -> SETTING_ROW_PAD * 2f + 26f
        else -> SETTING_ROW_PAD * 2f + 14f
    }

    /** TOGGLE_GROUP 头部开关(第一个 boolean 子项) */
    private fun groupHeadSwitch(v: Value<*>): Value<*>? =
        childrenOf(v).firstOrNull { it.valueType == ValueType.BOOLEAN }

    /** TOGGLE_GROUP 展开后的子项(跳过头部开关) */
    private fun groupNested(v: Value<*>): List<Value<*>> {
        val all = childrenOf(v)
        val head = groupHeadSwitch(v)
        return if (head != null) all.filter { it !== head } else all
    }

    /** 开关切换: TOGGLE_GROUP 切换其头部 boolean, 普通布尔直接切换 */
    private fun toggleSwitch(v: Value<*>) {
        runCatching {
            if (info(v).kind == Kind.TOGGLE_GROUP) {
                val head = groupHeadSwitch(v) ?: return
                (head as Value<Any>).set(!(head.get() as Boolean))
            } else {
                (v as Value<Any>).set(!(v.get() as Boolean))
            }
        }
    }

    /** 多选 chips 换行布局: 返回每行的 (choice, 宽) 列表 */
    private fun chipRows(font: Font, v: Value<*>, maxW: Float): List<List<Pair<Any?, Float>>> {
        val vi = info(v)
        val rows = ArrayList<List<Pair<Any?, Float>>>()
        var cur = ArrayList<Pair<Any?, Float>>()
        var curW = 0f
        for (c in vi.choices) {
            val label = taggedLabel(c)
            val cw = strW(font, label, 12f) + 12f // padding 3x6
            val gap = if (cur.isEmpty()) 0f else 7f
            if (cur.isNotEmpty() && curW + gap + cw > maxW) {
                rows.add(cur)
                cur = ArrayList()
                curW = 0f
            }
            curW += (if (cur.isEmpty()) 0f else 7f) + cw
            cur.add(c to cw)
        }
        if (cur.isNotEmpty()) rows.add(cur)
        return rows
    }

    /** 多选 chips 容器高度 (padding 7x2 + 行 20 + 行距 7) */
    private fun chipsHeight(v: Value<*>, indent: Int): Float {
        val maxW = panelWidth - 22f - NESTED_INDENT * indent - 16f
        val rows = chipRows(mc.font, v, maxW)
        return 14f + rows.size * 20f + (rows.size - 1).coerceAtLeast(0) * 7f
    }

    // ======================== 基础绘制工具 ========================

    /** 按像素字号绘制文本 (MC 字体基准 9px) */
    private fun drawText(
        ctx: GuiGraphicsExtractor, font: Font, text: String,
        x: Float, y: Float, color: Color4b, size: Float,
    ) {
        if (text.isEmpty()) return
        val k = size / 9f
        ctx.pose().withPush {
            translate(x, y)
            scale(k, k)
            ctx.text(font, text, 0, 0, color.argb, false)
        }
    }

    private fun strW(font: Font, text: String, size: Float): Float = font.width(text) * (size / 9f)

    /** 数值显示: noUiSlider toFixed(4) 规则(去尾零) */
    private fun fmtNum(f: Float, isInt: Boolean): String =
        if (isInt) {
            f.roundToInt().toString()
        } else {
            val s = String.format("%.4f", f).trimEnd('0').trimEnd('.')
            if (s.isEmpty() || s == "-") "0" else s
        }

    private fun lerpColor(c1: Color4b, c2: Color4b, t: Float): Color4b = Color4b(
        (c1.r + (c2.r - c1.r) * t).toInt().coerceIn(0, 255),
        (c1.g + (c2.g - c1.g) * t).toInt().coerceIn(0, 255),
        (c1.b + (c2.b - c1.b) * t).toInt().coerceIn(0, 255),
        (c1.a + (c2.a - c1.a) * t).toInt().coerceIn(0, 255),
    )

    /** color-mix(in srgb, c N%, black) */
    private fun mixColor(c: Color4b, pct: Float, a: Int = 255): Color4b = Color4b(
        (c.r * pct).toInt().coerceIn(0, 255),
        (c.g * pct).toInt().coerceIn(0, 255),
        (c.b * pct).toInt().coerceIn(0, 255),
        a,
    )

    /** color-mix(in srgb, c N%, transparent) */
    private fun alphaFrac(c: Color4b, frac: Float, a: Int): Color4b = Color4b(
        c.r, c.g, c.b, (255f * frac * a / 255f).toInt().coerceIn(0, 255),
    )

    /** 阶梯对角线 */
    private fun diag(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, t: Float, c: Color4b) {
        val steps = max(abs(x2 - x1), abs(y2 - y1)).toInt().coerceAtLeast(1)
        for (i in 0 until steps) {
            val f0 = i / steps.toFloat()
            val f1 = (i + 1) / steps.toFloat()
            ctx.drawQuad(
                x1 + (x2 - x1) * f0 - t * 0.5f, y1 + (y2 - y1) * f0 - t * 0.5f,
                x1 + (x2 - x1) * f1 + t * 0.5f, y1 + (y2 - y1) * f1 + t * 0.5f,
                c,
            )
        }
    }

    /** KEY 值存 InputConstants.Key, BIND 值存 InputBind */
    private fun keyLabel(v: Value<*>): String = runCatching {
        when (val cur: Any? = v.get()) {
            null -> "None"
            is InputConstants.Key -> if (cur == InputConstants.UNKNOWN) "None" else cur.displayName.string.ifBlank { "None" }
            is InputBind -> {
                val bk = cur.boundKey
                if (bk == InputConstants.UNKNOWN) "None" else bk.displayName.string.ifBlank { "None" }
            }
            else -> cur.toString().ifBlank { "None" }
        }
    }.getOrDefault("None")

    /** 写入按键: KEY 直接存 Key, BIND 存 InputBind */
    private fun setKeyValue(v: Value<*>, k: InputConstants.Key) {
        runCatching {
            when (val cur = v.get()) {
                is InputBind -> (v as Value<Any>).set(cur.copy(boundKey = k))
                else -> (v as Value<Any>).set(k)
            }
        }
    }

    // ======================== 分类图标 (icon-{category}.svg 矢量近似) ========================

    private fun drawCategoryIcon(ctx: GuiGraphicsExtractor, category: String, cx: Float, cy: Float, a: Int) {
        val c = alpha(textColor, a)
        val bg = mix(0.9f, a)
        val x = cx - 8.5f
        val y = cy - 7.5f
        when (category.lowercase()) {
            "combat" -> { // 交叉剑
                diag(ctx, x + 1f, y + 1f, x + 16f, y + 14f, 1.8f, c)
                diag(ctx, x + 16f, y + 1f, x + 1f, y + 14f, 1.8f, c)
            }
            "player" -> { // 人形
                ctx.drawRoundedRect(x + 5.5f, y, x + 11.5f, y + 5.5f, 2.75f, c)
                ctx.drawRoundedRect(x + 2f, y + 7.5f, x + 15f, y + 15f, 3.5f, c)
            }
            "movement" -> { // 双箭头
                for (off in 0..1) {
                    val ox = x + 2f + off * 6f
                    diag(ctx, ox, y + 1f, ox + 5f, y + 7.5f, 1.8f, c)
                    diag(ctx, ox, y + 14f, ox + 5f, y + 7.5f, 1.8f, c)
                }
            }
            "render" -> { // 眼睛
                diag(ctx, x + 0.5f, y + 7.5f, x + 8.5f, y + 1.5f, 1.6f, c)
                diag(ctx, x + 8.5f, y + 1.5f, x + 16.5f, y + 7.5f, 1.6f, c)
                diag(ctx, x + 0.5f, y + 7.5f, x + 8.5f, y + 13.5f, 1.6f, c)
                diag(ctx, x + 8.5f, y + 13.5f, x + 16.5f, y + 7.5f, 1.6f, c)
                ctx.drawRoundedRect(x + 7f, y + 5.5f, x + 10f, y + 9.5f, 1.5f, c)
            }
            "world" -> { // 地球
                ctx.drawRoundedRect(x + 1f, y + 0.5f, x + 16f, y + 14.5f, 7f, c)
                ctx.drawRoundedRect(x + 3f, y + 2.5f, x + 14f, y + 12.5f, 5f, bg)
                ctx.drawQuad(x + 1.5f, y + 6.5f, x + 15.5f, y + 8.5f, c)
                ctx.drawQuad(x + 7.5f, y + 1f, x + 9.5f, y + 14f, c)
            }
            "misc" -> { // 三点
                ctx.drawRoundedRect(x + 2f, y + 1f, x + 5.5f, y + 4.5f, 1.75f, c)
                ctx.drawRoundedRect(x + 6.75f, y + 5.25f, x + 10.25f, y + 8.75f, 1.75f, c)
                ctx.drawRoundedRect(x + 11.5f, y + 9.5f, x + 15f, y + 13f, 1.75f, c)
            }
            "exploit" -> { // 闪电
                ctx.drawQuad(x + 9f, y, x + 16f, y + 3f, c)
                ctx.drawQuad(x + 4f, y + 3.5f, x + 11f, y + 6.5f, c)
                ctx.drawQuad(x + 6f, y + 6.5f, x + 13f, y + 9.5f, c)
                ctx.drawQuad(x + 1f, y + 9.5f, x + 8f, y + 12.5f, c)
                ctx.drawQuad(x + 1f, y + 12.5f, x + 5f, y + 15f, c)
            }
            "fun" -> { // 笑脸
                ctx.drawRoundedRect(x + 0.5f, y, x + 16.5f, y + 15f, 7.5f, c)
                ctx.drawRoundedRect(x + 2.5f, y + 2f, x + 14.5f, y + 13f, 5.5f, bg)
                ctx.drawRoundedRect(x + 4.5f, y + 5f, x + 6.5f, y + 7f, 1f, c)
                ctx.drawRoundedRect(x + 10.5f, y + 5f, x + 12.5f, y + 7f, 1f, c)
                ctx.drawQuad(x + 5f, y + 9.5f, x + 12f, y + 11f, c)
            }
            "client" -> { // 显示器
                ctx.drawRoundedRect(x + 0.5f, y + 1f, x + 16.5f, y + 12f, 2f, c)
                ctx.drawQuad(x + 7f, y + 12.5f, x + 10f, y + 15f, c)
            }
            else -> ctx.drawRoundedRect(x + 3f, y + 2f, x + 14f, y + 13f, 2f, c)
        }
    }

    // ======================== 设置组件渲染 ========================

    private fun drawSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, indent: Int,
        p: Panel, a: Int,
    ) {
        val vi = info(v)
        val w = (p.x + panelWidth - SET_PAD_R) - x
        when (vi.kind) {
            Kind.BOOL -> drawBoolSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.TOGGLE_GROUP -> drawToggleGroupSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.GROUP -> drawGroupSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.SLIDER -> drawSliderSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.DUAL -> drawSliderSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.MODE, Kind.CHOICE -> drawDropdownSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.MULTI -> drawMultiSetting(ctx, font, v, x, y, h, w, p, a, indent)
            Kind.TEXT -> drawTextSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.COLOR -> drawColorSetting(ctx, font, v, x, y, h, w, p, a)
            Kind.KEY, Kind.BIND -> drawKeySetting(ctx, font, v, x, y, h, w, p, a)
            else -> {
                drawText(ctx, font, v.name, x, y + (h - 14f) / 2f, alpha(textColor, a), 12f)
                val s = runCatching { v.get().toString() }.getOrDefault("")
                drawText(ctx, font, s, x + w - strW(font, s, 12f), y + (h - 14f) / 2f, alpha(dimmedColor, a), 12f)
            }
        }
    }

    /** Switch.svelte: 22x12 轨道 r4 + 12px 圆钮 */
    private fun drawSwitch(ctx: GuiGraphicsExtractor, v: Value<*>, on: Boolean, x: Float, y: Float, a: Int) {
        val cur = switchAnim.getOrDefault(v, if (on) 1f else 0f)
        val t = approach(cur, if (on) 1f else 0f, 2.5f) // ease 0.4s
        switchAnim[v] = t
        val trackOff = mixColor(textColor, 0.45f, a)
        val trackOn = mixColor(accentColor, 0.4f, a)
        ctx.drawRoundedRect(x, y + 2f, x + SWITCH_W, y + 10f, 4f, lerpColor(trackOff, trackOn, t))
        val tx = x + t * (SWITCH_W - 12f)
        ctx.drawRoundedRect(
            tx, y, tx + 12f, y + 12f, 6f,
            lerpColor(alpha(textColor, a), alpha(accentColor, a), t),
        )
    }

    /** BooleanSetting: Switch + 名称 */
    private fun drawBoolSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val on = runCatching { v.get() as Boolean }.getOrDefault(false)
        drawSwitch(ctx, v, on, x, y + (h - 12f) / 2f, a)
        drawText(ctx, font, v.name, x + SWITCH_W + 7f, y + (h - 14f) / 2f, alpha(textColor, a), 12f)
        switchRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
    }

    /** ConfigurableSetting: 标题 + 展开箭头 */
    private fun drawGroupSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        drawText(ctx, font, v.name, x, y + (h - 14f) / 2f, alpha(textColor, a), 12f)
        if (childrenOf(v).isNotEmpty()) {
            drawExpandArrow(ctx, v, x + w - 9f, y + h / 2f, a)
        }
        groupRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
    }

    /** TogglableSetting: 头部 Switch(第一个子项) + 展开箭头 */
    private fun drawToggleGroupSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val head = groupHeadSwitch(v)
        val nested = groupNested(v)
        val on = runCatching { head?.get() as Boolean }.getOrDefault(false)
        if (head != null) {
            drawSwitch(ctx, head, on, x, y + (h - 12f) / 2f, a)
        }
        drawText(ctx, font, v.name, x + SWITCH_W + 7f, y + (h - 14f) / 2f, alpha(textColor, a), 12f)
        if (nested.isNotEmpty()) {
            drawExpandArrow(ctx, v, x + w - 9f, y + h / 2f, a)
            switchRects[v] = floatArrayOf(x, y, w - 20f, h, p.index.toFloat())
            groupRects[v] = floatArrayOf(x + w - 20f, y, 20f, h, p.index.toFloat())
        } else {
            switchRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
        }
    }

    /** Float/Int/Range Setting: 名称 + 可编辑数值 + 后缀 / 轨道滑条 */
    private fun drawSliderSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val vi = info(v)
        val bounds = vi.range ?: run {
            // 无范围: 仅显示名称与值
            drawText(ctx, font, v.name, x, y + 7f, alpha(textColor, a), 12f)
            return
        }
        val (minV, maxV) = bounds

        // 第一行: 名称(左) + 数值(右) + 后缀
        val editing = textEdit == v
        val valueStr = if (editing) textBuf else when (vi.kind) {
            Kind.DUAL -> {
                val (lo, hi) = dualOf(v)
                "${fmtNum(lo, vi.isInt)} - ${fmtNum(hi, vi.isInt)}"
            }
            else -> fmtNum(numOf(v), vi.isInt)
        }
        val valueW = strW(font, valueStr, 12f)
        val suffixW = if (vi.suffix.isNotEmpty()) strW(font, vi.suffix, 12f) + 5f else 0f
        val valueX = x + w - valueW - suffixW
        drawText(ctx, font, v.name, x, y + 7f, alpha(textColor, a), 12f)
        drawText(ctx, font, valueStr, valueX, y + 7f, alpha(textColor, a), 12f)
        if (vi.suffix.isNotEmpty()) {
            drawText(ctx, font, vi.suffix, x + w - strW(font, vi.suffix, 12f), y + 7f, alpha(textColor, a), 12f)
        }
        // 编辑光标
        if (editing && (System.currentTimeMillis() / 500) % 2 == 0L) {
            ctx.drawQuad(valueX + valueW + 1f, y + 7f, valueX + valueW + 2f, y + 21f, alpha(textColor, a))
        }
        // 数值区点击 → 编辑
        textRects[v] = floatArrayOf(valueX - 6f, y, valueW + suffixW + 10f, 24f, p.index.toFloat())

        // 第二行: 轨道 (margin 10px, 高 2px, padding-right 10px)
        val trackX = x
        val trackW = w - 10f
        val trackY = y + 31f
        val trackC = mixColor(textColor, 0.2f, a)
        val fillC = alpha(accentColor, a)
        if (vi.kind == Kind.DUAL) {
            val (lo, hi) = dualOf(v)
            val t1 = ((lo - minV) / (maxV - minV)).coerceIn(0f, 1f)
            val t2 = ((hi - minV) / (maxV - minV)).coerceIn(0f, 1f)
            ctx.drawQuad(trackX, trackY, trackX + trackW, trackY + 2f, trackC)
            ctx.drawQuad(trackX + t1 * trackW, trackY, trackX + t2 * trackW, trackY + 2f, fillC)
            // 双圆钮
            ctx.drawRoundedRect(trackX + t1 * trackW - 6f, trackY - 5f, trackX + t1 * trackW + 6f, trackY + 7f, 6f, fillC)
            ctx.drawRoundedRect(trackX + t2 * trackW - 6f, trackY - 5f, trackX + t2 * trackW + 6f, trackY + 7f, 6f, fillC)
        } else {
            val t = ((numOf(v) - minV) / (maxV - minV)).coerceIn(0f, 1f)
            ctx.drawQuad(trackX, trackY, trackX + trackW, trackY + 2f, trackC)
            ctx.drawQuad(trackX, trackY, trackX + t * trackW, trackY + 2f, fillC)
            ctx.drawRoundedRect(trackX + t * trackW - 6f, trackY - 5f, trackX + t * trackW + 6f, trackY + 7f, 6f, fillC)
        }
        // 命中区仅覆盖轨道行(第二行), 点击名称/数值不会跳变数值
        sliderRects[v] = floatArrayOf(trackX, y + 22f, trackW, h - 22f, p.index.toFloat())
    }

    /** Dropdown 触发器: 强调色底 r3, "name • value", 内嵌箭头 */
    private fun drawDropdownSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val vi = info(v)
        val open = dropdownOpen[v] == true
        val nested = childrenOf(v)
        val hx = x
        val hy = y + SETTING_ROW_PAD
        val hy2 = hy + DROPDOWN_H
        val headC = alpha(accentColor, a)

        ctx.drawRoundedRect(hx, hy, hx + w, hy2, 3f, headC)
        if (open) {
            // 展开时上圆角下直角
            ctx.drawQuad(hx, hy2 - 3f, hx + 3f, hy2, headC)
            ctx.drawQuad(hx + w - 3f, hy2 - 3f, hx + w, hy2, headC)
        }

        // 文本 "name • value" (省略号截断, 右侧留箭头 20px)
        val label = "${v.name} • ${choiceLabel(v)}"
        val maxTextW = w - 20f - 20f - 10f
        val text = shortenText(font, label, maxTextW, 12f)
        drawText(ctx, font, text, hx + 10f, hy + (DROPDOWN_H - 14f) / 2f, alpha(textColor, a), 12f)

        // 内嵌下拉箭头 (dropdownOpen, 右: 10px)
        drawDropdownArrow(ctx, v, hx + w - 15.5f, hy + DROPDOWN_H / 2f, a)

        // 命中: 触发器(左键开选项/右键切嵌套) + 嵌套箭头区
        if (nested.isNotEmpty()) {
            dropdownHeadRects[v] = floatArrayOf(x, y, w - 20f, h, p.index.toFloat())
            drawExpandArrow(ctx, v, x + w - 9f, y + h / 2f, a)
            groupRects[v] = floatArrayOf(x + w - 20f, y, 20f, h, p.index.toFloat())
        } else {
            dropdownHeadRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
        }
    }

    /** ExpandArrow: ▼(-90° 收起指向右 → 0° 展开指向下) */
    private fun drawExpandArrow(ctx: GuiGraphicsExtractor, v: Value<*>, cx: Float, cy: Float, a: Int) {
        val open = groupOpen[v] == true
        val cur = groupOpenAnim.getOrDefault(v, if (open) 1f else 0f)
        val t = approach(cur, if (open) 1f else 0f, 2.5f)
        groupOpenAnim[v] = t
        drawChevron(ctx, cx, cy, t, a)
    }

    /** Dropdown 内嵌箭头 (dropdownOpen 状态) */
    private fun drawDropdownArrow(ctx: GuiGraphicsExtractor, v: Value<*>, cx: Float, cy: Float, a: Int) {
        val open = dropdownOpen[v] == true
        val cur = dropdownAnim.getOrDefault(v, if (open) 1f else 0f)
        val t = approach(cur, if (open) 1f else 0f, 2.5f)
        dropdownAnim[v] = t
        drawChevron(ctx, cx, cy, t, a)
    }

    /** chevron: t=0 指向右(收起), t=1 指向下(展开) */
    private fun drawChevron(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, t: Float, a: Int) {
        val rad = Math.toRadians(90.0 * (1 - t))
        val dx = sin(rad).toFloat()
        val dy = cos(rad).toFloat()
        val px = -dy
        val py = dx
        val l = 5.5f
        val c = alpha(textColor, (a * 0.8f).toInt())
        val tipX = cx + dx * 2f
        val tipY = cy + dy * 2f
        diag(ctx, tipX, tipY, tipX - dx * l + px * l, tipY - dy * l + py * l, 1.6f, c)
        diag(ctx, tipX, tipY, tipX - dx * l - px * l, tipY - dy * l - py * l, 1.6f, c)
    }

    /** MultiChooseSetting: 标题 + n/m + 箭头 + chips */
    private fun drawMultiSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int, indent: Int,
    ) {
        val vi = info(v)
        val selected = multiSelected(v)

        // 头部
        drawText(ctx, font, v.name, x, y + SETTING_ROW_PAD, alpha(textColor, a), 12f)
        val amount = "${selected.size}/${vi.choices.size}"
        drawText(ctx, font, amount, x + w - 20f - strW(font, amount, 12f), y + SETTING_ROW_PAD, alpha(textColor, a), 12f)
        drawExpandArrow(ctx, v, x + w - 9f, y + SETTING_ROW_PAD + 7f, a)
        groupRects[v] = floatArrayOf(x, y, w, SETTING_ROW_PAD * 2f + 14f, p.index.toFloat())

        if (groupOpen[v] != true) return

        // chips 容器: 左侧 2px 强调色边框 + padding 7
        val contY = y + SETTING_ROW_PAD * 2f + 14f + 10f
        val contH = chipsHeight(v, indent)
        ctx.drawQuad(x, contY, x + 2f, contY + contH, alpha(accentColor, a))
        val maxW = w - 16f
        val rows = chipRows(font, v, maxW)
        var cy = contY + 7f
        for (row in rows) {
            var cx = x + 9f
            for ((choice, cw) in row) {
                val active = selected.contains(choice)
                val hovered = over(cx, cy, cw, 20f)
                val bgC = if (active) alphaFrac(accentColor, 0.12f, a) else mix(0.3f, a)
                val txC = when {
                    active -> alpha(accentColor, a)
                    hovered -> alpha(textColor, a)
                    else -> alpha(dimmedColor, a)
                }
                ctx.drawRoundedRect(cx, cy, cx + cw, cy + 20f, 3f, bgC)
                drawText(ctx, font, taggedLabel(choice), cx + 6f, cy + 3f, txC, 12f)
                chipRects.add(Triple(v, choice, floatArrayOf(cx, cy, cw, 20f, p.index.toFloat())))
                cx += cw + 7f
            }
            cy += 20f + 7f
        }
    }

    /** TextSetting: 名称 + 输入框(黑36%底 + 底部 2px 强调色边) */
    private fun drawTextSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        drawText(ctx, font, v.name, x, y + SETTING_ROW_PAD, alpha(textColor, a), 12f)
        val boxY = y + SETTING_ROW_PAD + 14f + 5f
        val boxH = 26f
        ctx.drawRoundedRect(x, boxY, x + w, boxY + boxH, 3f, mix(0.36f, a))
        ctx.drawQuad(x, boxY + boxH - 2f, x + w, boxY + boxH, alpha(accentColor, a))

        val editing = textEdit == v
        val cur = (v.get() as? String) ?: ""
        if (editing) {
            drawText(ctx, font, textBuf, x + 5f, boxY + 5f, alpha(textColor, a), 12f)
            if ((System.currentTimeMillis() / 500) % 2 == 0L) {
                val cw = strW(font, textBuf, 12f)
                ctx.drawQuad(x + 5f + cw + 1f, boxY + 5f, x + 5f + cw + 2f, boxY + 19f, alpha(textColor, a))
            }
        } else if (cur.isNotEmpty()) {
            drawText(ctx, font, cur, x + 5f, boxY + 5f, alpha(textColor, a), 12f)
        } else {
            drawText(ctx, font, v.name, x + 5f, boxY + 5f, alpha(dimmedColor, (a * 0.6f).toInt()), 12f)
        }
        textRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
    }

    /** ColorSetting: 名称 + HEX + 色块 + 内联 HSV 取色器 */
    private fun drawColorSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val cur = colorOf(v)

        // 色块 (30px 宽, 右缘, accent 边框)
        val swX = x + w - 30f
        val swY = y + 5f
        ctx.drawRoundedRect(swX, swY, x + w, y + 25f, 3f, alpha(cur, a))
        ctx.drawRoundedRect(swX, swY, x + w, y + 25f, 3f, Color4b.TRANSPARENT, alpha(accentColor, a), 1f)

        // HEX (右对齐, 70px 区, 与色块间距 15)
        val hex = if (cur.a < 255) {
            String.format("#%02X%02X%02X%02X", cur.r, cur.g, cur.b, cur.a)
        } else {
            String.format("#%02X%02X%02X", cur.r, cur.g, cur.b)
        }
        val hexW = strW(font, hex, 12f)
        drawText(ctx, font, hex, x + w - 30f - 15f - hexW, y + 8f, alpha(textColor, a), 12f)

        drawText(ctx, font, v.name, x, y + 8f, alpha(textColor, a), 12f)
        colorRects[v] = floatArrayOf(x, y, w, SETTING_ROW_PAD * 2f + 16f, p.index.toFloat())

        if (colorOpen != v) return

        // ---- 内联取色器 (pickr classic) ----
        val hue = colorHue.getOrDefault(v, rgbToHsv(cur).first)
        val (sv, vv) = colorSV.getOrDefault(v, 0f to 1f)

        // SB 面板: 左→右 饱和度, 上→下 亮度递减
        val sbY = y + SETTING_ROW_PAD * 2f + 16f + PICKER_GAP
        val cols = 24
        val rows = 16
        val cw = w / cols
        val ch = PICKER_SB_H / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                ctx.drawQuad(
                    x + cw * c, sbY + ch * r, x + cw * (c + 1), sbY + ch * (r + 1),
                    hsvToRgb(hue, (c + 0.5f) / cols, 1f - (r + 0.5f) / rows, a),
                )
            }
        }
        // SB 光标 (白环)
        val cursorX = x + sv.coerceIn(0f, 1f) * w
        val cursorY = sbY + (1f - vv.coerceIn(0f, 1f)) * PICKER_SB_H
        ctx.drawRoundedRect(cursorX - 5f, cursorY - 5f, cursorX + 5f, cursorY + 5f, 5f, alpha(Color4b(255, 255, 255), a))
        ctx.drawRoundedRect(cursorX - 3f, cursorY - 3f, cursorX + 3f, cursorY + 3f, 3f, alpha(Color4b(0, 0, 0), a))

        // 色相条
        val hueY = sbY + PICKER_SB_H + PICKER_GAP
        val segs = 32
        val sw = w / segs
        for (i in 0 until segs) {
            ctx.drawQuad(
                x + sw * i, hueY, x + sw * (i + 1), hueY + PICKER_BAR_H,
                hsvToRgb((i + 0.5f) / segs, 1f, 1f, a),
            )
        }
        drawBarCursor(ctx, x + hue.coerceIn(0f, 1f) * w, hueY, a)

        // 透明度条 (棋盘格 + 渐变)
        val alphaY = hueY + PICKER_BAR_H + PICKER_GAP
        drawCheckerboard(ctx, x, alphaY, w, PICKER_BAR_H, a)
        val aSegs = 16
        val asw = w / aSegs
        for (i in 0 until aSegs) {
            val f = (i + 0.5f) / aSegs
            ctx.drawQuad(
                x + asw * i, alphaY, x + asw * (i + 1), alphaY + PICKER_BAR_H,
                Color4b(cur.r, cur.g, cur.b, (f * a).toInt().coerceIn(0, 255)),
            )
        }
        drawBarCursor(ctx, x + (cur.a / 255f) * w, alphaY, a)

        // 命中区: [x, sbY, -, w, 总高, SB 高]
        pickerRect = floatArrayOf(x, sbY, 0f, w, alphaY + PICKER_BAR_H - sbY, PICKER_SB_H)
    }

    private fun drawBarCursor(ctx: GuiGraphicsExtractor, cx: Float, barY: Float, a: Int) {
        val h = PICKER_BAR_H + 4f
        val y = barY - 2f
        ctx.drawQuad(cx - 2.5f, y, cx + 2.5f, y + h, alpha(Color4b(0, 0, 0), a))
        ctx.drawQuad(cx - 1.5f, y + 1f, cx + 1.5f, y + h - 1f, alpha(Color4b(255, 255, 255), a))
    }

    private fun drawCheckerboard(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, a: Int) {
        val cell = 6f
        val light = Color4b(220, 220, 220, a)
        val dark = Color4b(160, 160, 160, a)
        var yy = y
        var rowIdx = 0
        while (yy < y + h) {
            val cy2 = min(yy + cell, y + h)
            var xx = x
            var colIdx = 0
            while (xx < x + w) {
                val cx2 = min(xx + cell, x + w)
                ctx.drawQuad(xx, yy, cx2, cy2, if ((rowIdx + colIdx) % 2 == 0) light else dark)
                xx += cell
                colIdx++
            }
            yy += cell
            rowIdx++
        }
    }

    /** KeySetting: 全宽按钮(2px 强调色边框) "name: key" */
    private fun drawKeySetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val by = y + SETTING_ROW_PAD
        val by2 = by + 26f
        ctx.drawRoundedRect(x, by, x + w, by2, 3f, Color4b.TRANSPARENT, alpha(accentColor, a), 2f)

        val listening = keyListen == v
        val label: String
        if (listening) {
            label = "Press any key"
        } else {
            label = "${v.name}:"
        }
        // 组合文本居中: "name: key" (key 为 None 时灰显)
        val keyName = if (listening) "" else keyLabel(v)
        val sep = if (listening) "" else " "
        val full = if (listening) label else "$label$sep$keyName"
        val fullW = strW(font, full, 12f)
        val fx = x + (w - fullW) / 2f
        drawText(ctx, font, full, fx, by + 6f, alpha(textColor, a), 12f)
        if (!listening && keyName == "None") {
            // None 灰显 (重画覆盖)
            val nameW = strW(font, label, 12f) + strW(font, sep, 12f)
            ctx.drawQuad(fx + nameW, by + 6f, fx + nameW + strW(font, keyName, 12f) + 1f, by + 20f, Color4b.TRANSPARENT)
            drawText(ctx, font, keyName, fx + nameW, by + 6f, alpha(dimmedColor, a), 12f)
        }
        keyRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
    }

    /** 超宽文本截断 "..." */
    private fun shortenText(font: Font, s: String, maxW: Float, size: Float): String {
        if (maxW <= 4f || strW(font, s, size) <= maxW) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (strW(font, s.substring(0, mid) + "...", size) <= maxW) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo.coerceAtLeast(0)) + "..."
    }

    // ======================== 下拉选项浮层 (portal, 最顶层) ========================

    private fun drawDropdown(ctx: GuiGraphicsExtractor, font: Font) {
        val v = dropdownValue ?: return
        val head = dropdownHeadRects[v] ?: return
        val opts = info(v).choices
        if (opts.isEmpty()) return
        val a = (uiAlpha * 255).toInt()

        val x = head[0]
        val y = head[1] + head[3]
        val w = head[2]
        val padV = 6f
        val listH = padV * 2f + opts.size * DROPDOWN_OPT_H

        // 容器: 黑底 + 强调色边框 1px(无上边框) + 下圆角 3
        ctx.drawRoundedRect(x - 1f, y, x + w + 1f, y + listH, 3f, mix(1f, a), alpha(accentColor, a), 1f)
        ctx.drawQuad(x - 1f, y, x + w + 1f, y + 1f, mix(1f, a))

        val activeLabel = choiceLabel(v)
        var oy = y + padV
        for (o in opts) {
            val label = taggedLabel(o)
            val hovered = over(x, oy, w, DROPDOWN_OPT_H)
            val col = when {
                label == activeLabel -> alpha(accentColor, a)
                hovered -> alpha(textColor, a)
                else -> alpha(dimmedColor, a)
            }
            val tw = strW(font, label, 12f)
            drawText(ctx, font, label, x + (w - tw) / 2f, oy + (DROPDOWN_OPT_H - 14f) / 2f, col, 12f)
            oy += DROPDOWN_OPT_H
        }
        // 命中区: 从首个选项起, 行高 DROPDOWN_OPT_H
        dropdownRect = floatArrayOf(x, y + padV, w, opts.size * DROPDOWN_OPT_H)
    }
}
