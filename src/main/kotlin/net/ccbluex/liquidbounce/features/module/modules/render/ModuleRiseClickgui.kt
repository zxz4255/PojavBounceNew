/*
 * ============================================================================
 *  ModuleRiseClickgui —— Rise Client ClickGUI 移植版 (LiquidBounce Nextgen 0.39)
 *
 *  逐文件还原 Rise 源码 (com.alan.clients.ui.click.standard):
 *   - RiseClickGUI.java   : 400x300 窗口, round=12, 顶栏15px拖动,
 *                           开启 EASE_IN_EXPO 300ms / 关闭 LINEAR 100ms 缩放,
 *                           透明度 EASE_OUT_EXPO 300ms, 分类切换 200ms 交叉淡黑
 *   - SidebarCategory.java: sidebarWidth=100, 左侧圆角, 右缘 30px 渐变阴影,
 *                           hover 透明度 ±2/ms, 滑出动画 700/2000ms
 *   - CategoryComponent   : y=py+offset+16 (offset 从 29.5 起, 步进 19.5),
 *                           选中高亮 rounded(w+8,15,r5) accent.darker(),
 *                           x 偏移 anim/80, 命中区 (x-11,y-5,70,22)
 *   - CategoryScreen.java : 模块 x=px+sidebarW+8, y=py+7+scroll, 间距7,
 *                           scrollMax = -contentH + winH - 7, 右缘滚动条
 *   - ModuleComponent.java: 卡片 283x38 r6 OVERLAY(0,0,0,50), 名称20/描述15,
 *                           hover 20 / 按下 35 黑色叠加, LINEAR 50ms,
 *                           展开 min(h*3,450)ms, settingOpacity 级联淡入,
 *                           值行 y < 卡片y+opening+15 才可见
 *   - NumberValue / BoundsNumberValue: 轨道 100x2 r1, 抓手 5x5 圆,
 *                           renderPercentage 速度30 平滑, 数值框可编辑
 *   - BooleanValue        : 名称右侧 5x5 圆点, 0→5 缩放动画
 *   - ModeValue/ListValue : "Name: value", 左键下一个 右键上一个
 *   - StringValue         : 名称 + 下方输入框, 行高 28
 *   - ColorValue          : 色块15x7 → 展开 110 行高, SB面板105x66(白→色相,
 *                           黑色纵向), 色相条 87x7, 预览块15x15, RGB+HEX
 *   - SearchScreen        : 顶部居中搜索框 150 宽, 打字自动切换, 显示分类后缀
 *
 *  LB 值类型映射: FLOAT/INT→滑条, FLOAT/INT_RANGE→双端, BOOLEAN→圆点,
 *   CHOICE(ModeValueGroup)→模式循环, CHOOSE→列表循环, MULTI_CHOOSE→循环开关,
 *   COLOR→取色器, TEXT→输入框, KEY/BIND→按键绑定,
 *   GROUP/TOGGLEABLE→分组标题(右键展开, TOGGLEABLE 左键开关)
 *
 *  所有 Rise 可调参数均做成设置项(45 项), 不依赖原客户端任何类。
 * ============================================================================
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
import kotlin.math.*

// ================================ 动画 ======================================

private enum class Ease { LINEAR, EASE_IN_EXPO, EASE_OUT_EXPO }

private fun easeOf(e: Ease, t: Double): Double = when (e) {
    Ease.LINEAR -> t
    Ease.EASE_IN_EXPO -> if (t >= 1.0) 1.0 else if (t <= 0.0) 0.0 else 2.0.pow(10.0 * t - 10.0)
    Ease.EASE_OUT_EXPO -> if (t >= 1.0) 1.0 else if (t <= 0.0) 0.0 else 1.0 - 2.0.pow(-10.0 * t)
}

/** Rise Animation 还原: run(target) 平滑逼近, easing + duration 每帧可改 */
private class Anim(var easing: Ease, var durationMs: Long) {
    var value: Double = 0.0
        private set
    private var from = 0.0
    private var startAt = 0L
    private var target = 0.0
    private var started = false

    fun run(target: Double) {
        val now = System.currentTimeMillis()
        if (!started || target != this.target) {
            from = value
            startAt = now
            this.target = target
            started = true
        }
        val t = if (durationMs <= 0L) 1.0 else ((now - startAt).toDouble() / durationMs).coerceIn(0.0, 1.0)
        value = from + (target - from) * easeOf(easing, t)
    }

    fun finished(): Boolean = !started ||
        System.currentTimeMillis() - startAt >= durationMs

    fun setValue(v: Double) {
        value = v
        from = v
        target = v
        started = true
    }
}

// ================================ 模块 ======================================

object ModuleRiseClickgui : ClientModule(
    "RiseClickgui",
    ModuleCategories.RENDER,
    aliases = listOf("RiseGui", "RiseClickGui", "Rise Click GUI"),
) {

    // ------------------------------ 设置 ------------------------------

    // 窗口
    private val windowW by float("Window Width", 400f, 280f..640f)
    private val windowH by float("Window Height", 300f, 200f..480f)
    private val cornerRadius by float("Corner Radius", 12f, 0f..24f)
    private val centerOnOpen by boolean("Center On Open", true)
    private val dimAlpha by float("Dim Background", 0.34f, 0f..0.8f)

    // 侧栏
    private val sidebarW by float("Sidebar Width", 100f, 60f..180f)
    private val sidebarAutoHide by boolean("Sidebar Auto Hide", true)
    private val catSpacing by float("Category Spacing", 19.5f, 12f..30f)

    // 模块卡片
    private val cardW by float("Card Width", 283f, 180f..460f)
    private val cardH by float("Card Height", 38f, 26f..60f)
    private val cardGap by float("Card Gap", 7f, 0f..20f)
    private val cardRadius by float("Card Radius", 6f, 0f..16f)
    private val showDescription by boolean("Show Description", true)

    // 字体 (Rise 20/16/15 → 以 MC 字体缩放)
    private val textScale by float("Text Scale", 1.0f, 0.7f..1.6f)
    private val nameSize by float("Name Size", 11f, 7f..16f)
    private val descSize by float("Description Size", 8f, 5f..12f)
    private val valueSize by float("Value Size", 9f, 6f..13f)
    private val catSize by float("Category Size", 9.5f, 6f..13f)
    private val titleSize by float("Title Size", 15f, 8f..24f)

    // 主题色 (Rise getAccentColor: firstColor→secondColor 纵向渐变)
    private val accentA by color("Accent A", Color4b(0x7A, 0x9C, 0xFF, 255))
    private val accentB by color("Accent B", Color4b(0x9C, 0x6F, 0xFF, 255))
    private val accentGradient by boolean("Accent Gradient", true)
    private val accentDrift by float("Accent Drift Sec", 6f, 0f..60f)
    private val selDarken by float("Selection Darken", 0.7f, 0.3f..1f)

    // Rise Colors 枚举
    private val bgColor by color("Background", Color4b(23, 26, 33, 254))
    private val sideBgColor by color("Sidebar Color", Color4b(18, 20, 25, 255))
    private val textColor by color("Text Color", Color4b(255, 255, 255, 255))
    private val secTextColor by color("Secondary Text", Color4b(255, 255, 255, 220))
    private val triTextColor by color("Trinary Text", Color4b(255, 255, 255, 130))

    // 动画
    private val openDur by float("Open Duration", 300f, 50f..1200f)
    private val closeDur by float("Close Duration", 100f, 50f..800f)
    private val expandCap by float("Expand Cap", 450f, 100f..1500f)
    private val catFadeMs by float("Category Fade", 200f, 50f..800f)
    private val scrollSmooth by float("Scroll Smooth", 14f, 4f..30f)
    private val scrollStep by float("Scroll Step", 28f, 5f..80f)

    // 滑条
    private val sliderW by float("Slider Width", 100f, 40f..240f)

    // 效果
    private val windowGlow by boolean("Window Glow", true)
    private val glowStrength by float("Glow Strength", 0.5f, 0.05f..1.5f)
    private val glowRadiusPx by float("Glow Radius", 18f, 4f..40f)
    private val showScrollbar by boolean("Show Scrollbar", true)
    private val hoverAlpha by float("Hover Alpha", 20f, 0f..80f)
    private val pressAlpha by float("Press Alpha", 35f, 0f..120f)

    // 搜索
    private val searchEnabled by boolean("Search Enabled", true)
    private val autoSearchSwitch by boolean("Auto Search Switch", true)

    // 其它
    private val middleClickBind by boolean("Middle Click Bind", true)

    // ------------------------------ 值类型 ------------------------------

    private enum class Kind {
        GROUP, TOGGLE_GROUP, MODE, CHOICE, MULTI, SLIDER, DUAL, BOOL, COLOR, TEXT, KEY, BIND, OTHER
    }

    private class VInfo(val v: Value<*>) {
        var kind = Kind.OTHER
        var range: Pair<Float, Float>? = null
        var isInt = false
        var choices: List<Any?> = emptyList()
    }

    private val infoCache = IdentityHashMap<Value<*>, VInfo>()

    private fun info(v: Value<*>): VInfo = infoCache.getOrPut(v) {
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
        }
        i
    }

    /** 分组/模式的直接子项 */
    private fun childrenOf(v: Value<*>): List<Value<*>> = runCatching {
        when (info(v).kind) {
            Kind.GROUP, Kind.TOGGLE_GROUP ->
                (v as ValueGroup).inner.filter { !it.notAnOption }
            Kind.MODE ->
                (v as ModeValueGroup<*>).activeMode.inner.filter { !it.notAnOption }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private val modChildren = IdentityHashMap<ClientModule, List<Value<*>>>()

    private fun moduleValues(mod: ClientModule): List<Value<*>> =
        modChildren.getOrPut(mod) {
            runCatching {
                mod.inner.filter { v ->
                    !v.notAnOption && !(v.name.equals("Enabled", true) && v.valueType == ValueType.BOOLEAN)
                }
            }.getOrDefault(emptyList())
        }

    // ------------------------------ UI 状态 ------------------------------

    private class VRow(val value: Value<*>, val indent: Int) {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        var alpha = 0
    }

    private class ModUi(val mod: ClientModule) {
        var expanded = false
        val opening = Anim(Ease.LINEAR, 200)
        val settingOpacity = Anim(Ease.LINEAR, 500)
        var hover = 0.0
        var mouseDown = false
        var initedH = false
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        var contentH = 0f
        val rows = ArrayList<VRow>()
    }

    private val modUis = IdentityHashMap<ClientModule, ModUi>()
    private val groupOpen = IdentityHashMap<Value<*>, Boolean>()
    private val colorOpen = IdentityHashMap<Value<*>, Boolean>()
    private val colorPointer = IdentityHashMap<Value<*>, Pair<Float, Float>>() // x,y ∈[0,1]
    private val colorHue = IdentityHashMap<Value<*>, Float>()
    private val sliderEase = IdentityHashMap<Value<*>, FloatArray>() // 单滑条 [p] / 双 [p1,p2]
    private val boolScale = IdentityHashMap<Value<*>, Float>()
    private val multiCursor = IdentityHashMap<Value<*>, Int>()

    // 命中区域(渲染帧记录)
    private val rowRects = IdentityHashMap<Value<*>, FloatArray>()
    private val sliderRects = IdentityHashMap<Value<*>, FloatArray>()
    private val pickerRects = IdentityHashMap<Value<*>, Array<FloatArray>>() // 0=SB 1=Hue
    private val numEditRects = IdentityHashMap<Value<*>, FloatArray>()
    private val textEditRects = IdentityHashMap<Value<*>, FloatArray>()

    // 拖动
    private var sliderDrag: Value<*>? = null
    private var dualWhich = 0
    private var colorDrag: Value<*>? = null
    private var colorDragChannel = -1

    // 编辑/监听
    private var editValue: Value<*>? = null
    private var editBuf = ""
    private var editNumeric = false
    private var keyListen: Value<*>? = null
    private var bindMod: ClientModule? = null

    // 窗口
    private var px = -1f
    private var py = -1f
    private var posInited = false
    private var wasOpen = false
    private var dragging = false
    private var dragOX = 0f
    private var dragOY = 0f
    private var leftDown = false

    // 开关动画
    private val scaleAnim = Anim(Ease.EASE_IN_EXPO, 300)
    private val opacityAnim = Anim(Ease.EASE_OUT_EXPO, 300)
    private var uiAlpha = 0f

    // 分类
    private var cats: List<ModuleCategory> = emptyList()
    private var selectedCat = 0
    private var lastCat = 0
    private var switchTime = 0L
    private val catAnims = ArrayList<Anim>()
    private val catHits = ArrayList<FloatArray>()
    private val catLabels = IdentityHashMap<ModuleCategory, String>()

    // 侧栏
    private var sideHover = false
    private var sideOpacity = 255.0
    private val sideSlide = Anim(Ease.EASE_OUT_EXPO, 700)
    private val sideShadow = Anim(Ease.LINEAR, 1000)

    // 滚动 (每个分类独立, 搜索独立)
    private val catScrollT = HashMap<Int, Float>()
    private val catScroll = HashMap<Int, Float>()
    private val catMax = HashMap<Int, Float>()
    private var searchScrollT = 0f
    private var searchScroll = 0f
    private var searchMax = 0f

    // 搜索
    private var searchMode = false
    private var searchBuf = ""
    private var searchResults: List<ClientModule> = emptyList()

    // 模块缓存
    private var allMods: List<ClientModule> = emptyList()
    private var modsByCat: Map<ModuleCategory, List<ClientModule>> = emptyMap()
    private var cacheSize = -1
    private var cacheTime = 0L

    // 帧状态
    private var lastFrame = 0L
    private var mx = 0f
    private var my = 0f
    private var scrollAccum = 0.0

    // ------------------------------ 生命周期 ------------------------------

    override fun onEnabled() {
        scaleAnim.setValue(0.0)
        opacityAnim.setValue(0.0)
        scaleAnim.durationMs = openDur.toLong().coerceAtLeast(1L)
        opacityAnim.durationMs = openDur.toLong().coerceAtLeast(1L)
        openScreen()
    }

    override fun onDisabled() {
        closeScreen()
        dragging = false
        leftDown = false
        sliderDrag = null
        colorDrag = null
        editValue = null
        keyListen = null
        bindMod = null
        searchMode = false
        searchBuf = ""
    }

    private fun openScreen() {
        runCatching { mc.gui.setScreen(RiseScreen()) }
    }

    private fun closeScreen() {
        runCatching { if (mc.gui.screen() is RiseScreen) mc.gui.setScreen(null) }
    }

    private class RiseScreen : Screen(Component.literal("RiseClickGui")) {
        override fun isPauseScreen() = false
        override fun shouldCloseOnEsc() = false

        override fun onClose() {
            if (ModuleRiseClickgui.enabled) ModuleRiseClickgui.enabled = false
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            ModuleRiseClickgui.onKey(event.key())
            return true
        }

        override fun charTyped(event: CharacterEvent): Boolean {
            val ch = runCatching { event.codepoint().toChar() }.getOrNull()
            if (ch != null) ModuleRiseClickgui.onChar(ch)
            return true
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            ModuleRiseClickgui.onClick(event.button())
            return true
        }

        override fun mouseReleased(event: MouseButtonEvent): Boolean {
            ModuleRiseClickgui.onRelease(event.button())
            return true
        }

        override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
            ModuleRiseClickgui.scrollAccum += v
            return true
        }
    }

    // ------------------------------ 输入 ------------------------------

    private fun guiMouseX(sw: Float) = (mc.mouseHandler.xpos() * sw / mc.window.width).toFloat()
    private fun guiMouseY(sh: Float) = (mc.mouseHandler.ypos() * sh / mc.window.height).toFloat()

    private fun over(x: Float, y: Float, w: Float, h: Float) =
        mx >= x && my >= y && mx < x + w && my < y + h

    private fun onClick(button: Int) {
        if (button == 0) leftDown = true
        // 顶栏拖动 (Rise: 前 15px)
        if (over(px, py, windowW, 15f)) {
            dragging = true
            dragOX = px - mx
            dragOY = py - my
            return
        }
        if (!over(px, py, windowW, windowH)) return

        // 侧栏分类
        if (sideOpacity > 0) {
            for (i in catHits.indices) {
                val r = catHits[i]
                if (button == 0 && over(r[0], r[1], r[2], r[3])) {
                    if (i != selectedCat || searchMode) {
                        lastCat = selectedCat
                        selectedCat = i
                        switchTime = System.currentTimeMillis()
                    }
                    searchMode = false
                    return
                }
            }
        }

        clickScreen(button)
    }

    private fun onRelease(button: Int) {
        if (button == 0) {
            leftDown = false
            dragging = false
        }
        sliderDrag = null
        colorDrag = null
        for (ui in modUis.values) ui.mouseDown = false
    }

    private fun onKey(key: Int) {
        // 按键捕获
        bindMod?.let { mod ->
            runCatching {
                val code = if (key == GLFW.GLFW_KEY_ESCAPE) InputConstants.UNKNOWN.value else key
                mod.bindValue.set(mod.bind.copy(boundKey = InputConstants.Type.KEYSYM.getOrCreate(code)))
            }
            bindMod = null
            return
        }
        keyListen?.let { v ->
            setKeyValue(v, key)
            keyListen = null
            return
        }
        // 文本编辑
        editValue?.let { v ->
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> editValue = null
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    commitEdit(v)
                    editValue = null
                }
                GLFW.GLFW_KEY_BACKSPACE -> if (editBuf.isNotEmpty()) editBuf = editBuf.dropLast(1)
            }
            return
        }
        // 搜索编辑
        if (searchMode) {
            when (key) {
                GLFW.GLFW_KEY_BACKSPACE -> if (searchBuf.isNotEmpty()) {
                    searchBuf = searchBuf.dropLast(1)
                    refreshSearch()
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    if (searchBuf.isNotEmpty()) {
                        searchBuf = ""
                        refreshSearch()
                    } else {
                        searchMode = false
                    }
                }
            }
            return
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            enabled = false
        }
    }

    private fun onChar(ch: Char) {
        if (ch.isISOControl()) return
        editValue?.let {
            if (editBuf.length < 64) editBuf += ch
            return
        }
        if (searchMode) {
            if (searchBuf.length < 40) {
                searchBuf += ch
                refreshSearch()
            }
            return
        }
        if (autoSearchSwitch && searchEnabled &&
            "abcdefghijklmnopqrstuvwxyz1234567890 ".contains(ch.lowercaseChar())
        ) {
            searchMode = true
            searchBuf = ch.toString()
            searchScrollT = 0f
            searchScroll = 0f
            refreshSearch()
        }
    }

    private fun refreshSearch() {
        searchResults = if (searchBuf.isBlank()) emptyList() else {
            allModules().filter { it.name.contains(searchBuf, ignoreCase = true) }
        }
    }

    // ------------------------------ 颜色/数值辅助 ------------------------------

    private fun numOf(v: Value<*>): Float = (v.get() as? Number)?.toFloat() ?: 0f

    private fun fmt(f: Float): String {
        if (!f.isFinite()) return "0"
        if (abs(f - f.roundToInt()) < 1e-3f) return f.roundToInt().toString()
        return String.format(java.util.Locale.US, "%.2f", f).trimEnd('0').trimEnd('.')
    }

    private fun colorOf(v: Value<*>): Color4b = runCatching { v.get() as Color4b }
        .getOrDefault(Color4b(255, 255, 255, 255))

    private fun setColorValue(v: Value<*>, c: Color4b) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (v as Value<Any>).set(c)
        }
    }

    private fun rgbMax(r: Float, g: Float, b: Float) = max(r, max(g, b))
    private fun rgbMin(r: Float, g: Float, b: Float) = min(r, min(g, b))

    private fun hueOf(c: Color4b): Float {
        val r = c.r / 255f
        val g = c.g / 255f
        val b = c.b / 255f
        val mx = rgbMax(r, g, b)
        val mn = rgbMin(r, g, b)
        val d = mx - mn
        if (d < 1e-5f) return 0f
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return ((h / 6f) % 1f + 1f) % 1f
    }

    /** RGB → (saturation, value), 用于取色器光标初始定位 */
    private fun satValOf(c: Color4b): Pair<Float, Float> {
        val r = c.r / 255f
        val g = c.g / 255f
        val b = c.b / 255f
        val mx = rgbMax(r, g, b)
        val mn = rgbMin(r, g, b)
        val s = if (mx < 1e-5f) 0f else (mx - mn) / mx
        return s to mx
    }

    private fun hsvColor(h: Float, s: Float, v: Float, a: Int): Color4b {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Color4b(
            (r * 255f).roundToInt().coerceIn(0, 255),
            (g * 255f).roundToInt().coerceIn(0, 255),
            (b * 255f).roundToInt().coerceIn(0, 255),
            a.coerceIn(0, 255),
        )
    }

    private fun mix(a: Color4b, b: Color4b, t: Float): Color4b = Color4b(
        lerpI(a.r, b.r, t), lerpI(a.g, b.g, t), lerpI(a.b, b.b, t), lerpI(a.a, b.a, t),
    )

    private fun lerpI(x: Int, y: Int, t: Float) = (x + (y - x) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    /** Rise Color.darker() = RGB * 0.7 */
    private fun Color4b.darker(f: Float): Color4b = Color4b(
        (r * f).toInt().coerceIn(0, 255),
        (g * f).toInt().coerceIn(0, 255),
        (b * f).toInt().coerceIn(0, 255),
        a,
    )

    /** Rise getTheme().getAccentColor(y): firstColor→secondColor 纵向渐变 + 漂移 */
    private fun accentAt(ySeed: Float): Color4b {
        if (!accentGradient) return accentA
        val cyc = accentDrift.coerceAtLeast(0f) * 1000f
        val t = if (cyc > 1f) (System.currentTimeMillis() % cyc.toLong()) / cyc else 0f
        val u = (((ySeed * 0.0035f + t) % 1f) + 1f) % 1f
        val s = u * u * (3f - 2f * u)
        return mix(accentA, accentB, s)
    }

    private fun A(c: Color4b): Color4b = c.alpha((c.a * uiAlpha).toInt().coerceIn(0, 255))

    private fun RA(c: Color4b, rowAlpha: Int): Color4b =
        c.alpha((c.a * uiAlpha * rowAlpha.coerceIn(0, 255) / 255f).toInt().coerceIn(0, 255))

    // ------------------------------ 模块缓存 ------------------------------

    private fun allModules(): List<ClientModule> {
        val now = System.currentTimeMillis()
        val raw = runCatching { ModuleManager.getModules() }.getOrNull() ?: return allMods
        if (raw.size != cacheSize || now - cacheTime > 2000) {
            cacheSize = raw.size
            cacheTime = now
            val list = raw.toList()
            val byCat = IdentityHashMap<ModuleCategory, MutableList<ClientModule>>()
            for (m in list) byCat.getOrPut(m.category) { ArrayList() }.add(m)
            for (k in byCat.keys.toList()) byCat[k] = byCat[k].orEmpty().sortedBy { it.name }
            modsByCat = byCat
            allMods = list
            modChildren.clear()
            cats = byCat.keys.sortedBy { catLabel(it) }
            while (catAnims.size < cats.size) catAnims.add(Anim(Ease.LINEAR, 200))
            while (catHits.size < cats.size) catHits.add(floatArrayOf(0f, 0f, 0f, 0f))
            if (selectedCat >= cats.size) selectedCat = 0
            if (lastCat >= cats.size) lastCat = selectedCat
        }
        return allMods
    }

    private fun modsIn(cat: ModuleCategory): List<ClientModule> =
        modsByCat[cat] ?: emptyList()

    private fun catLabel(cat: ModuleCategory): String = catLabels.getOrPut(cat) {
        runCatching {
            for (n in listOf("getReadableName", "readableName", "getDisplayName", "getName", "name")) {
                val m = cat.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                } ?: continue
                val r = m.invoke(cat) ?: continue
                if (r is String && r.isNotBlank() && !r.contains('@') && r.length < 24) {
                    return@getOrPut r.replaceFirstChar { it.uppercase() }
                }
            }
        }
        cat.toString().substringAfterLast('.').substringAfterLast('$').substringBefore('@')
            .trim().replace('_', ' ').lowercase()
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifBlank { "Misc" }
    }

    private fun uiOf(mod: ClientModule): ModUi = modUis.getOrPut(mod) { ModUi(mod) }

    private fun modDesc(mod: ClientModule): String = runCatching {
        val d: Any? = mod.description
        if (d is java.util.function.Supplier<*>) d.get()?.toString() else d?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: ""

    // ------------------------------ 值组件 ------------------------------

    private fun drawValue(ctx: GuiGraphicsExtractor, font: Font, r: VRow) {
        val v = r.value
        val a = r.alpha
        rowRects[v] = floatArrayOf(r.x, r.y, r.w, r.h)
        when (info(v).kind) {
            Kind.BOOL -> drawBool(ctx, font, r, a)
            Kind.SLIDER -> drawSlider(ctx, font, r, a, dual = false)
            Kind.DUAL -> drawSlider(ctx, font, r, a, dual = true)
            Kind.MODE, Kind.CHOICE, Kind.MULTI -> drawCycle(ctx, font, r, a)
            Kind.COLOR -> drawColor(ctx, font, r, a)
            Kind.TEXT -> drawTextValue(ctx, font, r, a)
            Kind.KEY, Kind.BIND -> drawKey(ctx, font, r, a)
            Kind.GROUP, Kind.TOGGLE_GROUP -> drawGroup(ctx, font, r, a)
            else -> {}
        }
    }

    /** Rise NumberValue / BoundsNumberValue: 轨道 sliderW x 2 r1, 抓手 5x5 圆 */
    private fun drawSlider(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int, dual: Boolean) {
        val v = r.value
        val vi = info(v)
        val b = vi.range
        if (b == null) {
            drawCycle(ctx, font, r, a)
            return
        }
        val name = shorten(font, v.name, r.w - sliderW - 30f, valueSize)
        drawStr(ctx, font, name, r.x, r.y, valueSize, RA(secTextColor, a))
        val nameW = strW(font, name, valueSize)
        val sx = r.x + nameW + 7f
        val sy = r.y + 1.5f * ts()
        val swid = sliderW.coerceAtMost(r.w - nameW - 12f).coerceAtLeast(30f)

        if (sliderDrag == v) applySlider(v)

        val pct = if (dual) {
            val c = dualOf(v)
            Pair(
                ((c.first - b.first) / (b.second - b.first)).coerceIn(0f, 1f),
                ((c.second - b.first) / (b.second - b.first)).coerceIn(0f, 1f),
            )
        } else {
            ((numOf(v) - b.first) / (b.second - b.first)).coerceIn(0f, 1f)
        }

        // renderPercentage 平滑 (Rise 速度 30/ms)
        val cur = sliderEase.getOrPut(v) {
            if (dual) floatArrayOf(pct.first, pct.second) else floatArrayOf(pct)
        }
        val k = (1f - 0.9f.pow(frameDt * 1000f)).coerceIn(0f, 1f)
        if (dual) {
            cur[0] += (pct.first - cur[0]) * k
            cur[1] += (pct.second - cur[1]) * k
        } else {
            cur[0] += (pct - cur[0]) * k
        }

        // 轨道
        ctx.drawRoundedRect(sx, sy, sx + swid, sy + 2f * ts(), 1f, RA(bgColor, a))
        val acc = accentAt(r.y)
        if (dual) {
            ctx.drawRoundedRect(
                sx + swid * cur[0], sy, sx + swid * cur[1], sy + 2f * ts(), 1f, RA(acc, a),
            )
            ctx.drawRoundedRect(
                sx + swid * cur[0] - 2.5f, r.y, sx + swid * cur[0] + 2.5f, r.y + 5f * ts(), 2.5f * ts(), RA(acc, a),
            )
            ctx.drawRoundedRect(
                sx + swid * cur[1] - 2.5f, r.y, sx + swid * cur[1] + 2.5f, r.y + 5f * ts(), 2.5f * ts(), RA(acc, a),
            )
        } else {
            ctx.drawRoundedRect(sx, sy, sx + swid * cur[0], sy + 2f * ts(), 1f, RA(acc, a))
            ctx.drawRoundedRect(
                sx + swid * cur[0] - 2.5f, r.y, sx + swid * cur[0] + 2.5f, r.y + 5f * ts(), 2.5f * ts(), RA(acc, a),
            )
        }
        sliderRects[v] = floatArrayOf(sx - 5f, r.y - 4f, swid + 10f, r.h + 8f)

        // 数值 (可点击编辑, 位于滑条右侧)
        val txt = if (dual) {
            val c = dualOf(v)
            fmt(c.first) + " " + fmt(c.second)
        } else {
            fmt(numOf(v))
        }
        val editing = editValue == v
        val showTxt = if (editing) editBuf else txt
        val cursor = if (editing && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        val tx2 = sx + swid + 5f
        val tw = strW(font, showTxt, valueSize)
        val availR = r.x + r.w - tx2
        drawStr(
            ctx, font, showTxt + cursor,
            (r.x + r.w - (tw + if (cursor.isEmpty()) 0f else strW(font, "_", valueSize))).coerceAtMost(tx2 + (availR - tw).coerceAtLeast(0f)),
            r.y, valueSize,
            RA(if (editing) acc else triTextColor, a),
        )
        numEditRects[v] = floatArrayOf(tx2, r.y - 3f, (r.x + r.w - tx2).coerceAtLeast(10f), r.h)
    }

    /** Rise BooleanValue: 名称右侧 5x5 圆点, 0→5 缩放动画 */
    private fun drawBool(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int) {
        val v = r.value
        val on = runCatching { v.get() as Boolean }.getOrDefault(false)
        drawStr(ctx, font, v.name, r.x, r.y, valueSize, RA(secTextColor, a))

        val dotX = r.x + strW(font, v.name, valueSize) + 3f
        val size = 5f * ts()
        // 背景圆
        ctx.drawRoundedRect(dotX, r.y + size / 2f, dotX + size, r.y + size * 1.5f, size / 2f, RA(bgColor, a))
        // 中心圆 (0→5, ~80ms)
        val target = if (on) 1f else 0f
        var cur = boolScale.getOrDefault(v, if (on) 1f else 0f)
        cur += (target - cur) * (frameDt / 0.08f).coerceIn(0f, 1f)
        boolScale[v] = cur
        if (cur > 0.01f) {
            val s2 = size * cur
            val acc = accentAt(r.y)
            ctx.drawRoundedRect(
                dotX + size / 2f - s2 / 2f, r.y + size - s2 / 2f,
                dotX + size / 2f + s2 / 2f, r.y + size + s2 / 2f,
                s2 / 2f, RA(acc, a),
            )
        }
    }

    /** Rise ModeValue/ListValue: "Name: value", 左键下一个 右键上一个 */
    private fun drawCycle(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int) {
        val v = r.value
        val kind = info(v).kind
        val display = runCatching {
            when (kind) {
                Kind.MODE -> (v as ModeValueGroup<*>).activeMode.name
                Kind.CHOICE -> choiceLabel(v.get())
                Kind.MULTI -> {
                    val sel = (v.get() as? Iterable<*>)?.map { choiceLabel(it) }
                        ?.joinToString(", ").orEmpty()
                    if (sel.isBlank()) "None" else sel
                }
                else -> v.get().toString()
            }
        }.getOrNull() ?: "..."
        val txt = "${v.name}: $display"
        drawStr(ctx, font, shorten(font, txt, r.w, valueSize), r.x, r.y, valueSize, RA(secTextColor, a))
        // 左右切换箭头指示
        val acc = accentAt(r.y)
        drawStr(ctx, font, "‹", r.x + r.w - 14f, r.y, valueSize, RA(acc.alpha(140), a))
        drawStr(ctx, font, "›", r.x + r.w - 7f, r.y, valueSize, RA(acc.alpha(140), a))
    }

    private fun choiceLabel(c: Any?): String = when (c) {
        null -> "None"
        is Tagged -> c.tag
        is Enum<*> -> c.name
        else -> c.toString()
    }

    /** KEY / BIND */
    private fun drawKey(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int) {
        val v = r.value
        val listening = keyListen == v
        val cur = runCatching {
            when (info(v).kind) {
                Kind.BIND -> (v.get() as? InputBind)?.boundKey?.name?.substringAfterLast('.')?.uppercase()
                else -> (v.get() as? InputConstants.Key)?.name?.substringAfterLast('.')?.uppercase()
            }
        }.getOrNull() ?: "NONE"
        val shown = if (listening) "..." else cur
        val col = if (listening) accentAt(r.y) else triTextColor
        drawStr(
            ctx, font, "${v.name}: $shown", r.x, r.y, valueSize,
            RA(if (listening) col else secTextColor, a),
        )
    }

    /** GROUP / TOGGLEABLE */
    private fun drawGroup(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int) {
        val v = r.value
        val kind = info(v).kind
        val open = groupOpen[v] == true
        val arrow = if (open) "-" else ">"
        drawStr(ctx, font, v.name, r.x, r.y, valueSize, RA(textColor, a))
        drawStr(ctx, font, arrow, r.x + r.w - 8f, r.y, valueSize, RA(triTextColor, a))
        if (kind == Kind.TOGGLE_GROUP) {
            val on = runCatching { (v as ToggleableValueGroup).enabled }.getOrDefault(true)
            val dotX = r.x + strW(font, v.name, valueSize) + 5f
            val size = 5f * ts()
            ctx.drawRoundedRect(dotX, r.y + size / 2f, dotX + size, r.y + size * 1.5f, size / 2f, RA(bgColor, a))
            if (on) {
                val acc = accentAt(r.y)
                ctx.drawRoundedRect(dotX, r.y + size / 2f, dotX + size, r.y + size * 1.5f, size / 2f, RA(acc, a))
            }
        }
    }

    /** Rise StringValue: 名称 + 下方输入框, 行高 28 */
    private fun drawTextValue(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int) {
        val v = r.value
        drawStr(ctx, font, v.name, r.x, r.y, valueSize, RA(secTextColor, a))

        val boxY = r.y + 14f * ts()
        val boxH = 13f * ts()
        val editing = editValue == v
        val acc = accentAt(r.y)
        ctx.drawRoundedRect(r.x, boxY, r.x + r.w, boxY + boxH, 3f, RA(bgColor, a))
        if (editing) {
            ctx.drawRoundedRect(
                r.x, boxY + boxH - 1f, r.x + r.w, boxY + boxH, 0.5f, RA(acc, a),
            )
        }
        val content = if (editing) editBuf else runCatching {
            when (val cur = v.get()) {
                is Regex -> cur.pattern
                else -> cur as? String
            }
        }.getOrNull() ?: ""
        val cursor = if (editing && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        drawStr(
            ctx, font, shorten(font, content + cursor, r.w - 6f, valueSize) + "",
            r.x + 3f, boxY + 2f, valueSize,
            RA(if (editing) textColor else triTextColor, a),
        )
        textEditRects[v] = floatArrayOf(r.x, boxY, r.w, boxH)
    }

    /**
     * Rise ColorValueComponent 还原:
     *  折叠: 名称 + 右侧 15x7 圆角色块 (COLOR_WIDTH=5 → 5*3 x 7, r=2.5)
     *  展开: 105x105 面板 (pickerHeight 120-15), SB 面板 104x66 (左白→右色相,
     *        上亮→下黑, mixColors(c1,c2,t)=t*c1+(1-t)*c2), 色相条 88x7 r=round-5,
     *        色相抓手 round-1 方块黑描边, 预览块 15x15.5 r=3.5, RGB 三值 + HEX
     */
    private fun drawColor(ctx: GuiGraphicsExtractor, font: Font, r: VRow, a: Int) {
        val v = r.value
        val nameW = strW(font, v.name, valueSize)
        drawStr(ctx, font, v.name, r.x, r.y, valueSize, RA(secTextColor, a))

        // 拖动中每帧实时应用 (同 Rise draw() 内 colorPickerDown/huePickerDown 分支)
        if (colorDrag == v) applyPicker(v)
        val cur = colorOf(v)

        // 名称右侧色块
        ctx.drawRoundedRect(
            r.x + nameW + 4f, r.y, r.x + nameW + 19f, r.y + 7f * ts(), 2.5f, RA(cur, a),
        )

        if (colorOpen[v] != true) return

        // ---- 展开面板 ----
        val panelW = 105f
        val panelH = 105f
        val panelX = (r.x + nameW + 18.5f).coerceAtMost(r.x + r.w - panelW - 2f)
        val panelY = r.y + 0.5f
        val rad = (cornerRadius - 3f).coerceIn(2f, 12f)

        // 面板边框 (SECONDARY) + 面板底 (BACKGROUND)
        ctx.drawRoundedRect(
            panelX - 0.5f, panelY, panelX + panelW, panelY + panelH, rad,
            RA(Color4b(255, 255, 255, 26), a),
        )
        ctx.drawRoundedRect(
            panelX, panelY, panelX + panelW - 1f, panelY + panelH - 1f, rad, RA(bgColor, a),
        )

        // SB 面板: 左白→右色相, 上亮→下黑; 格子中心落在圆角外则跳过 (r=7)
        val sbX = panelX + 0.5f
        val sbY = panelY + 0.5f
        val sbW = panelW - 1f
        val sbH = 66f
        val hue = colorHue.getOrDefault(v, hueOf(cur))
        val hueCol = hsvColor(hue, 1f, 1f, 255)
        val cols = 24
        val rows = 15
        val cw = sbW / cols
        val chh = sbH / rows
        val sbR = 7f
        for (row in 0 until rows) {
            val cy = sbY + chh * (row + 0.5f)
            for (c in 0 until cols) {
                val cx = sbX + cw * (c + 0.5f)
                // 圆角 SDF: 到内缩矩形的距离 > 半径即在圆外
                val nx = cx.coerceIn(sbX + sbR, sbX + sbW - sbR)
                val ny = cy.coerceIn(sbY + sbR, sbY + sbH - sbR)
                if ((cx - nx) * (cx - nx) + (cy - ny) * (cy - ny) > sbR * sbR) continue
                ctx.drawQuad(
                    sbX + cw * c, sbY + chh * row, sbX + cw * (c + 1), sbY + chh * (row + 1),
                    RA(hsvColor(hue, (c + 0.5f) / cols, 1f - (row + 0.5f) / rows, 255), a),
                )
            }
        }

        // 色相条 (padding 8.5, y = sb 底 + 8.5 - 5, 高 round-5)
        val pad = 8.5f
        val hueX = panelX + pad
        val hueY = sbY + sbH + pad - 5f
        val hueW = panelW - pad * 2f
        val hueBarH = (cornerRadius - 5f).coerceIn(3f, 10f)
        val segs = 22
        val sw = hueW / segs
        for (i in 0 until segs) {
            ctx.drawQuad(
                hueX + sw * i, hueY + 2.5f, hueX + sw * (i + 1), hueY + 2.5f + hueBarH,
                RA(hsvColor((i + 0.5f) / segs, 1f, 1f, 255), a),
            )
        }

        // 色相抓手 (round-1 方块, r=round/3+1, 黑描边)
        val ms = cornerRadius - 1f
        val hx = hueX + hue * hueW - ms / 2f + 0.5f
        val hy = hueY + 0.5f
        ctx.drawRoundedRect(
            hx, hy, hx + ms, hy + ms, cornerRadius / 3f + 1f,
            Color4b.TRANSPARENT, RA(Color4b(0, 0, 0, 220), a), 1f,
        )
        ctx.drawRoundedRect(
            hx + 1f, hy + 1f, hx + ms - 1f, hy + ms - 1f, cornerRadius / 3f, RA(hueCol, a),
        )

        // 预览块 15x15.5 (y = sb 底 + padding*2 + round - 11)
        val pvX = panelX + pad
        val pvY = sbY + sbH + pad * 2f + cornerRadius - 11f
        ctx.drawRoundedRect(
            pvX, pvY, pvX + 15f, pvY + 15.5f, 3.5f, RA(cur, a),
            RA(Color4b(0, 0, 0, 120), a), 0.75f,
        )

        // RGB 三值 + HEX (textX = x + padding*2 + 15)
        val textX = panelX + pad * 2f + 15f
        val rgbY = pvY
        fun ctr(s: String, cx: Float, size: Float, c: Color4b) =
            drawStr(ctx, font, s, cx - strW(font, s, size) / 2f, rgbY, size, c)
        ctr(cur.r.toString(), textX + pad, valueSize, RA(secTextColor, a))
        ctr(cur.g.toString(), textX + 30f, valueSize, RA(secTextColor, a))
        ctr(cur.b.toString(), textX + pad * 6f, valueSize, RA(secTextColor, a))
        // Rise 原版此处用 new Color(55,59,61).hashCode() (AWT 身份哈希, 实为 bug),
        // 这里还原为可读的次级灰
        drawStr(
            ctx, font, String.format("#%02X%02X%02X", cur.r, cur.g, cur.b),
            textX, rgbY + 13f * ts(), valueSize * 0.85f, RA(triTextColor, a),
        )

        // SB 光标 (白 7 / 黑 6 / 色 5 三层圆)
        val (ps, pv) = colorPointer.getOrDefault(v, satValOf(cur))
        val cx2 = sbX + ps.coerceIn(0f, 1f) * sbW
        val cy2 = sbY + (1f - pv.coerceIn(0f, 1f)) * sbH
        ctx.drawRoundedRect(cx2 - 3.5f, cy2 - 3.5f, cx2 + 3.5f, cy2 + 3.5f, 3.5f, RA(Color4b(255, 255, 255), a))
        ctx.drawRoundedRect(cx2 - 3f, cy2 - 3f, cx2 + 3f, cy2 + 3f, 3f, RA(Color4b(0, 0, 0), a))
        ctx.drawRoundedRect(cx2 - 2.5f, cy2 - 2.5f, cx2 + 2.5f, cy2 + 2.5f, 2.5f, RA(cur, a))

        // 命中区: 0=SB 面板 1=色相条 2=展开区(点击不收起)
        pickerRects[v] = arrayOf(
            floatArrayOf(sbX, sbY, sbW, sbH),
            floatArrayOf(hueX, hueY, hueW, hueBarH + 5f),
            floatArrayOf(panelX, sbY + sbH, panelW, panelH - sbH),
        )
    }

    private fun buildRows(out: MutableList<VRow>, values: List<Value<*>>, indent: Int) {
        for (v in values) {
            if (v.notAnOption) continue
            val vi = info(v)
            if (vi.kind == Kind.OTHER) continue
            out.add(VRow(v, indent))
            when (vi.kind) {
                Kind.GROUP, Kind.TOGGLE_GROUP ->
                    if (groupOpen[v] == true) buildRows(out, childrenOf(v), indent + 1)
                Kind.MODE -> buildRows(out, childrenOf(v), indent + 1)
                else -> {}
            }
        }
    }

    private fun rowHeight(v: Value<*>): Float = when (info(v).kind) {
        Kind.TEXT -> 28f
        Kind.COLOR -> if (colorOpen[v] == true) 110f else 15f
        else -> 14f
    } * ts()

    /** 返回卡片当前动画高度 (Rise ModuleComponent) */
    private fun drawModule(
        ctx: GuiGraphicsExtractor, font: Font,
        ui: ModUi, x: Float, y: Float, withCategory: String?,
    ): Float {
        val mod = ui.mod
        ui.rows.clear()
        buildRows(ui.rows, moduleValues(mod), 0)

        var contentH = cardH - 1f
        for (r in ui.rows) contentH += rowHeight(r.value)

        val expanded = ui.expanded && ui.rows.isNotEmpty()
        ui.opening.easing = Ease.LINEAR
        ui.opening.durationMs = (contentH * 3f).coerceAtMost(expandCap).toLong().coerceAtLeast(60L)
        if (!ui.initedH) {
            ui.opening.setValue(cardH.toDouble())
            ui.initedH = true
        }
        ui.opening.run(if (expanded) contentH.toDouble() else cardH.toDouble())
        val h = ui.opening.value.toFloat()

        ui.x = x
        ui.y = y
        ui.w = cardW
        ui.h = h
        ui.contentH = contentH

        val visible = y + h > py && y < py + windowH
        if (visible) {
            // hover / press 黑色叠加 (Rise: LINEAR 50ms, 20/35)
            val overCard = over(x, y, cardW, cardH - 3f)
            val target = when {
                !overCard -> 0.0
                ui.mouseDown && leftDown -> pressAlpha.toDouble()
                else -> hoverAlpha.toDouble()
            }
            ui.hover += (target - ui.hover) * (dt50())
            val hv = ui.hover

            ctx.drawRoundedRect(x, y, x + cardW, y + h, cardRadius, A(Color4b(0, 0, 0, 50)))
            if (hv > 0.5) {
                ctx.drawRoundedRect(
                    x, y, x + cardW, y + h, cardRadius,
                    Color4b(0, 0, 0, (hv * uiAlpha).toInt().coerceIn(0, 255)),
                )
            }

            // 名称 (启用 → 主题色) + 描述
            val nameCol = if (mod.enabled) accentAt(y) else secTextColor.alpha(200)
            drawStr(ctx, font, mod.name, x + 6f, y + 8f, nameSize, A(nameCol))
            if (withCategory != null) {
                drawStr(
                    ctx, font, withCategory,
                    x + 6f + strW(font, mod.name, nameSize) + 5f, y + 9f, descSize, A(triTextColor),
                )
            }
            if (showDescription) {
                val d = modDesc(mod)
                if (d.isNotBlank()) {
                    drawStr(ctx, font, d.take(48), x + 6f, y + 25f, descSize, A(triTextColor))
                }
            }
            // 绑定提示
            if (bindMod == mod) {
                drawStr(ctx, font, "Bind: press a key...", x + cardW - strW(font, "Bind: press a key...", descSize) - 6f,
                    y + 8f, descSize, A(accentAt(y)))
            }
        }

        // 值 (Rise: !finished || expanded)
        if (!ui.opening.finished() || ui.expanded) {
            ui.settingOpacity.easing = Ease.LINEAR
            ui.settingOpacity.durationMs = (ui.opening.durationMs / (if (expanded) 2L else 3L)).coerceAtLeast(30L)
            ui.settingOpacity.run(if (expanded) 255.0 else 0.0)

            var vy = y + cardH + 1f
            for (r in ui.rows) {
                val rh = rowHeight(r.value)
                r.x = x + 6f + r.indent * 10f
                r.y = vy
                r.w = cardW - 12f - r.indent * 10f
                r.h = rh
                val rowA = if (vy < y + h + 15f) ui.settingOpacity.value.toInt() else 0
                r.alpha = rowA
                if (visible && rowA > 0 && vy + rh > py && vy < py + windowH) {
                    drawValue(ctx, font, r)
                }
                vy += rh
            }
        }
        return h
    }

    private fun dt50() = (frameDt / 0.05f).coerceIn(0f, 1f)

    private fun drawRoundedRectLeft(
        ctx: GuiGraphicsExtractor,
        x1: Float, y1: Float, x2: Float, y2: Float, r: Float, c: Color4b,
    ) {
        if (r <= 0.5f) {
            ctx.drawQuad(x1, y1, x2, y2, c)
            return
        }
        ctx.drawRoundedRect(x1, y1, x2, y2, r, c)
        if (c.a >= 254) ctx.drawQuad(x2 - r, y1, x2, y2, c)
    }

    private fun renderSidebar(ctx: GuiGraphicsExtractor, font: Font, dt: Float) {
        val hideBar = searchMode && searchEnabled && sidebarAutoHide
        // Rise SidebarCategory: hovering 判定
        val zoneW = if (sideHover) 310f else 210f
        val overZone = mx >= px - 200f && my >= py && mx < px - 200f + zoneW && my < py + windowH
        val want = ((!leftDown || sideHover) && overZone) || !hideBar
        sideHover = want
        val rate = if (want) 2f else 1.5f
        sideOpacity = (sideOpacity + (if (want) 1 else -1) * rate * dt * 1000f).coerceIn(0.0, 255.0)
        val op = sideOpacity.toFloat()

        sideSlide.easing = Ease.EASE_OUT_EXPO
        sideSlide.durationMs = if (want) 700L else 2000L
        sideSlide.run(if (want) 0.0 else (-sidebarW / 1.5f).toDouble())
        val slide = sideSlide.value.toFloat()

        sideShadow.easing = Ease.LINEAR
        sideShadow.durationMs = 1000L
        sideShadow.run(if (hideBar) 255.0 else 0.0)

        if (op <= 0.5f && !want && abs(slide - (-sidebarW / 1.5f)) < 0.5f) return

        // 侧栏底 (左圆角)
        val bgC = sideBgColor.alpha((op * uiAlpha).toInt().coerceIn(0, 255))
        drawRoundedRectLeft(ctx, px, py, px + sidebarW + slide, py + windowH, cornerRadius, bgC)

        // 右缘渐变阴影 (30px)
        val shadowA = (min(sideShadow.value, op / 7.0) * uiAlpha).toInt().coerceIn(0, 255)
        if (shadowA > 1) {
            val gw = 30f
            val strips = 10
            for (i in 0 until strips) {
                val a = (shadowA * (1f - i.toFloat() / strips)).toInt()
                if (a <= 0) continue
                ctx.drawQuad(
                    px + sidebarW + slide + gw * i / strips, py,
                    px + sidebarW + slide + gw * (i + 1) / strips, py + windowH,
                    Color4b(0, 0, 0, a),
                )
            }
        }

        // 客户端名 (Rise: size32 标题 + 版本)
        drawStr(ctx, font, "Rise", px + 12f + slide, py + 5f, titleSize,
            A(textColor.alpha((op).toInt().coerceIn(0, 255))))
        drawStr(ctx, font, "6.0", px + 12f + slide + strW(font, "Rise", titleSize) + 4f, py + 8f,
            catSize, A(accentAt(py).alpha((op).toInt().coerceIn(0, 255))))

        // 分类 (Rise: offset 29.5 起, 步进 19.5, y=py+offset+16)
        var offset = 10.0
        val selectedNow = !searchMode || !searchEnabled
        for (i in cats.indices) {
            offset += catSpacing
            val cy = py + offset + 16f
            if (cy > py + windowH) break
            val anim = catAnims[i]
            anim.easing = Ease.LINEAR
            anim.durationMs = 200L
            anim.run(if (i == selectedCat && selectedNow) 255.0 else 0.0)
            val a = anim.value

            val cx = px + 10f + slide
            val label = catLabel(cats[i])
            val wpx = strW(font, label, catSize)

            if (a > 0.5f) {
                ctx.drawRoundedRect(
                    cx, cy - 5.5f, cx + wpx + 8f, cy + 9.5f, 5f,
                    accentAt(cy).darker(selDarken).alpha((min(a, op.toDouble()) * uiAlpha).toInt().coerceIn(0, 255)),
                )
            }
            val txtA = (min(if (i == selectedCat && selectedNow) 255.0 else 200.0, op.toDouble()) * uiAlpha).toInt()
            drawStr(
                ctx, font, label,
                cx + (a / 80f).toFloat() + 3f, cy, catSize,
                textColor.alpha(txtA.coerceIn(0, 255)),
            )
            catHits.add(floatArrayOf(cx - 11f, cy - 5f, 70f, 22f))
        }
    }

    // ------------------------------ 分类/搜索屏 ------------------------------

    private fun renderCategoryScreen(ctx: GuiGraphicsExtractor, font: Font, catIdx: Int) {
        val cat = cats.getOrNull(catIdx) ?: return
        val mods = modsIn(cat)
        val listX = px + sidebarW + 8f
        val scroll = catScroll.getOrDefault(catIdx, 0f)
        var y = py + 7f + scroll
        var contentH = 0f
        for (m in mods) {
            val ui = uiOf(m)
            val h = drawModule(ctx, font, ui, listX, y, withCategory = null)
            y += h + cardGap
            contentH += h + cardGap
        }
        catMax[catIdx] = -(contentH - cardGap - (windowH - 7f))
        drawScrollbar(ctx, catMax[catIdx]!!, scroll)
    }

    private fun renderSearchScreen(ctx: GuiGraphicsExtractor, font: Font) {
        val scroll = searchScroll
        val bw = 150f
        val bx = px + sidebarW + (windowW - sidebarW) / 2f - bw / 2f
        val by = py + 17f + scroll

        ctx.drawRoundedRect(bx, by - 2f, bx + bw, by + 13f, 3f,
            A(sideBgColor.alpha(150)))
        val t = if (searchBuf.isBlank()) "Search..." else searchBuf
        val col = if (searchBuf.isBlank()) triTextColor else textColor
        val tw = strW(font, t, valueSize)
        var cursorPad = ""
        if (searchBuf.isNotEmpty() && (System.currentTimeMillis() / 500) % 2 == 0L) cursorPad = "_"
        drawStr(
            ctx, font, t + cursorPad, bx + bw / 2f - (tw + if (cursorPad.isEmpty()) 0f else strW(font, "_", valueSize)) / 2f,
            by + 2f, valueSize, A(col),
        )

        val listX = px + sidebarW + 8f
        var y = py + 35f + scroll
        var contentH = 35f
        for (m in searchResults) {
            val ui = uiOf(m)
            val h = drawModule(ctx, font, ui, listX, y, withCategory = catLabel(m.category))
            y += h + cardGap
            contentH += h + cardGap
        }
        searchMax = -(contentH - cardGap - (windowH - 7f))
        drawScrollbar(ctx, searchMax, scroll)
    }

    private fun drawScrollbar(ctx: GuiGraphicsExtractor, maxS: Float, scroll: Float) {
        if (!showScrollbar || maxS >= -1f) return
        val trackH = windowH - 14f
        val contentH = windowH - 7f - maxS
        if (contentH <= windowH) return
        val thumbH = (trackH * (windowH / contentH)).coerceIn(18f, trackH)
        val frac = (scroll / maxS).coerceIn(0f, 1f)
        val ty = py + 7f + frac * (trackH - thumbH)
        ctx.drawRoundedRect(
            px + windowW - 4f, ty, px + windowW - 2f, ty + thumbH, 1.5f,
            Color4b(255, 255, 255, (60f * uiAlpha).toInt().coerceIn(0, 255)),
        )
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val font = mc.font
        val now = System.currentTimeMillis()
        val dt = if (lastFrame != 0L) ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.1f) else 0.016f
        lastFrame = now
        frameDt = dt

        // 开关动画 (Rise: scale EASE_IN_EXPO/300, opacity EASE_OUT_EXPO/300; 关闭均 LINEAR/100)
        val openingNow = enabled
        scaleAnim.easing = if (openingNow) Ease.EASE_IN_EXPO else Ease.LINEAR
        opacityAnim.easing = if (openingNow) Ease.EASE_OUT_EXPO else Ease.LINEAR
        scaleAnim.durationMs = (if (openingNow) openDur else closeDur).toLong().coerceAtLeast(1L)
        opacityAnim.durationMs = scaleAnim.durationMs
        scaleAnim.run(if (openingNow) 1.0 else 0.0)
        opacityAnim.run(if (openingNow) 1.0 else 0.0)
        val s = scaleAnim.value.toFloat()
        uiAlpha = opacityAnim.value.toFloat()
        if (s <= 0.002f) {
            lastFrame = 0L
            return@handler
        }

        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()

        // 位置
        if (centerOnOpen && !wasOpen) {
            px = sw / 2f - windowW / 2f
            py = sh / 2f - windowH / 2f
            posInited = true
        }
        wasOpen = openingNow
        if (!posInited || px + windowW < 30f || py + windowH < 30f || px > sw - 30f || py > sh - 30f) {
            px = sw / 2f - windowW / 2f
            py = sh / 2f - windowH / 2f
            posInited = true
        }
        if (dragging) {
            px = (mx0 + dragOX).coerceIn(4f - windowW * 0.6f, sw - windowW * 0.4f)
            py = (my0 + dragOY).coerceIn(2f, sh - 20f)
        }

        // 布局系鼠标 (渲染经缩放变换, 命中测试用逆变换)
        val gx = guiMouseX(sw)
        val gy = guiMouseY(sh)
        val tx = (px + windowW / 2f) * (1f - s)
        val ty = (py + windowH / 2f) * (1f - s)
        mx = (gx - tx) / s
        my = (gy - ty) / s
        mx0 = gx
        my0 = gy

        allModules()
        rowRects.clear()
        sliderRects.clear()
        pickerRects.clear()
        numEditRects.clear()
        textEditRects.clear()
        catHits.clear()

        // 背景压暗
        if (dimAlpha > 0.002f) {
            ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (dimAlpha * 255f * uiAlpha).toInt().coerceIn(0, 255)))
        }

        ctx.pose().withPush {
            translate(tx, ty)
            scale(s, s)

            // 窗口辉光
            if (windowGlow && glowStrength > 0.01f) {
                val acc = accentAt(py)
                val layers = 7
                for (j in 1..layers) {
                    val u = j.toFloat() / layers
                    val e = glowRadiusPx * u
                    val a = ((1f - u).pow(2.2f) * 36f * glowStrength * uiAlpha).toInt().coerceIn(0, 255)
                    if (a <= 0) continue
                    ctx.drawRoundedRect(
                        px - e, py - e, px + windowW + e, py + windowH + e,
                        (cornerRadius + e * 0.6f).coerceAtLeast(0f),
                        acc.alpha(a),
                    )
                }
            }

            // 投影 (Rise: 动画结束后才绘制 dropShadow)
            if (s > 0.993f) {
                for (j in 1..5) {
                    val u = j.toFloat() / 5f
                    val e = 4f + 14f * u
                    val a = ((1f - u).pow(2f) * 26f * uiAlpha).toInt().coerceIn(0, 255)
                    if (a <= 0) continue
                    ctx.drawRoundedRect(
                        px - e, py - e, px + windowW + e, py + windowH + e,
                        (cornerRadius + e * 0.5f).coerceAtLeast(0f),
                        Color4b(0, 0, 0, a),
                    )
                }
            }

            // 窗口底色
            ctx.drawRoundedRect(px, py, px + windowW, py + windowH, cornerRadius, A(bgColor))

            ctx.scissorStack.withPush(ctx.getBounds(px + 1f, py + 1f, px + windowW - 1f, py + windowH - 1f)) {
                applyScroll(dt)

                if (searchMode && searchEnabled) {
                    renderSearchScreen(ctx, font)
                } else {
                    // Rise: 淡出期间渲染旧屏, 淡黑后切换
                    val catIdx = if (now - switchTime < catFadeMs) lastCat else selectedCat
                    renderCategoryScreen(ctx, font, catIdx)
                }

                // 分类切换交叉淡黑 (Rise opacity2)
                val elapsed = now - switchTime
                val fade = catFadeMs.coerceAtLeast(1f)
                if (elapsed <= fade * 2f) {
                    val raw = if (elapsed < fade) 255f - elapsed * (255f / fade)
                    else (elapsed - fade) * (255f / fade)
                    val a2 = (255f - raw.coerceIn(0f, 255f)).toInt().coerceIn(0, 255)
                    ctx.drawRoundedRect(
                        px, py, px + windowW, py + windowH, cornerRadius,
                        bgColor.alpha((a2 * uiAlpha).toInt().coerceIn(0, 255)),
                    )
                }

                renderSidebar(ctx, font, dt)
            }
        }
    }

    private var mx0 = 0f
    private var my0 = 0f
    private var frameDt = 0.016f

    private fun applyScroll(dt: Float) {
        val idx = if (searchMode) -1 else selectedCat
        val maxS = (if (idx == -1) searchMax else catMax.getOrDefault(idx, 0f)).coerceAtMost(0f)
        val wheel = scrollAccum
        scrollAccum = 0.0
        if (idx == -1) {
            searchScrollT = (searchScrollT - wheel.toFloat() * scrollStep).coerceIn(maxS, 0f)
            searchScroll += (searchScrollT - searchScroll) * (dt * scrollSmooth).coerceIn(0f, 1f)
            if (abs(searchScrollT - searchScroll) < 0.3f) searchScroll = searchScrollT
        } else {
            val t0 = catScrollT.getOrDefault(idx, 0f)
            val t = (t0 - wheel.toFloat() * scrollStep).coerceIn(maxS, 0f)
            catScrollT[idx] = t
            var c = catScroll.getOrDefault(idx, 0f)
            c += (t - c) * (dt * scrollSmooth).coerceIn(0f, 1f)
            if (abs(t - c) < 0.3f) c = t
            catScroll[idx] = c
        }
    }

    private fun ts() = textScale

    private fun drawStr(
        ctx: GuiGraphicsExtractor, font: Font, s: String,
        x: Float, y: Float, sizePx: Float, c: Color4b,
    ) {
        val k = (sizePx * ts() / 9f).coerceAtLeast(0.01f)
        ctx.pose().withPush {
            translate(x, y)
            scale(k, k)
            ctx.text(font, s, 0, 0, c.argb, false)
        }
    }

    private fun strW(font: Font, s: String, sizePx: Float): Float =
        font.width(s) * (sizePx * ts() / 9f)

    private fun strH(sizePx: Float): Float = sizePx * ts()

    /** 超宽截断加 "..." (二分) */
    private fun shorten(font: Font, s: String, maxW: Float, sizePx: Float): String {
        if (maxW <= 4f || strW(font, s, sizePx) <= maxW) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (strW(font, s.substring(0, mid) + "...", sizePx) <= maxW) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo.coerceAtLeast(0)) + "..."
    }

    private fun drawStrShadow(
        ctx: GuiGraphicsExtractor, font: Font, s: String,
        x: Float, y: Float, sizePx: Float, c: Color4b,
    ) = drawStr(ctx, font, s, x + 1f, y + 1f, sizePx, c.alpha((c.a * 0.25f).toInt()))
        .let { drawStr(ctx, font, s, x, y, sizePx, c) }

    private fun setNumber(v: Value<*>, f: Float) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val c = v as Value<Any>
            when (v.get()) {
                is Float -> c.set(f)
                is Int -> c.set(f.roundToInt())
                is Double -> c.set(f.toDouble())
                is Long -> c.set(f.toLong())
                else -> c.set(if (info(v).isInt) f.roundToInt() else f)
            }
        }
    }

    private fun setDual(v: Value<*>, lo: Float, hi: Float) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val c = v as Value<Any>
            val cur = v.get() as ClosedRange<*>
            when (cur) {
                is IntRange -> c.set(lo.roundToInt()..hi.roundToInt())
                else -> c.set(lo..hi)
            }
        }
    }

    private fun dualOf(v: Value<*>): Pair<Float, Float> {
        val b = info(v).range ?: return 0f to 1f
        return runCatching {
            val r = v.get() as ClosedRange<*>
            ((r.start as? Number)?.toFloat() ?: b.first) to
                ((r.endInclusive as? Number)?.toFloat() ?: b.second)
        }.getOrDefault(b)
    }

    private fun setKeyValue(v: Value<*>, key: Int) {
        runCatching {
            val code = if (key == GLFW.GLFW_KEY_ESCAPE) InputConstants.UNKNOWN.value else key
            val k = InputConstants.Type.KEYSYM.getOrCreate(code)
            @Suppress("UNCHECKED_CAST")
            val c = v as Value<Any>
            when (info(v).kind) {
                Kind.BIND -> c.set((v.get() as InputBind).copy(boundKey = k))
                else -> c.set(k)
            }
        }
    }

    private fun commitEdit(v: Value<*>) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val c = v as Value<Any>
            if (editNumeric) {
                val d = editBuf.toDoubleOrNull() ?: return
                val b = info(v).range
                val f = if (b != null) d.toFloat().coerceIn(b.first, b.second) else d.toFloat()
                if (info(v).kind == Kind.DUAL) {
                    val parts = editBuf.split(' ', ',').mapNotNull { it.toDoubleOrNull() }
                    if (parts.size == 2 && b != null) {
                        setDual(v, parts[0].toFloat().coerceIn(b.first, b.second),
                            parts[1].toFloat().coerceIn(b.first, b.second))
                    }
                } else {
                    setNumber(v, f)
                }
            } else {
                when (val cur = v.get()) {
                    is Regex -> c.set(Regex(editBuf))
                    else -> c.set(editBuf)
                }
            }
        }
    }

    private fun cycleMode(v: Value<*>, dir: Int) {
        runCatching {
            val g = v as ModeValueGroup<*>
            val modes = g.modes
            if (modes.size < 2) return
            val idx = modes.indexOf(g.activeMode)
            val next = modes[((idx + dir) % modes.size + modes.size) % modes.size]
            v.setByString(next.name)
        }
    }

    private fun cycleChoice(v: Value<*>, dir: Int) {
        runCatching {
            val ch = info(v).choices
            if (ch.isEmpty()) return
            val cur = v.get()
            var idx = ch.indexOfFirst { it == cur }
            if (idx < 0) idx = 0
            val next = ch[((idx + dir) % ch.size + ch.size) % ch.size] ?: return
            @Suppress("UNCHECKED_CAST")
            (v as Value<Any>).set(next)
        }
    }

    /** MULTI_CHOOSE: 光标循环前进并 toggle 该项 (左键下一个, 右键上一个) */
    private fun cycleMulti(v: Value<*>, dir: Int) {
        runCatching {
            val ch = info(v).choices
            if (ch.isEmpty()) return
            val cur = multiCursor.getOrDefault(v, if (dir > 0) -1 else 0)
            val idx = ((cur + dir) % ch.size + ch.size) % ch.size
            multiCursor[v] = idx
            val choice = ch[idx] ?: return
            @Suppress("UNCHECKED_CAST")
            (v as MultiChoiceListValue<Any>).toggle(choice)
        }
    }

    private fun currentScreenUis(): List<ModUi> {
        val mods = if (searchMode) searchResults else modsIn(cats.getOrNull(selectedCat) ?: return emptyList())
        return mods.map { uiOf(it) }
    }

    private fun clickScreen(button: Int) {
        for (ui in currentScreenUis()) clickModule(ui, button)
    }

    private fun clickModule(ui: ModUi, button: Int) {
        val mod = ui.mod
        // 卡片本体 (Rise: 默认高 - 3)
        if (over(ui.x, ui.y, ui.w, cardH - 3f)) {
            ui.mouseDown = true
            when (button) {
                0 -> mod.enabled = !mod.enabled
                1 -> if (middleClickBind) bindMod = mod
                2 -> if (ui.rows.isNotEmpty()) {
                    ui.expanded = !ui.expanded
                    sliderDrag = null
                    colorDrag = null
                }
            }
        }
        // 值 (Rise: 仅展开时)
        if (ui.expanded) {
            for (r in ui.rows) {
                if (clickValue(r.value, button)) break
            }
        }
    }

    private fun clickValue(v: Value<*>, button: Int): Boolean {
        val rect = rowRects[v] ?: return false
        val left = button == 0
        val right = button == 2
        val vi = info(v)

        // 滑条区优先
        if (vi.kind == Kind.SLIDER || vi.kind == Kind.DUAL) {
            sliderRects[v]?.let { s ->
                if (left && over(s[0], s[1], s[2], s[3])) {
                    sliderDrag = v
                    if (vi.kind == Kind.DUAL) pickDualHandle(v)
                    applySlider(v)
                    return true
                }
            }
            numEditRects[v]?.let { e ->
                if (left && over(e[0], e[1], e[2], e[3])) {
                    editValue = v
                    editNumeric = true
                    editBuf = if (vi.kind == Kind.DUAL) {
                        val d = dualOf(v)
                        fmt(d.first) + " " + fmt(d.second)
                    } else {
                        fmt(numOf(v))
                    }
                    return true
                }
            }
        }

        // 取色器展开区优先
        if (vi.kind == Kind.COLOR && colorOpen[v] == true) {
            pickerRects[v]?.let { pr ->
                if (left && over(pr[0][0], pr[0][1], pr[0][2], pr[0][3])) {
                    colorDrag = v
                    colorDragChannel = 0
                    applyPicker(v)
                    return true
                }
                if (left && over(pr[1][0], pr[1][1], pr[1][2], pr[1][3])) {
                    colorDrag = v
                    colorDragChannel = 1
                    applyPicker(v)
                    return true
                }
                // 展开面板内空白区 (预览块/RGB/HEX 一带) 点击不收起 (Rise selected 保持)
                if (pr.size > 2 && over(pr[2][0], pr[2][1], pr[2][2], pr[2][3])) {
                    return true
                }
            }
        }

        if (!over(rect[0], rect[1] - 3.5f, rect[2], rect[3])) return false

        when (vi.kind) {
            Kind.BOOL -> if (left) runCatching {
                @Suppress("UNCHECKED_CAST")
                (v as Value<Any>).set(!(v.get() as Boolean))
            }
            Kind.MODE -> {
                if (left) cycleMode(v, 1)
                if (right) cycleMode(v, -1)
            }
            Kind.CHOICE -> {
                if (left) cycleChoice(v, 1)
                if (right) cycleChoice(v, -1)
            }
            Kind.MULTI -> {
                if (left) cycleMulti(v, 1)
                if (right) cycleMulti(v, -1)
            }
            Kind.COLOR -> if (left || right) {
                colorOpen[v] = !(colorOpen[v] == true)
                colorDrag = null
            }
            Kind.TEXT -> if (left) {
                editValue = v
                editNumeric = false
                editBuf = runCatching {
                    when (val cur = v.get()) {
                        is Regex -> cur.pattern
                        else -> cur as? String
                    }
                }.getOrNull() ?: ""
            }
            Kind.KEY, Kind.BIND -> if (left) keyListen = v
            Kind.GROUP -> if (right) groupOpen[v] = !(groupOpen[v] == true)
            Kind.TOGGLE_GROUP -> {
                if (left) runCatching { (v as ToggleableValueGroup).enabled = !(v as ToggleableValueGroup).enabled }
                if (right) groupOpen[v] = !(groupOpen[v] == true)
            }
            else -> return false
        }
        return true
    }

    private fun pickDualHandle(v: Value<*>) {
        val rect = sliderRects[v] ?: return
        val b = info(v).range ?: return
        val cur = dualOf(v)
        val t = ((mx - rect[0]) / rect[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
        val pos = b.first + t * (b.second - b.first)
        dualWhich = if (abs(pos - cur.first) <= abs(pos - cur.second)) 0 else 1
    }

    private fun applySlider(v: Value<*>) {
        val rect = sliderRects[v] ?: return
        val b = info(v).range ?: return
        val t = ((mx - rect[0]) / rect[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
        if (info(v).kind == Kind.DUAL) {
            val cur = dualOf(v)
            var nv = b.first + t * (b.second - b.first)
            nv = if (info(v).isInt) nv.roundToInt().toFloat() else nv
            if (dualWhich == 0) setDual(v, nv.coerceIn(b.first, cur.second), cur.second)
            else setDual(v, cur.first, nv.coerceIn(cur.first, b.second))
        } else {
            var nv = b.first + t * (b.second - b.first)
            if (info(v).isInt) nv = nv.roundToInt().toFloat()
            setNumber(v, nv)
        }
    }

    private fun applyPicker(v: Value<*>) {
        val pr = pickerRects[v] ?: return
        val col = colorOf(v)
        when (colorDragChannel) {
            0 -> {
                val r = pr[0]
                val s = ((mx - r[0]) / r[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
                val vv = 1f - ((my - r[1]) / r[3].coerceAtLeast(1f)).coerceIn(0f, 1f)
                colorPointer[v] = s to vv
                val h = colorHue.getOrDefault(v, hueOf(col))
                setColorValue(v, hsvColor(h, s, vv, col.a))
            }
            1 -> {
                val r = pr[1]
                val h = ((mx - r[0]) / r[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
                colorHue[v] = h
                val (s, vv) = colorPointer.getOrDefault(v, 1f to 1f)
                setColorValue(v, hsvColor(h, s, vv, col.a))
            }
        }
    }
}
