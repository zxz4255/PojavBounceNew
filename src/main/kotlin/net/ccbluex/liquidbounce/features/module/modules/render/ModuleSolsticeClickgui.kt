/*
 * ModuleSolsticeClickgui — Modern 分类列 ClickGUI
 * LiquidBounce Nextgen 0.39 兼容 API（GuiGraphicsExtractor / getModules / gui.setScreen）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

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
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private val catWidth by float("Category Width", 180f, 100f..320f)
    private val catHeight by float("Category Height", 28f, 18f..48f)
    private val catGap by float("Category Gap", 28f, 8f..80f)
    private val rowH by float("Row Height", 26f, 16f..44f)
    private val cornerRadius by float("Corner Radius", 10f, 0f..18f)
    private val midclickRound by float("Midclick Rounding", 1f, 0.01f..1f)
    private val themeA by color("Theme A", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val themeB by color("Theme B", Color4b(0xE9, 0xA8, 0xBC, 255))
    private val themeCycle by float("Theme Cycle Sec", 4f, 1f..12f)
    private val bgModule by color("Module BG", Color4b(30, 30, 30, 255))
    private val bgCategory by color("Category BG", Color4b(24, 24, 24, 255))
    private val bgSetting by color("Setting BG", Color4b(30, 30, 30, 255))
    private val textMain by color("Text", Color4b(255, 255, 255, 255))
    private val textDim by color("Text Dim", Color4b(180, 180, 180, 255))

    // 分类表四周辉光
    private val panelGlow by boolean("Panel Glow", true)
    private val panelGlowRadius by float("Glow Radius", 8f, 2f..24f)
    private val panelGlowLayers by int("Glow Layers", 5, 2..12)
    private val panelGlowStrength by float("Glow Strength", 0.55f, 0.05f..1.2f)
    private val panelGlowSoft by float("Glow Softness", 1.4f, 0.5f..3f)
    private val panelGlowColor by color("Glow Color", Color4b(110, 180, 255, 255))
    private val panelGlowTheme by boolean("Glow Use Theme", true)

    private class CatPos(
        var x: Float = 0f,
        var y: Float = 0f,
        var dragging: Boolean = false,
        var extended: Boolean = true,
        var scroll: Float = 0f,
        var scrollTarget: Float = 0f,
    )

    private val catPos = ArrayList<CatPos>()
    private var cats: List<ModuleCategory> = emptyList()
    private var dragIdx = -1
    private var dragOx = 0f
    private var dragOy = 0f

    private val modOpen = IdentityHashMap<ClientModule, Boolean>()
    private val modAnim = IdentityHashMap<ClientModule, Float>()
    private val modScale = IdentityHashMap<ClientModule, Float>()
    private val enumOpen = IdentityHashMap<Value<*>, Boolean>()
    private val sliderEase = IdentityHashMap<Value<*>, Float>()
    private val boolScale = IdentityHashMap<Value<*>, Float>()
    private val groupOpen = IdentityHashMap<Value<*>, Boolean>()
    private var sliderRectX = 0f
    private var sliderRectW = 1f
    private var dualSliderDrag: Value<*>? = null // 拖双端: 负=min 正=max
    private var dualWhich = 0 // 0=min 1=max

    private var sliderDrag: Value<*>? = null
    private var binding: ClientModule? = null
    private var tooltip = ""
    private var openAnim = 0f
    private var scaleAnim = 0f
    private var lastNs = 0L
    private var mouseX = 0f
    private var mouseY = 0f
    private var scrollDir = 0
    private var positionsReady = false

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
        return (2.0.pow((-10 * x).toDouble()) * kotlin.math.sin((x * 10 - 0.75) * (2 * Math.PI) / 3) + 1).toFloat()
    }

    private fun inScale(): Float {
        val p = scaleAnim
        return when (animMode) {
            AnimMode.ZOOM -> easeOutExpo(p).coerceIn(0f, 0.996f)
            AnimMode.BOUNCE -> if (enabled) easeOutElastic(p) else easeOutBack(p)
        }
    }

    private fun themed(seed: Float): Color4b {
        val t = ((System.currentTimeMillis() % (themeCycle * 1000).toLong()) / (themeCycle * 1000f) + seed * 0.01f) % 1f
        val s = (sin(t * Math.PI * 2).toFloat() * 0.5f + 0.5f)
        return Color4b(
            lerp(themeA.r.toFloat(), themeB.r.toFloat(), s).toInt().coerceIn(0, 255),
            lerp(themeA.g.toFloat(), themeB.g.toFloat(), s).toInt().coerceIn(0, 255),
            lerp(themeA.b.toFloat(), themeB.b.toFloat(), s).toInt().coerceIn(0, 255),
            255,
        )
    }

    private fun a(c: Color4b, mul: Float) = c.alpha((c.a * mul).toInt().coerceIn(0, 255))

    private fun categoryLabel(cat: ModuleCategory): String {
        // 优先可读名 Combat / Movement ...
        runCatching {
            for (n in listOf("getReadableName", "readableName", "getDisplayName", "getName", "name")) {
                val m = cat.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                } ?: continue
                val r = m.invoke(cat) ?: continue
                if (r is String && r.isNotBlank() && !r.contains('@') && r.length < 24) {
                    return r.replaceFirstChar { it.uppercase() }
                }
            }
        }
        val s = cat.toString()
            .substringAfterLast('.')
            .substringAfterLast('$')
            .substringBefore('@')
            .trim()
        // ModuleCategories.COMBAT → Combat
        return s.replace('_', ' ')
            .lowercase()
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Misc" }
    }


    private fun hover(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    private fun guiMX() =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()

    private fun guiMY() =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()

    private fun allModules(): List<ClientModule> {
        val fromMgr = runCatching {
            ModuleManager.getModules().toList()
        }.getOrNull()
        if (fromMgr != null) return fromMgr
        return runCatching {
            val f = ModuleManager.javaClass.getDeclaredField("modules")
            f.isAccessible = true
            (f.get(ModuleManager) as? Collection<*>)?.filterIsInstance<ClientModule>() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun allCategories(): List<ModuleCategory> {
        return allModules().map { it.category }.distinct().sortedBy { it.toString() }
    }

    private fun modulesIn(cat: ModuleCategory): List<ClientModule> {
        return allModules()
            .filter { it.category == cat && it !== this@ModuleSolsticeClickgui }
            .sortedBy { it.name }
    }

    private fun collectValues(mod: ClientModule): List<Value<*>> {
        return try {
            mod.collectValuesRecursively().toList()
        } catch (_: Exception) {
            emptyList()
        }
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
        var s = (raw ?: v.name).trim()
        // 去掉 [xxx] / (xxx) / 多余英文后缀
        s = s.replace(Regex("""\[[^\]]*\]"""), "")
        s = s.replace(Regex("""\([^)]*\)"""), "")
        s = s.substringBefore(" - ").substringBefore(" | ")
        // bind / keybind 统一短名
        val lower = s.lowercase()
        if (lower.contains("bind") || lower == "key" || lower.endsWith("keybind")) s = "Bind"
        s = s.trim().trimEnd('.', '-', ':')
        return s.take(20).ifBlank { "Setting" }
    }

    private fun choiceLabel(any: Any?): String {
        if (any == null) return "None"
        if (any is Boolean) return if (any) "ON" else "OFF"
        if (any is Number) {
            // 键码 -1 / GLFW_KEY_UNKNOWN
            if (any.toInt() == -1 || any.toInt() == 0 || any.toInt() == GLFW.GLFW_KEY_UNKNOWN) return "None"
            val d = any.toDouble()
            return if (d == d.toLong().toDouble()) any.toLong().toString() else "%.2f".format(d)
        }
        if (any is Enum<*>) {
            val n = any.name
            if (n.equals("UNKNOWN", true) || n.equals("NONE", true) || n.equals("UNBOUND", true)) return "None"
            return n
        }
        runCatching {
            for (n in listOf("getChoiceName", "getName", "getTag", "getDisplayName")) {
                val m = any.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                    ?: continue
                val r = m.invoke(any)
                if (r is String && r.isNotBlank()) return r.substringAfterLast('.').take(18)
            }
        }
        var s = any.toString().substringAfterLast('.').substringBefore('@')
        s = s.replace(Regex("""\[[^\]]*\]"""), "").trim()
        if (s.contains("InputBind", true) || s.contains("KeyBinding", true) ||
            s.equals("UNKNOWN", true) || s.equals("None", true) || s.contains("unknown", true)
        ) return "None"
        return s.take(18).ifBlank { "None" }
    }

    private fun listChoices(v: Value<*>): List<Any?> {
        val actual = getActual(v)
        if (actual is Enum<*>) {
            return actual.javaClass.enumConstants?.toList() ?: emptyList()
        }
        for (n in listOf("getChoices", "choices", "getModes", "modes", "getActiveChoices", "entries")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) } ?: continue
            when (val r = m.invoke(v)) {
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
        // 1) 实际值本身就是区间（部分 intRange 会如此）
        when (val act = getActual(v)) {
            is IntRange -> if (act.last > act.first) {
                // 这是当前选中区间，外层 bounds 另取；先返回外层
            }
            is ClosedFloatingPointRange<*> -> { /* 见下 */ }
        }
        // 2) getRange / range 字段
        for (n in listOf("getRange", "range", "getBounds", "bounds")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
            when (val r = runCatching { m?.invoke(v) }.getOrNull()) {
                is ClosedFloatingPointRange<*> -> {
                    val a = (r.start as? Number)?.toFloat()
                    val b = (r.endInclusive as? Number)?.toFloat()
                    if (a != null && b != null && b > a) return a to b
                }
                is IntRange -> if (r.last > r.first) return r.first.toFloat() to r.last.toFloat()
                is ClosedRange<*> -> {
                    val a = (r.start as? Number)?.toFloat()
                    val b = (r.endInclusive as? Number)?.toFloat()
                    if (a != null && b != null && b > a) return a to b
                }
            }
        }
        // 3) 字段扫描
        runCatching {
            for (f in v.javaClass.declaredFields) {
                f.isAccessible = true
                val r = f.get(v) ?: continue
                when (r) {
                    is ClosedFloatingPointRange<*> -> {
                        val a = (r.start as? Number)?.toFloat() ?: continue
                        val b = (r.endInclusive as? Number)?.toFloat() ?: continue
                        if (b > a) return a to b
                    }
                    is IntRange -> if (r.last > r.first) return r.first.toFloat() to r.last.toFloat()
                }
            }
        }
        fun num(names: List<String>): Float? {
            for (n in names) {
                val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                val r = runCatching { m?.invoke(v) }.getOrNull()
                if (r is Number) return r.toFloat()
                runCatching {
                    val f = v.javaClass.getDeclaredField(n)
                    f.isAccessible = true
                    val x = f.get(v)
                    if (x is Number) return x.toFloat()
                }
            }
            return null
        }
        val minV = num(listOf("getMinimum", "getMin", "minimum", "min", "from", "getFrom")) ?: return null
        val maxV = num(listOf("getMaximum", "getMax", "maximum", "max", "to", "getTo")) ?: return null
        if (maxV <= minV) return null
        return minV to maxV
    }

    /** CPS 等双端区间：当前值为 IntRange / ClosedRange */
    private fun dualRangeValue(v: Value<*>): Pair<Float, Float>? {
        when (val act = getActual(v)) {
            is IntRange -> return act.first.toFloat() to act.last.toFloat()
            is ClosedFloatingPointRange<*> -> {
                val a = (act.start as? Number)?.toFloat() ?: return null
                val b = (act.endInclusive as? Number)?.toFloat() ?: return null
                return a to b
            }
            is ClosedRange<*> -> {
                val a = (act.start as? Number)?.toFloat() ?: return null
                val b = (act.endInclusive as? Number)?.toFloat() ?: return null
                return a to b
            }
        }
        // 名称暗示 CPS 且有 range
        val n = displayName(v).lowercase()
        if (n.contains("cps") || n.contains("range") || n.contains("delay")) {
            // 有的实现用两个字段 value/minValue
            val a = runCatching {
                v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getMinValue", true) }
                    ?.invoke(v) as? Number
            }.getOrNull()?.toFloat()
            val b = runCatching {
                v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals("getMaxValue", true) }
                    ?.invoke(v) as? Number
            }.getOrNull()?.toFloat()
            if (a != null && b != null && b >= a) return a to b
        }
        return null
    }

    private fun setDualRange(v: Value<*>, minV: Float, maxV: Float) {
        val lo = min(minV, maxV)
        val hi = max(minV, maxV)
        if (trySet(v, lo.toInt()..hi.toInt())) return
        if (trySet(v, lo..hi)) return
        runCatching {
            for (n in listOf("set", "setValue", "setRange")) {
                for (m in v.javaClass.methods) {
                    if (m.parameterCount != 1 || !m.name.equals(n, true)) continue
                    try {
                        m.invoke(v, lo.toInt()..hi.toInt()); return
                    } catch (_: Exception) {
                    }
                    try {
                        m.invoke(v, lo..hi); return
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun nestedValues(v: Value<*>): List<Value<*>> {
        for (n in listOf("getInner", "inner", "getValues", "values", "getChildren", "children", "collectValuesRecursively")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) } ?: continue
            val r = runCatching { m.invoke(v) }.getOrNull() ?: continue
            when (r) {
                is Collection<*> -> return r.filterIsInstance<Value<*>>()
                is Array<*> -> return r.filterIsInstance<Value<*>>()
            }
        }
        return emptyList()
    }

    private fun isGroup(v: Value<*>): Boolean = nestedValues(v).isNotEmpty()

    private fun cycleChoice(v: Value<*>): Boolean {
        for (n in listOf("next", "cycle", "selectNext")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) } ?: continue
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

    private fun modDesc(mod: ClientModule): String {
        return runCatching {
            val d: Any? = mod.description
            // LB: description 多为 Supplier<String?>
            if (d is java.util.function.Supplier<*>) {
                d.get()?.toString()?.ifBlank { mod.name } ?: mod.name
            } else {
                d?.toString()?.ifBlank { mod.name } ?: mod.name
            }
        }.getOrDefault(mod.name)
    }

    private fun initPositions(sw: Float) {
        cats = allCategories()
        catPos.clear()
        val total = cats.size * (catWidth + catGap)
        var x = sw / 2f - total / 2f
        for (i in cats.indices) {
            catPos.add(CatPos(x = (x / 2f).roundToInt() * 2f, y = catGap * 2f))
            x += catWidth + catGap
        }
        positionsReady = true
    }

    private fun openLayer() {
        try {
            mc.gui.setScreen(SolsticeScreen())
        } catch (_: Throwable) {
            try {
                mc.execute { mc.gui.setScreen(SolsticeScreen()) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeLayer() {
        try {
            if (mc.gui.screen() is SolsticeScreen) mc.gui.setScreen(null)
        } catch (_: Throwable) {
            try {
                mc.execute { mc.gui.setScreen(null) }
            } catch (_: Throwable) {
            }
        }
    }

    private class SolsticeScreen : Screen(Component.literal("SolsticeClickGui")) {
        override fun isPauseScreen() = false
        override fun shouldCloseOnEsc() = false

        override fun onClose() {
            if (ModuleSolsticeClickgui.enabled) ModuleSolsticeClickgui.enabled = false
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            val key = event.key()
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (ModuleSolsticeClickgui.binding != null) {
                    ModuleSolsticeClickgui.binding = null
                } else {
                    ModuleSolsticeClickgui.enabled = false
                    ModuleSolsticeClickgui.closeLayer()
                }
                return true
            }
            val mod = ModuleSolsticeClickgui.binding
            if (mod != null) {
                runCatching {
                    val b = mod.bind
                    val m = b.javaClass.methods.firstOrNull {
                        it.parameterCount == 1 && (
                            it.name.contains("setKey", true) || it.name == "set"
                            )
                    }
                    if (m != null) {
                        m.invoke(b, if (key == GLFW.GLFW_KEY_ESCAPE) GLFW.GLFW_KEY_UNKNOWN else key)
                    } else {
                        val f = b.javaClass.declaredFields.firstOrNull {
                            it.name.contains("key", true) || it.type == Int::class.javaPrimitiveType
                        }
                        f?.isAccessible = true
                        f?.setInt(b, if (key == GLFW.GLFW_KEY_ESCAPE) GLFW.GLFW_KEY_UNKNOWN else key)
                    }
                }
                ModuleSolsticeClickgui.binding = null
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
            ModuleSolsticeClickgui.scrollDir = if (v > 0) -1 else if (v < 0) 1 else 0
            return true
        }
    }

    private fun onMouse(button: Int, pressed: Boolean) {
        mouseX = guiMX()
        mouseY = guiMY()
        if (!pressed) {
            if (button == 0) {
                dragIdx = -1
                catPos.forEach { it.dragging = false }
                sliderDrag = null
                dualSliderDrag = null
            }
            return
        }
        for (i in catPos.indices) {
            val p = catPos[i]
            if (hover(p.x, p.y, catWidth, catHeight)) {
                if (button == 0) {
                    p.dragging = true
                    dragIdx = i
                    dragOx = mouseX - p.x
                    dragOy = mouseY - p.y
                    return
                }
                if (button == 1) {
                    p.extended = !p.extended
                    return
                }
            }
        }
        for (i in catPos.indices) {
            if (clickCategory(i, button)) return
        }
    }

    private fun clickCategory(i: Int, button: Int): Boolean {
        if (i !in cats.indices || i !in catPos.indices) return false
        val p = catPos[i]
        if (!p.extended) return false
        val mods = modulesIn(cats[i])
        var y = p.y + catHeight - p.scroll
        for (mod in mods) {
            if (hover(p.x, y, catWidth, rowH)) {
                when (button) {
                    0 -> mod.enabled = !mod.enabled
                    1 -> if (collectValues(mod).isNotEmpty()) {
                        modOpen[mod] = !(modOpen[mod] ?: false)
                    }
                    2 -> binding = mod
                }
                return true
            }
            y += rowH
            val open = modOpen[mod] == true
            val anim = modAnim[mod] ?: 0f
            if (open || anim > 0.05f) {
                for (v in collectValues(mod)) {
                    if (hover(p.x, y, catWidth, rowH)) {
                        clickValue(v, button, p.x)
                        return true
                    }
                    y += rowH * anim
                    if (enumOpen[v] == true) {
                        for (c in listChoices(v)) {
                            if (hover(p.x, y, catWidth, rowH)) {
                                if (button == 0) {
                                    trySet(v, c!!)
                                    enumOpen[v] = false
                                }
                                return true
                            }
                            y += rowH
                        }
                    }
                }
            }
        }
        return false
    }

    private fun clickValue(v: Value<*>, button: Int, colX: Float) {
        // 分组：左键展开/收起，右键强制收起
        if (isGroup(v)) {
            if (button == 0) groupOpen[v] = !(groupOpen[v] ?: false)
            if (button == 1) groupOpen[v] = false
            return
        }
        val actual = getActual(v)
        when {
            actual is Boolean -> if (button == 0) trySet(v, !actual)
            dualRangeValue(v) != null -> {
                if (button == 0 || button == 2) {
                    sliderDrag = v
                    dualSliderDrag = v
                    val bounds = rangeOf(v)
                    val dual = dualRangeValue(v)
                    if (bounds != null && dual != null) {
                        val (bMin, bMax) = bounds
                        val (cMin, cMax) = dual
                        val span = sliderRectW.coerceAtLeast(1f)
                        val t = ((mouseX - sliderRectX) / span).coerceIn(0f, 1f)
                        val pos = bMin + t * (bMax - bMin)
                        // 靠近哪端拖哪端
                        dualWhich = if (abs(pos - cMin) <= abs(pos - cMax)) 0 else 1
                    }
                    applySlider(v, sliderRectX, sliderRectX + sliderRectW, button == 2)
                }
            }
            actual is Number || rangeOf(v) != null -> {
                if (button == 0 || button == 2) {
                    sliderDrag = v
                    dualSliderDrag = null
                    applySlider(v, sliderRectX, sliderRectX + sliderRectW, button == 2)
                }
            }
            actual is Enum<*> || listChoices(v).isNotEmpty() -> {
                if (button == 0) {
                    if (!cycleChoice(v)) enumOpen[v] = true
                } else if (button == 1) {
                    enumOpen[v] = !(enumOpen[v] ?: false)
                }
            }
            else -> if (button == 0) cycleChoice(v)
        }
    }

    private fun applySlider(v: Value<*>, x1: Float, x2: Float, mid: Boolean) {
        // 双端 CPS
        val dual = dualRangeValue(v)
        val bounds = rangeOf(v)
        if (dual != null && bounds != null) {
            val (bMin, bMax) = bounds
            val (cMin, cMax) = dual
            val span = (x2 - x1).coerceAtLeast(1f)
            val t = ((mouseX - x1) / span).coerceIn(0f, 1f)
            var nv = bMin + t * (bMax - bMin)
            if (mid) {
                val step = midclickRound.coerceAtLeast(0.01f)
                nv = (nv / step).roundToInt() * step
            }
            if (dualWhich == 0) {
                setDualRange(v, nv.coerceIn(bMin, cMax), cMax)
            } else {
                setDualRange(v, cMin, nv.coerceIn(cMin, bMax))
            }
            return
        }
        val range = bounds ?: return
        val (minV, maxV) = range
        if (maxV <= minV) return
        val span = (x2 - x1).coerceAtLeast(1f)
        // 使用屏幕坐标比例，避免缩放到顶死
        var t = ((mouseX - x1) / span).coerceIn(0f, 1f)
        var nv = minV + t * (maxV - minV)
        if (mid) {
            val step = midclickRound.coerceAtLeast(0.01f)
            nv = (nv / step).roundToInt() * step
        }
        nv = nv.coerceIn(minV, maxV)
        // 拖动时同步 ease，避免弹到最大后卡死
        sliderEase[v] = t * span
        when (val actual = getActual(v)) {
            is Float -> trySet(v, nv)
            is Double -> trySet(v, nv.toDouble())
            is Int -> trySet(v, nv.roundToInt())
            is Long -> trySet(v, nv.toLong())
            else -> trySet(v, nv)
        }
    }

    override fun onEnabled() {
        openAnim = 0f
        scaleAnim = 0f
        positionsReady = false
        binding = null
        openLayer()
    }

    override fun onDisabled() {
        closeLayer()
        sliderDrag = null
        binding = null
        dragIdx = -1
    }



    private fun drawPanelGlow(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
    ) {
        if (!panelGlow || anim < 0.01f || w < 2f || h < 2f) return
        val strength = panelGlowStrength.coerceIn(0.05f, 1.2f)
        val soft = panelGlowSoft.coerceIn(0.5f, 3f)
        val maxR = panelGlowRadius.coerceAtLeast(1f)
        val base = if (panelGlowTheme) themed(x + y) else panelGlowColor
        // 自下而上连续渐变辉光（细条叠加逼近平滑，避免大块分层）
        val steps = (28 + panelGlowLayers * 2).coerceIn(24, 48)
        val glowH = h + maxR * 1.2f
        val bottom = y + h + maxR * 0.35f
        val top = y - maxR * 0.15f
        for (i in 0 until steps) {
            val t0 = i / steps.toFloat()
            val t1 = (i + 1) / steps.toFloat()
            // 0=底 1=顶：底部最亮，向上按指数衰减
            val mid = (t0 + t1) * 0.5f
            val fall = kotlin.math.exp((-(1f - mid) * (1f - mid) * (2.8f * soft)).toDouble()).toFloat()
            // 越靠近栏体左右越收一点，边缘更自然
            val sideInset = maxR * 0.15f * mid
            val aa = (fall * strength * 70f * anim).toInt().coerceIn(0, 90)
            if (aa < 2) continue
            val y0 = bottom - glowH * t1
            val y1 = bottom - glowH * t0
            ctx.drawQuad(
                x - maxR * 0.25f + sideInset,
                y0,
                x + w + maxR * 0.25f - sideInset,
                y1,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
        // 底部再补一圈很淡的外扩，增加「托底」感
        val baseR = cornerRadius.coerceAtLeast(2f)
        for (j in 1..4) {
            val u = j / 4f
            val expand = maxR * 0.35f * u
            val aa = ((1f - u) * strength * 28f * anim).toInt().coerceIn(0, 40)
            if (aa < 2) continue
            ctx.drawRoundedRect(
                x - expand * 0.5f,
                y + h - baseR,
                x + w + expand * 0.5f,
                y + h + expand,
                baseR + expand * 0.3f,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
    }

    private fun drawFooterBottomRound(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        radius: Float, color: Color4b,
    ) {
        val r = radius.coerceIn(0f, minOf(h / 2f, w / 2f))
        ctx.drawRoundedRect(x, y, x + w, y + h, r, color)
        // 盖住上半圆角 → 只留下圆角
        if (r > 0.5f) {
            ctx.drawQuad(x, y, x + w, y + r, color)
        }
    }

    /** 仅顶部圆角、底部直角的标题栏 */
    private fun drawHeaderTopRound(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        radius: Float, color: Color4b,
    ) {
        val r = radius.coerceIn(0f, minOf(h / 2f, w / 2f))
        // 先画四角圆角
        ctx.drawRoundedRect(x, y, x + w, y + h, r, color)
        // 用直角矩形盖住下半圆角，只留上圆角
        if (r > 0.5f) {
            ctx.drawQuad(x, y + h - r, x + w, y + h, color)
        }
    }

    /** 模块行：略加 1px 高度消除 scale 浮点缝隙 */
    private fun drawSeamlessRow(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        color: Color4b,
    ) {
        // floor 对齐 + 向下多画 1px 与下一行重叠，避免缝
        val x0 = kotlin.math.floor(x.toDouble()).toFloat()
        val y0 = kotlin.math.floor(y.toDouble()).toFloat()
        val x1 = kotlin.math.ceil((x + w).toDouble()).toFloat()
        val y1 = kotlin.math.ceil((y + h).toDouble()).toFloat() + 1f
        ctx.drawQuad(x0, y0, x1, y1, color)
    }

    private fun scaleRect(cx: Float, cy: Float, x: Float, y: Float, w: Float, h: Float, s: Float): FloatArray {
        // 统一缩放后像素对齐，避免标题栏/模块错位与缝隙
        val x1 = kotlin.math.floor((cx + (x - cx) * s).toDouble()).toFloat()
        val y1 = kotlin.math.floor((cy + (y - cy) * s).toDouble()).toFloat()
        val x2 = kotlin.math.floor((cx + (x + w - cx) * s).toDouble()).toFloat()
        val y2 = kotlin.math.floor((cy + (y + h - cy) * s).toDouble()).toFloat()
        return floatArrayOf(x1, y1, (x2 - x1).coerceAtLeast(1f), (y2 - y1).coerceAtLeast(1f))
    }

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
        val s = inScale()
        if (anim < 0.001f) return@handler

        mouseX = guiMX()
        mouseY = guiMY()
        val ctx = event.context
        val font = mc.font
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        val cx = sw / 2f
        val cy = sh / 2f

        if (!positionsReady || catPos.size != allCategories().size) initPositions(sw)

        if (dragIdx in catPos.indices && catPos[dragIdx].dragging) {
            val p = catPos[dragIdx]
            p.x = ((mouseX - dragOx).coerceIn(0f, sw - catWidth) / 2f).roundToInt() * 2f
            p.y = ((mouseY - dragOy).coerceIn(0f, sh - catHeight) / 2f).roundToInt() * 2f
        }
        sliderDrag?.let {
            applySlider(it, sliderRectX, sliderRectX + sliderRectW, false)
        }

        ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (255 * dimAlpha * anim).toInt().coerceIn(0, 200)))

        if (bottomGlow) {
            val firstH = lerp(sh, sh - sh / 3f, s)
            val col = themed(0f)
            for (i in 0 until 12) {
                val t0 = i / 12f
                val y0 = lerp(firstH, sh, t0)
                val y1 = lerp(firstH, sh, (i + 1) / 12f)
                val aa = (bottomGlowStrength * anim * (1f - t0) * 180f).toInt().coerceIn(0, 180)
                ctx.drawQuad(0f, y0, sw, y1, Color4b(col.r, col.g, col.b, aa))
            }
        }

        tooltip = ""
        val sd = scrollDir
        scrollDir = 0

        for (i in cats.indices) {
            if (i >= catPos.size) break
            val p = catPos[i]
            val modList = modulesIn(cats[i])
            val rgb = themed(i * 20f)

            if (p.extended && sd != 0) {
                val r = scaleRect(cx, cy, p.x, p.y, catWidth, catHeight + modList.size * rowH + 80f, s)
                if (hover(r[0], r[1], r[2], maxOf(r[3], 40f))) {
                    p.scrollTarget = (p.scrollTarget + sd * catHeight)
                    // 具体上下限在下面按 content 再 clamp
                }
            }
            p.scroll = lerp(p.scroll, p.scrollTarget, (dt * 10.5f).coerceIn(0f, 1f))

            // 内容总高度（不含滚动）
            var contentH = 0f
            for (mod in modList) {
                contentH += rowH
                val ca = modAnim[mod] ?: if (modOpen[mod] == true) 1f else 0f
                if (ca > 0.01f) {
                    for (v in collectValues(mod)) {
                        contentH += rowH * ca
                        if (isGroup(v) && groupOpen[v] == true) {
                            contentH += nestedValues(v).size * rowH * ca
                        }
                        if (enumOpen[v] == true) contentH += listChoices(v).size * rowH * ca
                    }
                }
            }
            // 可见列表高度上限（不超出屏幕）
            val maxListH = (sh - p.y - catHeight - 12f).coerceAtLeast(rowH * 3f)
            val listH = if (p.extended) min(contentH, maxListH) else 0f
            val panelH = catHeight + listH

            // 滚动边界
            val maxScroll = (contentH - listH).coerceAtLeast(0f)
            p.scrollTarget = p.scrollTarget.coerceIn(0f, maxScroll)
            p.scroll = p.scroll.coerceIn(0f, maxScroll)

            // 栏体矩形（固定，不随内部滚动变高度错位）
            val panelR = scaleRect(cx, cy, p.x, p.y, catWidth, panelH, s)
            val rPanel = (cornerRadius * s.coerceAtLeast(0.35f)).coerceAtLeast(3f)

            // 辉光：贴着栏体圆角外框（位置与栏一致）
            drawPanelGlow(ctx, panelR[0], panelR[1], panelR[2], panelR[3], anim)

            // 整栏圆角底（彻底圆角，含最底角）
            ctx.drawRoundedRect(
                panelR[0], panelR[1],
                panelR[0] + panelR[2], panelR[1] + panelR[3],
                rPanel, a(bgModule, anim),
            )

            // 标题栏：上圆角覆盖顶部
            val cr = scaleRect(cx, cy, p.x, p.y, catWidth, catHeight, s)
            val headerW = panelR[2]
            val headerX = panelR[0]
            drawHeaderTopRound(ctx, headerX, cr[1], headerW, cr[3], rPanel, a(bgCategory, anim))
            val catName = categoryLabel(cats[i])
            val tw = font.width(catName)
            ctx.text(
                font, catName,
                (headerX + (headerW - tw) / 2f).roundToInt(),
                (cr[1] + (cr[3] - 8) / 2f).roundToInt(),
                a(textMain, anim).argb, false,
            )

            // 列表裁剪区域（标题下沿 → 栏底）
            val clipTop = cr[1] + cr[3]
            val clipBot = panelR[1] + panelR[3]
            val clipLeft = panelR[0]
            val clipRight = panelR[0] + panelR[2]
            runCatching {
                // GuiGraphics / Extractor scissor
                val m = ctx.javaClass.methods.firstOrNull {
                    it.name.contains("enableScissor", true) && it.parameterCount in 4..5
                }
                if (m != null && m.parameterCount == 4) {
                    m.invoke(ctx, clipLeft.toInt(), clipTop.toInt(), clipRight.toInt(), clipBot.toInt())
                } else if (m != null) {
                    m.invoke(ctx, clipLeft.toInt(), clipTop.toInt(), (clipRight - clipLeft).toInt(), (clipBot - clipTop).toInt())
                }
            }

            if (!p.extended) continue

            var moduleY = -p.scroll
            for (mod in modList) {
                val targetOpen = if (modOpen[mod] == true) 1f else 0f
                modAnim[mod] = lerp(modAnim.getOrDefault(mod, 0f), targetOpen, (dt * 12.5f).coerceIn(0f, 1f))
                val cAnim = modAnim[mod]!!
                modScale[mod] = lerp(modScale.getOrDefault(mod, 0f), if (mod.enabled) 1f else 0f, (dt * 10f).coerceIn(0f, 1f))
                val cScale = modScale[mod]!!

                val mr = scaleRect(cx, cy, p.x, p.y + catHeight + moduleY, catWidth, rowH, s)
                // 强制与标题栏同宽对齐
                mr[0] = panelR[0]
                mr[2] = panelR[2]
                // 是否为列表视觉上的最后一行（内容滚到底）
                val isLastRow = (moduleY + rowH >= contentH - 0.5f)
                if (mr[1] + mr[3] > cr[1] + cr[3] - 2f && mr[1] < panelR[1] + panelR[3]) {
                    // 最后一行：直接画下圆角；其余行直角无缝
                    if (isLastRow) {
                        drawFooterBottomRound(
                            ctx, mr[0], mr[1], mr[2], mr[3] + 1f,
                            rPanel, a(bgModule, anim),
                        )
                    } else {
                        drawSeamlessRow(ctx, mr[0], mr[1], mr[2], mr[3], a(bgModule, anim))
                    }
                    if (cScale > 0.01f) {
                        val g1 = themed(moduleY * 2f)
                        val g2 = themed(moduleY * 2f + 40f)
                        for (seg in 0 until 8) {
                            val t0 = seg / 8f
                            val col = Color4b(
                                lerp(g1.r.toFloat(), g2.r.toFloat(), t0).toInt(),
                                lerp(g1.g.toFloat(), g2.g.toFloat(), t0).toInt(),
                                lerp(g1.b.toFloat(), g2.b.toFloat(), t0).toInt(),
                                (255 * anim * cScale).toInt().coerceIn(0, 255),
                            )
                            val sx = mr[0] + mr[2] * t0
                            val ex = mr[0] + mr[2] * ((seg + 1) / 8f)
                            ctx.drawQuad(sx, mr[1], ex, mr[1] + mr[3] + 1f, col)
                        }
                    }
                    val nc = if (mod.enabled) textMain else textDim
                    val nw = font.width(mod.name)
                    ctx.text(
                        font, mod.name,
                        (mr[0] + (mr[2] - nw) / 2f).roundToInt(),
                        (mr[1] + (mr[3] - 8) / 2f).roundToInt(),
                        a(nc, anim).argb, false,
                    )
                    if (hover(mr[0], mr[1], mr[2], mr[3])) tooltip = modDesc(mod)
                }
                moduleY += rowH

                if (cAnim > 0.001f) {
                    for (v in collectValues(mod)) {
                        val sr = scaleRect(cx, cy, p.x, p.y + catHeight + moduleY, catWidth, rowH, s)
                        sr[0] = panelR[0]
                        sr[2] = panelR[2]
                        val settingLast = (mod == modList.last() &&
                            moduleY + rowH * cAnim >= contentH - rowH * 0.5f)
                        if (sr[1] > cr[1] + cr[3] - 2f && sr[1] < panelR[1] + panelR[3]) {
                            if (settingLast) {
                                drawFooterBottomRound(
                                    ctx, sr[0], sr[1], sr[2], sr[3] + 1f,
                                    rPanel, a(bgSetting, anim * cAnim),
                                )
                                // 再画文字/控件（背景已圆角）
                                drawSetting(ctx, font, v, sr[0], sr[1], sr[2], sr[3], anim * cAnim, skipBg = true)
                            } else {
                                drawSetting(ctx, font, v, sr[0], sr[1], sr[2], sr[3], anim * cAnim)
                            }
                            if (hover(sr[0], sr[1], sr[2], sr[3])) tooltip = displayName(v)
                        }
                        moduleY += rowH * cAnim

                        // 分组子项：左键打开后显示
                        if (isGroup(v) && groupOpen[v] == true) {
                            for (child in nestedValues(v)) {
                                val cr2 = scaleRect(cx, cy, p.x, p.y + catHeight + moduleY, catWidth, rowH, s)
                                cr2[0] = panelR[0]
                                cr2[2] = panelR[2]
                                if (cr2[1] > cr[1] + cr[3] - 2f) {
                                    drawSetting(ctx, font, child, cr2[0] + 6f, cr2[1], cr2[2] - 6f, cr2[3], anim * cAnim)
                                }
                                moduleY += rowH * cAnim
                            }
                        }

                        val eOpen = enumOpen[v] == true
                        if (eOpen) {
                            val cur = choiceLabel(getActual(v))
                            for (c in listChoices(v)) {
                                val er = scaleRect(cx, cy, p.x, p.y + catHeight + moduleY, catWidth, rowH, s)
                                er[0] = panelR[0]
                                er[2] = panelR[2]
                                if (er[1] > cr[1] + cr[3] - 2f) {
                                    ctx.drawQuad(er[0], er[1], er[0] + er[2], er[1] + er[3], a(Color4b(20, 20, 20, 255), anim * cAnim))
                                    val lab = choiceLabel(c)
                                    if (lab == cur) {
                                        ctx.drawQuad(er[0], er[1], er[0] + 2f, er[1] + er[3], a(themed(moduleY), anim))
                                    }
                                    ctx.text(
                                        font, lab,
                                        (er[0] + 8f).roundToInt(),
                                        (er[1] + (er[3] - 8) / 2f).roundToInt(),
                                        a(textMain, anim * cAnim).argb, false,
                                    )
                                }
                                moduleY += rowH
                            }
                        }
                    }
                }
            }

            // 关闭裁剪
            runCatching {
                ctx.javaClass.methods.firstOrNull {
                    it.name.contains("disableScissor", true) && it.parameterCount == 0
                }?.invoke(ctx)
            }
        }

        binding?.let { tooltip = "Binding ${it.name}... ESC to cancel" }

        if (tooltip.isNotEmpty()) {
            val pad = 4f
            val tw2 = font.width(tooltip).toFloat()
            val th = 12f
            val tx = (mouseX + 10f).coerceAtMost(sw - tw2 - pad * 2)
            val ty = (mouseY - th - 6f).coerceAtLeast(2f)
            ctx.drawRoundedRect(tx, ty, tx + tw2 + pad * 2, ty + th + pad, 3f, Color4b(20, 20, 20, (230 * anim).toInt()))
            ctx.text(font, tooltip, (tx + pad).roundToInt(), (ty + pad / 2f).roundToInt(), a(textMain, anim).argb, false)
        }
    }


    /** 左名右值，防重叠 */
    private fun drawLabelValue(
        ctx: GuiGraphicsExtractor,
        font: Font,
        name: String,
        value: String,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
        nameColor: Color4b,
        valueColor: Color4b,
    ) {
        val pad = 5f
        val gap = 8f
        val maxNameW = (w * 0.58f).toInt().coerceAtLeast(20)
        var n = name
        while (n.length > 1 && font.width(n) > maxNameW) n = n.dropLast(1)
        if (n != name && n.isNotEmpty()) n = n.dropLast(0).let { if (it.length > 1) it.dropLast(1) + ".." else it }
        // 重新截断
        n = name
        while (n.length > 2 && font.width(n) > maxNameW) n = n.dropLast(1)
        if (n != name) n = n.dropLast(1) + ".."
        var v = value
        val maxValW = (w * 0.36f).toInt().coerceAtLeast(12)
        while (v.length > 1 && font.width(v) > maxValW) v = v.dropLast(1)
        if (v != value && v.isNotEmpty()) v = v.dropLast(1) + ".."
        val ty = (y + (h - 8) / 2f).roundToInt()
        ctx.text(font, n, (x + pad).roundToInt(), ty, a(nameColor, anim).argb, false)
        ctx.text(font, v, (x + w - pad - font.width(v)).roundToInt(), ty, a(valueColor, anim).argb, false)
    }

    private fun drawSetting(
        ctx: GuiGraphicsExtractor,
        font: Font,
        v: Value<*>,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
        skipBg: Boolean = false,
    ) {
        // 记录滑条命中区域（屏幕坐标）
        sliderRectX = x
        sliderRectW = w

        if (!skipBg) {
            drawSeamlessRow(ctx, x, y, w, h, a(bgSetting, anim))
        }
        val actual = getActual(v)
        val name = displayName(v)
        val ty = (y + (h - 8) / 2f).roundToInt()

        // 分组头
        if (isGroup(v)) {
            val open = groupOpen[v] == true
            drawLabelValue(ctx, font, name, if (open) "v" else ">", x, y, w, h, anim, textMain, textDim)
            return
        }

        when {
            actual is Boolean -> {
                boolScale[v] = lerp(boolScale.getOrDefault(v, 0f), if (actual) 1f else 0f, 0.25f)
                val mark = if (boolScale[v]!! > 0.5f) "ON" else "OFF"
                drawLabelValue(ctx, font, name, mark, x, y, w, h, anim, textMain, if (actual) themed(y) else textDim)
            }
            dualRangeValue(v) != null -> {
                // CPS 等双端滑条
                val dual = dualRangeValue(v)!!
                val bounds = rangeOf(v) ?: (0f to 20f)
                val (bMin, bMax) = bounds
                val (cMin, cMax) = dual
                val span = (bMax - bMin).coerceAtLeast(0.001f)
                val t0 = ((cMin - bMin) / span).coerceIn(0f, 1f)
                val t1 = ((cMax - bMin) / span).coerceIn(0f, 1f)
                val vs = "${cMin.toInt()}-${cMax.toInt()}"
                drawLabelValue(ctx, font, name, vs, x, y, w, h - 8f, anim, textMain, textDim)
                val barY = y + h - 6f
                ctx.drawQuad(x, barY, x + w, barY + 3f, a(Color4b(50, 50, 50, 255), anim))
                ctx.drawQuad(x + w * t0, barY, x + w * t1, barY + 3f, a(themed(y), anim))
                // 两端圆点
                ctx.drawRoundedRect(x + w * t0 - 3f, barY - 2f, x + w * t0 + 3f, barY + 5f, 3f, a(textMain, anim))
                ctx.drawRoundedRect(x + w * t1 - 3f, barY - 2f, x + w * t1 + 3f, barY + 5f, 3f, a(textMain, anim))
            }
            actual is Number || rangeOf(v) != null -> {
                val fv = when (actual) {
                    is Number -> actual.toFloat()
                    else -> 0f
                }
                val range = rangeOf(v)
                val vs = if (abs(fv - fv.toInt()) < 1e-4f) fv.toInt().toString() else "%.2f".format(fv)
                drawLabelValue(ctx, font, name, vs, x, y, w, h - 6f, anim, textMain, textDim)
                if (range != null) {
                    val (minV, maxV) = range
                    val span = (maxV - minV).coerceAtLeast(0.001f)
                    val p = ((fv - minV) / span).coerceIn(0f, 1f)
                    val target = p * w
                    // 拖动中不 lerp，避免冲到最大后卡住
                    if (sliderDrag === v) {
                        sliderEase[v] = target
                    } else {
                        sliderEase[v] = lerp(sliderEase.getOrDefault(v, target), target, 0.35f)
                    }
                    val se = sliderEase[v]!!.coerceIn(0f, w)
                    val barY = y + h - 5f
                    ctx.drawQuad(x, barY, x + w, barY + 3f, a(Color4b(50, 50, 50, 255), anim))
                    ctx.drawRoundedRect(x, barY, x + se, barY + 3.5f, 2f, a(themed(y), anim))
                }
            }
            else -> {
                var mode = choiceLabel(actual)
                mode = mode.replace(Regex("""\[[^\]]*\]"""), "").trim()
                val bad = mode.isBlank() || mode == "-" ||
                    mode.contains("InputBind", true) || mode.contains("KeyBinding", true) ||
                    mode.contains("net.minecraft", true) || mode.contains("unknown", true) ||
                    mode.equals("UNKNOWN", true)
                if (bad) mode = "None"
                drawLabelValue(ctx, font, name, mode, x, y, w, h, anim, textMain, themed(y))
            }
        }
    }
}
