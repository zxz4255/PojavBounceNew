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
) {



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


    private fun mix(alphaPct: Float, a: Int = 255): Color4b {
        val base = baseColor
        return Color4b(base.r, base.g, base.b, (255 * alphaPct).toInt().coerceIn(0, 255) * a / 255)
    }

    private fun alpha(c: Color4b, a: Int) =
        Color4b(c.r, c.g, c.b, (c.a * a / 255).coerceIn(0, 255))



    private val HEADER_H get() = headerPadding * 2f + 17f
    private val ROW_H = 34f
    private val SET_PAD_L = 7f
    private val SET_PAD_R = 11f
    private val SWITCH_W = 22f
    private val SWITCH_H = 12f
    private val SLIDER_H = 46f
    private val DROPDOWN_H = 26f
    private val DROPDOWN_OPT_H = 24f
    private val SETTING_ROW_PAD = 7f
    private val DESC_OFFSET = 20f
    private val EXPAND_ZONE_W = 40f


    private val PICKER_SB_H = 130f
    private val PICKER_BAR_H = 12f
    private val PICKER_GAP = 8f
    private val PICKER_TOTAL = PICKER_GAP + PICKER_SB_H + PICKER_GAP + PICKER_BAR_H + PICKER_GAP + PICKER_BAR_H
    private val NESTED_INDENT = 9f

    private val CAT_ORDER = listOf("Combat", "Player", "Movement", "Render", "World", "Misc", "Exploit", "Fun")



    private class Panel(
        val category: String,
        val index: Int,
    ) {
        var x = 20f
        var y = 20f
        var expanded = false
        var extAnim = 0f
        var scroll = 0f
        var scrollTarget = 0f
        var z = 0
        var hoverAnim = 0f
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


    private var searchQuery = ""
    private var searchFocus = true
    private var searchSelected = 0
    private var searchScroll = 0f
    private var searchResults: List<ClientModule> = emptyList()
    private var searchOpenAnim = 0f


    private var descTarget: ClientModule? = null
    private var descAnchorRight = true
    private var descAnim = 0f


    private var highlightMod: ClientModule? = null
    private var highlightAnim = 0f


    private var sliderDrag: Value<*>? = null
    private var dualWhich = 0
    private val switchAnim = IdentityHashMap<Value<*>, Float>()
    private val sliderRects = IdentityHashMap<Value<*>, FloatArray>()
    private val dropdownOpen = IdentityHashMap<Value<*>, Boolean>()
    private val dropdownAnim = IdentityHashMap<Value<*>, Float>()
    private var dropdownRect: FloatArray? = null
    private var dropdownOptions = emptyList<Any?>()
    private var dropdownValue: Value<*>? = null
    private var colorOpen: Value<*>? = null
    private var colorOpenPanel: Panel? = null
    private var dropdownPanel: Panel? = null
    private var colorDragChannel = -1
    private val colorHue = IdentityHashMap<Value<*>, Float>()
    private val colorSV = IdentityHashMap<Value<*>, Pair<Float, Float>>()
    private var pickerRect: FloatArray? = null
    private var textEdit: Value<*>? = null
    private var textBuf = ""
    private var keyListen: Value<*>? = null


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
        // 兼容：部分构建 ValueType 不含 COLOR / 类型被标成 TEXT/OTHER
        if (i.kind == Kind.OTHER || i.kind == Kind.TEXT) {
            val typeName = runCatching { v.valueType.name }.getOrDefault("")
            if (typeName.equals("COLOR", true) || typeName.contains("COLOR", true)) {
                i.kind = Kind.COLOR
            }
        }
        if (i.kind != Kind.COLOR) {
            val actual = unwrapValue(v)
            if (isColorLike(actual)) {
                i.kind = Kind.COLOR
            } else if (i.kind == Kind.OTHER) {
                // 名称启发：Accent / Color / Theme 等
                val n = v.name.lowercase()
                if ((n.contains("color") || n.contains("accent") || n.contains("theme") ||
                        n.endsWith(" col") || n.contains("glow")) &&
                    actual is Number
                ) {
                    // 数字型颜色(ARGB int)也当 color
                    i.kind = Kind.COLOR
                }
            }
        }
        if (v is RangedValue) {
            val a = (v.range.start as? Number)?.toFloat()
            val b = (v.range.endInclusive as? Number)?.toFloat()
            if (a != null && b != null && b > a) i.range = a to b
            i.suffix = v.suffix
        }
        return i
    }

    private fun unwrapValue(v: Value<*>): Any? {
        var obj: Any? = runCatching { v.get() }.getOrNull()
        var depth = 0
        while (obj is Value<*> && depth < 5) {
            obj = runCatching { obj.get() }.getOrNull()
            depth++
        }
        return obj
    }

    private fun isColorLike(o: Any?): Boolean {
        if (o == null) return false
        if (o is Color4b) return true
        val sn = o.javaClass.simpleName
        if (sn.contains("Color", true) || sn.contains("Colour", true)) return true
        // java.awt.Color
        if (o is java.awt.Color) return true
        return false
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

    private fun colorOf(v: Value<*>): Color4b {
        val actual = unwrapValue(v)
        return when (actual) {
            is Color4b -> actual
            is java.awt.Color -> Color4b(actual.red, actual.green, actual.blue, actual.alpha)
            is Number -> {
                val argb = actual.toInt()
                Color4b(
                    (argb ushr 16) and 0xFF,
                    (argb ushr 8) and 0xFF,
                    argb and 0xFF,
                    (argb ushr 24) and 0xFF,
                )
            }
            else -> {
                // 反射读 r/g/b/a
                runCatching {
                    val cls = actual!!.javaClass
                    fun n(name: String) = cls.methods.firstOrNull {
                        it.parameterCount == 0 && it.name.equals(name, true)
                    }?.invoke(actual) as? Number
                    val r = n("getRed") ?: n("r") ?: n("getR")
                    val g = n("getGreen") ?: n("g") ?: n("getG")
                    val b = n("getBlue") ?: n("b") ?: n("getB")
                    val a = n("getAlpha") ?: n("a") ?: n("getA")
                    if (r != null && g != null && b != null) {
                        Color4b(r.toInt(), g.toInt(), b.toInt(), a?.toInt() ?: 255)
                    } else null
                }.getOrNull() ?: Color4b(255, 255, 255, 255)
            }
        }
    }

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



    override fun onEnabled() {
        ensurePanels()
        loadLayout()
        uiAlpha = 1f
        lastNs = 0L
        // 若分类为空也至少画搜索栏/背景，避免“全黑无界面”
        if (panels.isEmpty()) {
            panels.add(Panel("Render", 0).also {
                it.x = 20f; it.y = 20f; it.expanded = true; it.extAnim = 1f
            })
        }
        for (p in panels) {
            // 默认展开一点，否则 bodyH=0 像没内容
            if (!p.expanded) {
                p.expanded = true
                p.extAnim = 1f
            }
        }
        runCatching { mc.gui.setScreen(LiquidScreen()) }
        runCatching { mc.execute { mc.gui.setScreen(LiquidScreen()) } }
        super.onEnabled()
    }

    override fun onDisabled() {
        saveLayout()
        textEdit = null
        keyListen = null
        sliderDrag = null
        colorOpen = null
        colorOpenPanel = null
        dropdownValue = null
        dropdownPanel = null
        descTarget = null
        super.onDisabled()
    }

    private fun closeGui() {
        if (mc.gui.screen() is LiquidScreen) {
            runCatching { mc.gui.setScreen(null) }
        }
        enabled = false
    }



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
        infoCache.clear() // 重新识别 COLOR 等类型
        panels.forEach { p ->
            p.modules.forEach { m ->
                modOpenAnim[m] = if (modOpen[m] == true) 1f else 0f
            }
        }
    }

    private fun categoryLabel(m: ClientModule): String = try {
        m.category.tag
    } catch (_: Throwable) {
        try {
            m.category.toString().substringAfterLast('.').substringAfterLast('$')
        } catch (_: Throwable) {
            "Misc"
        }
    }

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



    private fun guiMX() = (mc.mouseHandler.xpos() * refW() / mc.window.width).toFloat()
    private fun guiMY() = (mc.mouseHandler.ypos() * refH() / mc.window.height).toFloat()
    private fun refW() = if (viewW > 0f) viewW else mc.window.guiScaledWidth.toFloat()
    private fun refH() = if (viewH > 0f) viewH else mc.window.guiScaledHeight.toFloat()

    internal fun onMouse(button: Int, down: Boolean): Boolean {
        if (down) {
            // 全屏坐标；Scale 只缩放面板外观
            mouseX = guiMX()
            mouseY = guiMY()
            if (button == 0) leftDown = true
            return click(button)
        }
        if (button == 0) leftDown = false
        release(button)
        return true
    }

    private fun over(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    /** 将全屏鼠标转为某面板布局坐标（面板以左上角按 Scale 缩放） */
    private fun toLayout(p: Panel): Pair<Float, Float> {
        val s = scale.coerceIn(0.5f, 2f).coerceAtLeast(0.01f)
        return (p.x + (mouseX - p.x) / s) to (p.y + (mouseY - p.y) / s)
    }

    private fun overLayout(p: Panel, x: Float, y: Float, w: Float, h: Float): Boolean {
        val (lx, ly) = toLayout(p)
        return lx >= x && ly >= y && lx < x + w && ly < y + h
    }

    /** Search 以 (屏幕中心X, searchY) 为锚点缩放，位置不变、大小随 Scale */
    private fun searchScale(): Float = scale.coerceIn(0.5f, 2f).coerceAtLeast(0.01f)

    private fun searchLayoutMouse(mx: Float = mouseX, my: Float = mouseY): Pair<Float, Float> {
        val s = searchScale()
        val ax = viewW / 2f
        val ay = searchY
        return (ax + (mx - ax) / s) to (ay + (my - ay) / s)
    }

    private fun inRect(r: FloatArray?) =
        r != null && over(r[0], r[1], r[2], r[3])



    private fun click(button: Int): Boolean {
        val left = button == 0
        val right = button == 1


        colorOpen?.let { v ->
            val panel = colorOpenPanel
            val savedMx = mouseX
            val savedMy = mouseY
            if (panel != null) {
                val (lx, ly) = toLayout(panel)
                mouseX = lx
                mouseY = ly
            }
            val pr = pickerRect
            if (pr != null && over(pr[0], pr[1], pr[3], pr[4])) {
                if (left) {
                    val sbBottom = pr[1] + pr[5]
                    val hueBottom = sbBottom + PICKER_GAP + PICKER_BAR_H
                    colorDragChannel = when {
                        mouseY < sbBottom -> 0
                        mouseY < hueBottom -> 1
                        else -> 2
                    }
                    applyColorPicker(v)
                }
                mouseX = savedMx
                mouseY = savedMy
                return true
            }
            // 点在颜色行上：保持打开，不切换关闭（避免刚开就关）
            val cr = colorRects[v]
            if (cr != null && over(cr[0], cr[1], cr[2], cr[3])) {
                mouseX = savedMx
                mouseY = savedMy
                return true
            } else if (pr == null || !over(pr[0] - 8f, pr[1] - 40f, pr[3] + 16f, pr[4] + 50f)) {
                // 点在调色板外才关闭
                colorOpen = null
                colorOpenPanel = null
                colorDragChannel = -1
                mouseX = savedMx
                mouseY = savedMy
            } else {
                // 点在扩大命中区内：开始拖
                if (left && pr != null) {
                    colorDragChannel = 0
                    applyColorPicker(v)
                }
                mouseX = savedMx
                mouseY = savedMy
                return true
            }
        }


        dropdownValue?.let { dv ->
            val panel = dropdownPanel
            val savedMx = mouseX
            val savedMy = mouseY
            if (panel != null) {
                val (lx, ly) = toLayout(panel)
                mouseX = lx
                mouseY = ly
            }
            val dr = dropdownRect
            val opts = info(dv).choices.ifEmpty { dropdownOptions }
            if (dr != null && left && over(dr[0], dr[1], dr[2], dr[3])) {
                val idx = ((mouseY - dr[1]) / DROPDOWN_OPT_H).toInt().coerceIn(0, (opts.size - 1).coerceAtLeast(0))
                selectOption(dv, opts.getOrNull(idx))
                mouseX = savedMx
                mouseY = savedMy
                return true
            }
            val head = dropdownHeadRects[dv]
            val onHead = head != null && over(head[0], head[1], head[2], head[3])
            mouseX = savedMx
            mouseY = savedMy
            if (!onHead) {
                dropdownValue = null
                dropdownOpen[dv] = false
                dropdownPanel = null
            } else {
                return true
            }
        }



        if (searchEnabled) {
            val sw = searchWidth
            val sx = viewW / 2f - sw / 2f
            val resultsH = if (searchResults.isNotEmpty()) min(searchResults.size * 39f, 250f) + 2f
            else if (searchQuery.isNotEmpty()) 39f else 0f
            val (slx, sly) = searchLayoutMouse()
            val savedMx = mouseX
            val savedMy = mouseY
            mouseX = slx
            mouseY = sly
            var handled = false
            if (over(sx, searchY, sw, 49f + resultsH)) {
                if (left) {
                    searchFocus = true
                    if (searchResults.isNotEmpty() && mouseY > searchY + 49f) {
                        val idx = ((mouseY - searchY - 49f - 2f) / 39f + searchScroll / 39f).toInt()
                        if (idx in searchResults.indices) {
                            searchResults[idx].enabled = !searchResults[idx].enabled
                            handled = true
                        }
                    }
                } else if (right && searchResults.isNotEmpty() && mouseY > searchY + 49f) {
                    val idx = ((mouseY - searchY - 49f - 2f) / 39f + searchScroll / 39f).toInt()
                    if (idx in searchResults.indices) {
                        locateModule(searchResults[idx])
                        handled = true
                    }
                } else {
                    handled = true // 点在搜索栏上
                }
                if (!handled && left) handled = true
            } else if (left) {
                searchFocus = false
            }
            mouseX = savedMx
            mouseY = savedMy
            if (handled) return true
        }


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


        val sorted = panels.sortedByDescending { it.z }
        for (p in sorted) {
            // 命中测试用布局坐标（绘制按左上角 Scale）
            val screenMx = mouseX
            val screenMy = mouseY
            val (lx, ly) = toLayout(p)
            mouseX = lx
            mouseY = ly

            val hr = headerRects[p]
            if (hr != null && over(hr[0], hr[1], hr[2], hr[3])) {
                val btnL = p.x + hr[2] - 15f - 12f - 4f
                val onExpandBtn = mouseX >= btnL
                if (left && !onExpandBtn) {
                    p.z = ++maxZ
                    dragPanel = p
                    // 拖动偏移用屏幕坐标，与 onDrag 一致
                    dragOffX = screenMx - p.x
                    dragOffY = screenMy - p.y
                } else if (left && onExpandBtn || right) {
                    togglePanel(p)
                }
                mouseX = screenMx
                mouseY = screenMy
                return true
            }

            if (p.extAnim > 0.05f) {
                val bodyY = p.y + HEADER_H + 2f
                val bodyH = p.extAnim * panelMaxHeight
                if (over(p.x, bodyY, panelWidth, bodyH)) {
                    val hit = handlePanelContent(p, bodyY, bodyH, left, right)
                    mouseX = screenMx
                    mouseY = screenMy
                    if (hit) return true
                }
            }
            mouseX = screenMx
            mouseY = screenMy
        }
        return true
    }


    private fun handlePanelContent(p: Panel, bodyY: Float, bodyH: Float, left: Boolean, right: Boolean): Boolean {

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
                if (left) openDropdown(v, p) else if (right) toggleGroup(v)
                return true
            }
        }
        for ((v, choice, r) in chipRects) {
            if (inRectRow(r, p) && over(r[0], r[1], r[2], r[3])) {
                if (left) {
                    runCatching { if (choice != null) (v as MultiChoiceListValue<Any>).toggle(choice) }
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
                        colorOpenPanel = null
                    } else {
                        colorOpen = v
                        colorOpenPanel = p
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

    }

    private fun openDropdown(v: Value<*>, panel: Panel? = null) {
        if (dropdownValue == v) {
            dropdownValue = null
            dropdownOpen[v] = false
            dropdownPanel = null
        } else {
            dropdownValue?.let { dropdownOpen[it] = false }
            dropdownValue = v
            dropdownPanel = panel
            dropdownOpen[v] = true
            dropdownOptions = info(v).choices
        }
    }

    private fun selectOption(v: Value<*>, choice: Any?) {
        runCatching {
            when (info(v).kind) {
                Kind.MODE -> v.setByString((choice as? Tagged)?.tag ?: choice?.toString() ?: return)
                Kind.CHOICE -> if (choice != null) (v as Value<Any>).set(choice)
                else -> if (choice != null) (v as Value<Any>).set(choice)
            }
        }
        dropdownValue = null
        dropdownPanel = null
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
        mouseX = guiMX()
        mouseY = guiMY()

        // 面板拖动（全屏范围）
        dragPanel?.let { panel ->
            val nx = mouseX - dragOffX
            val ny = mouseY - dragOffY
            val g = if (shiftIgnoreGrid || !snapEnabled) 0f else gridSize.toFloat().coerceAtLeast(1f)
            panel.x = if (g > 0f) (nx / g).roundToInt() * g else nx
            panel.y = if (g > 0f) (ny / g).roundToInt() * g else ny
            panel.x = panel.x.coerceIn(0f, (viewW - panelWidth * 0.2f).coerceAtLeast(0f))
            panel.y = panel.y.coerceIn(0f, (viewH - HEADER_H * 0.2f).coerceAtLeast(0f))
        }

        // 滑条 / 调色：不依赖 dragPanel，单独用布局坐标
        if (sliderDrag != null || (colorDragChannel >= 0 && colorOpen != null)) {
            val panel = colorOpenPanel
                ?: sliderDrag?.let { sv ->
                    val idx = sliderRects[sv]?.getOrNull(4)?.toInt()
                    if (idx != null) panels.getOrNull(idx) else null
                }
                ?: panels.firstOrNull()
            if (panel != null) {
                val s = scale.coerceIn(0.5f, 2f).coerceAtLeast(0.01f)
                mouseX = panel.x + (guiMX() - panel.x) / s
                mouseY = panel.y + (guiMY() - panel.y) / s
            }
            sliderDrag?.let { applySlider(it) }
            if (colorDragChannel >= 0) colorOpen?.let { applyColorPicker(it) }
            mouseX = guiMX()
            mouseY = guiMY()
        }
    }

    internal fun onScroll(v: Double): Boolean {
        val mx = guiMX()
        val my = guiMY()

        dropdownValue?.let {
            dropdownValue = null
            dropdownOpen[it] = false
        }

        if (searchEnabled && searchResults.isNotEmpty()) {
            val sw = searchWidth
            val sx = viewW / 2f - sw / 2f
            val (slx, sly) = searchLayoutMouse(mx, my)
            if (slx >= sx && slx < sx + sw && sly >= searchY && sly < searchY + 49f + 250f + 2f) {
                val dv = v.toFloat()
                searchScroll = (searchScroll - dv * 20f).coerceIn(
                    0f,
                    (searchResults.size * 39f - 250f).coerceAtLeast(0f),
                )
                return true
            }
        }

        val s = scale.coerceIn(0.5f, 2f)
        val sorted = panels.sortedByDescending { it.z }
        for (p in sorted) {
            if (p.extAnim < 0.9f) continue
            // 屏幕上的可视区域 = 以 p 为原点 scale 后的矩形
            val bodyY = p.y + (HEADER_H + 2f) * s
            val bodyH = p.extAnim * panelMaxHeight * s
            val bodyW = panelWidth * s
            if (mx >= p.x && mx < p.x + bodyW && my >= bodyY && my < bodyY + bodyH) {
                val maxScroll = (p.contentH - panelMaxHeight).coerceAtLeast(0f)
                p.scrollTarget = (p.scrollTarget - v.toFloat() * 20f).coerceIn(0f, maxScroll)
                saveScroll(p)
                return true
            }
        }
        return true
    }

    private fun saveScroll(p: Panel) {

    }



    internal fun onKeyPressed(key: Int): Boolean {
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) shiftIgnoreGrid = true


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

        if (keyListen != null) return true
        if (textEdit != null) {
            if (!ch.isISOControl() && textBuf.length < 128) {

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


    private fun locateModule(m: ClientModule) {
        highlightMod = m
        highlightAnim = 1f
        for (p in panels) {
            if (p.modules.contains(m)) {
                p.z = ++maxZ
                p.expanded = true

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




    private fun quintOut(t: Float): Float = 1f - (1f - t).pow(5f)

    private fun approach(cur: Float, target: Float, speed: Float): Float =
        cur + (target - cur) * (1f - (0.001f).pow(frameDt * speed)).coerceIn(0f, 1f)


    private fun settingsHeight(m: ClientModule): Float {
        var h = 0f
        walkValues(childrenOfModule(m), 0, 0f, 0f) { _, _, _, rowH, _ -> h += rowH }
        return h
    }


    private fun settingsHeightNow(m: ClientModule, openF: Float): Float {
        if (openF <= 0.001f) return 0f
        return settingsHeight(m) * quintOut(openF)
    }



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



    @Suppress("unused")
    private fun isGuiOpen(): Boolean =
        enabled || runCatching { mc.gui.screen() is LiquidScreen }.getOrDefault(false)

    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val open = isGuiOpen()
        if (!open && uiAlpha <= 0.01f) {
            lastNs = 0L
            return@handler
        }
        if (!panelsBuilt || panels.isEmpty()) ensurePanels()

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        frameDt = dt

        // 打开时立刻可见，不要等 fade 从 0 爬
        if (open && uiAlpha < 0.05f) uiAlpha = 1f
        uiAlpha = if (open) (uiAlpha + dt * 1000f / fadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        else (uiAlpha - dt * 1000f / fadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (uiAlpha <= 0.01f) return@handler

        val ctx = event.context
        val font = mc.font
        viewW = ctx.guiWidth().toFloat()
        viewH = ctx.guiHeight().toFloat()
        val s = scale.coerceIn(0.5f, 2f)

        // 全屏鼠标坐标：Search 与拖动范围不随 Scale 缩小
        mouseX = guiMX()
        mouseY = guiMY()

        updateAnimations(dt)

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

        if (dimBackground && dimAlpha > 0f) {
            ctx.drawQuad(0f, 0f, viewW, viewH, alpha(Color4b(0, 0, 0, 255), (dimAlpha * 255 * uiAlpha).toInt()))
        }

        val dragging = dragPanel != null
        if (dragging && snapEnabled && showGridWhileDrag) {
            drawGrid(ctx, 1f)
        }

        // Search 固定在屏幕坐标，不受 Scale 影响
        if (searchEnabled) drawSearch(ctx, font)

        // 面板以自身左上角缩放；下拉菜单在同一矩阵内绘制，避免跑到屏幕底部放大
        for (p in panels.sortedBy { it.z }) {
            ctx.pose().withPush {
                translate(p.x, p.y)
                scale(s, s)
                translate(-p.x, -p.y)
                drawPanel(ctx, font, p)
                if (dropdownValue != null && dropdownPanel == p) {
                    drawDropdown(ctx, font, layoutSpace = true)
                }
            }
        }
        // 无所属面板时兜底
        if (dropdownValue != null && dropdownPanel == null) {
            drawDropdown(ctx, font, layoutSpace = true)
        }

        drawHighlight(ctx)
        if (descEnabled) drawDescription(ctx, font)
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



    private fun drawPanel(ctx: GuiGraphicsExtractor, font: Font, p: Panel) {
        val a = (uiAlpha * 255).toInt()
        val w = panelWidth
        val bodyH = p.extAnim * panelMaxHeight


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


        ctx.drawRoundedRect(
            p.x, p.y, p.x + w, p.y + HEADER_H + 2f, panelRadius, mix(0.9f, a),
        )
        ctx.drawQuad(p.x, p.y + HEADER_H, p.x + w, p.y + HEADER_H + 2f, mix(0.9f, a))
        ctx.drawQuad(p.x, p.y + HEADER_H, p.x + w, p.y + HEADER_H + 2f, alpha(accentColor, a))


        if (bodyH > 0.5f) {
            ctx.drawRoundedRect(
                p.x, p.y + HEADER_H + 2f, p.x + w, p.y + HEADER_H + 2f + bodyH, panelRadius, mix(0.8f, a),
            )

            ctx.drawQuad(p.x, p.y + HEADER_H + 2f, p.x + w, p.y + HEADER_H + 8f, mix(0.8f, a))
        }


        if (catIcons) {
            drawCategoryIcon(ctx, p.category, p.x + 15f + 8.5f, p.y + HEADER_H / 2f, a)
        }


        val nameX = p.x + 15f + (if (catIcons) 17f + 12f else 0f)
        drawText(
            ctx, font, p.category, nameX, p.y + (HEADER_H - 17f) / 2f,
            alpha(textColor, a), 14f,
        )


        val bx = p.x + w - 15f - 6f
        val by = p.y + HEADER_H / 2f
        val ic = alpha(textColor, a)
        val ext = p.extAnim

        ctx.drawQuad(bx - 6f, by - 1f, bx + 6f, by + 1f, ic)

        if (ext < 0.99f) {
            val hh = (1f - ext).coerceAtLeast(0.08f)
            ctx.drawQuad(bx - hh, by - 6f, bx + hh, by + 6f, alpha(ic, (255 * (1f - ext)).toInt()))
        }

        headerRects[p] = floatArrayOf(p.x, p.y, w, HEADER_H + 2f)


        if (bodyH > 0.5f) {
            drawModuleList(ctx, font, p, bodyH, a)
        }
    }

    private fun drawModuleList(ctx: GuiGraphicsExtractor, font: Font, p: Panel, bodyH: Float, a: Int) {
        val clipTop = p.y + HEADER_H + 2f
        val clipBot = clipTop + bodyH
        val x = p.x
        val w = panelWidth


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

                if (y + ROW_H > clipTop && y < clipBot) {
                    drawModuleRow(ctx, font, m, x, y, w, a)
                }
                rowRects[m] = floatArrayOf(x, y, w, ROW_H, p.index.toFloat())
                y += ROW_H


                if (openF > 0.001f) {
                    val setH = settingsHeight(m) * quintOut(openF)
                    if (y + setH > clipTop && y < clipBot) {

                        ctx.drawQuad(x, y, x + w, y + setH, mix(0.5f, a))
                        ctx.drawQuad(x, y, x + 4f, y + setH, alpha(accentColor, a))

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


        if (hoverF > 0.01f) {
            ctx.drawQuad(x, y, x + w, y + ROW_H, mix(0.85f, (a * hoverF).toInt()))
        }


        val hasSettings = childrenOfModule(m).isNotEmpty()
        val name = shortenText(font, m.name, w - 8f, 12f)
        val nameW = font.width(name) * (12f / 9f)
        var nx = x + (w - nameW) / 2f
        if (nx < x + 4f) nx = x + 4f


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


        if (hasSettings) {
            val openF = modOpenAnim.getOrDefault(m, 0f)
            val ax = x + w - EXPAND_ZONE_W / 2f - 3f
            val ay = y + ROW_H / 2f
            val c = alpha(textColor, (a * (0.5f + 0.5f * openF)).toInt())

            ctx.drawQuad(ax - 1f, ay - 4f, ax + 1f, ay + 1f, c)
            ctx.drawQuad(ax - 3f, ay - 2f, ax - 1f, ay, c)
            ctx.drawQuad(ax + 1f, ay - 2f, ax + 3f, ay, c)
            ctx.drawQuad(ax - 3f, ay - 0.5f, ax + 3f, ay + 1f, c)
        }


        if (descEnabled && over(x, y, w, ROW_H)) {
            updateDescTarget(m, x, y, w)
        }
    }

    private fun updateDescTarget(m: ClientModule, x: Float, y: Float, w: Float) {
        if (descTarget !== m) {
            descTarget = m
            descAnim = 0f
        }

        val guiRight = viewW / 2f + viewW / (2f * scale)
        descAnchorRight = (guiRight - (x + w)) > 300f
    }



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


        val slide = 15f * (1f - descAnim)

        val bx: Float
        val arrowLeft: Boolean
        if (descAnchorRight) {
            bx = r[0] + r[2] + DESC_OFFSET + slide
            arrowLeft = true
        } else {
            bx = r[0] - DESC_OFFSET - bw - slide
            arrowLeft = false
        }
        val by = cy - bh / 2f


        ctx.drawRoundedRect(bx, by, bx + bw, by + bh, 5f, mix(0.9f, a))
        drawText(ctx, font, text, bx + 10f, by + (bh - 14f) / 2f, alpha(textColor, a), size)


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



    private fun drawSearch(ctx: GuiGraphicsExtractor, font: Font) {
        val a = (uiAlpha * 255).toInt()
        val sw = searchWidth
        val sx = viewW / 2f - sw / 2f
        val sy = searchY
        val inputH = 49f
        val s = searchScale()
        // 锚点固定：水平中心 + searchY，只缩放大小
        ctx.pose().withPush {
            translate(viewW / 2f, sy)
            scale(s, s)
            translate(-viewW / 2f, -sy)
            drawSearchInner(ctx, font, a, sw, sx, sy, inputH)
        }
    }

    private fun drawSearchInner(
        ctx: GuiGraphicsExtractor, font: Font,
        a: Int, sw: Float, sx: Float, sy: Float, inputH: Float,
    ) {


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


        if (resultsH > 0.5f) {
            ctx.drawRoundedRect(sx, sy, sx + sw, sy + inputH + 2f + resultsH, r, mix(0.9f, a))
            ctx.drawQuad(sx + 2f, sy + inputH, sx + sw - 2f, sy + inputH + resultsH + 2f, mix(0.9f, a))
        } else {
            ctx.drawRoundedRect(sx, sy, sx + sw, sy + inputH, r, mix(0.9f, a))
        }


        val showText = if (searchQuery.isEmpty()) "Search" else searchQuery
        val col = if (searchQuery.isEmpty()) alpha(dimmedColor, (a * 0.6f).toInt()) else alpha(textColor, a)
        drawText(ctx, font, showText, sx + 25f, sy + (inputH - 19f) / 2f, col, 16f)

        if (searchFocus && (System.currentTimeMillis() / 500) % 2 == 0L) {
            val cw = font.width(searchQuery) * (16f / 9f)
            ctx.drawQuad(sx + 25f + cw + 1f, sy + 16f, sx + 25f + cw + 2f, sy + inputH - 16f, alpha(textColor, a))
        }

        if (resultsH <= 0.5f) return


        ctx.drawQuad(sx, sy + inputH, sx + sw, sy + inputH + 2f, alpha(accentColor, a))

        val listH = resultsH
        ctx.scissorStack.withPush(ctx.getBounds(sx, sy + inputH + 2f, sx + sw, sy + inputH + 2f + listH)) {

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

                val padL = if (selected) 10f else 0f

                val nameCol = if (m.enabled) alpha(accentColor, a) else alpha(dimmedColor, a)
                drawText(ctx, font, m.name, sx + 25f + padL, y + (39f - 19f) / 2f, nameCol, 16f)
                val nameW = font.width(m.name) * (16f / 9f)

                if (m.aliases.isNotEmpty()) {
                    val alias = "(aka ${m.aliases.joinToString(", ")})"
                    val aw = font.width(alias) * (16f / 9f)
                    val ax = sx + 25f + padL + nameW + 10f
                    if (ax + aw < sx + sw - 25f) {
                        drawText(ctx, font, alias, ax, y + (39f - 19f) / 2f, alpha(dimmedColor, (a * 0.6f).toInt()), 16f)
                    }
                }

                if (over(sx, y, sw, 39f)) {
                    val hint = "Right-click to locate"
                    val hw = font.width(hint) * (12f / 9f)
                    drawText(ctx, font, hint, sx + sw - 25f - hw, y + (39f - 14f) / 2f, alpha(textColor, (a * 0.4f).toInt()), 12f)
                }
                y += 39f
            }
        }
    }




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
                        y += 10f
                        y = walkValues(children, indent + 1, originX + NESTED_INDENT, y, emit)
                    }
                }
            }
        }
        return y
    }


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
        else -> {
            if (isColorLike(unwrapValue(v))) {
                SETTING_ROW_PAD * 2f + 16f + (if (colorOpen == v) PICKER_TOTAL else 0f)
            } else {
                SETTING_ROW_PAD * 2f + 14f
            }
        }
    }


    private fun groupHeadSwitch(v: Value<*>): Value<*>? =
        childrenOf(v).firstOrNull { it.valueType == ValueType.BOOLEAN }


    private fun groupNested(v: Value<*>): List<Value<*>> {
        val all = childrenOf(v)
        val head = groupHeadSwitch(v)
        return if (head != null) all.filter { it !== head } else all
    }


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


    private fun chipRows(font: Font, v: Value<*>, maxW: Float): List<List<Pair<Any?, Float>>> {
        val vi = info(v)
        val rows = ArrayList<List<Pair<Any?, Float>>>()
        var cur = ArrayList<Pair<Any?, Float>>()
        var curW = 0f
        for (c in vi.choices) {
            val label = taggedLabel(c)
            val cw = strW(font, label, 12f) + 12f
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


    private fun chipsHeight(v: Value<*>, indent: Int): Float {
        val maxW = panelWidth - 22f - NESTED_INDENT * indent - 16f
        val rows = chipRows(mc.font, v, maxW)
        return 14f + rows.size * 20f + (rows.size - 1).coerceAtLeast(0) * 7f
    }




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


    private fun mixColor(c: Color4b, pct: Float, a: Int = 255): Color4b = Color4b(
        (c.r * pct).toInt().coerceIn(0, 255),
        (c.g * pct).toInt().coerceIn(0, 255),
        (c.b * pct).toInt().coerceIn(0, 255),
        a,
    )


    private fun alphaFrac(c: Color4b, frac: Float, a: Int): Color4b = Color4b(
        c.r, c.g, c.b, (255f * frac * a / 255f).toInt().coerceIn(0, 255),
    )


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


    private fun setKeyValue(v: Value<*>, k: InputConstants.Key) {
        runCatching {
            when (val cur = v.get()) {
                is InputBind -> (v as Value<Any>).set(cur.copy(boundKey = k))
                else -> (v as Value<Any>).set(k)
            }
        }
    }



    private fun drawCategoryIcon(ctx: GuiGraphicsExtractor, category: String, cx: Float, cy: Float, a: Int) {
        val c = alpha(textColor, a)
        val bg = mix(0.9f, a)
        val x = cx - 8.5f
        val y = cy - 7.5f
        when (category.lowercase()) {
            "combat" -> {
                diag(ctx, x + 1f, y + 1f, x + 16f, y + 14f, 1.8f, c)
                diag(ctx, x + 16f, y + 1f, x + 1f, y + 14f, 1.8f, c)
            }
            "player" -> {
                ctx.drawRoundedRect(x + 5.5f, y, x + 11.5f, y + 5.5f, 2.75f, c)
                ctx.drawRoundedRect(x + 2f, y + 7.5f, x + 15f, y + 15f, 3.5f, c)
            }
            "movement" -> {
                for (off in 0..1) {
                    val ox = x + 2f + off * 6f
                    diag(ctx, ox, y + 1f, ox + 5f, y + 7.5f, 1.8f, c)
                    diag(ctx, ox, y + 14f, ox + 5f, y + 7.5f, 1.8f, c)
                }
            }
            "render" -> {
                diag(ctx, x + 0.5f, y + 7.5f, x + 8.5f, y + 1.5f, 1.6f, c)
                diag(ctx, x + 8.5f, y + 1.5f, x + 16.5f, y + 7.5f, 1.6f, c)
                diag(ctx, x + 0.5f, y + 7.5f, x + 8.5f, y + 13.5f, 1.6f, c)
                diag(ctx, x + 8.5f, y + 13.5f, x + 16.5f, y + 7.5f, 1.6f, c)
                ctx.drawRoundedRect(x + 7f, y + 5.5f, x + 10f, y + 9.5f, 1.5f, c)
            }
            "world" -> {
                ctx.drawRoundedRect(x + 1f, y + 0.5f, x + 16f, y + 14.5f, 7f, c)
                ctx.drawRoundedRect(x + 3f, y + 2.5f, x + 14f, y + 12.5f, 5f, bg)
                ctx.drawQuad(x + 1.5f, y + 6.5f, x + 15.5f, y + 8.5f, c)
                ctx.drawQuad(x + 7.5f, y + 1f, x + 9.5f, y + 14f, c)
            }
            "misc" -> {
                ctx.drawRoundedRect(x + 2f, y + 1f, x + 5.5f, y + 4.5f, 1.75f, c)
                ctx.drawRoundedRect(x + 6.75f, y + 5.25f, x + 10.25f, y + 8.75f, 1.75f, c)
                ctx.drawRoundedRect(x + 11.5f, y + 9.5f, x + 15f, y + 13f, 1.75f, c)
            }
            "exploit" -> {
                ctx.drawQuad(x + 9f, y, x + 16f, y + 3f, c)
                ctx.drawQuad(x + 4f, y + 3.5f, x + 11f, y + 6.5f, c)
                ctx.drawQuad(x + 6f, y + 6.5f, x + 13f, y + 9.5f, c)
                ctx.drawQuad(x + 1f, y + 9.5f, x + 8f, y + 12.5f, c)
                ctx.drawQuad(x + 1f, y + 12.5f, x + 5f, y + 15f, c)
            }
            "fun" -> {
                ctx.drawRoundedRect(x + 0.5f, y, x + 16.5f, y + 15f, 7.5f, c)
                ctx.drawRoundedRect(x + 2.5f, y + 2f, x + 14.5f, y + 13f, 5.5f, bg)
                ctx.drawRoundedRect(x + 4.5f, y + 5f, x + 6.5f, y + 7f, 1f, c)
                ctx.drawRoundedRect(x + 10.5f, y + 5f, x + 12.5f, y + 7f, 1f, c)
                ctx.drawQuad(x + 5f, y + 9.5f, x + 12f, y + 11f, c)
            }
            "client" -> {
                ctx.drawRoundedRect(x + 0.5f, y + 1f, x + 16.5f, y + 12f, 2f, c)
                ctx.drawQuad(x + 7f, y + 12.5f, x + 10f, y + 15f, c)
            }
            else -> ctx.drawRoundedRect(x + 3f, y + 2f, x + 14f, y + 13f, 2f, c)
        }
    }



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
                if (isColorLike(unwrapValue(v))) {
                    // 缓存误判为 OTHER 时仍按颜色绘制
                    infoCache.remove(v)
                    drawColorSetting(ctx, font, v, x, y, h, w, p, a)
                } else {
                    drawText(ctx, font, v.name, x, y + (h - 14f) / 2f, alpha(textColor, a), 12f)
                    val s = runCatching { v.get().toString() }.getOrDefault("")
                    drawText(ctx, font, s.take(24), x + w - strW(font, s.take(24), 12f), y + (h - 14f) / 2f, alpha(dimmedColor, a), 12f)
                }
            }
        }
    }


    private fun drawSwitch(ctx: GuiGraphicsExtractor, v: Value<*>, on: Boolean, x: Float, y: Float, a: Int) {
        val cur = switchAnim.getOrDefault(v, if (on) 1f else 0f)
        val t = approach(cur, if (on) 1f else 0f, 2.5f)
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


    private fun drawBoolSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val on = runCatching { v.get() as Boolean }.getOrDefault(false)
        drawSwitch(ctx, v, on, x, y + (h - 12f) / 2f, a)
        drawText(ctx, font, v.name, x + SWITCH_W + 7f, y + (h - 14f) / 2f, alpha(textColor, a), 12f)
        switchRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
    }


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


    private fun drawSliderSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val vi = info(v)
        val bounds = vi.range ?: run {

            drawText(ctx, font, v.name, x, y + 7f, alpha(textColor, a), 12f)
            return
        }
        val (minV, maxV) = bounds


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

        if (editing && (System.currentTimeMillis() / 500) % 2 == 0L) {
            ctx.drawQuad(valueX + valueW + 1f, y + 7f, valueX + valueW + 2f, y + 21f, alpha(textColor, a))
        }

        textRects[v] = floatArrayOf(valueX - 6f, y, valueW + suffixW + 10f, 24f, p.index.toFloat())


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

            ctx.drawRoundedRect(trackX + t1 * trackW - 6f, trackY - 5f, trackX + t1 * trackW + 6f, trackY + 7f, 6f, fillC)
            ctx.drawRoundedRect(trackX + t2 * trackW - 6f, trackY - 5f, trackX + t2 * trackW + 6f, trackY + 7f, 6f, fillC)
        } else {
            val t = ((numOf(v) - minV) / (maxV - minV)).coerceIn(0f, 1f)
            ctx.drawQuad(trackX, trackY, trackX + trackW, trackY + 2f, trackC)
            ctx.drawQuad(trackX, trackY, trackX + t * trackW, trackY + 2f, fillC)
            ctx.drawRoundedRect(trackX + t * trackW - 6f, trackY - 5f, trackX + t * trackW + 6f, trackY + 7f, 6f, fillC)
        }

        sliderRects[v] = floatArrayOf(trackX, y + 22f, trackW, h - 22f, p.index.toFloat())
    }


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

            ctx.drawQuad(hx, hy2 - 3f, hx + 3f, hy2, headC)
            ctx.drawQuad(hx + w - 3f, hy2 - 3f, hx + w, hy2, headC)
        }


        val label = "${v.name} • ${choiceLabel(v)}"
        val maxTextW = w - 20f - 20f - 10f
        val text = shortenText(font, label, maxTextW, 12f)
        drawText(ctx, font, text, hx + 10f, hy + (DROPDOWN_H - 14f) / 2f, alpha(textColor, a), 12f)


        drawDropdownArrow(ctx, v, hx + w - 15.5f, hy + DROPDOWN_H / 2f, a)


        if (nested.isNotEmpty()) {
            dropdownHeadRects[v] = floatArrayOf(x, y, w - 20f, h, p.index.toFloat())
            drawExpandArrow(ctx, v, x + w - 9f, y + h / 2f, a)
            groupRects[v] = floatArrayOf(x + w - 20f, y, 20f, h, p.index.toFloat())
        } else {
            dropdownHeadRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
        }
    }


    private fun drawExpandArrow(ctx: GuiGraphicsExtractor, v: Value<*>, cx: Float, cy: Float, a: Int) {
        val open = groupOpen[v] == true
        val cur = groupOpenAnim.getOrDefault(v, if (open) 1f else 0f)
        val t = approach(cur, if (open) 1f else 0f, 2.5f)
        groupOpenAnim[v] = t
        drawChevron(ctx, cx, cy, t, a)
    }


    private fun drawDropdownArrow(ctx: GuiGraphicsExtractor, v: Value<*>, cx: Float, cy: Float, a: Int) {
        val open = dropdownOpen[v] == true
        val cur = dropdownAnim.getOrDefault(v, if (open) 1f else 0f)
        val t = approach(cur, if (open) 1f else 0f, 2.5f)
        dropdownAnim[v] = t
        drawChevron(ctx, cx, cy, t, a)
    }


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


    private fun drawMultiSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int, indent: Int,
    ) {
        val vi = info(v)
        val selected = multiSelected(v)


        drawText(ctx, font, v.name, x, y + SETTING_ROW_PAD, alpha(textColor, a), 12f)
        val amount = "${selected.size}/${vi.choices.size}"
        drawText(ctx, font, amount, x + w - 20f - strW(font, amount, 12f), y + SETTING_ROW_PAD, alpha(textColor, a), 12f)
        drawExpandArrow(ctx, v, x + w - 9f, y + SETTING_ROW_PAD + 7f, a)
        groupRects[v] = floatArrayOf(x, y, w, SETTING_ROW_PAD * 2f + 14f, p.index.toFloat())

        if (groupOpen[v] != true) return


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


    private fun drawColorSetting(
        ctx: GuiGraphicsExtractor, font: Font,
        v: Value<*>, x: Float, y: Float, h: Float, w: Float, p: Panel, a: Int,
    ) {
        val cur = colorOf(v)


        val swX = x + w - 30f
        val swY = y + 5f
        ctx.drawRoundedRect(swX, swY, x + w, y + 25f, 3f, alpha(cur, a))
        ctx.drawRoundedRect(swX, swY, x + w, y + 25f, 3f, Color4b.TRANSPARENT, alpha(accentColor, a), 1f)


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


        val hue = colorHue.getOrDefault(v, rgbToHsv(cur).first)
        val (sv, vv) = colorSV.getOrDefault(v, 0f to 1f)


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

        val cursorX = x + sv.coerceIn(0f, 1f) * w
        val cursorY = sbY + (1f - vv.coerceIn(0f, 1f)) * PICKER_SB_H
        ctx.drawRoundedRect(cursorX - 5f, cursorY - 5f, cursorX + 5f, cursorY + 5f, 5f, alpha(Color4b(255, 255, 255), a))
        ctx.drawRoundedRect(cursorX - 3f, cursorY - 3f, cursorX + 3f, cursorY + 3f, 3f, alpha(Color4b(0, 0, 0), a))


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

        val keyName = if (listening) "" else keyLabel(v)
        val sep = if (listening) "" else " "
        val full = if (listening) label else "$label$sep$keyName"
        val fullW = strW(font, full, 12f)
        val fx = x + (w - fullW) / 2f
        drawText(ctx, font, full, fx, by + 6f, alpha(textColor, a), 12f)
        if (!listening && keyName == "None") {

            val nameW = strW(font, label, 12f) + strW(font, sep, 12f)
            ctx.drawQuad(fx + nameW, by + 6f, fx + nameW + strW(font, keyName, 12f) + 1f, by + 20f, Color4b.TRANSPARENT)
            drawText(ctx, font, keyName, fx + nameW, by + 6f, alpha(dimmedColor, a), 12f)
        }
        keyRects[v] = floatArrayOf(x, y, w, h, p.index.toFloat())
    }


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



    private fun drawDropdown(ctx: GuiGraphicsExtractor, font: Font, layoutSpace: Boolean = true) {
        val v = dropdownValue ?: return
        val head = dropdownHeadRects[v] ?: return
        val opts = info(v).choices.ifEmpty { dropdownOptions }
        if (opts.isEmpty()) return
        val a = (uiAlpha * 255).toInt()

        // 与 Mode 头同在布局坐标（已在面板 Scale 矩阵内），贴在头下方展开
        val hx = head[0]
        val hy = head[1] + head[3]
        val hw = head[2]
        val optH = DROPDOWN_OPT_H
        val padV = 6f
        val listH = padV * 2f + opts.size * optH

        ctx.drawRoundedRect(hx - 1f, hy, hx + hw + 1f, hy + listH, 3f, mix(1f, a), alpha(accentColor, a), 1f)
        ctx.drawQuad(hx - 1f, hy, hx + hw + 1f, hy + 1f, mix(1f, a))

        val activeLabel = choiceLabel(v)
        // hover 用布局鼠标
        val panel = dropdownPanel
        val (lx, ly) = if (panel != null) toLayout(panel) else mouseX to mouseY
        var oy = hy + padV
        for (o in opts) {
            val label = taggedLabel(o)
            val hovered = lx >= hx && lx < hx + hw && ly >= oy && ly < oy + optH
            val col = when {
                label == activeLabel -> alpha(accentColor, a)
                hovered -> alpha(textColor, a)
                else -> alpha(dimmedColor, a)
            }
            val tw = strW(font, label, 12f)
            drawText(ctx, font, label, hx + (hw - tw) / 2f, oy + (optH - 14f) / 2f, col, 12f)
            oy += optH
        }

        // 命中区：布局坐标 + 所属面板 index 在 [4]
        val pi = panel?.index?.toFloat() ?: -1f
        dropdownRect = floatArrayOf(hx, hy + padV, hw, opts.size * optH, pi)
    }
}
