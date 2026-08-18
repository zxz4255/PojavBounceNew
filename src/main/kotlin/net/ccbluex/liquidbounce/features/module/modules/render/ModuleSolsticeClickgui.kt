/*
 * ============================================================================
 *  ModuleSolsticeClickgui —— 还原 ClickGui.cpp/hpp + ModernDropdown.cpp/hpp
 *
 *  ModernGui 核心 (ModernDropdown):
 *   - catWidth=200, catHeight=30, catGap=40, 分类水平居中
 *   - 全屏遮罩 alpha*0.38 + 底部 getThemedColor 渐变
 *   - scaleToPoint(屏幕中心, inScale)
 *   - 左键 toggle / 右键展开设置 / 中键绑键
 *   - Bool/Enum/Number/Color 设置行 + 滚动 scrollEase
 *   - 色板: darkBlack(24,24,24) gray(40,40,40) setting(30,30,30)
 *   - 主题色 ColorUtils::getThemedColor
 *
 *  原生: OverlayRenderEvent + GuiGraphicsExtractor, 无 Web
 * ============================================================================
 */

package net.ccbluex.liquidbounce.features.module.modules.render

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollInHotbarEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleSolsticeClickgui : ClientModule(
    "SolsticeClickGui",
    ModuleCategories.RENDER,
    bind = GLFW.GLFW_KEY_TAB,
    aliases = listOf("ClickGui", "ModernClickGui"),
) {

    /* ============================= 枚举 ============================= */

    private enum class ClickGuiStyle(override val tag: String) : Tagged { MODERN("Modern") }
    private enum class ClickGuiAnimation(override val tag: String) : Tagged { ZOOM("Zoom"), BOUNCE("Bounce") }

    /* ============================= 可调节项 ============================= */

    private val style by enumChoice("Style", ClickGuiStyle.MODERN)
    private val animation by enumChoice("Animation", ClickGuiAnimation.BOUNCE)
    private val blurStrength by float("Blur Strength", 7f, 0f..20f)      // 原生近似: 背景透明度
    private val easeSpeed by float("Ease Speed", 14f, 5f..30f)
    private val midclickRounding by float("Midclick Rounding", 1f, 0.01f..1f)

    // —— 外观 ——
    private val useThemeAccent by boolean("Use Theme Accent", true)
    private val accentColor by color("Accent Color", Color4b(0x6E, 0xC8, 0xF1))
    private val themeA by color("Theme A", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeB by color("Theme B", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeC by color("Theme C", Color4b(255, 255, 255, 128))
    private val themeSeconds by float("Theme Cycle Sec", 3.0f, 0.5f..10.0f)
    private val panelWidth by int("Panel Width", 200, 140..280)
    private val panelMaxHeight by int("Panel Max Height", 340, 200..500)
    private val radius by int("Radius", 8, 0..16)
    private val backgroundAlpha by int("Background Alpha", 230, 0..255)
    private val textShadow by boolean("Text Shadow", true)
    private val showStatusDot by boolean("Show Status Dot", true)
    private val headerHeight by int("Header Height", 30, 22..44)
    private val itemHeight by int("Item Height", 30, 18..40)
    private val scaleAnimation by boolean("Scale Animation", true)       // Zoom/Bounce 缩放开关

    /* ============================= 缓动工具 ============================= */

    private class EasingUtil {
        var percentage = 0f
        fun incrementPercentage(delta: Float) {
            percentage = min(1f, percentage + delta)
        }
        fun decrementPercentage(delta: Float) {
            percentage = max(0f, percentage - delta)
        }
        fun isPercentageMax(): Boolean = percentage >= 1f

        // 对应 C++ EasingUtil::easeOutExpo
        fun easeOutExpo(): Float =
            if (percentage >= 1f) 1f else 1f - 2f.pow(-10f * percentage)

        // 标准 easeOutElastic（修正原移植错误的负号公式）
        fun easeOutElastic(): Float {
            val p = percentage
            if (p == 0f) return 0f
            if (p >= 1f) return 1f
            val c4 = (2.0 * PI) / 3.0
            return (2.0.pow(-10.0 * p) * sin((p * 10.0 - 0.75) * c4) + 1.0).toFloat()
        }

        // 对应 C++ easeOutBack（退出 Bounce 用）
        fun easeOutBack(): Float {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val p = percentage - 1f
            return 1f + c3 * p * p * p + c1 * p * p
        }
    }

    /**
     * 对应 ClickGui::getEaseAnim
     * mode 0 = Zoom → easeOutExpo
     * mode 1 = Bounce → enable: easeOutElastic / disable: easeOutBack
     */
    private fun getEaseAnim(ease: EasingUtil, mode: Int): Float = when (mode) {
        1 -> if (enabled) ease.easeOutElastic() else ease.easeOutBack()
        else -> ease.easeOutExpo()
    }

    /** MathUtils::lerp */
    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

    /** ColorUtils::LerpColors / getThemedColor */
    private fun getThemedColor(index: Float, ms: Long = 0L): Color4b {
        val colors = listOf(themeA, themeB, themeC)
        val time = 10000.0f / themeSeconds.coerceAtLeast(0.01f)
        val now = if (ms == 0L) System.currentTimeMillis() else ms
        val angle = ((now + index.toLong()) % time.toLong()).toFloat()
        val segmentTime = time / colors.size
        val seg = (angle / segmentTime).toInt() % colors.size
        val t = (angle / segmentTime - (angle / segmentTime).toInt()).coerceIn(0f, 1f)
        val s = colors[seg]
        val e = colors[(seg + 1) % colors.size]
        return Color4b(
            lerp(s.r.toFloat(), e.r.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.g.toFloat(), e.g.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.b.toFloat(), e.b.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(s.a.toFloat(), e.a.toFloat(), t).toInt().coerceIn(0, 255),
        )
    }

    private fun accentAt(y: Float): Color4b =
        if (useThemeAccent) getThemedColor(y * 2f) else accentColor

    /* ============================= 内部状态 ============================= */

    private val ease = EasingUtil()
    private var lastFrameNs = 0L
    private var isPressingShift = false
    private var scrollDirection = 0

    private data class PanelState(
        val category: ModuleCategory?,
        var x: Float, var y: Float,
        var scrollOffset: Float = 0f,
        var targetScroll: Float = 0f,
        var dragging: Boolean = false,
        var dragOffsetX: Float = 0f,
        var dragOffsetY: Float = 0f,
    )
    private val panels = mutableListOf<PanelState>()
    private var expandedModule: ClientModule? = null
    private var listeningBind: Value<*>? = null
    private val collapsedGroups = mutableSetOf<Value<*>>()
    private val sliderDrag = IdentityHashMap<Value<*>, Float>()   // 滑块拖动值
    private var activeColorValue: Value<*>? = null
    private val paletteColors = listOf(
        Color4b(0xE9, 0xA8, 0xBC), Color4b(0x6E, 0xC8, 0xF1), Color4b(255, 255, 255),
        Color4b(255, 70, 70), Color4b(255, 170, 40), Color4b(255, 230, 60),
        Color4b(90, 230, 110), Color4b(60, 200, 230), Color4b(140, 110, 255),
        Color4b(255, 120, 200), Color4b(40, 40, 40), Color4b(200, 200, 200),
    )

    private var mouseX = 0f
    private var mouseY = 0f

    /* ============================= 坐标工具 ============================= */

    private fun guiMouseX(): Float =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()

    private fun guiMouseY(): Float =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()

    /* ============================= 值工具 ============================= */

    private fun getActualValue(v: Value<*>): Any? {
        var obj: Any? = try { v.get() } catch (_: Exception) { null }
        var depth = 0
        while (obj is Value<*> && depth < 5) {
            obj = try { obj.get() } catch (_: Exception) { null }
            depth++
        }
        return obj
    }

    private fun trySetValue(v: Value<*>, value: Any) {
        try {
            v.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 1 }?.invoke(v, value)
        } catch (_: Exception) {}
    }

    private fun isGroupValue(v: Value<*>): Boolean = try {
        v.javaClass.simpleName.contains("Group", true) || v.javaClass.simpleName.contains("Container", true)
    } catch (_: Exception) { false }

    private fun collectValues(module: ClientModule): List<Value<*>> = try {
        module.collectValuesRecursively().toList()
    } catch (_: Exception) { emptyList() }

    /* ============================= 输入隔离 (Screen 图层) ============================= */

    /**
     * 透明 Screen：打开时接管鼠标/键盘，游戏不再出现十字准星、不再攻击/转向。
     * 实际 UI 仍由 OverlayRenderEvent 绘制。
     */
    private class SolsticeClickGuiScreen : Screen(Component.literal("SolsticeClickGui")) {
        override fun isPauseScreen(): Boolean = false

        override fun shouldCloseOnEsc(): Boolean = false

        // 1.21+ / 本 fork：KeyEvent / MouseButtonEvent 签名
        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                if (ModuleSolsticeClickgui.listeningBind == null &&
                    ModuleSolsticeClickgui.activeColorValue == null
                ) {
                    // 只关 ClickGUI，不进入游戏暂停菜单
                    ModuleSolsticeClickgui.enabled = false
                    try {
                        mc.gui.setScreen(null)
                    } catch (_: Throwable) {}
                } else {
                    ModuleSolsticeClickgui.listeningBind = null
                    ModuleSolsticeClickgui.activeColorValue = null
                }
                return true
            }
            return true // 吞掉所有按键，避免传到游戏
        }


        override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean = true
        override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean = true
        override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dx: Double, dy: Double): Boolean = true
        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean = true
        // 不 override render：UI 由 OverlayRenderEvent 绘制，避免签名不匹配
    }

    private fun openInputLayer() {
        if (mc.gui.screen() is SolsticeClickGuiScreen) return
        try {
            mc.gui.setScreen(SolsticeClickGuiScreen())
        } catch (_: Throwable) {
            try {
                mc.execute { mc.gui.setScreen(SolsticeClickGuiScreen()) }
            } catch (_: Throwable) {}
        }
    }

    private fun closeInputLayer() {
        if (mc.gui.screen() is SolsticeClickGuiScreen) {
            try {
                mc.gui.setScreen(null)
            } catch (_: Throwable) {
                try {
                    mc.execute { mc.gui.setScreen(null) }
                } catch (_: Throwable) {}
            }
        }
    }

    /* ============================= JSON 状态保存 / 加载 ============================= */

    private val stateGson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val stateFile: File
        get() = File(ConfigSystem.rootFolder, "solstice_clickgui.json")

    /** 关闭时写入：面板位置、滚动、展开模块、折叠组 */
    private fun saveState() {
        try {
            val root = JsonObject()
            root.addProperty("version", 1)
            root.addProperty("expandedModule", expandedModule?.name)

            val panelsArr = JsonArray()
            for (p in panels) {
                val o = JsonObject()
                o.addProperty("category", p.category?.tag ?: "")
                o.addProperty("x", p.x)
                o.addProperty("y", p.y)
                o.addProperty("scrollOffset", p.scrollOffset)
                o.addProperty("targetScroll", p.targetScroll)
                panelsArr.add(o)
            }
            root.add("panels", panelsArr)

            val collapsed = JsonArray()
            for (v in collapsedGroups) {
                // 用 模块名/设置名 尽量唯一定位
                val owner = expandedModule?.name ?: ""
                collapsed.add("${owner}/${v.name}")
            }
            // 保存时把所有已知折叠组名也记下（仅 name）
            for (v in collapsedGroups) {
                collapsed.add(v.name)
            }
            root.add("collapsedGroups", collapsed)

            stateFile.parentFile?.mkdirs()
            stateFile.writeText(stateGson.toJson(root))
        } catch (_: Exception) {
            // 保存失败不打断关闭流程
        }
    }

    /** 打开时读取并还原 */
    private fun loadState() {
        try {
            if (!stateFile.exists()) return
            val root = JsonParser.parseString(stateFile.readText()).asJsonObject

            val expandedName = root.get("expandedModule")?.takeIf { !it.isJsonNull }?.asString
            if (!expandedName.isNullOrEmpty()) {
                expandedModule = ModuleManager.getModules().find { it.name == expandedName }
            }

            val arr = root.getAsJsonArray("panels") ?: return
            if (arr.size() == 0) return

            panels.clear()
            val byTag = ModuleCategories.entries.associateBy { it.tag }
            for (el in arr) {
                val o = el.asJsonObject
                val tag = o.get("category")?.asString ?: continue
                val cat = byTag[tag] ?: continue
                panels += PanelState(
                    category = cat,
                    x = o.get("x")?.asFloat ?: 0f,
                    y = o.get("y")?.asFloat ?: 0f,
                    scrollOffset = o.get("scrollOffset")?.asFloat ?: 0f,
                    targetScroll = o.get("targetScroll")?.asFloat ?: 0f,
                )
            }

            collapsedGroups.clear()
            val collapsed = root.getAsJsonArray("collapsedGroups")
            if (collapsed != null && expandedModule != null) {
                val names = collapsed.mapNotNull { it.asString?.substringAfterLast('/') }
                for (v in collectValues(expandedModule!!)) {
                    if (v.name in names) collapsedGroups.add(v)
                }
            }
        } catch (_: Exception) {
            // 损坏的 json 忽略，使用默认布局
        }
    }

    override suspend fun enabledEffect() {
        panels.clear()
        expandedModule = null
        listeningBind = null
        activeColorValue = null
        collapsedGroups.clear()
        ease.percentage = 0f
        loadState()
        openInputLayer()
    }

    override fun onDisabled() {
        saveState()
        closeInputLayer()
        // 注意：不立刻清空 panels，以便若需调试；下次 enabled 会 load
        listeningBind = null
        activeColorValue = null
        sliderDrag.clear()
    }

    /* ============================= 事件处理 ============================= */

    /** 禁止视角转动（防止仍操作准星） */
    @Suppress("unused")
    private val mouseRotHandler = handler<MouseRotationEvent> { event ->
        if (enabled || ease.percentage > 0.01f) {
            event.cancelEvent()
        }
    }

    /** 禁止滚轮切物品栏 */
    @Suppress("unused")
    private val hotbarScrollHandler = handler<MouseScrollInHotbarEvent> { event ->
        if (enabled || ease.percentage > 0.01f) {
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (!enabled && ease.percentage < 0.01f) return@handler
        // ESC 关闭 (原版: 非绑定状态且按下时 toggle)
        if (event.keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (listeningBind == null && activeColorValue == null && event.action == 1) {
                enabled = false
            } else if (event.action == 1) {
                listeningBind = null
                activeColorValue = null
            }
            return@handler
        }
        // 绑键监听
        val bindTarget = listeningBind
        if (bindTarget != null) {
            if (event.action == 1) {
                trySetValue(bindTarget, event.keyCode)
                listeningBind = null
            }
            return@handler
        }
        // Shift 状态 (原版 isPressingShift)
        if ((event.keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || event.keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) && event.action == 1) {
            isPressingShift = true
        } else {
            isPressingShift = false
        }
    }

    @Suppress("unused")
    private val scrollHandler = handler<MouseScrollEvent> { event ->
        if (!enabled) return@handler
        scrollDirection = if (event.vertical > 0) -1 else if (event.vertical < 0) 1 else 0
        for (panel in panels) {
            if (mouseX in panel.x..(panel.x + panelWidth) && mouseY in panel.y..(panel.y + panelMaxHeight)) {
                panel.targetScroll = (panel.targetScroll - event.vertical.toFloat() * 24f)
                    .coerceAtLeast(0f)
            }
        }
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        if (!enabled || ease.percentage < 0.01f) return@handler
        // GUI 打开时保持 Screen 存在
        if (mc.gui.screen() !is SolsticeClickGuiScreen) openInputLayer()
        val mx = guiMouseX()
        val my = guiMouseY()

        // 调色板优先
        val colorVal = activeColorValue
        if (colorVal != null) {
            if (event.action == 1 && event.button == 0) {
                if (mx in paletteX..(paletteX + 12 * 14f) && my in paletteY..(paletteY + 12 * 14f)) {
                    val col = ((mx - paletteX) / 14f).toInt().coerceIn(0, 11)
                    val row = ((my - paletteY) / 14f).toInt().coerceIn(0, 0)
                    trySetValue(colorVal, paletteColors[col])
                    activeColorValue = null
                } else {
                    activeColorValue = null
                }
            }
            return@handler
        }

        // 任意松开左键：立即结束所有面板拖拽（必须先于 header 分支，避免 return 漏清）
        if (event.action == 0 && event.button == 0) {
            for (panel in panels) panel.dragging = false
        }

        // 面板标题拖拽 / 折叠
        for (panel in panels) {
            if (mx in panel.x..(panel.x + panelWidth) && my in panel.y..(panel.y + headerHeight)) {
                if (event.action == 1) {
                    if (event.button == 2) {
                        panel.dragging = false
                    } else if (event.button == 0) {
                        // 只拖一个面板
                        for (p in panels) p.dragging = false
                        panel.dragging = true
                        panel.dragOffsetX = mx - panel.x
                        panel.dragOffsetY = my - panel.y
                    }
                    return@handler
                }
                // action==0 已在上方清理，此处也 return 避免点穿到内容
                if (event.action == 0) return@handler
            }
        }

        // 模块与设置点击
        if (event.action == 1 && (event.button == 0 || event.button == 1)) {
            handleContentClick(mx, my, event.button)
        }
    }

    /* ============================= 内容点击 ============================= */

    private fun handleContentClick(mx: Float, my: Float, button: Int) {
        for (panel in panels) {
            val category = panel.category ?: continue
            if (mx !in panel.x..(panel.x + panelWidth)) continue

            val listY = panel.y + headerHeight - panel.scrollOffset
            val modules = ModuleManager.getModules().filter { it.category == category && !it.hidden }

            var curY = listY
            for (mod in modules) {
                if (my in curY..(curY + itemHeight)) {
                    if (mod.name == name) return // 避免关掉自己导致无法再开
                    when (button) {
                        0 -> mod.enabled = !mod.enabled // 左键开关
                        1 -> expandedModule = if (expandedModule == mod) null else mod // 右键展开
                    }
                    return
                }
                curY += itemHeight
                if (expandedModule == mod) {
                    for (v in collectValues(mod)) {
                        if (my in curY..(curY + itemHeight)) {
                            if (button == 0) handleValueClick(v, mx, my)
                            return
                        }
                        curY += itemHeight
                    }
                }
            }
        }
    }

    private fun handleValueClick(v: Value<*>, mx: Float, my: Float) {
        val actual = getActualValue(v) ?: return
        if (isGroupValue(v)) {
            if (collapsedGroups.contains(v)) collapsedGroups.remove(v) else collapsedGroups.add(v)
            return
        }
        if (actual is Boolean) {
            trySetValue(v, !actual)
            return
        }
        if (actual is Enum<*>) {
            val constants = actual.javaClass.enumConstants?.toList() ?: emptyList()
            if (constants.isNotEmpty()) {
                val idx = constants.indexOfFirst { it.toString() == actual.name }
                val next = constants[(idx + 1) % constants.size]
                trySetValue(v, next)
            }
            return
        }
        // 滑块
        if (actual is Number && v is RangedValue<*>) {
            val min = (v.range.start as? Number)?.toFloat() ?: 0f
            val max = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
            val sliderW = 80f
            val sliderX = panelRightEdgeOf(v) - sliderW
            val p = ((mx - sliderX) / sliderW).coerceIn(0f, 1f)
            val newVal = min + (max - min) * p
            when (actual) {
                is Float -> trySetValue(v, newVal)
                is Double -> trySetValue(v, newVal.toDouble())
                is Int -> trySetValue(v, newVal.toInt())
                is Long -> trySetValue(v, newVal.toLong())
            }
            sliderDrag[v] = newVal
            return
        }
        // 绑键
        if (v.name.contains("Bind", true)) {
            listeningBind = if (listeningBind == v) null else v
            return
        }
        // 颜色
        if (actual.javaClass.simpleName.contains("Color", true)) {
            activeColorValue = if (activeColorValue == v) null else v
            paletteX = (panelRightEdgeOf(v) - 12 * 14f).coerceAtLeast(4f)
            paletteY = (my + 8f).coerceAtMost((mc.window.guiScaledHeight - 12 * 14f - 4f))
            return
        }
    }

    private fun panelRightEdgeOf(v: Value<*>): Float {
        // 找到 v 所在面板右边缘
        for (p in panels) {
            val cat = p.category ?: continue
            val mod = expandedModule ?: continue
            if (mod.category == cat) return p.x + panelWidth
        }
        return 0f
    }

    private var paletteX = 0f
    private var paletteY = 0f

    /* ============================= 渲染 ============================= */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f) else 0.016f
        lastFrameNs = now

        // 平滑开合：步进封顶，减轻低帧/卡顿时的跳跃
        if (enabled) {
            val step = (frameTime * easeSpeed * 0.14f).coerceIn(0.006f, 0.075f)
            ease.incrementPercentage(step)
        } else {
            val step = (frameTime * easeSpeed * 0.22f).coerceIn(0.01f, 0.12f)
            ease.decrementPercentage(step)
        }
        var inScale = getEaseAnim(ease, if (animation == ClickGuiAnimation.BOUNCE) 1 else 0)
        if (ease.isPercentageMax() || ease.percentage > 0.995f) inScale = 1f
        if (animation == ClickGuiAnimation.ZOOM) inScale = inScale.coerceIn(0f, 1f)
        val animAlpha = ease.easeOutExpo().coerceIn(0f, 1f)
        if (animAlpha < 0.0001f) return@handler

        mouseX = guiMouseX()
        mouseY = guiMouseY()

        // 拖拽更新
        for (panel in panels) {
            // 左键未按住时强制结束拖拽（防止事件丢失导致跟着鼠标跑）
            val leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                mc.window.handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
            ) == org.lwjgl.glfw.GLFW.GLFW_PRESS
            if (!leftDown) panel.dragging = false
            if (panel.dragging) {
                panel.x = mouseX - panel.dragOffsetX
                panel.y = mouseY - panel.dragOffsetY
            }
            panel.scrollOffset += (panel.targetScroll - panel.scrollOffset) * 0.3f
        }

        // 面板数据准备
        if (panels.isEmpty()) {
            val count = ModuleCategories.entries.size
            val totalW = count * panelWidth
            val startX = (context.guiWidth() - totalW) / 2f
            for ((idx, cat) in ModuleCategories.entries.withIndex()) {
                panels += PanelState(cat, startX + idx * panelWidth, context.guiHeight() / 2f - 100f)
            }
        }

        val screenW = context.guiWidth().toFloat()
        val screenH = context.guiHeight().toFloat()

        // —— ① 模糊 / 遮罩近似 (ModernDropdown: black*0.38 + addBlur) ——
        // 多层半透明黑 + 轻微扩散，模拟 blurStrength
        val dimA = (220 * animAlpha * animAlpha * 0.42f).roundToInt().coerceIn(0, 110) // 二次曲线，过渡更自然
        context.drawQuad(0f, 0f, screenW, screenH, Color4b(0, 0, 0, dimA))
        val blurLayers = (blurStrength / 4f).roundToInt().coerceIn(1, 6)
        for (i in 1..blurLayers) {
            val t = i / blurLayers.toFloat()
            val expand = blurStrength * t * 0.35f
            val ba = (animAlpha * blurStrength * 1.2f * (1f - t * 0.55f)).roundToInt().coerceIn(0, 40)
            if (ba < 2) continue
            context.drawQuad(
                -expand, -expand, screenW + expand, screenH + expand,
                Color4b(0, 0, 0, ba),
            )
        }

        // —— ② 底部主题色渐变 (ModernDropdown: 下 1/3, alpha 随高度上淡) ——
        // firstheight = lerp(screenH, screenH - screenH/3, inScale)
        val firstH = lerp(screenH, screenH - screenH / 3f, inScale)
        val baseTheme = getThemedColor(0f)
        val steps = 16
        for (s in 0 until steps) {
            val t0 = s / steps.toFloat()
            val t1 = (s + 1) / steps.toFloat()
            val y0 = lerp(firstH, screenH, t0)
            val y1 = lerp(firstH, screenH, t1)
            // 越往上越淡: (1 - t0) * 0.4 * inScale * animation
            val al = (0.45f * inScale * animAlpha * (1f - t0) * 255f).roundToInt().coerceIn(0, 140)
            if (al < 2) continue
            // 略微横向换色，更接近主题流动
            val col = getThemedColor(y0 * 0.5f).alpha(al)
            context.drawQuad(0f, y0, screenW, y1, col)
        }

        // 整体动画: 缩放 (以屏幕中心为锚点) — 仅面板/控件
        context.pose().withPush {
            val cx = screenW / 2f
            val cy = screenH / 2f
            translate(cx, cy)
            scale(if (scaleAnimation) inScale else 1f, if (scaleAnimation) inScale else 1f)
            translate(-cx, -cy)

            for (panel in panels) {
                renderPanel(context, font, panel, animAlpha)
            }
            renderPalette(context, font, animAlpha)
        }
    }

    /** 面板外软阴影（模糊近似） */
    private fun GuiGraphicsExtractor.drawSoftShadow(
        x1: Float, y1: Float, x2: Float, y2: Float, r: Float, strength: Float, alpha: Float,
    ) {
        if (strength < 0.5f || alpha < 0.01f) return
        val layers = 5
        for (i in 1..layers) {
            val t = i / layers.toFloat()
            val expand = strength * t
            val a = (18f * alpha * (1f - t) * (1f - t)).roundToInt().coerceIn(0, 50)
            if (a < 2) continue
            drawRoundedRect(
                x1 - expand, y1 - expand, x2 + expand, y2 + expand,
                r + expand * 0.25f,
                Color4b(0, 0, 0, a),
            )
        }
    }

    /** 启用模块：左右主题双色圆角渐变 (fillRoundedGradientRectangle 近似) */
    private fun GuiGraphicsExtractor.drawEnabledModGradient(
        x1: Float, y1: Float, x2: Float, y2: Float, r: Float,
        c1: Color4b, c2: Color4b, alpha: Float,
    ) {
        // 半透明主题色条，分段重叠避免黑边/缝隙
        val a = (110 * alpha).roundToInt().coerceIn(0, 160)
        val w = x2 - x1
        if (w <= 2f) {
            drawQuad(x1, y1, x2, y2, c1.alpha(a))
            return
        }
        val segments = 16
        val segW = w / segments
        for (i in 0 until segments) {
            val t = i / (segments - 1).toFloat().coerceAtLeast(1f)
            val s = t * t * (3f - 2f * t)
            val col = Color4b(
                lerp(c1.r.toFloat(), c2.r.toFloat(), s).toInt().coerceIn(0, 255),
                lerp(c1.g.toFloat(), c2.g.toFloat(), s).toInt().coerceIn(0, 255),
                lerp(c1.b.toFloat(), c2.b.toFloat(), s).toInt().coerceIn(0, 255),
                a,
            )
            val sx = x1 + segW * i - 0.25f
            val ex = x1 + segW * (i + 1) + 0.25f
            drawQuad(sx.coerceAtLeast(x1), y1, ex.coerceAtMost(x2), y2, col)
        }
    }

    /* ============================= 面板渲染 ============================= */

    private fun renderPanel(ctx: GuiGraphicsExtractor, font: Font, panel: PanelState, alpha: Float) {
        val cat = panel.category ?: return
        val px = panel.x
        val py = panel.y
        val pw = panelWidth.toFloat()
        val a = (255 * alpha).roundToInt().coerceIn(0, 255)
        val rr = radius.toFloat()
        val ph = panelMaxHeight.toFloat()

        // 面板阴影 (blur 近似)
        ctx.drawSoftShadow(px, py, px + pw, py + ph, rr, 6f + blurStrength * 0.25f, alpha)

        // 面板背景 darkBlack
        val bg = Color4b(24, 24, 24, (backgroundAlpha * alpha).roundToInt().coerceIn(0, 255))
        // 面板主体圆角
        ctx.drawRoundedRect(px, py, px + pw, py + ph, rr, bg)

        // 标题栏：只要上方两个圆角，下方用方边盖住
        val accent = accentAt(py)
        val titleBg = Color4b(accent.r, accent.g, accent.b, (90 * alpha).roundToInt())
        ctx.drawRoundedRect(px, py, px + pw, py + headerHeight, rr, titleBg)
        // 盖住标题栏底部两个圆角 → 标题与列表直角相接
        if (rr > 0.5f) {
            ctx.drawQuad(px, py + headerHeight - rr, px + pw, py + headerHeight, titleBg)
        }
        ctx.drawQuad(px, py + headerHeight - 1f, px + pw, py + headerHeight, accent.alpha((a * 0.9f).toInt()))
        ctx.text(
            font, cat.tag,
            (px + 8f).roundToInt(), (py + headerHeight / 2f - 4f).roundToInt(),
            Color4b.WHITE.alpha(a).argb, textShadow,
        )

        // 模块列表 —— 严格裁剪到标题栏下方 ~ 面板底，避免文字溢出
        val clipTop = py + headerHeight
        val clipBottom = py + ph
        val listY = clipTop - panel.scrollOffset
        val modules = ModuleManager.getModules().filter { it.category == cat && !it.hidden }

        // 内容高度 (含展开设置，用于滚动限制)
        var contentH = 0
        val expanded = expandedModule
        for (mod in modules) {
            contentH += itemHeight
            if (expanded == mod) contentH += collectValues(mod).size * itemHeight
        }
        val maxScroll = max(0f, (contentH - (panelMaxHeight - headerHeight)).toFloat())
        panel.targetScroll = panel.targetScroll.coerceIn(0f, maxScroll)

        /** 行是否与可见区相交（部分可见也要画，避免子项「消失」） */
        fun rowVisible(y: Float, h: Float = itemHeight.toFloat()): Boolean =
            y + h > clipTop + 0.5f && y < clipBottom - 0.5f

        var curY = listY
        for (mod in modules) {
            val isExpanded = expanded == mod
            val rowY2 = curY + itemHeight
            val rowR = if (!isExpanded && mod == modules.lastOrNull()) rr * 0.6f else 0f

            // 模块行：仅在可见时绘制，但无论是否可见都推进 curY
            if (rowVisible(curY)) {
                // 底色略浅，避免与启用渐变叠出黑边
                ctx.drawQuad(px, max(curY, clipTop), px + pw, min(rowY2, clipBottom), Color4b(36, 36, 40, (a * 0.9f).toInt()))
                if (isExpanded) {
                    ctx.drawQuad(px, max(curY, clipTop), px + pw, min(rowY2, clipBottom), Color4b(accent.r, accent.g, accent.b, 32))
                }
                if (mod.enabled) {
                    val c1 = getThemedColor(curY * 2f)
                    val c2 = getThemedColor(curY * 2f + pw)
                    val gy1 = max(curY, clipTop)
                    val gy2 = min(rowY2, clipBottom)
                    if (gy2 > gy1) ctx.drawEnabledModGradient(px, gy1, px + pw, gy2, 0f, c1, c2, alpha)
                }
                if (mouseX in px..(px + pw) && mouseY in max(curY, clipTop)..min(rowY2, clipBottom)) {
                    ctx.drawQuad(px, max(curY, clipTop), px + pw, min(rowY2, clipBottom), Color4b(255, 255, 255, 18))
                }
                // 文字中心在 clip 内才画，防止标题栏上下溢出
                val textCy = curY + itemHeight / 2f - 4f
                if (textCy >= clipTop && textCy + 9f <= clipBottom) {
                    val modColor = if (mod.enabled) Color4b.WHITE.alpha(a) else Color4b(180, 180, 180, a)
                    ctx.text(font, mod.name, (px + 8f).roundToInt(), textCy.roundToInt(), modColor.argb, textShadow)
                    if (showStatusDot) {
                        val dotX = px + pw - 12f
                        ctx.drawRoundedRect(
                            dotX, curY + itemHeight / 2f - 3f, dotX + 6f, curY + itemHeight / 2f + 3f, 3f,
                            if (mod.enabled) accentAt(curY).alpha(a) else Color4b(90, 90, 90, a),
                        )
                    }
                }
            }

            curY += itemHeight

            // 设置项：模块头滚出视野后仍继续遍历，可见的子项照常绘制
            if (isExpanded) {
                for (v in collectValues(mod)) {
                    if (rowVisible(curY)) {
                        renderSetting(ctx, font, v, px, curY, pw, a, accent)
                    }
                    curY += itemHeight
                    // 已远低于面板底可提前结束本分类
                    if (curY > clipBottom + itemHeight * 2) break
                }
            }

            if (curY > clipBottom + itemHeight * 2) break
        }

        // 重新盖住标题栏（同样去掉底部圆角）
        val titleBg2 = Color4b(accent.r, accent.g, accent.b, (90 * alpha).roundToInt())
        ctx.drawRoundedRect(px, py, px + pw, py + headerHeight, rr, titleBg2)
        if (rr > 0.5f) {
            ctx.drawQuad(px, py + headerHeight - rr, px + pw, py + headerHeight, titleBg2)
        }
        ctx.drawQuad(px, py + headerHeight - 1f, px + pw, py + headerHeight, accent.alpha((a * 0.9f).toInt()))
        ctx.text(
            font, cat.tag,
            (px + 8f).roundToInt(), (py + headerHeight / 2f - 4f).roundToInt(),
            Color4b.WHITE.alpha(a).argb, textShadow,
        )
        // 底边遮罩，挡住下方溢出
        ctx.drawQuad(px, clipBottom - 2f, px + pw, clipBottom + 1f, bg)
    }

    /* ============================= 设置项渲染 ============================= */

    private fun renderSetting(
        ctx: GuiGraphicsExtractor, font: Font, v: Value<*>,
        px: Float, y: Float, pw: Float, a: Int, accent: Color4b,
    ) {
        val actual = getActualValue(v) ?: return
        val isGroup = isGroupValue(v)

        // 悬停高亮
        if (mouseX in px..(px + pw) && mouseY in y..(y + itemHeight)) {
            ctx.drawQuad(px, y, px + pw, y + itemHeight, Color4b(255, 255, 255, 12))
        }

        // 组头
        if (isGroup) {
            val collapsed = collapsedGroups.contains(v)
            ctx.drawQuad(px, y, px + pw, y + itemHeight, Color4b(accent.r, accent.g, accent.b, 18))
            ctx.text(
                font, "${if (collapsed) "▶" else "▼"} ${v.name}",
                (px + 8f).roundToInt(), (y + 5f).roundToInt(),
                Color4b(200, 200, 200, a).argb, textShadow,
            )
            return
        }

        // 标签 (限宽)
        val label = v.name
        val labelMaxW = (pw * 0.42f).toInt()
        val shownLabel = if (font.width(label) > labelMaxW) label.take(8) + "…" else label
        val labelColor = Color4b(180, 180, 180, a)

        when {
            actual is Boolean -> {
                ctx.text(font, shownLabel, (px + 8f).roundToInt(), (y + 5f).roundToInt(), labelColor.argb, textShadow)
                // 开关
                val swX = px + pw - 34f
                val swY = y + 4f
                ctx.drawRoundedRect(swX, swY, swX + 26f, swY + 10f, 5f,
                    if (actual) accent.alpha(a) else Color4b(90, 90, 90, a))
                val knobX = if (actual) swX + 16f else swX + 2f
                ctx.drawRoundedRect(knobX, swY + 2f, knobX + 8f, swY + 8f, 4f, Color4b.WHITE.alpha(a))
            }
            actual is Enum<*> -> {
                ctx.text(font, shownLabel, (px + 8f).roundToInt(), (y + 5f).roundToInt(), labelColor.argb, textShadow)
                ctx.text(
                    font, actual.name,
                    (px + pw - 8f - font.width(actual.name)).roundToInt(), (y + 5f).roundToInt(),
                    accent.alpha(a).argb, textShadow,
                )
            }
            actual is Number && v is RangedValue<*> -> {
                ctx.text(font, shownLabel, (px + 8f).roundToInt(), (y + 3f).roundToInt(), labelColor.argb, textShadow)
                val min = (v.range.start as? Number)?.toFloat() ?: 0f
                val max = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                val fv = actual.toFloat()
                val progress = if (max > min) ((fv - min) / (max - min)).coerceIn(0f, 1f) else 0f
                val sliderW = 64f
                val sliderX = px + pw - sliderW - 8f
                val sliderY = y + 10f
                ctx.drawRoundedRect(sliderX, sliderY, sliderX + sliderW, sliderY + 2f, 1f, Color4b(80, 80, 80, a))
                ctx.drawRoundedRect(sliderX, sliderY, sliderX + sliderW * progress, sliderY + 2f, 1f, accent.alpha(a))
                // 值文本
                val valText = String.format(java.util.Locale.US, "%.1f", fv)
                ctx.text(font, valText, (sliderX - 4f - font.width(valText)).roundToInt(), (y + 3f).roundToInt(), Color4b(200, 200, 200, a).argb, textShadow)
            }
            actual.javaClass.simpleName.contains("Color", true) -> {
                ctx.text(font, shownLabel, (px + 8f).roundToInt(), (y + 5f).roundToInt(), labelColor.argb, textShadow)
                val color = try {
                    val argb = actual.javaClass.getMethod("getArgb").invoke(actual) as Int
                    Color4b(argb)
                } catch (_: Exception) {
                    Color4b.WHITE
                }
                val blockX = px + pw - 22f
                ctx.drawRoundedRect(blockX, y + 4f, blockX + 14f, y + 14f, 3f, color)
            }
            v.name.contains("Bind", true) -> {
                ctx.text(font, shownLabel, (px + 8f).roundToInt(), (y + 5f).roundToInt(), labelColor.argb, textShadow)
                val listening = listeningBind == v
                ctx.text(
                    font, if (listening) "[...]" else "Bind",
                    (px + pw - 8f - font.width(if (listening) "[...]" else "Bind")).roundToInt(), (y + 5f).roundToInt(),
                    (if (listening) accent else Color4b(150, 150, 150, a)).argb, textShadow,
                )
            }
            else -> {
                ctx.text(font, shownLabel, (px + 8f).roundToInt(), (y + 5f).roundToInt(), labelColor.argb, textShadow)
                val dv = actual.toString().take(12)
                ctx.text(font, dv, (px + pw - 8f - font.width(dv)).roundToInt(), (y + 5f).roundToInt(), Color4b(150, 150, 150, a).argb, textShadow)
            }
        }
    }

    /* ============================= 调色板 ============================= */

    private fun renderPalette(ctx: GuiGraphicsExtractor, font: Font, alpha: Float) {
        val colorVal = activeColorValue ?: return
        val cell = 14f
        val pad = 4f
        val cols = 6
        val rows = 2
        val w = cols * cell + pad * 2
        val h = rows * cell + pad * 2
        val a = (255 * alpha).roundToInt().coerceIn(0, 255)

        ctx.drawRoundedRect(paletteX, paletteY, paletteX + w, paletteY + h, 4f, Color4b(30, 30, 30, (230 * alpha).roundToInt()))
        for (i in paletteColors.indices) {
            val col = i % cols
            val row = i / cols
            val cx = paletteX + pad + col * cell
            val cy = paletteY + pad + row * cell
            ctx.drawRoundedRect(cx, cy, cx + cell - 2f, cy + cell - 2f, 3f, paletteColors[i].alpha(a))
        }
    }

}
