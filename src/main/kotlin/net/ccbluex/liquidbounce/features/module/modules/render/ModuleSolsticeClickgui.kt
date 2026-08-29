/*
 * ModuleSolsticeClickgui — Modern 分类列 ClickGUI
 * LiquidBounce Nextgen 0.39 兼容 API（GuiGraphicsExtractor / getModules / gui.setScreen）
 *
 * 本次修复:
 *  1. 滑条: 命中区域按 Value 记录(渲染与点击同一坐标系), CPS/Minimum/Range 等全部可拖
 *  2. 双端滑条(INT_RANGE/FLOAT_RANGE): 两端独立拖动, 按距离自动选择手柄
 *  3. Mode 子项: 正确显示 activeMode.name 并可切换/下拉选择(原显示 "ArrayList"/"ValueGroup(...)")
 *  4. 分组标题(ValueGroup/TargetRendering 等)为 GROUP 类型, 不再被误判为文本项弹出输入光标
 *  5. 调色板: HSV 取色器(SB 面板 + 色相条 + 透明度条 + HEX 显示), 命中不再穿透到下方项
 *  6. 性能: 缩放改为 pose 矩阵(不再逐行手动换算); 剪裁改用官方 scissorStack(去掉反射);
 *     类型/区间/选项/子项/描述/模块名全部缓存; 主题相位每帧一次; 分类列表缓存
 *  7. 统一布局走查(walk)与统一 metrics: 渲染/测量/点击共用同一套行坐标, 彻底消除点击错位
 *  8. 键绑定: 模块中键绑定, Bind/Key 值可在 GUI 内直接按键绑定 (ESC = 解绑)
 *  9. 滚轮: 事件累加而非覆盖, 方向修正, 仅作用于悬停面板
 * 10. 修复: InputBind.copy 属性名、多选值使用 MultiChoiceListValue.toggle、
 *     Regex 文本值编辑、面板收起动画、部分被裁剪行不可点击等
 * 11. 二轮修复: 点击/渲染共用 ctx 尺寸(消除 guiScaled 与 ctx 尺寸不一致的错位);
 *     任意新按下/任意键释放都会终结拖动(防止滑条拖动状态泄漏);
 *     下拉与调色板互斥展开, 单选后自动收起;
 *     空分组不再显示 ">" 箭头且点击无副作用;
 *     MODE 子项按 activeMode 缓存(切换模式立即刷新);
 *     文本编辑失焦自动提交; 模块名缩短缓存; 设置行悬停显示描述
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

    // ======================== 值类型识别(缓存, 无每帧反射) ========================

    private enum class Kind {
        GROUP,        // CONFIGURABLE
        TOGGLE_GROUP, // TOGGLEABLE (含子项, 可开关)
        MODE,         // CHOICE (ModeValueGroup)
        CHOICE,       // CHOOSE (ChoiceListValue 单选)
        MULTI,        // MULTI_CHOOSE (多选)
        SLIDER,       // FLOAT / INT
        DUAL,         // FLOAT_RANGE / INT_RANGE (双端)
        BOOL,         // BOOLEAN
        COLOR,        // COLOR
        TEXT,         // TEXT (含 Regex)
        KEY,          // KEY
        BIND,         // BIND
        OTHER,        // 注册表/列表/向量等: 只读展示
    }

    private class VInfo(val v: Value<*>) {
        var kind: Kind = Kind.OTHER
        var range: Pair<Float, Float>? = null
        var isInt: Boolean = false
        var suffix: String = ""
        var choices: List<Any?> = emptyList()
        var children: List<Value<*>>? = null // GROUP/TOGGLE_GROUP/MODE 缓存
        var modeRef: Any? = null             // MODE: 缓存对应的 activeMode
        var shortName: String = v.name
        var shortNameW: Int = -1
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

    /** 分组/模式的子项(跳过 notAnOption 与冗余 Enabled; MODE 按 activeMode 缓存) */
    private fun childrenOf(v: Value<*>?): List<Value<*>> {
        if (v == null) return emptyList()
        val vi = info(v)
        when (vi.kind) {
            Kind.GROUP, Kind.TOGGLE_GROUP -> {
                vi.children?.let { return it }
                val list = runCatching {
                    (v as ValueGroup).inner
                        .filter { !it.notAnOption }
                        .filterNot { vi.kind == Kind.TOGGLE_GROUP && isRedundantEnabled(it) }
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

    // ======================== 状态 ========================

    private class CatPos(
        var x: Float = 0f,
        var y: Float = 0f,
        var dragging: Boolean = false,
        var extended: Boolean = true,
        var extAnim: Float = 1f,
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
    private val colorOpen = IdentityHashMap<Value<*>, Boolean>()
    private val pickerHue = IdentityHashMap<Value<*>, Float>()
    private var textEditValue: Value<*>? = null
    private var textBuffer = ""
    private var keyListen: Value<*>? = null
    private var bindMod: ClientModule? = null

    // 拖动状态
    private var sliderDrag: Value<*>? = null
    private var dualWhich = 0 // 0=min 1=max
    private var colorDrag: Value<*>? = null
    private var colorDragChannel = -1

    // 命中区域(未缩放 GUI 坐标, 渲染时记录 — 与鼠标逆变换坐标一致)
    private val sliderRects = IdentityHashMap<Value<*>, FloatArray>() // x,y,w,h
    private val pickerRects = IdentityHashMap<Value<*>, Array<FloatArray>>() // 0=SB 1=Hue 2=Alpha

    private var openAnim = 0f
    private var scaleAnim = 0f
    private var lastNs = 0L
    private var frameDt = 0.016f
    private var mouseX = 0f
    private var mouseY = 0f
    private var scrollAccum = 0.0
    private var positionsReady = false
    private var layoutKey = ""
    private var tooltip = ""
    private var themeT = 0f

    // 视图逆变换(点击时把屏幕鼠标换算回未缩放 GUI 坐标)
    private var viewScale = 1f
    private var viewCx = 0f
    private var viewCy = 0f
    private var viewW = 0f
    private var viewH = 0f

    // 缓存
    private var catModules = IdentityHashMap<ModuleCategory, List<ClientModule>>()
    private var allModulesCache: List<ClientModule> = emptyList()
    private val catLabelCache = IdentityHashMap<ModuleCategory, String>()
    private val modNameCache = IdentityHashMap<ClientModule, Pair<Int, String>>()
    private val descCache = IdentityHashMap<ClientModule, String>()

    /** 模块名缩短缓存(避免每帧反复 font.width 量测) */
    private fun shortModName(mod: ClientModule, font: Font, maxW: Int): String {
        val cached = modNameCache[mod]
        if (cached != null && cached.first == maxW) return cached.second
        val s = shorten(mod.name, font, maxW)
        modNameCache[mod] = maxW to s
        return s
    }
    private var moduleCacheTime = 0L
    private var moduleCacheSize = -1

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

    private fun inScale(): Float {
        val p = scaleAnim
        return when (animMode) {
            AnimMode.ZOOM -> easeOutExpo(p).coerceIn(0f, 0.996f)
            AnimMode.BOUNCE -> if (enabled) easeOutElastic(p) else easeOutBack(p)
        }
    }

    private fun themed(seed: Float): Color4b {
        val t = ((themeT + seed * 0.01f) % 1f + 1f) % 1f
        val s = (sin(t * Math.PI * 2).toFloat() * 0.5f + 0.5f)
        return Color4b(
            lerp(themeA.r.toFloat(), themeB.r.toFloat(), s).toInt().coerceIn(0, 255),
            lerp(themeA.g.toFloat(), themeB.g.toFloat(), s).toInt().coerceIn(0, 255),
            lerp(themeA.b.toFloat(), themeB.b.toFloat(), s).toInt().coerceIn(0, 255),
            255,
        )
    }

    private fun a(c: Color4b, mul: Float) = c.alpha((c.a * mul).toInt().coerceIn(0, 255))

    private fun categoryLabel(cat: ModuleCategory): String = catLabelCache.getOrPut(cat) {
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
        val s = cat.toString()
            .substringAfterLast('.')
            .substringAfterLast('$')
            .substringBefore('@')
            .trim()
        s.replace('_', ' ')
            .lowercase()
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "Misc" }
    }

    private fun hover(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    /** 布局参考尺寸: 与渲染帧 ctx 尺寸保持一致(点击与渲染共用, 消除错位) */
    private fun guiRefW() = if (viewW > 0f) viewW else mc.window.guiScaledWidth.toFloat()

    private fun guiRefH() = if (viewH > 0f) viewH else mc.window.guiScaledHeight.toFloat()

    private fun guiMX() =
        (mc.mouseHandler.xpos() * guiRefW() / mc.window.width).toFloat()

    private fun guiMY() =
        (mc.mouseHandler.ypos() * guiRefH() / mc.window.height).toFloat()

    private fun allModules(): List<ClientModule> {
        val now = System.currentTimeMillis()
        val raw = runCatching { ModuleManager.getModules() }.getOrNull()
            ?: return runCatching {
                val f = ModuleManager.javaClass.getDeclaredField("modules")
                f.isAccessible = true
                (f.get(ModuleManager) as? Collection<*>)?.filterIsInstance<ClientModule>() ?: emptyList()
            }.getOrDefault(emptyList())

        if (raw.size != moduleCacheSize || now - moduleCacheTime > 2000) {
            moduleCacheSize = raw.size
            moduleCacheTime = now
            val list = raw.toList()
            catModules = IdentityHashMap<ModuleCategory, List<ClientModule>>().apply {
                for (m in list) {
                    merge(m.category, listOf(m)) { a, b -> a + b }
                }
                for (k in keys.toList()) {
                    put(k, getOrDefault(k, emptyList()).sortedBy { it.name })
                }
            }
            allModulesCache = catModules.values.flatten()
            // 模块集合可能变化: 重建子项缓存
            modChildren.clear()
        }
        return allModulesCache
    }

    private fun allCategories(): List<ModuleCategory> {
        allModules() // 确保缓存刷新
        if (catsCacheRef == null || catsCacheRef?.first != allModulesCache) {
            catsCacheRef = allModulesCache.map { it.category }.distinct().sortedBy { categoryLabel(it) } to allModulesCache
        }
        return catsCacheRef!!.first
    }

    private var catsCacheRef: Pair<List<ModuleCategory>, List<ClientModule>>? = null

    private fun modulesIn(cat: ModuleCategory): List<ClientModule> =
        catModules.getOrDefault(cat, emptyList())

    private fun modDesc(mod: ClientModule): String = descCache.getOrPut(mod) {
        runCatching {
            val d: Any? = mod.description
            val str = if (d is java.util.function.Supplier<*>) {
                d.get()?.toString()
            } else {
                d?.toString()
            }
            str?.takeIf { it.isNotBlank() } ?: mod.name
        }.getOrDefault(mod.name)
    }

    // ======================== 显示辅助 ========================

    private fun formatNumber(n: Float): String {
        if (!n.isFinite()) return "0"
        if (abs(n - n.roundToInt()) < 1e-4f && abs(n) < 1e9f) return n.roundToInt().toString()
        return java.lang.String.format(java.util.Locale.US, "%.2f", n)
            .trimEnd('0').trimEnd('.').ifBlank { "0" }
    }

    private fun taggedLabel(any: Any?): String = when (any) {
        null -> "-"
        is Tagged -> any.tag
        is Enum<*> -> any.name.lowercase().replaceFirstChar { it.uppercase() }
        else -> any.toString().substringAfterLast('.').substringBefore('@').take(20)
    }

    private fun currentChoiceLabel(v: Value<*>): String = runCatching {
        when (info(v).kind) {
            Kind.MODE -> (v as ModeValueGroup<*>).activeMode.name
            else -> taggedLabel(v.get())
        }
    }.getOrDefault("-")

    private fun isModeSelected(v: Value<*>, choice: Any?): Boolean = runCatching {
        (v as ModeValueGroup<*>).activeMode === choice
    }.getOrDefault(false)

    private fun multiSelected(v: Value<*>): Set<*> = runCatching {
        (v as MultiChoiceListValue<*>).get()
    }.getOrDefault(emptySet<Any>())

    private fun multiLabel(v: Value<*>): String {
        val sel = multiSelected(v)
        if (sel.isEmpty()) return "None"
        val joined = sel.joinToString(", ") { taggedLabel(it) }
        return if (joined.length > 18) sel.size.toString() + " sel" else joined
    }

    private fun bindLabel(b: InputBind): String =
        if (b.isUnbound) "None" else b.keyName

    private fun keyLabel(v: Value<*>): String = runCatching {
        val k = v.get() as InputConstants.Key
        if (k == InputConstants.UNKNOWN) "None" else k.displayName.string
    }.getOrDefault("None")

    private fun otherLabel(v: Value<*>): String = runCatching {
        v.get().toString().substringAfterLast('/').substringAfterLast(':').take(18)
    }.getOrDefault("-")

    private fun hexLabel(c: Color4b): String =
        String.format("#%02X%02X%02X", c.r, c.g, c.b)

    private fun colorOf(v: Value<*>): Color4b = runCatching {
        v.get() as Color4b
    }.getOrDefault(Color4b(255, 255, 255, 255))

    private fun setColorValue(v: Value<*>, c: Color4b) {
        runCatching { (v as Value<Any>).set(c) }
    }

    // ---- HSV 工具 ----
    private fun hsvToColor(h: Float, s: Float, v: Float, a: Int = 255): Color4b {
        val hh = (h.coerceIn(0f, 1f) * 6f)
        val i = hh.toInt().coerceIn(0, 5)
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i) {
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

    private fun colorToHsv(c: Color4b): Triple<Float, Float, Float> {
        val r = c.r / 255f
        val g = c.g / 255f
        val b = c.b / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> ((g - b) / d + 6f) % 6f / 6f
            mx == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return Triple(h, if (mx == 0f) 0f else d / mx, mx)
    }

    // ======================== 布局走查(渲染/测量/点击共用) ========================

    private class Row(
        @JvmField var y: Float = 0f,
        @JvmField var h: Float = 0f,
        @JvmField var mod: ClientModule? = null,
        @JvmField var value: Value<*>? = null,
        @JvmField var choice: Any? = null,
        @JvmField var channel: Int = -1,
        @JvmField var indent: Int = 0,
    )

    /** 复用的 Row (emit 消费方不持有引用, 递归安全) */
    private val rowPool = Row()

    private inline fun emitRow(
        y: Float, h: Float, listH: Float,
        mod: ClientModule?, value: Value<*>?, choice: Any?, channel: Int, indent: Int,
        emit: (Row) -> Unit,
    ) {
        if (y >= listH || y + h <= 0f) return
        rowPool.y = y
        rowPool.h = h
        rowPool.mod = mod
        rowPool.value = value
        rowPool.choice = choice
        rowPool.channel = channel
        rowPool.indent = indent
        emit(rowPool)
    }

    private val PICKER_SB_ROWS = 2.6f

    /** 返回内容总高; emit 只接收可见行 (listH 为当前可见列表高度, 行高已含动画系数 f) */
    private inline fun walkSettings(
        mods: List<ClientModule>,
        f: Float,
        listH: Float,
        emit: (Row) -> Unit,
    ): Float {
        var y = 0f
        val rh = rowH * f
        for (mod in mods) {
            val open = modOpen[mod] == true
            val cAnim = modAnim.getOrDefault(mod, if (open) 1f else 0f)
            emitRow(y, rh, listH, mod, null, null, -1, 0, emit)
            y += rh
            if (cAnim > 0.001f) {
                y = walkValues(childrenOfModule(mod), cAnim * f, 1, y, listH, emit)
            }
        }
        return y
    }

    private inline fun walkValues(
        values: List<Value<*>>,
        f: Float,
        indent: Int,
        startY: Float,
        listH: Float,
        emit: (Row) -> Unit,
    ): Float {
        var y = startY
        val rh = rowH * f
        for (v in values) {
            val vi = info(v)
            emitRow(y, rh, listH, null, v, null, -1, indent, emit)
            y += rh
            when (vi.kind) {
                Kind.GROUP, Kind.TOGGLE_GROUP, Kind.MODE ->
                    if (groupOpen[v] == true) {
                        y = walkValues(childrenOf(v), f, indent + 1, y, listH, emit)
                    }
                else -> {}
            }
            if ((vi.kind == Kind.MODE || vi.kind == Kind.CHOICE || vi.kind == Kind.MULTI) &&
                enumOpen[v] == true
            ) {
                for (c in vi.choices) {
                    emitRow(y, rh, listH, null, v, c, -1, indent + 1, emit)
                    y += rh
                }
            }
            if (vi.kind == Kind.COLOR && colorOpen[v] == true) {
                val sbH = rowH * PICKER_SB_ROWS * f
                emitRow(y, sbH, listH, null, v, null, 0, indent, emit)
                y += sbH
                emitRow(y, rh, listH, null, v, null, 1, indent, emit)
                y += rh
                emitRow(y, rh, listH, null, v, null, 2, indent, emit)
                y += rh
            }
        }
        return y
    }

    /** 测量子项高度(与 walkValues 完全一致) */
    private fun measureValues(values: List<Value<*>>, f: Float, startY: Float): Float {
        var y = startY
        val rh = rowH * f
        for (v in values) {
            val vi = info(v)
            y += rh
            when (vi.kind) {
                Kind.GROUP, Kind.TOGGLE_GROUP, Kind.MODE ->
                    if (groupOpen[v] == true) y = measureValues(childrenOf(v), f, y)
                else -> {}
            }
            if ((vi.kind == Kind.MODE || vi.kind == Kind.CHOICE || vi.kind == Kind.MULTI) &&
                enumOpen[v] == true
            ) {
                y += rh * vi.choices.size
            }
            if (vi.kind == Kind.COLOR && colorOpen[v] == true) {
                y += rowH * f * (PICKER_SB_ROWS + 2f)
            }
        }
        return y
    }

    private fun panelContentHeight(mods: List<ClientModule>, f: Float): Float {
        var contentH = 0f
        val rh = rowH * f
        for (mod in mods) {
            contentH += rh
            val open = modOpen[mod] == true
            val ca = modAnim.getOrDefault(mod, if (open) 1f else 0f)
            if (ca > 0.001f) {
                contentH = measureValues(childrenOfModule(mod), ca * f, contentH)
            }
        }
        return contentH
    }

    /** 面板可见指标: 渲染与点击共用, 保证坐标一致 */
    private class Metrics(
        val listTop: Float,
        val listH: Float,     // 动画后的可见列表高度
        val clipBot: Float,   // 未缩放坐标下的裁剪底
        val maxScroll: Float,
    )

    private fun metricsFor(p: CatPos, mods: List<ClientModule>, sh: Float): Metrics {
        val f = p.extAnim
        val contentFull = panelContentHeight(mods, 1f)
        val maxListH = (sh - p.y - catHeight - 12f).coerceAtLeast(0f)
        val visibleFull = min(contentFull, maxListH)
        val listH = visibleFull * f
        return Metrics(
            listTop = p.y + catHeight,
            listH = listH,
            clipBot = p.y + catHeight + listH,
            maxScroll = (contentFull - visibleFull).coerceAtLeast(0f),
        )
    }

    private fun updateAnims(mods: List<ClientModule>, dt: Float) {
        val k = (dt * 12.5f).coerceIn(0f, 1f)
        val k2 = (dt * 10f).coerceIn(0f, 1f)
        for (mod in mods) {
            val openTarget = if (modOpen[mod] == true) 1f else 0f
            modAnim[mod] = lerp(modAnim.getOrDefault(mod, openTarget), openTarget, k)
            val enTarget = if (mod.enabled) 1f else 0f
            modScale[mod] = lerp(modScale.getOrDefault(mod, enTarget), enTarget, k2)
        }
    }

    // ======================== 交互 ========================

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

            // 键捕获(模块 Bind)
            ModuleSolsticeClickgui.bindMod?.let { mod ->
                ModuleSolsticeClickgui.setModuleBind(mod, key)
                ModuleSolsticeClickgui.bindMod = null
                return true
            }
            // 键捕获(KEY/BIND 设置值)
            ModuleSolsticeClickgui.keyListen?.let { v ->
                ModuleSolsticeClickgui.setKeyValue(v, key)
                ModuleSolsticeClickgui.keyListen = null
                return true
            }
            // 文本编辑
            val te = ModuleSolsticeClickgui.textEditValue
            if (te != null) {
                when (key) {
                    GLFW.GLFW_KEY_ESCAPE -> ModuleSolsticeClickgui.textEditValue = null
                    GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        ModuleSolsticeClickgui.commitText(te)
                        ModuleSolsticeClickgui.textEditValue = null
                    }
                    GLFW.GLFW_KEY_BACKSPACE ->
                        if (ModuleSolsticeClickgui.textBuffer.isNotEmpty()) {
                            ModuleSolsticeClickgui.textBuffer =
                                ModuleSolsticeClickgui.textBuffer.dropLast(1)
                        }
                }
                return true
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                when {
                    ModuleSolsticeClickgui.enumOpen.isNotEmpty() -> ModuleSolsticeClickgui.enumOpen.clear()
                    ModuleSolsticeClickgui.colorOpen.isNotEmpty() -> ModuleSolsticeClickgui.colorOpen.clear()
                    ModuleSolsticeClickgui.groupOpen.isNotEmpty() -> ModuleSolsticeClickgui.groupOpen.clear()
                    else -> {
                        ModuleSolsticeClickgui.enabled = false
                        ModuleSolsticeClickgui.closeLayer()
                    }
                }
                return true
            }
            return true
        }

        override fun charTyped(event: CharacterEvent): Boolean {
            val te = ModuleSolsticeClickgui.textEditValue ?: return true
            val ch: Char? = try {
                event.codepoint().toChar()
            } catch (_: Throwable) {
                runCatching {
                    val m = event.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name.lowercase().contains("code")
                    }
                    (m?.invoke(event) as? Number)?.toInt()?.toChar()
                }.getOrNull()
            }
            if (ch != null && !ch.isISOControl() && ModuleSolsticeClickgui.textBuffer.length < 64) {
                ModuleSolsticeClickgui.textBuffer += ch
            }
            return true
        }

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
            ModuleSolsticeClickgui.scrollAccum += v
            return true
        }
    }

    /** 文本值提交(支持 String 与 Regex 值) */
    private fun commitText(v: Value<*>) {
        runCatching {
            when (val cur = v.get()) {
                is String -> (v as Value<Any>).set(textBuffer)
                is Regex -> (v as Value<Any>).set(Regex(textBuffer))
                else -> (v as Value<Any>).set(textBuffer)
            }
        }
    }

    private fun setModuleBind(mod: ClientModule, key: Int) {
        runCatching {
            val keyCode = if (key == GLFW.GLFW_KEY_ESCAPE) InputConstants.UNKNOWN.value else key
            mod.bindValue.set(mod.bind.copy(boundKey = InputConstants.Type.KEYSYM.getOrCreate(keyCode)))
        }
    }

    private fun setKeyValue(v: Value<*>, key: Int) {
        runCatching {
            val keyCode = if (key == GLFW.GLFW_KEY_ESCAPE) InputConstants.UNKNOWN.value else key
            val k = InputConstants.Type.KEYSYM.getOrCreate(keyCode)
            when (info(v).kind) {
                Kind.BIND -> (v as Value<Any>).set((v.get() as InputBind).copy(boundKey = k))
                else -> (v as Value<Any>).set(k)
            }
        }
    }

    private fun onMouse(button: Int, pressed: Boolean) {
        // 屏幕鼠标 → 未缩放 GUI 坐标(与渲染记录的命中矩形同一坐标系)
        val rx = guiMX()
        val ry = guiMY()
        mouseX = viewCx + (rx - viewCx) / viewScale
        mouseY = viewCy + (ry - viewCy) / viewScale

        if (!pressed) {
            if (button == 0) {
                dragIdx = -1
                catPos.forEach { it.dragging = false }
            }
            colorDrag = null
            colorDragChannel = -1
            sliderDrag = null
            return
        }

        // 新按下: 先结束旧的拖动状态(若新命中滑条/调色板会立即重建)
        sliderDrag = null
        colorDrag = null
        colorDragChannel = -1
        // 点击任意处: 提交未保存的文本编辑, 取消按键监听
        textEditValue?.let { commitText(it) }
        textEditValue = null
        keyListen = null

        // 面板标题: 左键拖动 / 右键收起
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

        val sh = guiRefH()
        for (i in catPos.indices) {
            if (clickPanel(i, button, sh)) return
        }

        // 点击空白处: 结束编辑/监听(已在上方统一处理)
        if (button == 0) {
            textEditValue = null
            keyListen = null
        }
    }

    /** 点击面板内部(模块行/设置行/下拉/调色板) — 与渲染共用 metrics 与 walk */
    private fun clickPanel(i: Int, button: Int, sh: Float): Boolean {
        if (i !in cats.indices || i !in catPos.indices) return false
        val p = catPos[i]
        if (p.extAnim < 0.5f) return false
        val mods = modulesIn(cats[i])
        val m = metricsFor(p, mods, sh)
        if (m.listH <= 0.5f) return false
        if (mouseY < m.listTop || mouseY >= m.clipBot) return false

        var hit = false
        walkSettings(mods, p.extAnim, m.listH) { row ->
            if (hit) return@walkSettings
            val ry = m.listTop + row.y - p.scroll
            // 与渲染相同的可见性判断
            if (ry + row.h <= m.listTop + 1f || ry >= m.clipBot - 1f) return@walkSettings
            if (!hover(p.x, ry, catWidth, row.h)) return@walkSettings
            hit = true
            handleClickRow(row, button, p.x, ry)
        }
        return hit
    }

    /** 打开/关闭下拉(互斥: 同一时间只有一个下拉/调色板展开) */
    private fun toggleEnum(v: Value<*>): Boolean {
        val open = !(enumOpen[v] ?: false)
        enumOpen.clear()
        colorOpen.clear()
        groupOpen.remove(v)
        if (open) enumOpen[v] = true
        return open
    }

    /** 打开/关闭调色板(互斥) */
    private fun toggleColor(v: Value<*>): Boolean {
        val open = !(colorOpen[v] ?: false)
        colorOpen.clear()
        enumOpen.clear()
        if (open) {
            colorOpen[v] = true
            textEditValue = null
            val (h, _, _) = colorToHsv(colorOf(v))
            pickerHue[v] = h
        }
        return open
    }

    private fun handleClickRow(row: Row, button: Int, colX: Float, rowY: Float) {
        // 模块行
        row.mod?.let { mod ->
            when (button) {
                0 -> if (!mod.disableActivation) mod.enabled = !mod.enabled
                1 -> if (childrenOfModule(mod).isNotEmpty()) {
                    modOpen[mod] = !(modOpen[mod] ?: false)
                }
                2 -> bindMod = mod
            }
            return
        }

        val v = row.value ?: return
        val vi = info(v)
        val chevron = mouseX < colX + 16f // 左侧箭头区

        // 下拉选项行
        if (row.choice != null) {
            if (button == 0) selectChoice(v, row.choice!!)
            return
        }

        // 调色板行
        if (row.channel >= 0) {
            if (button == 0) {
                colorDrag = v
                colorDragChannel = row.channel
                applyPicker(v)
            }
            return
        }

        when (vi.kind) {
            Kind.GROUP -> if (button == 0 || button == 1) {
                if (childrenOf(v).isNotEmpty()) groupOpen[v] = !(groupOpen[v] ?: false)
            }
            Kind.TOGGLE_GROUP -> runCatching {
                val g = v as ToggleableValueGroup
                val hasKids = childrenOf(v).isNotEmpty()
                when (button) {
                    0 -> if (chevron && hasKids) {
                        groupOpen[v] = !(groupOpen[v] ?: false)
                    } else if (!g.disableActivation) {
                        g.enabled = !g.enabled
                    }
                    1 -> if (hasKids) {
                        groupOpen[v] = !(groupOpen[v] ?: false)
                    }
                }
            }
            Kind.MODE -> when (button) {
                0 -> if (chevron && childrenOf(v).isNotEmpty()) {
                    // 展开/收起子项(与下拉互斥)
                    enumOpen.remove(v)
                    groupOpen[v] = !(groupOpen[v] ?: false)
                } else {
                    cycleMode(v)
                }
                1 -> toggleEnum(v)
            }
            Kind.CHOICE -> when (button) {
                0 -> if (!cycleChoice(v)) toggleEnum(v)
                1 -> toggleEnum(v)
            }
            Kind.MULTI -> when (button) {
                0, 1 -> toggleEnum(v)
            }
            Kind.BOOL -> if (button == 0) runCatching {
                (v as Value<Any>).set(!(v.get() as Boolean))
            }
            Kind.SLIDER -> if (button == 0 || button == 2) {
                sliderDrag = v
                applySlider(v, mid = button == 2)
            }
            Kind.DUAL -> if (button == 0 || button == 2) {
                sliderDrag = v
                pickDualHandle(v)
                applySlider(v, mid = button == 2)
            }
            Kind.COLOR -> if (button == 0 || button == 1) {
                toggleColor(v)
            }
            Kind.TEXT -> if (button == 0) {
                textEditValue = v
                textBuffer = runCatching {
                    when (val cur = v.get()) {
                        is Regex -> cur.pattern
                        else -> cur as? String
                    }
                }.getOrDefault("") ?: ""
                colorOpen.clear()
            }
            Kind.KEY, Kind.BIND -> if (button == 0) {
                keyListen = v
                textEditValue = null
            }
            Kind.OTHER -> {}
        }
    }

    private fun selectChoice(v: Value<*>, choice: Any) {
        when (info(v).kind) {
            Kind.CHOICE -> {
                runCatching { (v as Value<Any>).set(choice) }
                enumOpen.remove(v) // 单选后收起下拉
            }
            Kind.MULTI -> runCatching {
                @Suppress("UNCHECKED_CAST")
                (v as MultiChoiceListValue<Any>).toggle(choice)
            }
            Kind.MODE -> runCatching {
                val name = when (choice) {
                    is ModeValueGroup.Mode -> choice.name
                    is Tagged -> choice.tag
                    else -> choice.toString()
                }
                v.setByString(name)
                enumOpen.remove(v) // 单选后收起下拉
            }
            else -> {}
        }
    }

    private fun cycleChoice(v: Value<*>): Boolean = runCatching {
        val vi = info(v)
        if (vi.choices.isEmpty()) return false
        val cur = v.get()
        var idx = vi.choices.indexOfFirst { it == cur }
        if (idx < 0) idx = 0
        val next = vi.choices[(idx + 1) % vi.choices.size] ?: return false
        (v as Value<Any>).set(next)
        true
    }.getOrDefault(false)

    private fun cycleMode(v: Value<*>): Boolean = runCatching {
        val g = v as ModeValueGroup<*>
        val modes = g.modes
        if (modes.size < 2) return false
        val idx = modes.indexOf(g.activeMode)
        val next = modes[(idx + 1 + modes.size) % modes.size]
        v.setByString(next.name)
        true
    }.getOrDefault(false)

    /** 双端滑条: 选择离鼠标近的一端 */
    private fun pickDualHandle(v: Value<*>) {
        val rect = sliderRects[v] ?: return
        val bounds = info(v).range ?: return
        val cur = runCatching {
            val r = v.get() as ClosedRange<*>
            ((r.start as? Number)?.toFloat() ?: bounds.first) to
                ((r.endInclusive as? Number)?.toFloat() ?: bounds.second)
        }.getOrDefault(bounds)
        val t = ((mouseX - rect[0]) / rect[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
        val pos = bounds.first + t * (bounds.second - bounds.first)
        dualWhich = if (abs(pos - cur.first) <= abs(pos - cur.second)) 0 else 1
    }

    /** 滑条/双端滑条取值(使用按值记录的命中矩形) */
    private fun applySlider(v: Value<*>, mid: Boolean) {
        val rect = sliderRects[v] ?: return
        val vi = info(v)
        val bounds = vi.range ?: return
        val (minV, maxV) = bounds
        val t = ((mouseX - rect[0]) / rect[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
        var nv = minV + t * (maxV - minV)
        if (mid) {
            val step = midclickRound.coerceAtLeast(0.01f)
            nv = (nv / step).roundToInt() * step
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
                    when (val cur = v.get()) {
                        is Float -> casted.set(nv)
                        is Int -> casted.set(nv.roundToInt())
                        is Double -> casted.set(nv.toDouble())
                        is Long -> casted.set(nv.toLong())
                        else -> casted.set(if (vi.isInt) nv.roundToInt() else nv)
                    }
                }
            }
        }
    }

    /** 调色板取色(使用按值记录的命中矩形) */
    private fun applyPicker(v: Value<*>) {
        val rects = pickerRects[v] ?: return
        val ch = colorDragChannel
        if (ch < 0 || ch > 2) return
        val r = rects[ch]
        val col = colorOf(v)
        when (ch) {
            0 -> {
                val s = ((mouseX - r[0]) / r[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
                val vv = (1f - ((mouseY - r[1]) / r[3].coerceAtLeast(1f)).coerceIn(0f, 1f))
                val h = pickerHue.getOrDefault(v, colorToHsv(col).first)
                setColorValue(v, hsvToColor(h, s, vv, col.a))
            }
            1 -> {
                val t = ((mouseX - r[0]) / r[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
                pickerHue[v] = t
                val (_, s, vv) = colorToHsv(col)
                setColorValue(v, hsvToColor(t, s, if (vv < 0.02f) 1f else vv, col.a))
            }
            2 -> {
                val t = ((mouseX - r[0]) / r[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
                setColorValue(v, Color4b(col.r, col.g, col.b, (t * 255f).roundToInt()))
            }
        }
    }

    override fun onEnabled() {
        openAnim = 0f
        scaleAnim = 0f
        positionsReady = false
        keyListen = null
        bindMod = null
        moduleCacheTime = 0L
        moduleCacheSize = -1
        allModules()
        openLayer()
    }

    override fun onDisabled() {
        closeLayer()
        sliderDrag = null
        colorDrag = null
        bindMod = null
        keyListen = null
        textEditValue = null
        dragIdx = -1
        scrollAccum = 0.0
        catPos.forEach { it.dragging = false }
        enumOpen.clear()
        colorOpen.clear()
        groupOpen.clear()
        modOpen.clear()
        modAnim.clear()
        modScale.clear()
        sliderEase.clear()
        boolScale.clear()
        sliderRects.clear()
        pickerRects.clear()
        modNameCache.clear()
    }

    // ======================== 渲染 ========================

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
        val steps = (20 + panelGlowLayers * 2).coerceIn(22, 36)
        val bottom = y + h
        val glowTop = y + 1f
        val glowH = (bottom - glowTop).coerceAtLeast(4f)
        for (i in 0 until steps) {
            val t0 = i / steps.toFloat()
            val t1 = (i + 1) / steps.toFloat()
            val mid = (t0 + t1) * 0.5f
            val fall = (1f - mid).toDouble().pow((1.1f + soft * 0.6f).toDouble()).toFloat()
            val aa = (fall * strength * 80f * anim).toInt().coerceIn(0, 100)
            if (aa < 2) continue
            val yBottom = bottom - glowH * t0
            val yTop = bottom - glowH * t1
            if (yBottom <= glowTop) continue
            val drawTop = max(yTop, glowTop)
            val sideInset = maxR * 0.1f * mid
            ctx.drawQuad(
                x - maxR * 0.2f + sideInset,
                drawTop,
                x + w + maxR * 0.2f - sideInset,
                yBottom,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
        val baseR = cornerRadius.coerceAtLeast(2f)
        for (j in 1..3) {
            val u = j / 3f
            val expand = maxR * 0.4f * u
            val aa = ((1f - u) * strength * 32f * anim).toInt().coerceIn(0, 45)
            if (aa < 2) continue
            ctx.drawRoundedRect(
                x - expand * 0.4f,
                bottom - baseR * 0.5f,
                x + w + expand * 0.4f,
                bottom + expand,
                baseR + expand * 0.25f,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
    }

    /** 仅顶部圆角、底部直角的标题栏 */
    private fun drawHeaderTopRound(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        radius: Float, color: Color4b,
    ) {
        val r = radius.coerceIn(0f, minOf(h / 2f, w / 2f))
        ctx.drawRoundedRect(x, y, x + w, y + h, r, color)
        if (r > 0.5f) {
            ctx.drawQuad(x, y + h - r, x + w, y + h, color)
        }
    }

    /** 行背景: 像素对齐; 不透明时向下 1px 重叠消缝 */
    private fun drawSeamlessRow(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        color: Color4b,
    ) {
        val x0 = kotlin.math.floor(x.toDouble()).toFloat()
        val y0 = kotlin.math.floor(y.toDouble()).toFloat()
        val x1 = kotlin.math.ceil((x + w).toDouble()).toFloat()
        var y1 = kotlin.math.ceil((y + h).toDouble()).toFloat()
        if (color.a >= 250) y1 += 1f
        ctx.drawQuad(x0, y0, x1, y1, color)
    }

    private fun shortenName(v: Value<*>, font: Font, maxW: Int): String {
        val vi = info(v)
        if (vi.shortNameW == maxW) return vi.shortName
        var n = v.name
        while (n.length > 2 && font.width(n) > maxW) n = n.dropLast(1)
        if (n != v.name) n = n.dropLast(1) + ".."
        vi.shortName = n
        vi.shortNameW = maxW
        return n
    }

    private fun shorten(s: String, font: Font, maxW: Int): String {
        if (font.width(s) <= maxW) return s
        var r = s
        while (r.length > 1 && font.width(r) > maxW) r = r.dropLast(1)
        return if (r.length > 1) r.dropLast(1) + ".." else r
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled && openAnim < 0.01f) {
            lastNs = 0L
            return@handler
        }

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        frameDt = dt

        val speed = (easeSpeed / 10f) * dt
        if (enabled) {
            openAnim = (openAnim + speed).coerceIn(0f, 1f)
            scaleAnim = (scaleAnim + speed).coerceIn(0f, 1f)
        } else {
            openAnim = (openAnim - speed * 2f).coerceIn(0f, 1f)
            scaleAnim = (scaleAnim - speed * 2f).coerceIn(0f, 1f)
        }
        val anim = easeOutExpo(openAnim)
        val s = inScale().coerceAtLeast(0.02f)
        if (anim < 0.001f) return@handler

        val ctx = event.context
        val font = mc.font
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        val cx = sw / 2f
        val cy = sh / 2f

        // 视图变换记录(点击逆变换用); 所有命中矩形/绘制都在未缩放坐标系
        viewScale = s
        viewCx = cx
        viewCy = cy
        viewW = sw
        viewH = sh
        mouseX = cx + (guiMX() - cx) / s
        mouseY = cy + (guiMY() - cy) / s

        // 主题相位每帧一次
        val cycleMs = (themeCycle * 1000).toLong().coerceAtLeast(1L)
        themeT = (System.currentTimeMillis() % cycleMs) / cycleMs.toFloat()

        val newCats = allCategories()
        val key = "${sw}_${catWidth}_${catGap}_${catHeight}_${newCats.size}"
        if (!positionsReady || key != layoutKey) {
            cats = newCats
            layoutKey = key
            initPositions(sw)
        }

        if (dragIdx in catPos.indices && catPos[dragIdx].dragging) {
            val p = catPos[dragIdx]
            p.x = ((mouseX - dragOx).coerceIn(0f, (sw - catWidth).coerceAtLeast(0f)) / 2f).roundToInt() * 2f
            p.y = ((mouseY - dragOy).coerceIn(0f, (sh - catHeight).coerceAtLeast(0f)) / 2f).roundToInt() * 2f
        }

        // 拖动应用(每帧)
        sliderDrag?.let { applySlider(it, mid = false) }
        colorDrag?.let { applyPicker(it) }

        // 全屏遮罩 (不参与缩放)
        ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (255 * dimAlpha * anim).toInt().coerceIn(0, 200)))

        if (bottomGlow) {
            val firstH = lerp(sh, sh - sh / 3f, s)
            val col = themed(0f)
            for (i in 0 until 16) {
                val t0 = i / 16f
                val t1 = (i + 1) / 16f
                val y0 = lerp(firstH, sh, t0)
                val y1 = lerp(firstH, sh, t1)
                val mid = (t0 + t1) * 0.5f
                val aa = (bottomGlowStrength * anim * mid * 180f).toInt().coerceIn(0, 180)
                if (aa < 2) continue
                ctx.drawQuad(0f, y0, sw, y1, Color4b(col.r, col.g, col.b, aa))
            }
        }

        tooltip = ""

        // ===== 缩放绘制: 之后所有坐标均为未缩放 GUI 坐标 =====
        ctx.pose().withPush {
            translate(cx, cy)
            scale(s, s)
            translate(-cx, -cy)

            // 滚轮: 只作用于悬停面板 (未缩放坐标命中测试)
            if (scrollAccum != 0.0) {
                for (i in cats.indices) {
                    if (i >= catPos.size) break
                    val p = catPos[i]
                    if (!p.extended || p.extAnim < 0.9f) continue
                    val mods = modulesIn(cats[i])
                    val m = metricsFor(p, mods, sh)
                    if (m.listH <= 0.5f) continue
                    if (hover(p.x, p.y, catWidth, catHeight + m.listH + 8f)) {
                        p.scrollTarget = (p.scrollTarget - scrollAccum.toFloat() * rowH * 2.5f)
                            .coerceIn(0f, m.maxScroll)
                        scrollAccum = 0.0
                        break
                    }
                }
                scrollAccum = 0.0
            }

            for (i in cats.indices) {
                if (i >= catPos.size) break
                val p = catPos[i]
                val modList = modulesIn(cats[i])

                updateAnims(modList, dt)
                p.extAnim = lerp(p.extAnim, if (p.extended) 1f else 0f, (dt * 14f).coerceIn(0f, 1f))

                val m = metricsFor(p, modList, sh)
                p.scroll = lerp(p.scroll, p.scrollTarget, (dt * 14f).coerceIn(0f, 1f))
                    .coerceIn(0f, m.maxScroll)

                val f = p.extAnim
                val bottomPad = cornerRadius.coerceAtLeast(6f) * f
                val panelH = catHeight + m.listH + bottomPad
                val r = cornerRadius

                drawPanelGlow(ctx, p.x, p.y, catWidth, panelH, anim)

                if (f < 0.995f) {
                    ctx.drawRoundedRect(
                        p.x, p.y, p.x + catWidth, p.y + catHeight, r, a(bgCategory, anim),
                    )
                } else {
                    ctx.drawRoundedRect(
                        p.x, p.y, p.x + catWidth, p.y + panelH, r, a(bgModule, anim),
                    )
                    drawHeaderTopRound(ctx, p.x, p.y, catWidth, catHeight, r, a(bgCategory, anim))
                }

                var catName = categoryLabel(cats[i])
                val titlePad = max(8f, catWidth * 0.1f)
                val maxTitleW = (catWidth - titlePad * 2f).toInt().coerceAtLeast(10)
                while (catName.length > 2 && font.width(catName) > maxTitleW) {
                    catName = catName.dropLast(1)
                }
                val tw = font.width(catName)
                ctx.text(
                    font, catName,
                    (p.x + (catWidth - tw) / 2f).roundToInt(),
                    (p.y + (catHeight - 8) / 2f).roundToInt(),
                    a(textMain, anim).argb, false,
                )

                if (f <= 0.001f) continue

                val clipTop = p.y + catHeight
                val clipBot = m.clipBot
                if (clipBot <= clipTop + 2f) continue

                ctx.scissorStack.withPush(ctx.getBounds(p.x, clipTop, p.x + catWidth, clipBot)) {
                    walkSettings(modList, f, m.listH) { row ->
                        val ry = clipTop + row.y - p.scroll
                        if (ry + row.h <= clipTop + 1f || ry >= clipBot - 1f) return@walkSettings

                        val mod = row.mod
                        if (mod != null) {
                            drawModuleRow(ctx, font, mod, p, ry, anim, i)
                            return@walkSettings
                        }

                        val v = row.value ?: return@walkSettings

                        if (row.choice != null) {
                            drawChoiceRow(ctx, font, v, row.choice!!, p.x, ry, catWidth, row.h, anim * f)
                            return@walkSettings
                        }
                        if (row.channel >= 0) {
                            drawPickerRow(ctx, v, row.channel, p.x, ry, catWidth, row.h, anim * f)
                            return@walkSettings
                        }

                        drawSetting(ctx, font, v, p.x, ry, catWidth, row.h, anim * f, row.indent)
                    }
                }
            }
        }

        bindMod?.let { tooltip = "Binding ${it.name}... press key (ESC = none)" }
        keyListen?.let { tooltip = "Press key to bind '${it.name}' (ESC = none)" }

        if (tooltip.isNotEmpty()) {
            val pad = 4f
            val tw2 = font.width(tooltip).toFloat()
            val th = 12f
            val tx = (guiMX() + 10f).coerceAtMost(sw - tw2 - pad * 2)
            val ty = (guiMY() - th - 6f).coerceAtLeast(2f)
            ctx.drawRoundedRect(tx, ty, tx + tw2 + pad * 2, ty + th + pad, 3f, Color4b(20, 20, 20, (230 * anim).toInt()))
            ctx.text(font, tooltip, (tx + pad).roundToInt(), (ty + pad / 2f).roundToInt(), a(textMain, anim).argb, false)
        }
    }

    private fun initPositions(sw: Float) {
        catPos.clear()
        val total = cats.size * (catWidth + catGap)
        var x = sw / 2f - total / 2f
        for (i in cats.indices) {
            catPos.add(CatPos(x = (x / 2f).roundToInt() * 2f, y = catGap * 2f))
            x += catWidth + catGap
        }
        positionsReady = true
    }

    private fun drawModuleRow(
        ctx: GuiGraphicsExtractor,
        font: Font,
        mod: ClientModule,
        p: CatPos,
        ry: Float,
        anim: Float,
        catIdx: Int,
    ) {
        val x = p.x
        val w = catWidth
        val h = rowH * p.extAnim
        val cScale = modScale.getOrDefault(mod, if (mod.enabled) 1f else 0f)

        drawSeamlessRow(ctx, x, ry, w, h, a(bgModule, anim))
        if (cScale > 0.01f) {
            val g1 = themed(ry * 2f + catIdx * 7f)
            val g2 = themed(ry * 2f + 40f + catIdx * 7f)
            for (seg in 0 until 6) {
                val t0 = seg / 6f
                val col = Color4b(
                    lerp(g1.r.toFloat(), g2.r.toFloat(), t0).toInt(),
                    lerp(g1.g.toFloat(), g2.g.toFloat(), t0).toInt(),
                    lerp(g1.b.toFloat(), g2.b.toFloat(), t0).toInt(),
                    (255 * anim * cScale).toInt().coerceIn(0, 255),
                )
                val sx = x + w * t0
                val ex = x + w * ((seg + 1) / 6f)
                ctx.drawQuad(sx, ry, ex, ry + h + 1f, col)
            }
        }

        val hovered = hover(x, ry, w, h)
        val nc = if (mod.enabled) textMain else textDim
        val pad = max(6f, w * 0.08f)
        val maxW = (w - pad * 2f).toInt().coerceAtLeast(12)

        // 悬停时右侧显示绑定键
        var bindText: String? = null
        if (hovered && !mod.bind.isUnbound) {
            bindText = bindLabel(mod.bind)
        }
        val bindW = if (bindText != null) font.width(bindText) + 8 else 0

        val show = shortModName(mod, font, (maxW - bindW).coerceAtLeast(12))
        val nw = font.width(show)
        ctx.text(
            font, show,
            (x + (w - nw) / 2f).roundToInt(),
            (ry + (h - 8) / 2f).roundToInt(),
            a(nc, anim).argb, false,
        )
        if (bindText != null) {
            ctx.text(
                font, bindText,
                (x + w - pad - font.width(bindText)).roundToInt(),
                (ry + (h - 8) / 2f).roundToInt(),
                a(textDim, anim * 0.8f).argb, false,
            )
        }
        if (hovered && bindMod == null && keyListen == null) tooltip = modDesc(mod)
    }

    /** 左名右值 */
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
        val maxValW = (w * 0.42f).toInt().coerceAtLeast(12)
        var v = value
        if (font.width(v) > maxValW) {
            v = shorten(v, font, maxValW)
        }
        val maxNameW = (w - pad * 2f - font.width(v) - 8f).toInt().coerceAtLeast(16)
        val n = shorten(name, font, maxNameW)
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
        indent: Int,
    ) {
        drawSeamlessRow(ctx, x, y, w, h, a(bgSetting, anim))
        val vi = info(v)
        val ix = x + indent * 7f
        val iw = w - indent * 7f
        val name = shortenName(v, font, (iw * 0.55f).toInt().coerceAtLeast(14))
        val ty = (y + (h - 8) / 2f).roundToInt()

        // 悬停提示: 显示设置描述(有翻译时)
        if (hover(x, y, w, h) && bindMod == null && keyListen == null && textEditValue == null &&
            sliderDrag == null && colorDrag == null
        ) {
            val d = runCatching { v.description.get()?.string }.getOrNull()
            if (!d.isNullOrBlank() && !d.startsWith("liquidbounce.")) {
                tooltip = "$name: $d"
            }
        }

        when (vi.kind) {
            Kind.GROUP -> {
                val open = groupOpen[v] == true
                if (childrenOf(v).isNotEmpty()) {
                    ctx.text(font, if (open) "v" else ">", (ix + 4f).roundToInt(), ty, a(textDim, anim).argb, false)
                }
                drawLabelValue(ctx, font, name, "", ix + 10f, y, iw - 10f, h, anim, textMain, textDim)
            }
            Kind.TOGGLE_GROUP -> runCatching {
                val g = v as ToggleableValueGroup
                if (childrenOf(v).isNotEmpty()) {
                    ctx.text(font, if (groupOpen[v] == true) "v" else ">", (ix + 4f).roundToInt(), ty, a(textDim, anim).argb, false)
                }
                drawLabelValue(
                    ctx, font, name, if (g.enabled) "ON" else "OFF",
                    ix + 10f, y, iw - 10f, h, anim, textMain,
                    if (g.enabled) themed(y) else textDim,
                )
            }
            Kind.MODE -> {
                val expanded = groupOpen[v] == true
                if (childrenOf(v).isNotEmpty()) {
                    ctx.text(font, if (expanded) "v" else ">", (ix + 4f).roundToInt(), ty, a(textDim, anim).argb, false)
                }
                val mode = currentChoiceLabel(v)
                drawLabelValue(ctx, font, name, mode, ix + 10f, y, iw - 10f, h, anim, textMain, themed(y))
            }
            Kind.CHOICE -> drawLabelValue(ctx, font, name, currentChoiceLabel(v), ix, y, iw, h, anim, textMain, themed(y))
            Kind.MULTI -> drawLabelValue(ctx, font, name, multiLabel(v), ix, y, iw, h, anim, textMain, themed(y))
            Kind.BOOL -> {
                val on = runCatching { v.get() as Boolean }.getOrDefault(false)
                boolScale[v] = lerp(
                    boolScale.getOrDefault(v, if (on) 1f else 0f),
                    if (on) 1f else 0f,
                    (frameDt * 12f).coerceIn(0f, 1f),
                )
                drawLabelValue(ctx, font, name, if (on) "ON" else "OFF", ix, y, iw, h, anim, textMain, if (on) themed(y) else textDim)
            }
            Kind.SLIDER -> {
                val fv = runCatching { (v.get() as Number).toFloat() }.getOrDefault(0f)
                val range = vi.range ?: (0f to 1f)
                val (minV, maxV) = range
                val span = (maxV - minV).coerceAtLeast(0.001f)
                val p = ((fv - minV) / span).coerceIn(0f, 1f)
                sliderRects[v] = floatArrayOf(x, y, w, h)
                drawLabelValue(ctx, font, name, formatNumber(fv) + vi.suffix, ix, y, iw, h - 6f, anim, textMain, textDim)
                val target = p * w
                sliderEase[v] = if (sliderDrag === v) {
                    target
                } else {
                    lerp(sliderEase.getOrDefault(v, target), target, (frameDt * 18f).coerceIn(0f, 1f))
                }
                val se = sliderEase.getOrDefault(v, target).coerceIn(0f, w)
                val barY = y + h - 5f
                ctx.drawQuad(x, barY, x + w, barY + 3f, a(Color4b(50, 50, 50, 255), anim))
                ctx.drawRoundedRect(x, barY, x + se.coerceAtLeast(2f), barY + 3.5f, 2f, a(themed(y), anim))
            }
            Kind.DUAL -> {
                val range = vi.range ?: (0f to 20f)
                val (bMin, bMax) = range
                val cur = runCatching {
                    val r = v.get() as ClosedRange<*>
                    ((r.start as? Number)?.toFloat() ?: bMin) to ((r.endInclusive as? Number)?.toFloat() ?: bMax)
                }.getOrDefault(bMin to bMax)
                val span = (bMax - bMin).coerceAtLeast(0.001f)
                val t0 = ((cur.first - bMin) / span).coerceIn(0f, 1f)
                val t1 = ((cur.second - bMin) / span).coerceIn(0f, 1f)
                sliderRects[v] = floatArrayOf(x, y, w, h)
                val vs = if (vi.isInt) "${cur.first.roundToInt()}-${cur.second.roundToInt()}"
                else "${formatNumber(cur.first)}-${formatNumber(cur.second)}"
                drawLabelValue(ctx, font, name, vs + vi.suffix, ix, y, iw, h - 8f, anim, textMain, textDim)
                val barY = y + h - 6f
                ctx.drawQuad(x, barY, x + w, barY + 3f, a(Color4b(50, 50, 50, 255), anim))
                ctx.drawQuad(x + w * t0, barY, x + w * t1, barY + 3f, a(themed(y), anim))
                ctx.drawRoundedRect(x + w * t0 - 3f, barY - 2f, x + w * t0 + 3f, barY + 5f, 3f, a(textMain, anim))
                ctx.drawRoundedRect(x + w * t1 - 3f, barY - 2f, x + w * t1 + 3f, barY + 5f, 3f, a(textMain, anim))
            }
            Kind.COLOR -> {
                val col = colorOf(v)
                drawLabelValue(ctx, font, name, hexLabel(col), ix, y, iw - 22f, h, anim, textMain, textDim)
                val cx0 = x + w - 18f
                ctx.drawRoundedRect(cx0, y + 3f, cx0 + 14f, y + h - 3f, 3f, a(col, anim))
                ctx.text(
                    font, if (colorOpen[v] == true) "v" else ">",
                    (cx0 - 10f).roundToInt(), ty, a(textDim, anim).argb, false,
                )
            }
            Kind.TEXT -> {
                val editing = textEditValue === v
                val shown = if (editing) {
                    textBuffer + if ((System.currentTimeMillis() / 400) % 2L == 0L) "_" else ""
                } else {
                    runCatching {
                        when (val cur = v.get()) {
                            is Regex -> cur.pattern.ifBlank { "..." }
                            else -> (cur as? String)?.ifBlank { "..." }
                        }
                    }.getOrDefault("...") ?: "..."
                }
                drawLabelValue(ctx, font, name, shown, ix, y, iw, h, anim, textMain, if (editing) themed(y) else textDim)
            }
            Kind.KEY -> drawLabelValue(
                ctx, font, name,
                if (keyListen === v) "..." else keyLabel(v),
                ix, y, iw, h, anim, textMain, themed(y),
            )
            Kind.BIND -> drawLabelValue(
                ctx, font, name,
                if (keyListen === v) "..." else runCatching { bindLabel(v.get() as InputBind) }.getOrDefault("None"),
                ix, y, iw, h, anim, textMain, themed(y),
            )
            Kind.OTHER -> drawLabelValue(ctx, font, name, otherLabel(v), ix, y, iw, h, anim, textMain, textDim)
        }
    }

    /** 下拉选项行(CHOICE/MULTI/MODE) */
    private fun drawChoiceRow(
        ctx: GuiGraphicsExtractor,
        font: Font,
        v: Value<*>,
        choice: Any,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
    ) {
        drawSeamlessRow(ctx, x, y, w, h, a(Color4b(20, 20, 20, 255), anim))
        val kind = info(v).kind
        val label = taggedLabel(choice)
        val selected = when (kind) {
            Kind.MODE -> isModeSelected(v, choice)
            Kind.MULTI -> multiSelected(v).contains(choice)
            else -> runCatching { v.get() == choice }.getOrDefault(false)
        }
        val ty = (y + (h - 8) / 2f).roundToInt()
        if (kind == Kind.MULTI) {
            ctx.text(
                font, if (selected) "[x]" else "[ ]",
                (x + 6f).roundToInt(), ty,
                a(if (selected) themed(y) else textDim, anim).argb, false,
            )
        } else if (selected) {
            ctx.drawQuad(x, y, x + 2f, y + h, a(themed(y), anim))
        }
        val show = shorten(label, font, (w - 20f).toInt().coerceAtLeast(10))
        ctx.text(
            font, show,
            (x + 14f).roundToInt(), ty,
            a(if (selected) textMain else textDim, anim).argb, false,
        )
    }

    /** 调色板行: 0=SB 面板 1=色相条 2=透明度条 */
    private fun drawPickerRow(
        ctx: GuiGraphicsExtractor,
        v: Value<*>,
        channel: Int,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
    ) {
        val col = colorOf(v)
        val (hsvH, hsvS, hsvV) = colorToHsv(col)
        val hue = pickerHue.getOrDefault(v, hsvH)
        drawSeamlessRow(ctx, x, y, w, h, a(Color4b(20, 20, 20, 255), anim))
        val pad = 6f
        val bw = w - pad * 2f

        when (channel) {
            0 -> {
                val cols = 20
                val rows = 12
                val cw = bw / cols
                val chh = (h - 6f) / rows
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val sx = (c + 0.5f) / cols
                        val sy = (r + 0.5f) / rows
                        val cc = hsvToColor(hue, sx, 1f - sy)
                        ctx.drawQuad(
                            x + pad + cw * c, y + 3f + chh * r,
                            x + pad + cw * (c + 1), y + 3f + chh * (r + 1),
                            a(cc, anim),
                        )
                    }
                }
                ctx.drawRoundedRect(
                    x + pad - 1f, y + 2f, x + pad + bw + 1f, y + h - 1f,
                    2f, Color4b.TRANSPARENT, Color4b(0, 0, 0, (140 * anim).toInt().coerceIn(0, 255)), 1f,
                )
                // 光标
                val px = x + pad + hsvS.coerceIn(0f, 1f) * bw
                val py = y + 3f + (1f - hsvV.coerceIn(0f, 1f)) * (h - 6f)
                ctx.drawRoundedRect(
                    px - 3f, py - 3f, px + 3f, py + 3f, 3f,
                    Color4b.TRANSPARENT,
                    Color4b(255, 255, 255, (255 * anim).toInt().coerceIn(0, 255)), 1.5f,
                )
                pickerRects.computeIfAbsent(v) { Array(3) { FloatArray(4) } }[0] =
                    floatArrayOf(x + pad, y + 3f, bw, h - 6f)
            }
            1 -> {
                val segs = 24
                val segW = bw / segs
                for (i in 0 until segs) {
                    val t = (i + 0.5f) / segs
                    ctx.drawQuad(
                        x + pad + segW * i, y + 4f,
                        x + pad + segW * (i + 1), y + h - 4f,
                        a(hsvToColor(t, 1f, 1f), anim),
                    )
                }
                val px = x + pad + hue.coerceIn(0f, 1f) * bw
                ctx.drawRoundedRect(
                    px - 2f, y + 3f, px + 2f, y + h - 3f, 2f,
                    Color4b(255, 255, 255, (255 * anim).toInt().coerceIn(0, 255)),
                    Color4b(0, 0, 0, (120 * anim).toInt().coerceIn(0, 255)), 1f,
                )
                pickerRects.computeIfAbsent(v) { Array(3) { FloatArray(4) } }[1] =
                    floatArrayOf(x + pad, y + 3f, bw, h - 6f)
            }
            2 -> {
                // 棋盘底
                val cells = 12
                val rowsC = 3
                val cw2 = bw / cells
                val ch2 = (h - 8f) / rowsC
                for (r in 0 until rowsC) {
                    for (c in 0 until cells) {
                        val g = if ((r + c) % 2 == 0) 60 else 90
                        ctx.drawQuad(
                            x + pad + cw2 * c, y + 4f + ch2 * r,
                            x + pad + cw2 * (c + 1), y + 4f + ch2 * (r + 1),
                            a(Color4b(g, g, g, 255), anim),
                        )
                    }
                }
                // 透明度渐变(色相/饱和度保持)
                val segs = 16
                val segW = bw / segs
                for (i in 0 until segs) {
                    val t = (i + 0.5f) / segs
                    val cc = hsvToColor(hue, hsvS, hsvV, (t * 255f).toInt())
                    ctx.drawQuad(
                        x + pad + segW * i, y + 4f,
                        x + pad + segW * (i + 1), y + h - 4f,
                        a(cc, anim),
                    )
                }
                val px = x + pad + (col.a / 255f).coerceIn(0f, 1f) * bw
                ctx.drawRoundedRect(
                    px - 2f, y + 3f, px + 2f, y + h - 3f, 2f,
                    Color4b(255, 255, 255, (255 * anim).toInt().coerceIn(0, 255)),
                    Color4b(0, 0, 0, (120 * anim).toInt().coerceIn(0, 255)), 1f,
                )
                pickerRects.computeIfAbsent(v) { Array(3) { FloatArray(4) } }[2] =
                    floatArrayOf(x + pad, y + 3f, bw, h - 6f)
            }
        }
    }
}
