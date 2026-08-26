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
        if (any == null) return "-"
        if (any is Boolean) return if (any) "ON" else "OFF"
        if (any is Number) {
            val d = any.toDouble()
            return if (d == d.toLong().toDouble()) any.toLong().toString() else "%.2f".format(d)
        }
        if (any is Enum<*>) return any.name
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
        if (s.contains("InputBind", true) || s.contains("KeyBinding", true)) return "None"
        return s.take(18).ifBlank { "-" }
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
        for (n in listOf("getRange", "range")) {
            val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
            when (val r = m?.invoke(v)) {
                is ClosedFloatingPointRange<*> -> {
                    val a = (r.start as? Number)?.toFloat() ?: continue
                    val b = (r.endInclusive as? Number)?.toFloat() ?: continue
                    if (b > a) return a to b
                }
                is IntRange -> if (r.last > r.first) return r.first.toFloat() to r.last.toFloat()
            }
        }
        fun num(names: List<String>): Float? {
            for (n in names) {
                val m = v.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.equals(n, true) }
                val r = m?.invoke(v)
                if (r is Number) return r.toFloat()
            }
            return null
        }
        val minV = num(listOf("getMinimum", "getMin", "minimum", "min")) ?: return null
        val maxV = num(listOf("getMaximum", "getMax", "maximum", "max")) ?: return null
        if (maxV <= minV) return null
        return minV to maxV
    }

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
        val actual = getActual(v)
        when {
            actual is Boolean -> if (button == 0) trySet(v, !actual)
            actual is Number -> {
                if (button == 0 || button == 2) {
                    sliderDrag = v
                    applySlider(v, colX, colX + catWidth, button == 2)
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
        val range = rangeOf(v) ?: return
        val (minV, maxV) = range
        var nv = ((mouseX - x1) / (x2 - x1).coerceAtLeast(1f) * (maxV - minV) + minV).coerceIn(minV, maxV)
        if (mid) {
            val step = midclickRound.coerceAtLeast(0.01f)
            nv = (nv / step).roundToInt() * step
        }
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
        if (!panelGlow || anim < 0.01f) return
        val layers = panelGlowLayers.coerceIn(2, 12)
        val maxR = panelGlowRadius.coerceAtLeast(1f)
        val strength = panelGlowStrength.coerceIn(0.05f, 1.2f)
        val soft = panelGlowSoft.coerceIn(0.5f, 3f)
        val base = if (panelGlowTheme) themed(x + y) else panelGlowColor
        for (i in layers downTo 1) {
            val u = i / layers.toFloat()
            val expand = maxR * u
            val gauss = kotlin.math.exp((-(u * u) * (2.6f / soft)).toDouble()).toFloat()
            val aa = (gauss * strength * 90f * anim).toInt().coerceIn(0, 120)
            if (aa < 2) continue
            ctx.drawRoundedRect(
                x - expand, y - expand,
                x + w + expand, y + h + expand,
                cornerRadius + expand * 0.35f,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
    }

    /** 模块列表底部圆角（仅下两角） */
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
            val px = catPos.getOrNull(dragIdx.coerceAtLeast(0))?.x ?: catPos.firstOrNull()?.x ?: return@let
            applySlider(it, px, px + catWidth, false)
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
                    p.scrollTarget = (p.scrollTarget + sd * catHeight).coerceAtLeast(0f)
                }
            }
            p.scroll = lerp(p.scroll, p.scrollTarget, (dt * 10.5f).coerceIn(0f, 1f))

            // 预估整列高度（标题 + 模块 + 展开设置）
            var estH = catHeight
            for (mod in modList) {
                estH += rowH
                val ca = modAnim[mod] ?: if (modOpen[mod] == true) 1f else 0f
                if (ca > 0.01f) {
                    for (v in collectValues(mod)) {
                        estH += rowH * ca
                        if (enumOpen[v] == true) estH += listChoices(v).size * rowH * ca
                    }
                }
            }
            if (!p.extended) estH = catHeight

            val panelR = scaleRect(cx, cy, p.x, p.y, catWidth, estH, s)
            // 整表辉光
            drawPanelGlow(ctx, panelR[0], panelR[1], panelR[2], panelR[3], anim)

            val cr = scaleRect(cx, cy, p.x, p.y, catWidth, catHeight, s)
            // 标题栏：上圆角、下直角（与模块同宽像素对齐）
            val headerW = panelR[2]
            val headerX = panelR[0]
            drawHeaderTopRound(ctx, headerX, cr[1], headerW, cr[3], cornerRadius * s.coerceAtLeast(0.3f), a(bgCategory, anim))
            val catName = categoryLabel(cats[i])
            val tw = font.width(catName)
            // 文字在标题栏正中（基于缩放后矩形）
            ctx.text(
                font, catName,
                (headerX + (headerW - tw) / 2f).roundToInt(),
                (cr[1] + (cr[3] - 8) / 2f).roundToInt(),
                a(textMain, anim).argb, false,
            )

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
                if (mr[1] + mr[3] > cr[1] + cr[3] - 2f) {
                    // 无缝模块行（消除缩放浮点缝隙）
                    drawSeamlessRow(ctx, mr[0], mr[1], mr[2], mr[3], a(bgModule, anim))
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
                        if (sr[1] > cr[1] + cr[3] - 2f) {
                            drawSetting(ctx, font, v, sr[0], sr[1], sr[2], sr[3], anim * cAnim)
                            if (hover(sr[0], sr[1], sr[2], sr[3])) tooltip = displayName(v)
                        }
                        moduleY += rowH * cAnim

                        val eOpen = enumOpen[v] == true
                        if (eOpen) {
                            val cur = choiceLabel(getActual(v))
                            for (c in listChoices(v)) {
                                val er = scaleRect(cx, cy, p.x, p.y + catHeight + moduleY, catWidth, rowH, s)
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

            // 整列最底部两角改为圆角（盖住最后一行直角）
            if (p.extended && moduleY > 0f) {
                val foot = scaleRect(cx, cy, p.x, p.y + catHeight + moduleY - rowH, catWidth, rowH, s)
                val r = (cornerRadius * s.coerceAtLeast(0.3f)).coerceAtLeast(2f)
                // 只重绘底部圆角区域的左右下角
                val footColor = a(bgModule, anim)
                drawFooterBottomRound(ctx, panelR[0], foot[1], panelR[2], foot[3], r, footColor)
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

    private fun drawSetting(
        ctx: GuiGraphicsExtractor,
        font: Font,
        v: Value<*>,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
    ) {
        drawSeamlessRow(ctx, x, y, w, h, a(bgSetting, anim))
        val actual = getActual(v)
        val name = displayName(v)
        val ty = (y + (h - 8) / 2f).roundToInt()
        when {
            actual is Boolean -> {
                boolScale[v] = lerp(boolScale.getOrDefault(v, 0f), if (actual) 1f else 0f, 0.25f)
                ctx.text(font, name, (x + 5f).roundToInt(), ty, a(textMain, anim).argb, false)
                if (boolScale[v]!! > 0.01f) {
                    ctx.text(
                        font, "OK",
                        (x + w - 20f).roundToInt(), ty,
                        a(themed(y), anim * boolScale[v]!!).argb, false,
                    )
                }
            }
            actual is Number -> {
                ctx.text(font, name, (x + 5f).roundToInt(), (y + 2f).roundToInt(), a(textMain, anim).argb, false)
                val fv = actual.toFloat()
                val vs = if (abs(fv - fv.toInt()) < 1e-4f) fv.toInt().toString() else "%.2f".format(fv)
                ctx.text(font, vs, (x + w - 5f - font.width(vs)).roundToInt(), (y + 2f).roundToInt(), a(textDim, anim).argb, false)
                val range = rangeOf(v)
                if (range != null) {
                    val (minV, maxV) = range
                    val p = ((fv - minV) / (maxV - minV)).coerceIn(0f, 1f)
                    val target = p * w
                    sliderEase[v] = lerp(sliderEase.getOrDefault(v, target), target, 0.3f)
                    val se = sliderEase[v]!!
                    val barY = y + h - 5f
                    ctx.drawRoundedRect(x, barY, x + se, barY + 3.5f, 2f, a(themed(y), anim))
                }
                // 无 range 的数值：不画滑条，只显示纯数字（已在上方）
            }
            else -> {
                // Bind / 文本 / 枚举：清理多余 [xxx]
                ctx.text(font, name, (x + 5f).roundToInt(), ty, a(textMain, anim).argb, false)
                var mode = choiceLabel(actual)
                mode = mode.replace(Regex("""\[[^\]]*\]"""), "").trim()
                if (mode.length > 16) mode = mode.take(14) + ".."
                if (mode.isBlank() || mode == "-" || mode.contains("InputBind", true) ||
                    mode.contains("KeyBinding", true) || mode.contains("net.minecraft", true)
                ) {
                    mode = "None"
                }
                ctx.text(
                    font, mode,
                    (x + w - 5f - font.width(mode)).roundToInt(), ty,
                    a(themed(y), anim).argb, false,
                )
            }
        }
    }
}
