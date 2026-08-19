/*
 * ModuleRiseClickgui —— Rise 风格单窗 ClickGUI（分组 / 搜索 / 滚动条 / 色板透明度 / 范围数值）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
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
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    private val colOverlay by color("Overlay", Color4b(0, 0, 0, 50))
    private val colAccent by color("Accent", Color4b(0x56, 0xB4, 0xE9, 255))

    private val windowW by float("Window Width", 420f, 300f..600f)
    private val windowH by float("Window Height", 320f, 220f..520f)
    private val sidebarW by float("Sidebar Width", 96f, 70f..150f)
    private val round by float("Round", 10f, 0f..20f)
    private val moduleRowH by float("Module Row H", 22f, 16f..32f)
    private val settingRowH by float("Setting Row H", 20f, 14f..28f)
    private val animMs by float("Anim Ms", 300f, 80f..800f)
    private val shadow by boolean("Drop Shadow", true)
    private val scaleAnim by boolean("Scale Animation", true)

    private var winX = -1f
    private var winY = -1f
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var selectedCat: ModuleCategory? = null
    private var expanded: ClientModule? = null
    private var scroll = 0f
    private var targetScroll = 0f
    private var contentH = 0f
    private var mouseX = 0f
    private var mouseY = 0f
    private var sliderDrag: Value<*>? = null
    private var scrollBarDrag = false

    private var scale = 0f
    private var opacity = 0f
    private var lastNs = 0L

    private val enumOpen = IdentityHashMap<Value<*>, Boolean>()

    // 搜索
    private var searchText = ""
    private var searchFocused = false

    // 色板 + 透明度
    private val palette = listOf(
        Color4b(0x56, 0xB4, 0xE9), Color4b(255, 70, 70), Color4b(90, 230, 110),
        Color4b(255, 170, 40), Color4b(140, 110, 255), Color4b(255, 120, 200),
        Color4b(255, 255, 255), Color4b(40, 40, 40),
        Color4b(0xE9, 0xA8, 0xBC), Color4b(0x6E, 0xC8, 0xF1), Color4b(255, 230, 60), Color4b(120, 120, 120),
    )
    private var colorEdit: Value<*>? = null
    private var paletteX = 0f
    private var paletteY = 0f
    private var colorAlpha = 255
    private var alphaDragging = false

    private fun easeOutExpo(t: Float): Float =
        if (t >= 1f) 1f else (1.0 - 2.0.pow((-10.0 * t))).toFloat()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun guiMX() =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()

    private fun guiMY() =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()

    private fun over(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    /** 全部分类（保证侧边栏有分组） */
    private fun allCategories(): List<ModuleCategory> {
        val fromModules = ModuleManager.getModules()
            .filter { !it.hidden }
            .map { it.category }
            .distinct()
        val entries = try {
            ModuleCategories.entries.toList()
        } catch (_: Throwable) {
            emptyList()
        }
        // 优先官方枚举顺序，再补模块里出现过的
        val ordered = mutableListOf<ModuleCategory>()
        for (c in entries) if (c !in ordered) ordered += c
        for (c in fromModules) if (c !in ordered) ordered += c
        return ordered
    }

    private fun catLabel(cat: ModuleCategory): String = try {
        cat.tag
    } catch (_: Throwable) {
        try { cat.name } catch (_: Throwable) { cat.toString() }
    }

    private fun modulesIn(cat: ModuleCategory): List<ClientModule> {
        val q = searchText.trim().lowercase()
        return ModuleManager.getModules()
            .filter { !it.hidden && it.category == cat }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.aliases.any { a -> a.lowercase().contains(q) } }
            .sortedBy { it.name }
    }

    private fun modulesSearchAll(): List<ClientModule> {
        val q = searchText.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return ModuleManager.getModules()
            .filter { !it.hidden }
            .filter { it.name.lowercase().contains(q) || it.aliases.any { a -> a.lowercase().contains(q) } }
            .sortedBy { it.name }
    }

    private fun collectValues(mod: ClientModule): List<Value<*>> = try {
        mod.collectValuesRecursively().toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun getActual(v: Value<*>): Any? {
        var o: Any? = try { v.get() } catch (_: Exception) { null }
        var d = 0
        while (o is Value<*> && d < 5) {
            o = try { o.get() } catch (_: Exception) { null }
            d++
        }
        return o
    }

    private fun trySet(v: Value<*>, value: Any) {
        try {
            v.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 1 }
                ?.invoke(v, value)
        } catch (_: Exception) {}
    }

    /** 解析数值范围：优先 RangedValue，否则反射 min/max */
    private fun rangeOf(v: Value<*>): Pair<Float, Float>? {
        if (v is RangedValue<*>) {
            val minV = (v.range.start as? Number)?.toFloat()
            val maxV = (v.range.endInclusive as? Number)?.toFloat()
            if (minV != null && maxV != null && maxV > minV) return minV to maxV
        }
        return try {
            val minM = v.javaClass.methods.firstOrNull { it.name.equals("getMinimum", true) || it.name == "getMin" }
            val maxM = v.javaClass.methods.firstOrNull { it.name.equals("getMaximum", true) || it.name == "getMax" }
            val minV = (minM?.takeIf { it.parameterCount == 0 }?.invoke(v) as? Number)?.toFloat()
            val maxV = (maxM?.takeIf { it.parameterCount == 0 }?.invoke(v) as? Number)?.toFloat()
            if (minV != null && maxV != null && maxV > minV) minV to maxV else null
        } catch (_: Exception) {
            null
        }
    }

    private fun applySliderValue(v: Value<*>, x1: Float, x2: Float) {
        val range = rangeOf(v) ?: return
        val (minV, maxV) = range
        val nv = ((mouseX - x1) / (x2 - x1).coerceAtLeast(1f) * (maxV - minV) + minV).coerceIn(minV, maxV)
        when (val actual = getActual(v)) {
            is Float -> trySet(v, nv)
            is Double -> trySet(v, nv.toDouble())
            is Int -> trySet(v, nv.roundToInt())
            is Long -> trySet(v, nv.toLong())
            is Number -> trySet(v, nv)
            else -> trySet(v, nv)
        }
    }

    private fun readColor4b(actual: Any?): Color4b {
        if (actual == null) return colAccent
        return try {
            when (actual) {
                is Color4b -> actual
                is Int -> Color4b(actual)
                else -> {
                    val argb = actual.javaClass.methods.firstOrNull {
                        (it.name == "argb" || it.name == "getArgb") && it.parameterCount == 0
                    }?.invoke(actual) as? Int
                    if (argb != null) Color4b(argb) else colAccent
                }
            }
        } catch (_: Exception) {
            colAccent
        }
    }

    private fun setColorValue(v: Value<*>, c: Color4b) {
        try {
            trySet(v, c)
        } catch (_: Exception) {
            trySet(v, c.argb)
        }
    }

    /* —— Screen —— */
    private class RiseGuiScreen : Screen(Component.literal("RiseClickGui")) {
        override fun isPauseScreen() = false
        override fun shouldCloseOnEsc() = false
        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                if (ModuleRiseClickgui.searchFocused) {
                    ModuleRiseClickgui.searchFocused = false
                } else {
                    ModuleRiseClickgui.enabled = false
                    try { mc.gui.setScreen(null) } catch (_: Throwable) {}
                }
                return true
            }
            // 搜索输入
            if (ModuleRiseClickgui.searchFocused) {
                when (event.key()) {
                    GLFW.GLFW_KEY_BACKSPACE -> {
                        if (ModuleRiseClickgui.searchText.isNotEmpty()) {
                            ModuleRiseClickgui.searchText = ModuleRiseClickgui.searchText.dropLast(1)
                        }
                        return true
                    }
                    GLFW.GLFW_KEY_ENTER -> {
                        ModuleRiseClickgui.searchFocused = false
                        return true
                    }
                    else -> {
                        val name = GLFW.glfwGetKeyName(event.key(), event.scancode())
                        if (name != null && name.length == 1 && ModuleRiseClickgui.searchText.length < 32) {
                            ModuleRiseClickgui.searchText += name
                            ModuleRiseClickgui.targetScroll = 0f
                            return true
                        }
                    }
                }
            }
            return true
        }
        override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean = true
        override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean = true
        override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dx: Double, dy: Double): Boolean = true
        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean = true
    }

    private fun openLayer() {
        if (mc.gui.screen() is RiseGuiScreen) return
        try { mc.gui.setScreen(RiseGuiScreen()) } catch (_: Throwable) {
            try { mc.execute { mc.gui.setScreen(RiseGuiScreen()) } } catch (_: Throwable) {}
        }
    }

    private fun closeLayer() {
        if (mc.gui.screen() is RiseGuiScreen) {
            try { mc.gui.setScreen(null) } catch (_: Throwable) {
                try { mc.execute { mc.gui.setScreen(null) } } catch (_: Throwable) {}
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
        winX = -1f
        searchText = ""
        searchFocused = false
        openLayer()
    }

    override fun onDisabled() {
        closeLayer()
        sliderDrag = null
        colorEdit = null
        alphaDragging = false
        scrollBarDrag = false
        searchFocused = false
    }

    @Suppress("unused")
    private val rotHandler = handler<MouseRotationEvent> { e ->
        if (enabled || scale > 0.01f) e.cancelEvent()
    }

    @Suppress("unused")
    private val hotbarHandler = handler<MouseScrollInHotbarEvent> { e ->
        if (enabled || scale > 0.01f) e.cancelEvent()
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { e ->
        if (!enabled && scale < 0.01f) return@handler
        if (e.keyCode == GLFW.GLFW_KEY_ESCAPE && e.action == 1) {
            if (searchFocused) searchFocused = false
            else enabled = false
        }
    }

    @Suppress("unused")
    private val scrollHandler = handler<MouseScrollEvent> { e ->
        if (!enabled) return@handler
        val contentX = winX + sidebarW
        if (over(contentX, winY + 52f, windowW - sidebarW, windowH - 58f)) {
            targetScroll = (targetScroll - e.vertical.toFloat() * 22f).coerceAtLeast(0f)
        }
    }

    private fun listClipTop() = winY + 52f
    private fun listClipBot() = winY + windowH - 8f
    private fun listViewH() = listClipBot() - listClipTop()

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { e ->
        if (!enabled || scale < 0.05f) return@handler
        if (mc.gui.screen() !is RiseGuiScreen) openLayer()
        mouseX = guiMX()
        mouseY = guiMY()

        if (e.action == 0) {
            dragging = false
            sliderDrag = null
            alphaDragging = false
            scrollBarDrag = false
            return@handler
        }
        if (e.action != 1) return@handler

        // 色板
        colorEdit?.let { cv ->
            if (e.button == 0) {
                val cell = 14f
                val cols = 6
                val rows = 2
                val panelW = cols * cell + 8f
                val panelH = rows * cell + 28f
                if (over(paletteX, paletteY, panelW, panelH)) {
                    // alpha 条
                    val barY = paletteY + rows * cell + 8f
                    if (over(paletteX + 4f, barY, panelW - 8f, 12f)) {
                        alphaDragging = true
                        colorAlpha = (((mouseX - paletteX - 4f) / (panelW - 8f)) * 255f)
                            .roundToInt().coerceIn(0, 255)
                        val cur = readColor4b(getActual(cv))
                        setColorValue(cv, Color4b(cur.r, cur.g, cur.b, colorAlpha))
                        return@handler
                    }
                    val c = ((mouseX - paletteX - 4f) / cell).toInt()
                    val r = ((mouseY - paletteY - 4f) / cell).toInt()
                    if (c in 0 until cols && r in 0 until rows) {
                        val idx = r * cols + c
                        if (idx in palette.indices) {
                            val p = palette[idx]
                            setColorValue(cv, Color4b(p.r, p.g, p.b, colorAlpha))
                            colorEdit = null
                        }
                    }
                    return@handler
                }
                colorEdit = null
            }
            return@handler
        }

        // 搜索框
        val searchX = winX + sidebarW + 8f
        val searchY = winY + 30f
        val searchW = windowW - sidebarW - 16f
        if (e.button == 0 && over(searchX, searchY, searchW, 18f)) {
            searchFocused = true
            return@handler
        } else if (e.button == 0) {
            searchFocused = false
        }

        // 标题拖
        if (e.button == 0 && over(winX, winY, windowW, 28f)) {
            dragging = true
            dragOffX = mouseX - winX
            dragOffY = mouseY - winY
            return@handler
        }

        // 滚动条
        val barX = winX + windowW - 10f
        val viewH = listViewH()
        if (contentH > viewH && e.button == 0 && over(barX, listClipTop(), 6f, viewH)) {
            scrollBarDrag = true
            val ratio = ((mouseY - listClipTop()) / viewH).coerceIn(0f, 1f)
            targetScroll = ratio * max(0f, contentH - viewH)
            return@handler
        }

        // 侧边栏分类
        val cats = allCategories()
        var cy = winY + 36f
        for (cat in cats) {
            if (over(winX + 4f, cy, sidebarW - 8f, 20f)) {
                if (e.button == 0) {
                    selectedCat = cat
                    expanded = null
                    targetScroll = 0f
                    scroll = 0f
                    if (searchText.isNotEmpty()) {
                        // 保留搜索，也可清空
                    }
                }
                return@handler
            }
            cy += 22f
        }

        // 模块列表点击
        val mods = if (searchText.isNotBlank()) modulesSearchAll() else modulesIn(selectedCat ?: return@handler)
        val listX = winX + sidebarW + 6f
        val listW = windowW - sidebarW - 20f
        val clipTop = listClipTop()
        val clipBot = listClipBot()
        var y = clipTop - scroll
        for (mod in mods) {
            if (y + moduleRowH > clipTop && y < clipBot) {
                if (over(listX, y, listW, moduleRowH)) {
                    when (e.button) {
                        0 -> if (mod.name != name) mod.enabled = !mod.enabled
                        1 -> if (collectValues(mod).isNotEmpty()) {
                            expanded = if (expanded == mod) null else mod
                        }
                    }
                    return@handler
                }
            }
            y += moduleRowH
            if (expanded == mod) {
                for (v in collectValues(mod)) {
                    if (y + settingRowH > clipTop && y < clipBot) {
                        if (over(listX, y, listW, settingRowH)) {
                            handleSettingClick(v, listX, listW, e.button)
                            return@handler
                        }
                    }
                    y += settingRowH
                    if (enumOpen[v] == true) {
                        val actual = getActual(v)
                        if (actual is Enum<*>) {
                            for (c in actual.javaClass.enumConstants ?: emptyArray()) {
                                if (y + settingRowH > clipTop && y < clipBot) {
                                    if (over(listX, y, listW, settingRowH) && e.button == 0) {
                                        trySet(v, c)
                                        return@handler
                                    }
                                }
                                y += settingRowH
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleSettingClick(v: Value<*>, x: Float, w: Float, button: Int) {
        val actual = getActual(v) ?: return
        when {
            actual is Boolean && button == 0 -> trySet(v, !actual)
            actual is Enum<*> && button == 0 -> enumOpen[v] = !(enumOpen[v] ?: false)
            actual is Number && button == 0 -> {
                val range = rangeOf(v)
                if (range != null) {
                    sliderDrag = v
                    applySliderValue(v, x + w * 0.38f, x + w - 10f)
                }
            }
            actual != null && actual.javaClass.simpleName.contains("Color", true) && button == 0 -> {
                colorEdit = if (colorEdit == v) null else v
                colorAlpha = readColor4b(actual).a
                paletteX = (x + w - 6 * 14f - 8f).coerceAtLeast(4f)
                paletteY = (mouseY + 8f)
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val font = mc.font
        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        val open = enabled
        val speed = if (open) (1000f / animMs) else (1000f / (animMs * 0.35f))
        scale = (scale + (if (open) 1f else 0f - scale) * (dt * speed).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val sVis = if (scaleAnim) easeOutExpo(scale) else if (open) 1f else 0f
        opacity = lerp(opacity, if (open) 1f else 0f, (dt * speed).coerceIn(0f, 1f))
        if (sVis < 0.01f && !open) return@handler

        mouseX = guiMX()
        mouseY = guiMY()
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        if (winX < 0f) {
            winX = sw / 2f - windowW / 2f
            winY = sh / 2f - windowH / 2f
        }
        if (dragging) {
            winX = (mouseX - dragOffX).coerceIn(0f, sw - windowW)
            winY = (mouseY - dragOffY).coerceIn(0f, sh - windowH)
        }

        val cats = allCategories()
        if (selectedCat == null || selectedCat !in cats) {
            selectedCat = cats.firstOrNull()
        }

        val mods = if (searchText.isNotBlank()) modulesSearchAll() else modulesIn(selectedCat!!)
        contentH = 0f
        for (m in mods) {
            contentH += moduleRowH
            if (expanded == m) {
                for (v in collectValues(m)) {
                    contentH += settingRowH
                    if (enumOpen[v] == true) {
                        val act = getActual(v)
                        if (act is Enum<*>) contentH += settingRowH * (act.javaClass.enumConstants?.size ?: 0)
                    }
                }
            }
        }
        val viewH = listViewH()
        targetScroll = targetScroll.coerceIn(0f, max(0f, contentH - viewH))
        scroll += (targetScroll - scroll) * 0.28f

        if (scrollBarDrag && contentH > viewH) {
            val ratio = ((mouseY - listClipTop()) / viewH).coerceIn(0f, 1f)
            targetScroll = ratio * max(0f, contentH - viewH)
        }

        sliderDrag?.let {
            val listX = winX + sidebarW + 6f
            val listW = windowW - sidebarW - 20f
            applySliderValue(it, listX + listW * 0.38f, listX + listW - 10f)
        }

        if (alphaDragging && colorEdit != null) {
            val cell = 14f
            val panelW = 6 * cell + 8f
            colorAlpha = (((mouseX - paletteX - 4f) / (panelW - 8f)) * 255f).roundToInt().coerceIn(0, 255)
            val cur = readColor4b(getActual(colorEdit!!))
            setColorValue(colorEdit!!, Color4b(cur.r, cur.g, cur.b, colorAlpha))
        }

        val a = (255 * opacity * sVis.coerceAtLeast(0.15f)).roundToInt().coerceIn(0, 255)
        val cx = winX + windowW / 2f
        val cy = winY + windowH / 2f

        ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (40 * opacity).roundToInt()))

        ctx.pose().withPush {
            if (scaleAnim && sVis < 0.999f) {
                translate(cx, cy)
                scale(sVis, sVis)
                translate(-cx, -cy)
            }

            if (shadow && sVis > 0.7f) {
                for (i in 1..5) {
                    val e = i * 2.2f
                    val sa = (12 * opacity * (1f - i / 6f)).roundToInt()
                    ctx.drawRoundedRect(winX - e, winY - e, winX + windowW + e, winY + windowH + e, round + 2f, Color4b(0, 0, 0, sa))
                }
            }

            ctx.drawRoundedRect(winX, winY, winX + windowW, winY + windowH, round, colBg.alpha(a))
            ctx.drawRoundedRect(winX, winY, winX + sidebarW, winY + windowH, round, colSecondary.alpha(a))
            ctx.drawQuad(winX + sidebarW - 8f, winY, winX + sidebarW, winY + windowH, colSecondary.alpha(a))

            ctx.text(font, "Rise", (winX + 12f).roundToInt(), (winY + 10f).roundToInt(), colText.alpha(a).argb, true)

            // 侧边栏分类（分组）
            var catY = winY + 36f
            for (cat in cats) {
                val sel = cat == selectedCat && searchText.isBlank()
                if (sel) {
                    ctx.drawRoundedRect(winX + 4f, catY - 1f, winX + sidebarW - 4f, catY + 19f, 4f, colAccent.alpha((a * 0.35f).toInt()))
                }
                if (over(winX + 4f, catY, sidebarW - 8f, 20f)) {
                    ctx.drawRoundedRect(winX + 4f, catY - 1f, winX + sidebarW - 4f, catY + 19f, 4f, colOverlay.alpha((a * 0.5f).toInt()))
                }
                val tc = if (sel) colAccent.alpha(a) else colTrinaryText.alpha(a)
                val label = catLabel(cat)
                val shown = if (font.width(label) > sidebarW - 20) label.take(6) + "…" else label
                ctx.text(font, shown, (winX + 12f).roundToInt(), (catY + 4f).roundToInt(), tc.argb, false)
                catY += 22f
            }

            // 搜索框
            val searchX = winX + sidebarW + 8f
            val searchY = winY + 30f
            val searchW = windowW - sidebarW - 16f
            ctx.drawRoundedRect(searchX, searchY, searchX + searchW, searchY + 18f, 4f, colSecondary.alpha(a))
            if (searchFocused) {
                ctx.drawRoundedRect(searchX, searchY, searchX + searchW, searchY + 18f, 4f, colAccent.alpha((a * 0.25f).toInt()))
            }
            val hint = if (searchText.isEmpty() && !searchFocused) "Search..." else searchText
            val sc = if (searchText.isEmpty() && !searchFocused) colTrinaryText.alpha(a) else colSecondaryText.alpha(a)
            ctx.text(font, hint, (searchX + 6f).roundToInt(), (searchY + 5f).roundToInt(), sc.argb, false)

            // 模块列表
            val listX = winX + sidebarW + 6f
            val listW = windowW - sidebarW - 20f
            val clipTop = listClipTop()
            val clipBot = listClipBot()
            var y = clipTop - scroll

            for (mod in mods) {
                val rowBot = y + moduleRowH
                if (rowBot > clipTop && y < clipBot) {
                    val y1 = max(y, clipTop)
                    val y2 = min(rowBot, clipBot)
                    if (mod.enabled) {
                        ctx.drawQuad(listX, y1, listX + listW, y2, colAccent.alpha((a * 0.18f).toInt()))
                    }
                    if (over(listX, y1, listW, y2 - y1)) {
                        ctx.drawQuad(listX, y1, listX + listW, y2, colOverlay.alpha(a))
                    }
                    val ty = y + moduleRowH / 2f - 4f
                    if (ty >= clipTop && ty + 9f <= clipBot) {
                        val mc_ = if (mod.enabled) colText.alpha(a) else colSecondaryText.alpha(a)
                        val nameShow = if (searchText.isNotBlank()) "${mod.name}  [${catLabel(mod.category)}]" else mod.name
                        ctx.text(font, nameShow, (listX + 6f).roundToInt(), ty.roundToInt(), mc_.argb, false)
                        if (collectValues(mod).isNotEmpty()) {
                            val mark = if (expanded == mod) "▾" else "▸"
                            ctx.text(font, mark, (listX + listW - 12f).roundToInt(), ty.roundToInt(), colTrinaryText.alpha(a).argb, false)
                        }
                    }
                }
                y += moduleRowH

                if (expanded == mod) {
                    for (v in collectValues(mod)) {
                        if (y + settingRowH > clipTop && y < clipBot) {
                            drawSetting(ctx, font, v, listX, y, listW, a, clipTop, clipBot)
                        }
                        y += settingRowH
                        if (enumOpen[v] == true) {
                            val act = getActual(v)
                            if (act is Enum<*>) {
                                for (c in act.javaClass.enumConstants ?: emptyArray()) {
                                    if (y + settingRowH > clipTop && y < clipBot) {
                                        val y1 = max(y, clipTop)
                                        val y2 = min(y + settingRowH, clipBot)
                                        ctx.drawQuad(listX, y1, listX + listW, y2, colSecondary.alpha((a * 0.6f).toInt()))
                                        if (c == act) ctx.drawQuad(listX, y1, listX + 2f, y2, colAccent.alpha(a))
                                        val ty = y + settingRowH / 2f - 4f
                                        if (ty >= clipTop && ty + 9f <= clipBot) {
                                            ctx.text(font, c.name, (listX + 14f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                                        }
                                    }
                                    y += settingRowH
                                }
                            }
                        }
                    }
                }
                if (y > clipBot + 40f) break
            }

            // 顶底遮罩
            ctx.drawQuad(winX + sidebarW, winY, winX + windowW, clipTop, colBg.alpha(a))
            ctx.drawQuad(winX + sidebarW, clipBot, winX + windowW, winY + windowH, colBg.alpha(a))
            // 重画搜索区在遮罩上
            ctx.drawRoundedRect(searchX, searchY, searchX + searchW, searchY + 18f, 4f, colSecondary.alpha(a))
            if (searchFocused) {
                ctx.drawRoundedRect(searchX, searchY, searchX + searchW, searchY + 18f, 4f, colAccent.alpha((a * 0.25f).toInt()))
            }
            ctx.text(font, hint, (searchX + 6f).roundToInt(), (searchY + 5f).roundToInt(), sc.argb, false)

            // 滚动条
            if (contentH > viewH + 1f) {
                val barX = winX + windowW - 10f
                val trackH = viewH
                val thumbH = max(20f, trackH * (viewH / contentH))
                val maxOff = contentH - viewH
                val thumbY = clipTop + (scroll / maxOff.coerceAtLeast(0.001f)) * (trackH - thumbH)
                ctx.drawRoundedRect(barX, clipTop, barX + 5f, clipBot, 2f, Color4b(40, 42, 50, a))
                ctx.drawRoundedRect(barX, thumbY, barX + 5f, thumbY + thumbH, 2f, colAccent.alpha((a * 0.85f).toInt()))
            }

            // 色板 + alpha
            colorEdit?.let {
                val cell = 14f
                val cols = 6
                val rows = 2
                val pw = cols * cell + 8f
                val ph = rows * cell + 28f
                ctx.drawRoundedRect(paletteX, paletteY, paletteX + pw, paletteY + ph, 4f, colSecondary.alpha(a))
                for (i in palette.indices) {
                    val c = i % cols
                    val r = i / cols
                    if (r >= rows) break
                    ctx.drawRoundedRect(
                        paletteX + 4 + c * cell, paletteY + 4 + r * cell,
                        paletteX + 4 + c * cell + cell - 2, paletteY + 4 + r * cell + cell - 2,
                        3f, palette[i].alpha(a),
                    )
                }
                val barY = paletteY + rows * cell + 8f
                ctx.drawQuad(paletteX + 4f, barY, paletteX + pw - 4f, barY + 10f, Color4b(30, 30, 35, a))
                val aw = (pw - 8f) * (colorAlpha / 255f)
                ctx.drawQuad(paletteX + 4f, barY, paletteX + 4f + aw, barY + 10f, colAccent.alpha(a))
                ctx.text(
                    font, "A:$colorAlpha",
                    (paletteX + 6f).roundToInt(), (barY - 1f).roundToInt(),
                    colTrinaryText.alpha(a).argb, false,
                )
            }
        }
    }

    private fun drawSetting(
        ctx: GuiGraphicsExtractor, font: Font, v: Value<*>,
        x: Float, y: Float, w: Float, a: Int, clipTop: Float, clipBot: Float,
    ) {
        val actual = getActual(v) ?: return
        val y1 = max(y, clipTop)
        val y2 = min(y + settingRowH, clipBot)
        ctx.drawQuad(x, y1, x + w, y2, colSecondary.alpha((a * 0.45f).toInt()))
        if (over(x, y1, w, y2 - y1)) {
            ctx.drawQuad(x, y1, x + w, y2, colOverlay.alpha(a))
        }
        val ty = y + settingRowH / 2f - 4f
        if (ty < clipTop || ty + 9f > clipBot) return

        when {
            actual is Boolean -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                val bx = x + w - 28f
                ctx.drawRoundedRect(bx, y + 4f, bx + 20f, y + settingRowH - 4f, 4f,
                    if (actual) colAccent.alpha(a) else Color4b(60, 60, 70, a))
                val kx = if (actual) bx + 10f else bx + 2f
                ctx.drawRoundedRect(kx, y + 5f, kx + 8f, y + settingRowH - 5f, 3f, Color4b.WHITE.alpha(a))
            }
            actual is Enum<*> -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                ctx.text(
                    font, actual.name,
                    (x + w - 8f - font.width(actual.name)).roundToInt(), ty.roundToInt(),
                    colAccent.alpha(a).argb, false,
                )
            }
            actual is Number -> {
                val range = rangeOf(v)
                ctx.text(font, v.name, (x + 8f).roundToInt(), (y + 2f).roundToInt(), colSecondaryText.alpha(a).argb, false)
                val fv = actual.toFloat()
                if (range != null) {
                    val (minV, maxV) = range
                    val p = ((fv - minV) / (maxV - minV)).coerceIn(0f, 1f)
                    val sx = x + w * 0.38f
                    val sw = w * 0.48f
                    val sy = y + settingRowH - 7f
                    ctx.drawQuad(sx, sy, sx + sw, sy + 3f, Color4b(50, 50, 60, a))
                    ctx.drawQuad(sx, sy, sx + sw * p, sy + 3f, colAccent.alpha(a))
                    // 滑块圆点
                    val kx = sx + sw * p - 3f
                    ctx.drawRoundedRect(kx, sy - 2f, kx + 6f, sy + 5f, 3f, Color4b.WHITE.alpha(a))
                    val vs = if (fv == fv.toInt().toFloat()) fv.toInt().toString()
                    else String.format("%.2f", fv)
                    ctx.text(
                        font, vs,
                        (sx + sw + 4f).roundToInt().coerceAtMost((x + w - 4).roundToInt()),
                        (y + 2f).roundToInt(),
                        colTrinaryText.alpha(a).argb, false,
                    )
                } else {
                    val vs = actual.toString()
                    ctx.text(font, vs, (x + w - 8f - font.width(vs)).roundToInt(), ty.roundToInt(), colTrinaryText.alpha(a).argb, false)
                }
            }
            actual != null && actual.javaClass.simpleName.contains("Color", true) -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                val col = readColor4b(actual)
                ctx.drawRoundedRect(x + w - 22f, y + 3f, x + w - 6f, y + settingRowH - 3f, 3f, col.alpha(a))
            }
            else -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
            }
        }
    }
}
