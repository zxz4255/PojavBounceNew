/*
 * ModuleSolsticeClickgui — 复刻 ClickGui.cpp + ModernDropdown.cpp/hpp
 * LiquidBounce Nextgen 0.39 · 原生 Overlay · 无 Web
 *
 * 原版要点:
 *  - Style: Modern 分类列（可拖动 / 右键折叠 / 滚轮滚动）
 *  - 动画: Zoom / Bounce + ease 缩放
 *  - 开启时 releaseMouse，Mouse/Key 事件吞掉；ESC 仅关 GUI
 *  - 模块: 左键开关，右键展开设置，中键绑键
 *  - 设置: Bool / Enum / Number 滑条 / Color
 *  - 全屏暗化 + 底部主题渐变；可调 Blur 强度近似、圆角、主题色
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.Value
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
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object ModuleSolsticeClickgui : ClientModule(
    "SolsticeClickgui",
    ModuleCategories.RENDER,
    aliases = listOf("SolsticeGui", "ModernClickGui"),
) {

    private enum class AnimMode(override val tag: String) : Tagged {
        ZOOM("Zoom"),
        BOUNCE("Bounce"),
    }

    private val animMode by enumChoice("Animation", AnimMode.BOUNCE)
    private val easeSpeed by float("Ease Speed", 18f, 5f..30f)
    private val dimAlpha by float("Dim Alpha", 0.38f, 0f..0.8f)
    private val bottomGlow by boolean("Bottom Glow", true)
    private val bottomGlowStrength by float("Bottom Glow Strength", 0.4f, 0f..1f)
    private val catWidth by float("Category Width", 200f, 140f..280f)
    private val catHeight by float("Category Height", 30f, 22f..44f)
    private val catGap by float("Category Gap", 40f, 16f..80f)
    private val modHeight by float("Module Height", 30f, 22f..40f)
    private val cornerRadius by float("Corner Radius", 10f, 0f..18f)
    private val midclickRound by float("Midclick Rounding", 1f, 0.01f..1f)
    private val themeA by color("Theme A", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeB by color("Theme B", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeCycle by float("Theme Cycle Sec", 4f, 1f..12f)
    private val bgModule by color("Module BG", Color4b(30, 30, 30, 255))
    private val bgCategory by color("Category BG", Color4b(24, 24, 24, 255))
    private val bgSetting by color("Setting BG", Color4b(30, 30, 30, 255))
    private val textColor by color("Text", Color4b(255, 255, 255, 255))
    private val textDim by color("Text Dim", Color4b(180, 180, 180, 255))
    private val openKey by key("Open Key", GLFW.GLFW_KEY_RIGHT_SHIFT)

    // —— 分类面板状态（对应 CategoryPosition）——
    private class CatPos(
        var x: Float = 0f,
        var y: Float = 0f,
        var isDragging: Boolean = false,
        var isExtended: Boolean = true,
        var wasExtended: Boolean = true,
        var yOffset: Float = 0f,
        var scrollEase: Float = 0f,
    )

    private val catPositions = ArrayList<CatPos>()
    private var categories: List<ModuleCategory> = emptyList()
    private var dragIndex = -1
    private var dragOffX = 0f
    private var dragOffY = 0f

    private val moduleOpen = IdentityHashMap<ClientModule, Boolean>()
    private val moduleAnim = IdentityHashMap<ClientModule, Float>()
    private val moduleScale = IdentityHashMap<ClientModule, Float>()
    private val enumOpen = IdentityHashMap<Value<*>, Boolean>()
    private val enumSlide = IdentityHashMap<Value<*>, Float>()
    private val boolScale = IdentityHashMap<Value<*>, Float>()
    private val sliderEase = IdentityHashMap<Value<*>, Float>()

    private var sliderDrag: Value<*>? = null
    private var bindingModule: ClientModule? = null
    private var tooltip: String = ""

    private var openAnim = 0f
    private var scaleAnim = 0f
    private var lastNs = 0L
    private var mouseX = 0f
    private var mouseY = 0f
    private var guiScreen: SolsticeScreen? = null
    private var positionsInit = false

    // ── math / ease ──────────────────────────────────────────────
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun easeOutExpo(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x >= 1f) 1f else (1.0 - 2.0.pow((-10 * x).toDouble())).toFloat()
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t.coerceIn(0f, 1f) - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun easeOutElastic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        return (2.0.pow((-10 * x).toDouble()) * sin((x * 10 - 0.75) * (2 * Math.PI) / 3) + 1).toFloat()
    }

    private fun getEaseAnim(pct: Float): Float = when (animMode) {
        AnimMode.ZOOM -> easeOutExpo(pct).coerceIn(0f, 0.996f)
        AnimMode.BOUNCE -> if (enabled) easeOutElastic(pct) else easeOutBack(pct)
    }

    private fun themed(seed: Float): Color4b {
        val t = ((System.currentTimeMillis() % (themeCycle * 1000).toLong()) / (themeCycle * 1000f)
            + seed * 0.01f) % 1f
        val s = (sin(t * Math.PI * 2).toFloat() * 0.5f + 0.5f)
        return Color4b(
            lerp(themeA.r.toFloat(), themeB.r.toFloat(), s).toInt().coerceIn(0, 255),
            lerp(themeA.g.toFloat(), themeB.g.toFloat(), s).toInt().coerceIn(0, 255),
            lerp(themeA.b.toFloat(), themeB.b.toFloat(), s).toInt().coerceIn(0, 255),
            255,
        )
    }

    private fun hover(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    private fun alpha(c: Color4b, a: Float) = c.alpha((c.a * a).toInt().coerceIn(0, 255))

    // ── modules / values ─────────────────────────────────────────
    private fun allCategories(): List<ModuleCategory> {
        return runCatching {
            ModuleManager.modules.map { it.category }.distinct().sortedBy { it.toString() }
        }.getOrElse { emptyList() }
    }

    private fun modulesIn(cat: ModuleCategory): List<ClientModule> =
        ModuleManager.modules.filter { it.category == cat && it != this@ModuleSolsticeClickgui }
            .sortedBy { it.name }

    private fun collectValues(mod: ClientModule): List<Value<*>> = try {
        mod.collectValuesRecursively().toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun getActual(v: Value<*>): Any? {
        var obj: Any? = try {
            v.get()
        } catch (_: Exception) {
            null
        }
        var d = 0
        while (obj is Value<*> && d < 5) {
            obj = try {
                obj.get()
            } catch (_: Exception) {
                null
            }
            d++
        }
        return obj
    }

    private fun displayName(v: Value<*>): String {
        val raw = runCatching {
            v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getName", true) }
                ?.invoke(v) as? String
        }.getOrNull()
        return (raw ?: v.name).substringBefore('[').take(22)
    }

    private fun choiceLabel(a: Any?): String {
        if (a == null) return "-"
        if (a is Boolean) return if (a) "ON" else "OFF"
        if (a is Number) {
            val d = a.toDouble()
            return if (d == d.toLong().toDouble()) a.toLong().toString() else "%.2f".format(d)
        }
        if (a is NamedChoice) return a.choiceName
        if (a is Enum<*>) return a.name
        runCatching {
            for (n in listOf("getName", "getTag", "getChoiceName", "getDisplayName")) {
                val m = a.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                    ?: continue
                val r = m.invoke(a)
                if (r is String && r.isNotBlank()) return r.substringAfterLast('.').take(18)
            }
        }
        return a.toString().substringAfterLast('.').substringBefore('@').take(18).ifBlank { "-" }
    }

    private fun listChoices(v: Value<*>): List<Any?> {
        val actual = getActual(v)
        if (actual is Enum<*>) {
            return actual.javaClass.enumConstants?.toList() ?: emptyList()
        }
        for (n in listOf("getChoices", "choices", "getModes", "modes", "getActiveChoices", "entries")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                ?: continue
            val r = m.invoke(v) ?: continue
            when (r) {
                is Collection<*> -> return r.toList()
                is Array<*> -> return r.toList()
            }
        }
        return emptyList()
    }

    private fun trySet(v: Value<*>, value: Any): Boolean {
        val label = choiceLabel(value)
        for (n in listOf("setByString", "setAsString", "set", "setValue", "setActiveChoice", "select")) {
            for (m in v.javaClass.methods) {
                if (m.parameterCount != 1 || !m.name.equals(n, true)) continue
                try {
                    m.invoke(v, value)
                    return true
                } catch (_: Exception) {
                }
                try {
                    m.invoke(v, label)
                    return true
                } catch (_: Exception) {
                }
            }
        }
        if (value is Boolean) {
            runCatching {
                v.javaClass.methods.firstOrNull { it.name == "toggle" && it.parameterCount == 0 }?.invoke(v)
                return true
            }
        }
        return false
    }

    private fun rangeOf(v: Value<*>): Pair<Float, Float>? {
        fun read(names: List<String>): Float? {
            for (n in names) {
                val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                    ?: continue
                val r = m.invoke(v)
                when (r) {
                    is Number -> return r.toFloat()
                    is ClosedFloatingPointRange<*> -> {
                        val a = (r.start as? Number)?.toFloat()
                        val b = (r.endInclusive as? Number)?.toFloat()
                        if (a != null && b != null) return null // handled below
                    }
                }
            }
            for (f in v.javaClass.declaredFields) {
                if (names.any { f.name.equals(it, true) }) {
                    f.isAccessible = true
                    val r = f.get(v)
                    if (r is Number) return r.toFloat()
                    if (r is ClosedFloatingPointRange<*>) {
                        // fall through
                    }
                    if (r is IntRange) return null
                }
            }
            return null
        }
        // range object
        for (n in listOf("getRange", "range")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
            val r = m?.invoke(v)
            when (r) {
                is ClosedFloatingPointRange<*> -> {
                    val a = (r.start as? Number)?.toFloat() ?: continue
                    val b = (r.endInclusive as? Number)?.toFloat() ?: continue
                    return a to b
                }
                is IntRange -> return r.first.toFloat() to r.last.toFloat()
            }
        }
        val minV = read(listOf("getMinimum", "getMin", "minimum", "min")) ?: return null
        val maxV = read(listOf("getMaximum", "getMax", "maximum", "max")) ?: return null
        if (maxV <= minV) return null
        return minV to maxV
    }

    private fun cycleChoice(v: Value<*>): Boolean {
        for (n in listOf("next", "cycle", "selectNext")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                ?: continue
            runCatching { m.invoke(v); return true }
        }
        val list = listChoices(v)
        if (list.isEmpty()) return false
        val cur = choiceLabel(getActual(v))
        var idx = list.indexOfFirst { choiceLabel(it) == cur }
        if (idx < 0) idx = 0
        val next = list[(idx + 1) % list.size] ?: return false
        return trySet(v, next) || trySet(v, choiceLabel(next))
    }

    private fun initPositions(sw: Float) {
        categories = allCategories()
        catPositions.clear()
        val total = categories.size * (catWidth + catGap)
        var x = sw / 2f - total / 2f
        for (i in categories.indices) {
            catPositions.add(CatPos(x = (x / 2f).roundToInt() * 2f, y = catGap * 2f))
            x += catWidth + catGap
        }
        positionsInit = true
    }

    // ── Screen（吞鼠标/键盘，ESC 只关 GUI）────────────────────────
    private class SolsticeScreen : Screen(Component.literal("SolsticeClickGui")) {
        override fun isPauseScreen(): Boolean = false

        override fun keyPressed(event: KeyEvent): Boolean {
            val key = event.key()
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (ModuleSolsticeClickgui.bindingModule != null) {
                    ModuleSolsticeClickgui.bindingModule = null
                } else {
                    ModuleSolsticeClickgui.enabled = false
                }
                return true
            }
            val bind = ModuleSolsticeClickgui.bindingModule
            if (bind != null) {
                runCatching {
                    bind.bind = if (key == GLFW.GLFW_KEY_ESCAPE) GLFW.GLFW_KEY_UNKNOWN else key
                }
                ModuleSolsticeClickgui.bindingModule = null
                return true
            }
            return true
        }

        override fun charTyped(event: CharacterEvent): Boolean = true

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            ModuleSolsticeClickgui.onMouse(event.button(), true)
            return true
        }

        override fun mouseReleased(event: MouseButtonEvent): Boolean {
            ModuleSolsticeClickgui.onMouse(event.button(), false)
            return true
        }

        override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean = true

        override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
            ModuleSolsticeClickgui.onScroll(if (v > 0) -1 else if (v < 0) 1 else 0)
            return true
        }

        override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            // 内容由 OverlayRenderEvent 绘制，避免双绘
        }
    }

    private var scrollDir = 0

    private fun onScroll(dir: Int) {
        scrollDir = dir
    }

    private fun onMouse(button: Int, pressed: Boolean) {
        mouseX = guiMX()
        mouseY = guiMY()
        if (!pressed) {
            if (button == 0) {
                dragIndex = -1
                catPositions.forEach { it.isDragging = false }
                sliderDrag = null
            }
            return
        }
        // 分类头
        for (i in catPositions.indices) {
            val p = catPositions[i]
            if (hover(p.x, p.y, catWidth, catHeight)) {
                if (button == 0) {
                    p.isDragging = true
                    dragIndex = i
                    dragOffX = mouseX - p.x
                    dragOffY = mouseY - p.y
                    return
                }
                if (button == 1) {
                    p.isExtended = !p.isExtended
                    return
                }
            }
        }
        // 模块与设置（从上到下命中）
        for (i in catPositions.indices) {
            if (handleCategoryClick(i, button)) return
        }
    }

    private fun handleCategoryClick(i: Int, button: Int): Boolean {
        if (i !in categories.indices || i !in catPositions.indices) return false
        val p = catPositions[i]
        if (!p.isExtended) return false
        val mods = modulesIn(categories[i])
        var moduleY = -p.yOffset
        for (mod in mods) {
            val my = p.y + catHeight + moduleY
            if (hover(p.x, my, catWidth, modHeight)) {
                if (button == 0) {
                    mod.enabled = !mod.enabled
                    return true
                }
                if (button == 1) {
                    val vals = collectValues(mod)
                    if (vals.isNotEmpty()) {
                        moduleOpen[mod] = !(moduleOpen[mod] ?: false)
                    }
                    return true
                }
                if (button == 2) {
                    bindingModule = mod
                    return true
                }
            }
            moduleY += modHeight
            val open = moduleOpen[mod] == true
            val anim = moduleAnim[mod] ?: 0f
            if (anim > 0.01f || open) {
                for (v in collectValues(mod)) {
                    val sy = p.y + catHeight + moduleY
                    val sh = modHeight
                    if (hover(p.x, sy, catWidth, sh)) {
                        handleValueClick(v, button)
                        return true
                    }
                    moduleY += sh * anim
                    if (enumOpen[v] == true) {
                        val choices = listChoices(v)
                        for (c in choices) {
                            val cy = p.y + catHeight + moduleY
                            if (hover(p.x, cy, catWidth, modHeight)) {
                                if (button == 0) {
                                    trySet(v, c!!)
                                    enumOpen[v] = false
                                }
                                return true
                            }
                            moduleY += modHeight * (enumSlide[v] ?: 0f)
                        }
                    }
                }
            }
        }
        return false
    }

    private fun handleValueClick(v: Value<*>, button: Int) {
        val actual = getActual(v)
        when {
            actual is Boolean -> {
                if (button == 0) trySet(v, !actual)
            }
            actual is Number -> {
                if (button == 0 || button == 2) {
                    sliderDrag = v
                    applySlider(v, button == 2)
                }
            }
            actual is Enum<*> || listChoices(v).isNotEmpty() -> {
                if (button == 0) {
                    if (!cycleChoice(v)) enumOpen[v] = true
                } else if (button == 1) {
                    enumOpen[v] = !(enumOpen[v] ?: false)
                }
            }
            else -> {
                if (button == 0) cycleChoice(v)
            }
        }
    }

    private fun applySlider(v: Value<*>, midRound: Boolean) {
        val range = rangeOf(v) ?: return
        val (minV, maxV) = range
        // 找当前分类中该设置行的 x 范围：用整列宽近似
        val x1 = catPositions.getOrNull(dragIndex.coerceAtLeast(0))?.x
            ?: catPositions.firstOrNull()?.x ?: return
        val x0 = x1
        val x2 = x0 + catWidth
        var nv = ((mouseX - x0) / (x2 - x0).coerceAtLeast(1f) * (maxV - minV) + minV).coerceIn(minV, maxV)
        if (midRound) {
            val step = midclickRound.coerceAtLeast(0.01f)
            nv = (nv / step).roundToInt() * step
        }
        val actual = getActual(v)
        when (actual) {
            is Float -> trySet(v, nv)
            is Double -> trySet(v, nv.toDouble())
            is Int -> trySet(v, nv.roundToInt())
            is Long -> trySet(v, nv.toLong())
            else -> trySet(v, nv)
        }
    }

    private fun guiMX(): Float =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()

    private fun guiMY(): Float =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()

    // ── lifecycle ────────────────────────────────────────────────
    override fun onEnabled() {
        openAnim = 0f
        scaleAnim = 0f
        positionsInit = false
        bindingModule = null
        val screen = SolsticeScreen()
        guiScreen = screen
        mc.setScreen(screen)
    }

    override fun onDisabled() {
        if (mc.screen is SolsticeScreen) {
            mc.setScreen(null)
        }
        guiScreen = null
        sliderDrag = null
        bindingModule = null
        dragIndex = -1
    }

    // ── render ───────────────────────────────────────────────────
    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled && openAnim < 0.01f) return@handler

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        val speed = (easeSpeed / 10f) * dt
        if (enabled) {
            openAnim = (openAnim + speed).coerceIn(0f, 1f)
            scaleAnim = (scaleAnim + speed).coerceIn(0f, 1f)
        } else {
            openAnim = (openAnim - speed * 2f).coerceIn(0f, 1f)
            scaleAnim = (scaleAnim - speed * 2f).coerceIn(0f, 1f)
        }
        val anim = easeOutExpo(openAnim)
        val inScale = getEaseAnim(scaleAnim)
        if (anim < 0.001f) return@handler

        mouseX = guiMX()
        mouseY = guiMY()
        val ctx = event.context
        val font = mc.font
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()

        if (!positionsInit || catPositions.size != allCategories().size) {
            initPositions(sw)
        }

        // 拖动
        if (dragIndex in catPositions.indices && catPositions[dragIndex].isDragging) {
            val p = catPositions[dragIndex]
            p.x = (mouseX - dragOffX).coerceIn(0f, sw - catWidth)
            p.y = (mouseY - dragOffY).coerceIn(0f, sh - catHeight)
            p.x = (p.x / 2f).roundToInt() * 2f
            p.y = (p.y / 2f).roundToInt() * 2f
        }

        // 滑条拖动
        sliderDrag?.let { applySlider(it, false) }

        // 全屏暗化（对应 animation * 0.38）
        ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (255 * dimAlpha * anim).toInt().coerceIn(0, 200)))

        // 底部主题渐变
        if (bottomGlow) {
            val firstH = lerp(sh, sh - sh / 3f, inScale)
            val steps = 12
            val col = themed(0f)
            for (s in 0 until steps) {
                val t0 = s / steps.toFloat()
                val t1 = (s + 1) / steps.toFloat()
                val y0 = lerp(firstH, sh, t0)
                val y1 = lerp(firstH, sh, t1)
                val a = (bottomGlowStrength * anim * (1f - t0) * 180f).toInt().coerceIn(0, 180)
                ctx.drawQuad(0f, y0, sw, y1, Color4b(col.r, col.g, col.b, a))
            }
        }

        tooltip = ""
        val center = sw / 2f to sh / 2f

        fun scaleRect(x: Float, y: Float, w: Float, h: Float): FloatArray {
            // scaleToPoint toward screen center by inScale
            val x1 = center.first + (x - center.first) * inScale
            val y1 = center.second + (y - center.second) * inScale
            val x2 = center.first + (x + w - center.first) * inScale
            val y2 = center.second + (y + h - center.second) * inScale
            return floatArrayOf(x1, y1, x2 - x1, y2 - y1)
        }

        for (i in categories.indices) {
            if (i >= catPositions.size) break
            val p = catPositions[i]
            val cat = categories[i]
            val mods = modulesIn(cat)
            val rgb = themed(i * 20f)

            // 滚动
            if (p.isExtended && scrollDir != 0) {
                val r = scaleRect(p.x, p.y, catWidth, catHeight + mods.size * modHeight + 80f)
                if (hover(r[0], r[1], r[2], r[3].coerceAtLeast(40f))) {
                    p.scrollEase = (p.scrollEase + scrollDir * catHeight).coerceAtLeast(0f)
                }
            }
            if (!p.isExtended) {
                p.scrollEase = 0f
                p.wasExtended = false
            } else if (!p.wasExtended) {
                p.scrollEase = 0f
                p.wasExtended = true
            }
            p.yOffset = lerp(p.yOffset, p.scrollEase, (dt * 10.5f).coerceIn(0f, 1f))

            // 分类标题栏（仅上方圆角：用整圆角矩形）
            val cr = scaleRect(p.x, p.y, catWidth, catHeight)
            ctx.drawRoundedRect(cr[0], cr[1], cr[0] + cr[2], cr[1] + cr[3], cornerRadius, alpha(bgCategory, anim))
            val catName = cat.toString().substringAfterLast('.').substringAfter('$')
            val tw = font.width(catName)
            ctx.drawString(
                font, catName,
                (cr[0] + (cr[2] - tw) / 2f).roundToInt(),
                (cr[1] + (cr[3] - 8) / 2f).roundToInt(),
                alpha(textColor, anim).argb,
                false,
            )

            if (!p.isExtended) continue

            var moduleY = -p.yOffset
            for (mod in mods) {
                val targetOpen = if (moduleOpen[mod] == true) 1f else 0f
                val ca = moduleAnim.getOrDefault(mod, 0f)
                moduleAnim[mod] = lerp(ca, targetOpen, (dt * 12.5f).coerceIn(0f, 1f))
                val cAnim = moduleAnim[mod]!!

                val targetScale = if (mod.enabled) 1f else 0f
                moduleScale[mod] = lerp(moduleScale.getOrDefault(mod, 0f), targetScale, (dt * 10f).coerceIn(0f, 1f))
                val cScale = moduleScale[mod]!!

                val mr = scaleRect(p.x, p.y + catHeight + moduleY, catWidth, modHeight)
                // 裁剪：标题下方
                if (mr[1] + mr[3] > cr[1] + cr[3] - 2f) {
                    // 背景
                    ctx.drawRoundedRect(
                        mr[0], mr[1], mr[0] + mr[2], mr[1] + mr[3],
                        0f, alpha(bgModule, anim),
                    )
                    // 启用渐变条
                    if (cScale > 0.01f) {
                        val g1 = themed(moduleY * 2f)
                        val g2 = themed(moduleY * 2f + 40f)
                        val segs = 8
                        for (s in 0 until segs) {
                            val t0 = s / segs.toFloat()
                            val col = Color4b(
                                lerp(g1.r.toFloat(), g2.r.toFloat(), t0).toInt(),
                                lerp(g1.g.toFloat(), g2.g.toFloat(), t0).toInt(),
                                lerp(g1.b.toFloat(), g2.b.toFloat(), t0).toInt(),
                                (255 * anim * cScale).toInt().coerceIn(0, 255),
                            )
                            val sx = mr[0] + mr[2] * t0
                            val ex = mr[0] + mr[2] * ((s + 1) / segs.toFloat())
                            ctx.drawQuad(sx, mr[1], ex, mr[1] + mr[3], col)
                        }
                    }
                    val nameCol = if (mod.enabled) textColor else textDim
                    val nw = font.width(mod.name)
                    ctx.drawString(
                        font, mod.name,
                        (mr[0] + (mr[2] - nw) / 2f).roundToInt(),
                        (mr[1] + (mr[3] - 8) / 2f).roundToInt(),
                        alpha(nameCol, anim).argb,
                        false,
                    )
                    if (hover(mr[0], mr[1], mr[2], mr[3])) {
                        tooltip = mod.description.ifBlank { mod.name }
                    }
                }
                moduleY += modHeight

                // 设置
                if (cAnim > 0.001f) {
                    for (v in collectValues(mod)) {
                        val sr = scaleRect(p.x, p.y + catHeight + moduleY, catWidth, modHeight)
                        if (sr[1] > cr[1] + cr[3] - 2f) {
                            drawSetting(ctx, font, v, sr[0], sr[1], sr[2], sr[3], anim, cAnim)
                            if (hover(sr[0], sr[1], sr[2], sr[3])) {
                                tooltip = displayName(v)
                            }
                        }
                        moduleY += modHeight * cAnim

                        // enum 展开
                        val eOpen = enumOpen[v] == true
                        enumSlide[v] = lerp(enumSlide.getOrDefault(v, 0f), if (eOpen) 1f else 0f, (dt * 10f).coerceIn(0f, 1f))
                        val es = enumSlide[v]!!
                        if (es > 0.01f) {
                            val choices = listChoices(v)
                            val cur = choiceLabel(getActual(v))
                            for (c in choices) {
                                val er = scaleRect(p.x, p.y + catHeight + moduleY, catWidth, modHeight)
                                if (er[1] > cr[1] + cr[3] - 2f) {
                                    ctx.drawQuad(er[0], er[1], er[0] + er[2], er[1] + er[3], alpha(Color4b(20, 20, 20, 255), anim * es))
                                    val lab = choiceLabel(c)
                                    if (lab == cur) {
                                        val tc = themed(moduleY)
                                        ctx.drawQuad(er[0], er[1], er[0] + 2f, er[1] + er[3], alpha(tc, anim * es))
                                    }
                                    ctx.drawString(
                                        font, lab,
                                        (er[0] + 8f).roundToInt(),
                                        (er[1] + (er[3] - 8) / 2f).roundToInt(),
                                        alpha(textColor, anim * es).argb,
                                        false,
                                    )
                                }
                                moduleY += modHeight * es
                            }
                        }
                    }
                }
            }
        }
        scrollDir = 0

        // 绑定提示
        bindingModule?.let {
            tooltip = "Binding ${it.name}... ESC to cancel"
        }

        // tooltip
        if (tooltip.isNotEmpty()) {
            val pad = 4f
            val tw = font.width(tooltip).toFloat()
            val th = 12f
            val tx = (mouseX + 10f).coerceAtMost(sw - tw - pad * 2)
            val ty = (mouseY - th - 6f).coerceAtLeast(2f)
            ctx.drawRoundedRect(tx, ty, tx + tw + pad * 2, ty + th + pad, 3f, Color4b(20, 20, 20, (230 * anim).toInt()))
            ctx.drawString(font, tooltip, (tx + pad).roundToInt(), (ty + pad / 2f).roundToInt(), alpha(textColor, anim).argb, false)
        }
    }

    private fun drawSetting(
        ctx: GuiGraphics,
        font: net.minecraft.client.gui.Font,
        v: Value<*>,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float, cAnim: Float,
    ) {
        val a = anim * cAnim
        ctx.drawQuad(x, y, x + w, y + h, alpha(bgSetting, a))
        val actual = getActual(v)
        val name = displayName(v)
        val ty = (y + (h - 8) / 2f).roundToInt()

        when {
            actual is Boolean -> {
                boolScale[v] = lerp(boolScale.getOrDefault(v, 0f), if (actual) 1f else 0f, 0.25f)
                ctx.drawString(font, name, (x + 5f).roundToInt(), ty, alpha(textColor, a).argb, false)
                val bs = boolScale[v]!!
                val rgb = themed(y)
                if (bs > 0.01f) {
                    ctx.drawString(
                        font, "✓",
                        (x + w - 18f).roundToInt(), ty,
                        alpha(rgb, a * bs).argb, false,
                    )
                }
            }
            actual is Number -> {
                ctx.drawString(font, name, (x + 5f).roundToInt(), (y + 2f).roundToInt(), alpha(textColor, a).argb, false)
                val range = rangeOf(v)
                val fv = actual.toFloat()
                val vs = if (fv == fv.toInt().toFloat()) fv.toInt().toString() else "%.2f".format(fv)
                ctx.drawString(font, vs, (x + w - 5f - font.width(vs)).roundToInt(), (y + 2f).roundToInt(), alpha(textDim, a).argb, false)
                if (range != null) {
                    val (minV, maxV) = range
                    val p = ((fv - minV) / (maxV - minV)).coerceIn(0f, 1f)
                    val target = p * w
                    sliderEase[v] = lerp(sliderEase.getOrDefault(v, target), target, 0.3f)
                    val se = sliderEase[v]!!
                    val barY = y + h - 5f
                    val rgb = themed(y)
                    ctx.drawRoundedRect(x, barY, x + se * inScaleSafe(), barY + 3.5f, 2f, alpha(rgb, a))
                }
            }
            else -> {
                ctx.drawString(font, name, (x + 5f).roundToInt(), ty, alpha(textColor, a).argb, false)
                val mode = choiceLabel(actual)
                ctx.drawString(
                    font, mode,
                    (x + w - 5f - font.width(mode)).roundToInt(), ty,
                    alpha(themed(y), a).argb, false,
                )
            }
        }
    }

    private fun inScaleSafe(): Float = getEaseAnim(scaleAnim).coerceIn(0.2f, 1f)
}
