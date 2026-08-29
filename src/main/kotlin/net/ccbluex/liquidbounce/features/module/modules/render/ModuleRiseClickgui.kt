/*
 * ModuleRiseClickgui —— Rise 风格 ClickGUI（修复：子项居中、Mode 可切换、点击不卡死）
 * LiquidBounce Nextgen 0.39
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ModuleRiseClickgui : ClientModule(
    "RiseClickGui",
    ModuleCategories.RENDER,
    bind = GLFW.GLFW_KEY_RIGHT_SHIFT,
    aliases = listOf("RiseClickGUI", "RiseGUI"),
) {
    private val colBg by color("Background", Color4b(23, 26, 33, 254))
    private val colSecondary by color("Secondary", Color4b(18, 20, 25, 255))
    private val colText by color("Text", Color4b(255, 255, 255, 255))
    private val colSecondaryText by color("Secondary Text", Color4b(255, 255, 255, 220))
    private val colTrinaryText by color("Trinary Text", Color4b(255, 255, 255, 130))
    private val colAccent by color("Accent", Color4b(0x56, 0xB4, 0xE9, 255))
    private val screenDim by boolean("Screen Dim", true)
    private val screenDimAlpha by int("Screen Dim Alpha", 28, 0..60)

    private val windowW by float("Window Width", 420f, 320f..640f)
    private val windowH by float("Window Height", 280f, 200f..480f)
    private val sidebarW by float("Sidebar Width", 100f, 70f..160f)
    private val moduleRowH by float("Module Row", 18f, 14f..26f)
    private val settingRowH by float("Setting Row", 16f, 12f..24f)
    private val animSpeed by float("Anim Speed", 12f, 4f..24f)

    private var winX = -1f
    private var winY = -1f
    private var selectedCat: ModuleCategory? = null
    private var expanded: ClientModule? = null
    private var scroll = 0f
    private var targetScroll = 0f
    private var scale = 0f
    private var opacity = 0f
    private var mouseX = 0f
    private var mouseY = 0f
    private var lastNs = 0L
    private var searchText = ""
    private var searchFocused = false
    private var sliderDrag: Value<*>? = null
    private var textEditValue: Value<*>? = null
    private var textEditBuf = ""
    private val enumOpen = IdentityHashMap<Value<*>, Boolean>()
    private var scrollBarDrag = false

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    private fun over(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    private fun guiMX() =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()
    private fun guiMY() =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()

    /** 垂直居中文字 y */
    private fun textMidY(rowY: Float, rowH: Float, font: Font): Int {
        val lh = font.lineHeight.toFloat().coerceAtLeast(8f)
        return (rowY + (rowH - lh) * 0.5f).roundToInt()
    }

    private fun allModules(): List<ClientModule> = try {
        ModuleManager.getModules().filter { !it.hidden }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun allCategories(): List<ModuleCategory> {
        val from = allModules().map { it.category }.distinct()
        val entries = try {
            ModuleCategories.entries.toList()
        } catch (_: Throwable) {
            emptyList()
        }
        val ordered = mutableListOf<ModuleCategory>()
        for (c in entries) if (c !in ordered) ordered += c
        for (c in from) if (c !in ordered) ordered += c
        return ordered
    }

    private fun catLabel(cat: ModuleCategory): String = try {
        cat.tag
    } catch (_: Throwable) {
        cat.toString().substringAfterLast('.').substringAfterLast('$')
    }

    private fun modulesIn(cat: ModuleCategory): List<ClientModule> {
        val q = searchText.trim().lowercase()
        return allModules()
            .filter { it.category == cat }
            .filter {
                q.isEmpty() || it.name.lowercase().contains(q) ||
                    it.aliases.any { a -> a.lowercase().contains(q) }
            }
            .sortedBy { it.name }
    }

    private fun modulesSearchAll(): List<ClientModule> {
        val q = searchText.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allModules().filter {
            it.name.lowercase().contains(q) || it.aliases.any { a -> a.lowercase().contains(q) }
        }.sortedBy { it.name }
    }

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
        while (obj is Value<*> && d < 4) {
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
        return (raw ?: v.name).replace(Regex("""\[[^\]]*\]"""), "").take(18).ifBlank { "Setting" }
    }

    private fun formatNumber(n: Number): String {
        val d = n.toDouble()
        if (!d.isFinite()) return "0"
        if (abs(d - d.toLong()) < 1e-6 && abs(d) < 1e12) return d.toLong().toString()
        return String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.').ifBlank { "0" }
    }

    private fun choiceLabel(any: Any?): String {
        if (any == null) return "-"
        if (any is Boolean) return if (any) "ON" else "OFF"
        if (any is Number) return formatNumber(any)
        if (any is Enum<*>) return any.name.lowercase().replaceFirstChar { it.uppercase() }
        runCatching {
            for (n in listOf("getTag", "getName", "getChoiceName", "getDisplayName", "tag", "name")) {
                val m = any.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                } ?: continue
                val r = m.invoke(any)
                if (r is String && r.isNotBlank() && r != "-") {
                    return r.substringAfterLast('.').substringBefore('@').take(20)
                }
            }
        }
        var s = any.toString().substringAfterLast('$').substringAfterLast('.').substringBefore('@')
        s = s.replace(Regex("""\[[^\]]*\]"""), "").trim()
        return s.take(20).ifBlank { "Mode" }
    }

    private fun listChoices(v: Value<*>): List<Any?> {
        val actual = getActual(v)
        if (actual is Enum<*>) {
            return actual.javaClass.enumConstants?.toList() ?: emptyList()
        }
        for (target in listOf<Any?>(v, actual)) {
            if (target == null) continue
            for (n in listOf("getChoices", "choices", "getModes", "modes", "entries", "values")) {
                val m = target.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                } ?: continue
                when (val r = runCatching { m.invoke(target) }.getOrNull()) {
                    is Collection<*> -> if (r.isNotEmpty()) return r.toList()
                    is Array<*> -> if (r.isNotEmpty()) return r.toList()
                }
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
                    m.invoke(v, value); return true
                } catch (_: Exception) {
                }
                try {
                    m.invoke(v, label); return true
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun cycleChoice(v: Value<*>): Boolean {
        for (n in listOf("next", "cycle", "selectNext")) {
            val m = v.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name.equals(n, true)
            } ?: continue
            if (runCatching { m.invoke(v); true }.getOrDefault(false)) return true
        }
        val list = listChoices(v)
        if (list.isEmpty()) return false
        val cur = getActual(v)
        val curL = choiceLabel(cur)
        var idx = list.indexOfFirst { it === cur || choiceLabel(it).equals(curL, true) }
        if (idx < 0) idx = 0
        val next = list[(idx + 1) % list.size] ?: return false
        return trySet(v, next) || trySet(v, choiceLabel(next))
    }

    private fun rangeOf(v: Value<*>): Pair<Float, Float>? {
        for (n in listOf("getRange", "range")) {
            val m = v.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name.equals(n, true)
            }
            when (val r = runCatching { m?.invoke(v) }.getOrNull()) {
                is ClosedFloatingPointRange<*> -> {
                    val a = (r.start as? Number)?.toFloat()
                    val b = (r.endInclusive as? Number)?.toFloat()
                    if (a != null && b != null && b > a) return a to b
                }
                is IntRange -> if (r.last > r.first) return r.first.toFloat() to r.last.toFloat()
            }
        }
        fun num(names: List<String>): Float? {
            for (n in names) {
                val m = v.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals(n, true)
                }
                val r = runCatching { m?.invoke(v) }.getOrNull()
                if (r is Number) return r.toFloat()
            }
            return null
        }
        val minV = num(listOf("getMinimum", "getMin", "minimum", "min"))
        val maxV = num(listOf("getMaximum", "getMax", "maximum", "max"))
        if (minV != null && maxV != null && maxV > minV) return minV to maxV
        val name = displayName(v).lowercase()
        when {
            "scale" in name -> return 0.1f to 5f
            "speed" in name -> return 0f to 10f
            "alpha" in name -> return 0f to 1f
            "size" in name || "width" in name || "height" in name -> return 0.1f to 20f
            "radius" in name || "range" in name -> return 0f to 64f
        }
        val cur = (getActual(v) as? Number)?.toFloat() ?: return null
        return when {
            cur in 0f..1.5f -> 0f to 2f
            cur in 0f..10f -> 0f to 20f
            else -> 0f to max(cur * 2f, 10f)
        }
    }

    private fun isTextLike(v: Value<*>, actual: Any?): Boolean {
        if (actual is Boolean || actual is Number || actual is Enum<*>) return false
        if (listChoices(v).isNotEmpty()) return false
        if (rangeOf(v) != null) return false
        if (actual is String) return true
        val n = displayName(v).lowercase()
        if (listOf("text", "block", "path", "file", "name", "string").any { it in n }) return true
        val sn = actual?.javaClass?.simpleName?.lowercase() ?: ""
        return "block" in sn || "identifier" in sn
    }

    private fun isColorValue(actual: Any?): Boolean {
        if (actual == null) return false
        if (actual is Color4b || actual is java.awt.Color) return true
        return actual.javaClass.simpleName.contains("Color", true)
    }

    private fun applySlider(v: Value<*>, x: Float, w: Float) {
        val range = rangeOf(v) ?: return
        val (minV, maxV) = range
        val t = ((mouseX - x) / w.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val nv = minV + t * (maxV - minV)
        val actual = getActual(v)
        when (actual) {
            is Int -> trySet(v, nv.roundToInt())
            is Long -> trySet(v, nv.toLong())
            is Double -> trySet(v, nv.toDouble())
            else -> trySet(v, nv)
        }
    }

    /** 安全处理点击：任何异常都不影响后续模块开关 */
    private fun handleSettingClick(v: Value<*>, x: Float, w: Float, button: Int) {
        try {
            val actual = getActual(v)
            when {
                actual is Boolean -> {
                    if (button == 0) trySet(v, !actual)
                }
                actual is Number || rangeOf(v) != null -> {
                    if (button == 0) {
                        sliderDrag = v
                        applySlider(v, x + 8f, w - 16f)
                    }
                }
                isColorValue(actual) -> {
                    // 简单循环 alpha / 略过完整色板
                    if (button == 0 && actual is Color4b) {
                        trySet(v, Color4b(actual.r, actual.g, actual.b, if (actual.a > 128) 80 else 255))
                    }
                }
                listChoices(v).isNotEmpty() || actual is Enum<*> -> {
                    if (button == 0) {
                        if (!cycleChoice(v)) enumOpen[v] = true
                    } else if (button == 1) {
                        enumOpen[v] = !(enumOpen[v] ?: false)
                    }
                }
                isTextLike(v, actual) -> {
                    if (button == 0) {
                        if (textEditValue == v) {
                            trySet(v, textEditBuf)
                            textEditValue = null
                        } else {
                            textEditValue = v
                            textEditBuf = if (actual is String) actual else choiceLabel(actual).let {
                                if (it == "-" || it == "Mode") "" else it
                            }
                        }
                    } else if (button == 1) {
                        textEditValue = null
                        textEditBuf = ""
                    }
                }
                else -> {
                    // 默认当 Mode 尝试切换
                    if (button == 0) {
                        if (!cycleChoice(v)) {
                            if (actual is String) {
                                textEditValue = v
                                textEditBuf = actual
                            } else {
                                enumOpen[v] = true
                            }
                        }
                    } else if (button == 1) {
                        enumOpen[v] = !(enumOpen[v] ?: false)
                    }
                }
            }
        } catch (_: Throwable) {
            // 吞掉异常，避免卡死后续点击
            sliderDrag = null
        }
    }

    private fun listClipTop() = winY + 52f
    private fun listClipBot() = winY + windowH - 8f

    // —— Screen ——
    private class RiseGuiScreen : Screen(Component.literal("RiseClickGui")) {
        override fun isPauseScreen() = false
        override fun shouldCloseOnEsc() = false

        override fun onClose() {
            if (ModuleRiseClickgui.enabled) ModuleRiseClickgui.enabled = false
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            val key = event.key()
            val self = ModuleRiseClickgui
            if (self.textEditValue != null) {
                when (key) {
                    GLFW.GLFW_KEY_ESCAPE -> {
                        self.textEditValue = null; self.textEditBuf = ""; return true
                    }
                    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        self.textEditValue?.let { self.trySet(it, self.textEditBuf) }
                        self.textEditValue = null; self.textEditBuf = ""; return true
                    }
                    GLFW.GLFW_KEY_BACKSPACE -> {
                        if (self.textEditBuf.isNotEmpty()) self.textEditBuf = self.textEditBuf.dropLast(1)
                        return true
                    }
                }
                return true
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (self.searchFocused) self.searchFocused = false
                else self.enabled = false
                return true
            }
            if (self.searchFocused && key == GLFW.GLFW_KEY_BACKSPACE) {
                if (self.searchText.isNotEmpty()) {
                    self.searchText = self.searchText.dropLast(1)
                    self.targetScroll = 0f
                }
                return true
            }
            return true
        }

        override fun charTyped(event: CharacterEvent): Boolean {
            val self = ModuleRiseClickgui
            val ch = try {
                event.codepoint().toChar()
            } catch (_: Throwable) {
                return true
            }
            if (ch.isISOControl()) return true
            if (self.textEditValue != null) {
                if (self.textEditBuf.length < 64) self.textEditBuf += ch
                return true
            }
            if (self.searchFocused && self.searchText.length < 48) {
                self.searchText += ch
                self.targetScroll = 0f
            }
            return true
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            ModuleRiseClickgui.onMouse(event.button(), true)
            return true
        }

        override fun mouseReleased(event: MouseButtonEvent): Boolean {
            ModuleRiseClickgui.onMouse(event.button(), false)
            return true
        }

        override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean = true
        override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
            if (v != 0.0) ModuleRiseClickgui.targetScroll =
                (ModuleRiseClickgui.targetScroll - v.toFloat() * 22f).coerceAtLeast(0f)
            return true
        }
    }

    private fun openLayer() {
        try {
            if (mc.gui.screen() !is RiseGuiScreen) mc.gui.setScreen(RiseGuiScreen())
        } catch (_: Throwable) {
            try {
                mc.execute { mc.gui.setScreen(RiseGuiScreen()) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeLayer() {
        try {
            if (mc.gui.screen() is RiseGuiScreen) mc.gui.setScreen(null)
        } catch (_: Throwable) {
        }
    }

    private fun onMouse(button: Int, pressed: Boolean) {
        mouseX = guiMX()
        mouseY = guiMY()
        if (!pressed) {
            sliderDrag = null
            scrollBarDrag = false
            return
        }
        if (scale < 0.4f) return

        // 搜索框
        val searchX = winX + sidebarW + 6f
        val searchY = winY + 28f
        val searchW = windowW - sidebarW - 14f
        if (button == 0 && over(searchX, searchY, searchW, 16f)) {
            searchFocused = true
            textEditValue = null
            return
        } else if (button == 0) {
            searchFocused = false
        }

        // 分类
        var cy = winY + 36f
        for (cat in allCategories()) {
            if (over(winX + 4f, cy, sidebarW - 8f, 20f)) {
                if (button == 0) {
                    selectedCat = cat
                    expanded = null
                    targetScroll = 0f
                    scroll = 0f
                }
                return
            }
            cy += 22f
        }

        val mods = if (searchText.isNotBlank()) modulesSearchAll()
        else modulesIn(selectedCat ?: return)
        val listX = winX + sidebarW + 6f
        val listW = windowW - sidebarW - 20f
        val clipTop = listClipTop()
        val clipBot = listClipBot()
        var y = clipTop - scroll

        for (mod in mods) {
            if (y + moduleRowH > clipTop && y < clipBot) {
                if (over(listX, y, listW, moduleRowH)) {
                    when (button) {
                        0 -> mod.enabled = !mod.enabled
                        1 -> if (collectValues(mod).isNotEmpty()) {
                            expanded = if (expanded == mod) null else mod
                            // 展开时清掉可能坏掉的状态
                            enumOpen.clear()
                            textEditValue = null
                            sliderDrag = null
                        }
                    }
                    return
                }
            }
            y += moduleRowH
            if (expanded == mod) {
                for (v in collectValues(mod)) {
                    if (y + settingRowH > clipTop && y < clipBot) {
                        if (over(listX, y, listW, settingRowH)) {
                            handleSettingClick(v, listX, listW, button)
                            return
                        }
                    }
                    y += settingRowH
                    if (enumOpen[v] == true) {
                        for (c in listChoices(v)) {
                            if (y + settingRowH > clipTop && y < clipBot) {
                                if (over(listX, y, listW, settingRowH) && button == 0) {
                                    try {
                                        trySet(v, c!!)
                                        enumOpen[v] = false
                                    } catch (_: Throwable) {
                                    }
                                    return
                                }
                            }
                            y += settingRowH
                        }
                    }
                }
            }
        }
    }

    override suspend fun enabledEffect() {
        if (selectedCat == null) selectedCat = allCategories().firstOrNull()
        expanded = null
        scroll = 0f
        targetScroll = 0f
        scale = 0f
        opacity = 0f
        enumOpen.clear()
        textEditValue = null
        openLayer()
    }

    override fun onDisabled() {
        closeLayer()
        sliderDrag = null
        searchFocused = false
        textEditValue = null
        enumOpen.clear()
    }

    @Suppress("unused")
    private val hotbarHandler = handler<MouseScrollInHotbarEvent> { e ->
        if (enabled || scale > 0.01f) runCatching { e.cancelEvent() }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { e ->
        if (!enabled && scale < 0.01f) return@handler
        if (e.keyCode == GLFW.GLFW_KEY_ESCAPE && e.action == 1) {
            if (searchFocused) searchFocused = false
            else if (textEditValue != null) {
                textEditValue = null; textEditBuf = ""
            } else if (enabled) enabled = false
        }
    }

    private fun drawSetting(
        ctx: GuiGraphicsExtractor,
        font: Font,
        v: Value<*>,
        x: Float, y: Float, w: Float, a: Int,
    ) {
        val actual = getActual(v)
        val ty = textMidY(y, settingRowH, font)
        val name = displayName(v)

        when {
            actual is Boolean -> {
                ctx.text(font, name, (x + 8f).roundToInt(), ty, colSecondaryText.alpha(a).argb, false)
                val mark = if (actual) "ON" else "OFF"
                val mc = if (actual) colAccent.alpha(a) else colTrinaryText.alpha(a)
                ctx.text(font, mark, (x + w - 8f - font.width(mark)).roundToInt(), ty, mc.argb, false)
            }
            actual is Number || rangeOf(v) != null -> {
                val fv = (actual as? Number)?.toFloat() ?: 0f
                val range = rangeOf(v) ?: (0f to 1f)
                val (minV, maxV) = range
                val p = ((fv - minV) / (maxV - minV).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                ctx.text(font, name, (x + 8f).roundToInt(), ty, colSecondaryText.alpha(a).argb, false)
                val vs = formatNumber(fv)
                ctx.text(font, vs, (x + w - 8f - font.width(vs)).roundToInt(), ty, colTrinaryText.alpha(a).argb, false)
                val barY = y + settingRowH - 4f
                ctx.drawQuad(x + 8f, barY, x + w - 8f, barY + 2f, Color4b(40, 40, 50, a))
                ctx.drawQuad(x + 8f, barY, x + 8f + (w - 16f) * p, barY + 2f, colAccent.alpha(a))
                if (sliderDrag === v) applySlider(v, x + 8f, w - 16f)
            }
            isColorValue(actual) -> {
                ctx.text(font, name, (x + 8f).roundToInt(), ty, colSecondaryText.alpha(a).argb, false)
                val c = when (actual) {
                    is Color4b -> actual
                    is java.awt.Color -> Color4b(actual.red, actual.green, actual.blue, actual.alpha)
                    else -> colAccent
                }
                ctx.drawRoundedRect(x + w - 22f, y + 3f, x + w - 6f, y + settingRowH - 3f, 3f, c.alpha(a))
            }
            else -> {
                ctx.text(font, name, (x + 8f).roundToInt(), ty, colSecondaryText.alpha(a).argb, false)
                val editing = textEditValue == v
                val mode = if (editing) {
                    textEditBuf + if ((System.currentTimeMillis() / 400) % 2L == 0L) "_" else ""
                } else {
                    val lab = choiceLabel(actual)
                    if (lab == "-" || lab.isBlank()) {
                        if (listChoices(v).isNotEmpty()) "Click" else if (isTextLike(v, actual)) "edit" else "Mode"
                    } else lab
                }
                val mc = if (editing) Color4b(120, 220, 255, a) else colAccent.alpha(a)
                ctx.text(
                    font, mode.take(28),
                    (x + w - 8f - font.width(mode.take(28))).roundToInt(),
                    ty, mc.argb, false,
                )
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled && scale < 0.05f) return@handler

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        mouseX = guiMX()
        mouseY = guiMY()
        scale = lerp(scale, if (enabled) 1f else 0f, (dt * animSpeed * 0.12f).coerceIn(0f, 1f))
        opacity = lerp(opacity, if (enabled) 1f else 0f, (dt * animSpeed * 0.15f).coerceIn(0f, 1f))
        if (scale < 0.02f) return@handler

        val ctx = event.context
        val font = mc.font
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        if (winX < 0f) {
            winX = (sw - windowW) / 2f
            winY = (sh - windowH) / 2f
        }

        val a = (255 * opacity).toInt().coerceIn(0, 255)
        if (screenDim) {
            ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (screenDimAlpha * opacity).toInt().coerceIn(0, 80)))
        }

        // 窗体
        ctx.drawRoundedRect(winX, winY, winX + windowW, winY + windowH, 6f, colBg.alpha(a))
        ctx.drawQuad(winX, winY, winX + windowW, winY + 26f, colSecondary.alpha(a))
        // 顶栏标题 Rise
        ctx.text(font, "Rise", (winX + 12f).roundToInt(), (winY + (26f - font.lineHeight) * 0.5f).roundToInt(), colText.alpha(a).argb, true)

        // 侧边分类
        var cy = winY + 36f
        for (cat in allCategories()) {
            val sel = cat == selectedCat
            if (sel) {
                ctx.drawRoundedRect(winX + 4f, cy, winX + sidebarW - 4f, cy + 18f, 4f, colAccent.alpha((a * 0.35f).toInt()))
            }
            val tc = if (sel) colText.alpha(a) else colTrinaryText.alpha(a)
            ctx.text(
                font, catLabel(cat).take(12),
                (winX + 10f).roundToInt(),
                textMidY(cy, 18f, font),
                tc.argb, false,
            )
            cy += 22f
        }

        // 搜索
        val searchX = winX + sidebarW + 6f
        val searchY = winY + 28f
        val searchW = windowW - sidebarW - 14f
        ctx.drawRoundedRect(searchX, searchY, searchX + searchW, searchY + 16f, 4f, colSecondary.alpha(a))
        val hint = if (searchText.isEmpty() && !searchFocused) "Search..." else searchText + if (searchFocused && (System.currentTimeMillis() / 400) % 2L == 0L) "_" else ""
        ctx.text(font, hint.take(40), (searchX + 6f).roundToInt(), textMidY(searchY, 16f, font), colTrinaryText.alpha(a).argb, false)

        // 列表
        if (selectedCat == null) selectedCat = allCategories().firstOrNull()
        val mods = if (searchText.isNotBlank()) modulesSearchAll() else modulesIn(selectedCat!!)
        val listX = winX + sidebarW + 6f
        val listW = windowW - sidebarW - 20f
        val clipTop = listClipTop()
        val clipBot = listClipBot()

        // content height
        var contentH = 0f
        for (mod in mods) {
            contentH += moduleRowH
            if (expanded == mod) {
                for (v in collectValues(mod)) {
                    contentH += settingRowH
                    if (enumOpen[v] == true) contentH += listChoices(v).size * settingRowH
                }
            }
        }
        val viewH = clipBot - clipTop
        val maxScroll = (contentH - viewH).coerceAtLeast(0f)
        targetScroll = targetScroll.coerceIn(0f, maxScroll)
        scroll = lerp(scroll, targetScroll, (dt * 14f).coerceIn(0f, 1f))

        var y = clipTop - scroll
        for (mod in mods) {
            if (y + moduleRowH > clipTop && y < clipBot) {
                if (mod.enabled) {
                    ctx.drawQuad(listX, y, listX + 2f, y + moduleRowH, colAccent.alpha(a))
                }
                val mc_ = if (mod.enabled) colText.alpha(a) else colSecondaryText.alpha(a)
                ctx.text(
                    font, mod.name.take(22),
                    (listX + 6f).roundToInt(),
                    textMidY(y, moduleRowH, font),
                    mc_.argb, false,
                )
                if (collectValues(mod).isNotEmpty()) {
                    val mark = if (expanded == mod) "-" else "+"
                    ctx.text(
                        font, mark,
                        (listX + listW - 12f).roundToInt(),
                        textMidY(y, moduleRowH, font),
                        colTrinaryText.alpha(a).argb, false,
                    )
                }
            }
            y += moduleRowH
            if (expanded == mod) {
                for (v in collectValues(mod)) {
                    if (y + settingRowH > clipTop && y < clipBot) {
                        ctx.drawQuad(listX, y, listX + listW, y + settingRowH, Color4b(0, 0, 0, (30 * opacity).toInt()))
                        drawSetting(ctx, font, v, listX, y, listW, a)
                    }
                    y += settingRowH
                    if (enumOpen[v] == true) {
                        val cur = choiceLabel(getActual(v))
                        for (c in listChoices(v)) {
                            if (y + settingRowH > clipTop && y < clipBot) {
                                val lab = choiceLabel(c)
                                val sel = lab.equals(cur, true)
                                if (sel) {
                                    ctx.drawQuad(listX, y, listX + 2f, y + settingRowH, colAccent.alpha(a))
                                }
                                ctx.text(
                                    font, lab.take(24),
                                    (listX + 14f).roundToInt(),
                                    textMidY(y, settingRowH, font),
                                    colSecondaryText.alpha(a).argb, false,
                                )
                            }
                            y += settingRowH
                        }
                    }
                }
            }
        }
    }
}
