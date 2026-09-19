package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui.setting

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui.ClickGuiColors
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import kotlin.math.max
import kotlin.math.roundToInt

object ClickGuiSettingRenderer {

    const val ROW_HEIGHT = 28f
    const val SLIDER_HEIGHT = 36f
    const val COLOR_PICKER_HEIGHT = 96f
    const val PAD_X = 10f
    const val NEST_INDENT = 8f

    /** Module name -> set of open nested group paths */
    private val openGroups = mutableMapOf<String, MutableSet<String>>()
    /** Module name -> open color picker value name */
    private val openColorPickers = mutableMapOf<String, String>()

    fun isColorPickerOpen(moduleName: String, valueName: String) =
        openColorPickers[moduleName] == valueName

    fun toggleColorPicker(moduleName: String, valueName: String) {
        val cur = openColorPickers[moduleName]
        if (cur == valueName) openColorPickers.remove(moduleName)
        else openColorPickers[moduleName] = valueName
    }

    fun isGroupOpen(moduleName: String, path: String) =
        openGroups[moduleName]?.contains(path) == true

    fun toggleGroup(moduleName: String, path: String) {
        val set = openGroups.getOrPut(moduleName) { mutableSetOf() }
        if (!set.add(path)) set.remove(path)
    }

    fun measureHeight(value: Value<*>, moduleName: String = "", path: String = value.name): Float {
        if (!value.visibleCondition.asBoolean) return 0f
        return when (value.valueType) {
            ValueType.BOOLEAN -> ROW_HEIGHT
            ValueType.INT, ValueType.FLOAT -> SLIDER_HEIGHT
            ValueType.INT_RANGE, ValueType.FLOAT_RANGE -> SLIDER_HEIGHT
            ValueType.TEXT, ValueType.KEY, ValueType.BIND -> ROW_HEIGHT
            ValueType.CHOOSE, ValueType.CHOICE, ValueType.MULTI_CHOOSE -> ROW_HEIGHT + 4f
            ValueType.COLOR -> {
                var h = ROW_HEIGHT
                if (isColorPickerOpen(moduleName, value.name)) h += COLOR_PICKER_HEIGHT
                h
            }
            ValueType.CONFIGURABLE, ValueType.TOGGLEABLE -> {
                var h = ROW_HEIGHT
                if (value is ValueGroup && isGroupOpen(moduleName, path)) {
                    for (child in value.containedValues) {
                        if (child.visibleCondition.asBoolean && !child.notAnOption) {
                            h += measureHeight(child, moduleName, "$path.${child.name}")
                        }
                    }
                }
                h
            }
            else -> ROW_HEIGHT
        }
    }

    fun render(
        ctx: GuiGraphicsExtractor,
        value: Value<*>,
        x: Float, y: Float, width: Float,
        mouseX: Float, mouseY: Float,
        moduleName: String = "",
        path: String = value.name
    ): Float {
        if (!value.visibleCondition.asBoolean) return 0f
        return when (value.valueType) {
            ValueType.BOOLEAN -> renderBoolean(ctx, value, x, y, width, mouseX, mouseY)
            ValueType.INT, ValueType.FLOAT -> renderSlider(ctx, value, x, y, width, mouseX, mouseY)
            ValueType.INT_RANGE, ValueType.FLOAT_RANGE -> renderRangeSlider(ctx, value, x, y, width, mouseX, mouseY)
            ValueType.TEXT -> renderText(ctx, value, x, y, width)
            ValueType.CHOOSE, ValueType.CHOICE -> renderChoice(ctx, value, x, y, width, mouseX, mouseY)
            ValueType.COLOR -> renderColor(ctx, value, x, y, width, mouseX, mouseY, moduleName)
            ValueType.CONFIGURABLE, ValueType.TOGGLEABLE ->
                renderGroup(ctx, value, x, y, width, mouseX, mouseY, moduleName, path)
            else -> renderGeneric(ctx, value, x, y, width)
        }
    }

    fun mouseClicked(
        value: Value<*>,
        x: Float, y: Float, width: Float,
        mx: Float, my: Float, button: Int,
        moduleName: String = "",
        path: String = value.name
    ): Boolean {
        if (!value.visibleCondition.asBoolean) return false
        val h = measureHeight(value, moduleName, path)
        if (mx !in x..(x + width) || my !in y..(y + h)) return false
        if (button != 0) return false

        return when (value.valueType) {
            ValueType.BOOLEAN -> {
                @Suppress("UNCHECKED_CAST")
                (value as Value<Boolean>).set(!value.get())
                true
            }
            ValueType.INT, ValueType.FLOAT -> {
                applySliderClick(value, x, y, width, mx, single = true)
                true
            }
            ValueType.INT_RANGE, ValueType.FLOAT_RANGE -> {
                applyRangeClick(value, x, y, width, mx)
                true
            }
            ValueType.CHOOSE, ValueType.CHOICE -> {
                cycleChoice(value)
                true
            }
            ValueType.COLOR -> {
                if (my in y..(y + ROW_HEIGHT)) {
                    toggleColorPicker(moduleName, value.name)
                    true
                } else if (isColorPickerOpen(moduleName, value.name)) {
                    applyColorPickerClick(value, x, y + ROW_HEIGHT, width, mx, my)
                    true
                } else true
            }
            ValueType.CONFIGURABLE, ValueType.TOGGLEABLE -> {
                if (my in y..(y + ROW_HEIGHT)) {
                    toggleGroup(moduleName, path)
                    true
                } else if (value is ValueGroup && isGroupOpen(moduleName, path)) {
                    var sy = y + ROW_HEIGHT
                    for (child in value.containedValues) {
                        if (!child.visibleCondition.asBoolean || child.notAnOption) continue
                        val ch = measureHeight(child, moduleName, "$path.${child.name}")
                        if (my in sy..(sy + ch)) {
                            return mouseClicked(child, x + NEST_INDENT, sy, width - NEST_INDENT, mx, my, button, moduleName, "$path.${child.name}")
                        }
                        sy += ch
                    }
                    true
                } else true
            }
            else -> true
        }
    }

    fun mouseDragged(
        value: Value<*>,
        x: Float, y: Float, width: Float,
        mx: Float, my: Float,
        moduleName: String = "",
        path: String = value.name
    ): Boolean {
        if (!value.visibleCondition.asBoolean) return false
        val h = measureHeight(value, moduleName, path)
        if (mx !in x..(x + width) || my !in y..(y + h)) return false
        return when (value.valueType) {
            ValueType.INT, ValueType.FLOAT -> {
                applySliderClick(value, x, y, width, mx, single = true); true
            }
            ValueType.INT_RANGE, ValueType.FLOAT_RANGE -> {
                applyRangeClick(value, x, y, width, mx); true
            }
            ValueType.COLOR -> {
                if (isColorPickerOpen(moduleName, value.name) && my > y + ROW_HEIGHT) {
                    applyColorPickerClick(value, x, y + ROW_HEIGHT, width, mx, my); true
                } else false
            }
            ValueType.CONFIGURABLE, ValueType.TOGGLEABLE -> {
                if (value is ValueGroup && isGroupOpen(moduleName, path)) {
                    var sy = y + ROW_HEIGHT
                    for (child in value.containedValues) {
                        if (!child.visibleCondition.asBoolean || child.notAnOption) continue
                        val ch = measureHeight(child, moduleName, "$path.${child.name}")
                        if (my in sy..(sy + ch)) {
                            return mouseDragged(child, x + NEST_INDENT, sy, width - NEST_INDENT, mx, my, moduleName, "$path.${child.name}")
                        }
                        sy += ch
                    }
                }
                false
            }
            else -> false
        }
    }

    // ── Boolean ─────────────────────────────────────────────────────────────

    private fun renderBoolean(
        ctx: GuiGraphicsExtractor, value: Value<*>,
        x: Float, y: Float, width: Float, mouseX: Float, mouseY: Float
    ): Float {
        val c = ClickGuiColors
        val font = mc.font
        val enabled = (value.get() as? Boolean) == true
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT)
        if (hovered) ctx.drawQuadXYWH(x, y, width, ROW_HEIGHT, c.MODULE_HOVER_BG)

        ctx.text(font, value.name.asPlainText(), (x + PAD_X).toInt(), (y + (ROW_HEIGHT - 8f) / 2f).toInt(), c.TEXT_DIMMED.argb, false)

        val sw = 28f; val sh = 14f
        val sx = x + width - PAD_X - sw
        val sy = y + (ROW_HEIGHT - sh) / 2f
        ctx.drawRoundedRect(sx, sy, sx + sw, sy + sh, sh / 2f, fillColor = if (enabled) c.ACCENT else Color4b(80, 80, 80, 200))
        val knobR = 5f
        val kx = if (enabled) sx + sw - knobR - 3f else sx + knobR + 3f
        val ky = sy + sh / 2f
        ctx.drawRoundedRect(kx - knobR, ky - knobR, kx + knobR, ky + knobR, knobR, fillColor = Color4b(255, 255, 255, 255))
        return ROW_HEIGHT
    }

    // ── Single slider ───────────────────────────────────────────────────────

    private fun renderSlider(
        ctx: GuiGraphicsExtractor, value: Value<*>,
        x: Float, y: Float, width: Float, mouseX: Float, mouseY: Float
    ): Float {
        val c = ClickGuiColors
        val font = mc.font
        if (mouseX in x..(x + width) && mouseY in y..(y + SLIDER_HEIGHT))
            ctx.drawQuadXYWH(x, y, width, SLIDER_HEIGHT, c.MODULE_HOVER_BG)

        val (progress, display) = sliderState(value)
        ctx.text(font, "${value.name}: $display".asPlainText(), (x + PAD_X).toInt(), (y + 6f).toInt(), c.TEXT_DIMMED.argb, false)

        val trackX = x + PAD_X; val trackW = width - PAD_X * 2; val trackY = y + 22f; val trackH = 4f
        ctx.drawRoundedRect(trackX, trackY, trackX + trackW, trackY + trackH, 2f, fillColor = Color4b(60, 60, 60, 220))
        val fillW = trackW * progress.coerceIn(0f, 1f)
        if (fillW > 0.5f) ctx.drawRoundedRect(trackX, trackY, trackX + fillW, trackY + trackH, 2f, fillColor = c.ACCENT)
        val kx = trackX + fillW
        ctx.drawRoundedRect(kx - 5f, trackY - 3f, kx + 5f, trackY + trackH + 3f, 5f, fillColor = Color4b(255, 255, 255, 255))
        return SLIDER_HEIGHT
    }

    // ── Dual-thumb range ────────────────────────────────────────────────────

    private fun renderRangeSlider(
        ctx: GuiGraphicsExtractor, value: Value<*>,
        x: Float, y: Float, width: Float, mouseX: Float, mouseY: Float
    ): Float {
        val c = ClickGuiColors
        val font = mc.font
        if (mouseX in x..(x + width) && mouseY in y..(y + SLIDER_HEIGHT))
            ctx.drawQuadXYWH(x, y, width, SLIDER_HEIGHT, c.MODULE_HOVER_BG)

        val (p0, p1, label) = rangeState(value)
        ctx.text(font, "${value.name}: $label".asPlainText(), (x + PAD_X).toInt(), (y + 6f).toInt(), c.TEXT_DIMMED.argb, false)

        val trackX = x + PAD_X; val trackW = width - PAD_X * 2; val trackY = y + 22f; val trackH = 4f
        ctx.drawRoundedRect(trackX, trackY, trackX + trackW, trackY + trackH, 2f, fillColor = Color4b(60, 60, 60, 220))
        val a = trackX + trackW * p0.coerceIn(0f, 1f)
        val b = trackX + trackW * p1.coerceIn(0f, 1f)
        ctx.drawRoundedRect(a, trackY, b, trackY + trackH, 2f, fillColor = c.ACCENT)
        // two knobs
        for (kx in listOf(a, b)) {
            ctx.drawRoundedRect(kx - 5f, trackY - 3f, kx + 5f, trackY + trackH + 3f, 5f, fillColor = Color4b(255, 255, 255, 255))
        }
        return SLIDER_HEIGHT
    }

    @Suppress("UNCHECKED_CAST")
    private fun rangeState(value: Value<*>): Triple<Float, Float, String> {
        val ranged = value as? RangedValue<*>
        val outer = ranged?.range
        val min = (outer?.start as? Number)?.toFloat() ?: 0f
        val max = (outer?.endInclusive as? Number)?.toFloat() ?: 1f
        val span = (max - min).takeIf { it > 0f } ?: 1f
        return when (val raw = value.get()) {
            is IntRange, is ClosedRange<*> -> {
                val cr = raw as ClosedRange<*>
                val s = (cr.start as Number).toFloat()
                val e = (cr.endInclusive as Number).toFloat()
                Triple((s - min) / span, (e - min) / span, "${s.toInt()}..${e.toInt()}")
            }
            is ClosedFloatingPointRange<*> -> {
                val s = (raw.start as Number).toFloat()
                val e = (raw.endInclusive as Number).toFloat()
                Triple((s - min) / span, (e - min) / span, "%.2f..%.2f".format(s, e))
            }
            else -> Triple(0f, 1f, raw.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyRangeClick(value: Value<*>, x: Float, y: Float, width: Float, mx: Float) {
        val trackX = x + PAD_X
        val trackW = width - PAD_X * 2
        val t = ((mx - trackX) / trackW).coerceIn(0f, 1f)
        val ranged = value as? RangedValue<*> ?: return
        val outer = ranged.range
        val min = (outer.start as Number).toFloat()
        val maxV = (outer.endInclusive as Number).toFloat()
        val (p0, p1, _) = rangeState(value)
        // move nearest thumb
        val moveStart = kotlin.math.abs(t - p0) <= kotlin.math.abs(t - p1)
        when (val raw = value.get()) {
            is IntRange, is ClosedRange<*> -> {
                val cr = raw as ClosedRange<*>
                var s = (cr.start as Number).toInt()
                var e = (cr.endInclusive as Number).toInt()
                val v = (min + (maxV - min) * t).roundToInt()
                if (moveStart) s = v.coerceAtMost(e) else e = v.coerceAtLeast(s)
                (value as Value<Any>).set((s..e) as Any)
            }
            is ClosedFloatingPointRange<*> -> {
                var s = (raw.start as Number).toFloat()
                var e = (raw.endInclusive as Number).toFloat()
                val v = Mth.lerp(t, min, maxV)
                if (moveStart) s = v.coerceAtMost(e) else e = v.coerceAtLeast(s)
                (value as Value<Any>).set((s..e) as Any)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sliderState(value: Value<*>): Pair<Float, String> {
        val ranged = value as? RangedValue<*>
        val raw = value.get()
        return when (raw) {
            is Int -> {
                val min = (ranged?.range?.start as? Number)?.toFloat() ?: 0f
                val max = (ranged?.range?.endInclusive as? Number)?.toFloat() ?: 100f
                val p = if (max > min) (raw - min) / (max - min) else 0f
                p to raw.toString()
            }
            is Float -> {
                val min = (ranged?.range?.start as? Number)?.toFloat() ?: 0f
                val max = (ranged?.range?.endInclusive as? Number)?.toFloat() ?: 1f
                val p = if (max > min) (raw - min) / (max - min) else 0f
                p to "%.2f".format(raw)
            }
            is Double -> {
                val min = (ranged?.range?.start as? Number)?.toFloat() ?: 0f
                val max = (ranged?.range?.endInclusive as? Number)?.toFloat() ?: 1f
                val p = if (max > min) ((raw - min) / (max - min)).toFloat() else 0f
                p to "%.2f".format(raw)
            }
            else -> 0f to raw.toString()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySliderClick(value: Value<*>, x: Float, y: Float, width: Float, mx: Float, single: Boolean) {
        val trackX = x + PAD_X
        val trackW = width - PAD_X * 2
        val t = ((mx - trackX) / trackW).coerceIn(0f, 1f)
        val ranged = value as? RangedValue<*> ?: return
        val range = ranged.range
        when (value.get()) {
            is Int -> {
                val min = (range.start as Number).toInt()
                val max = (range.endInclusive as Number).toInt()
                (value as Value<Int>).set((min + (max - min) * t).roundToInt().coerceIn(min, max))
            }
            is Float -> {
                val min = (range.start as Number).toFloat()
                val max = (range.endInclusive as Number).toFloat()
                (value as Value<Float>).set(Mth.lerp(t, min, max))
            }
            is Double -> {
                val min = (range.start as Number).toDouble()
                val max = (range.endInclusive as Number).toDouble()
                (value as Value<Double>).set(min + (max - min) * t)
            }
        }
    }

    // ── Choice / Mode ───────────────────────────────────────────────────────

    private fun renderChoice(
        ctx: GuiGraphicsExtractor, value: Value<*>,
        x: Float, y: Float, width: Float, mouseX: Float, mouseY: Float
    ): Float {
        val c = ClickGuiColors
        val font = mc.font
        val h = ROW_HEIGHT + 4f
        if (mouseX in x..(x + width) && mouseY in y..(y + h))
            ctx.drawQuadXYWH(x, y, width, h, c.MODULE_HOVER_BG)

        val current = when (value) {
            is ModeValueGroup<*> -> value.activeMode.tag
            else -> value.get().toString()
        }
        ctx.text(font, value.name.asPlainText(), (x + PAD_X).toInt(), (y + 4f).toInt(), c.TEXT_DIMMED.argb, false)
        val tw = font.width(current)
        val chipW = tw + 12f
        val cx = x + width - PAD_X - chipW
        val cy = y + 4f
        ctx.drawRoundedRect(cx, cy, cx + chipW, cy + 16f, 3f, fillColor = c.ACCENT)
        ctx.text(font, current.asPlainText(), (cx + 6f).toInt(), (cy + 4f).toInt(), c.TEXT.argb, false)
        return h
    }

    @Suppress("UNCHECKED_CAST")
    private fun cycleChoice(value: Value<*>) {
        when (value) {
            is ChoiceListValue<*> -> {
                runCatching {
                    val list = value.choices.toList()
                    if (list.isEmpty()) return
                    val cur = value.get()
                    val idx = list.indexOf(cur).let { if (it < 0) 0 else it }
                    (value as Value<Any>).set(list[(idx + 1) % list.size] as Any)
                }
            }
            is ModeValueGroup<*> -> {
                runCatching {
                    val modes = value.modes
                    if (modes.isEmpty()) return
                    val idx = modes.indexOf(value.activeMode).let { if (it < 0) 0 else it }
                    val next = modes[(idx + 1) % modes.size]
                    value.setByString(next.tag)
                }
            }
        }
    }

    // ── Color + HS picker ───────────────────────────────────────────────────

    private fun renderColor(
        ctx: GuiGraphicsExtractor, value: Value<*>,
        x: Float, y: Float, width: Float,
        mouseX: Float, mouseY: Float, moduleName: String
    ): Float {
        val c = ClickGuiColors
        val font = mc.font
        val col = when (val v = value.get()) {
            is Color4b -> v
            is Int -> Color4b(v)
            else -> c.ACCENT
        }
        if (mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT))
            ctx.drawQuadXYWH(x, y, width, ROW_HEIGHT, c.MODULE_HOVER_BG)

        ctx.text(font, value.name.asPlainText(), (x + PAD_X).toInt(), (y + (ROW_HEIGHT - 8f) / 2f).toInt(), c.TEXT_DIMMED.argb, false)
        val sw = 18f
        val sx = x + width - PAD_X - sw
        val sy = y + (ROW_HEIGHT - sw) / 2f
        ctx.drawRoundedRect(sx, sy, sx + sw, sy + sw, 3f, fillColor = col)

        var total = ROW_HEIGHT
        if (isColorPickerOpen(moduleName, value.name)) {
            total += renderColorPicker(ctx, x, y + ROW_HEIGHT, width, col)
        }
        return total
    }

    private fun renderColorPicker(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, width: Float, current: Color4b
    ): Float {
        val hsb = java.awt.Color.RGBtoHSB(current.r, current.g, current.b, null)
        val hue = hsb[0]
        // saturation-brightness square
        val sq = 70f
        val sqX = x + PAD_X
        val sqY = y + 6f
        val steps = 12
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val s = i / (steps - 1f)
                val b = 1f - j / (steps - 1f)
                val rgb = java.awt.Color.HSBtoRGB(hue, s, b)
                val cell = sq / steps
                ctx.drawQuadXYWH(sqX + i * cell, sqY + j * cell, cell + 0.5f, cell + 0.5f, Color4b(rgb or 0xFF000000.toInt()))
            }
        }
        // hue bar
        val barX = sqX + sq + 8f
        val barW = 12f
        for (i in 0 until steps) {
            val h = i / (steps - 1f)
            val rgb = java.awt.Color.HSBtoRGB(h, 1f, 1f)
            val cell = sq / steps
            ctx.drawQuadXYWH(barX, sqY + i * cell, barW, cell + 0.5f, Color4b(rgb or 0xFF000000.toInt()))
        }
        // cursor on SB
        val cx = sqX + hsb[1] * sq
        val cy = sqY + (1f - hsb[2]) * sq
        ctx.drawRoundedRect(cx - 3f, cy - 3f, cx + 3f, cy + 3f, 3f, fillColor = Color4b(255, 255, 255, 255))
        // cursor on hue
        val hy = sqY + hue * sq
        ctx.drawQuadXYWH(barX - 2f, hy - 1f, barW + 4f, 2f, Color4b(255, 255, 255, 255))
        return COLOR_PICKER_HEIGHT
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyColorPickerClick(
        value: Value<*>, x: Float, y: Float, width: Float, mx: Float, my: Float
    ) {
        val current = when (val v = value.get()) {
            is Color4b -> v
            is Int -> Color4b(v)
            else -> return
        }
        val hsb = java.awt.Color.RGBtoHSB(current.r, current.g, current.b, null)
        val sq = 70f
        val sqX = x + PAD_X
        val sqY = y + 6f
        val barX = sqX + sq + 8f
        val barW = 12f

        when {
            mx in sqX..(sqX + sq) && my in sqY..(sqY + sq) -> {
                val s = ((mx - sqX) / sq).coerceIn(0f, 1f)
                val b = (1f - (my - sqY) / sq).coerceIn(0f, 1f)
                val rgb = java.awt.Color.HSBtoRGB(hsb[0], s, b)
                val nc = Color4b(rgb or 0xFF000000.toInt())
                (value as Value<Color4b>).set(nc)
            }
            mx in barX..(barX + barW) && my in sqY..(sqY + sq) -> {
                val h = ((my - sqY) / sq).coerceIn(0f, 1f)
                val rgb = java.awt.Color.HSBtoRGB(h, hsb[1], hsb[2])
                (value as Value<Color4b>).set(Color4b(rgb or 0xFF000000.toInt()))
            }
        }
    }

    // ── Nested group ────────────────────────────────────────────────────────

    private fun renderGroup(
        ctx: GuiGraphicsExtractor, value: Value<*>,
        x: Float, y: Float, width: Float,
        mouseX: Float, mouseY: Float,
        moduleName: String, path: String
    ): Float {
        val c = ClickGuiColors
        val font = mc.font
        val open = isGroupOpen(moduleName, path)
        if (mouseX in x..(x + width) && mouseY in y..(y + ROW_HEIGHT))
            ctx.drawQuadXYWH(x, y, width, ROW_HEIGHT, c.MODULE_HOVER_BG)

        ctx.text(font, value.name.asPlainText(), (x + PAD_X).toInt(), (y + (ROW_HEIGHT - 8f) / 2f).toInt(), c.TEXT.argb, false)
        // chevron
        val ax = x + width - 16f
        val ay = y + ROW_HEIGHT / 2f
        val ac = if (open) c.ACCENT else Color4b(255, 255, 255, 140)
        if (open) {
            ctx.drawQuadXYWH(ax - 4f, ay - 1f, 8f, 2f, ac)
        } else {
            ctx.drawQuadXYWH(ax - 1f, ay - 4f, 2f, 8f, ac)
            ctx.drawQuadXYWH(ax - 4f, ay - 1f, 8f, 2f, ac)
        }

        var total = ROW_HEIGHT
        if (open && value is ValueGroup) {
            var sy = y + ROW_HEIGHT
            for (child in value.containedValues) {
                if (!child.visibleCondition.asBoolean || child.notAnOption) continue
                val ch = render(ctx, child, x + NEST_INDENT, sy, width - NEST_INDENT, mouseX, mouseY, moduleName, "$path.${child.name}")
                sy += ch
                total += ch
            }
        }
        return total
    }

    // ── Text / Generic ──────────────────────────────────────────────────────

    private fun renderText(ctx: GuiGraphicsExtractor, value: Value<*>, x: Float, y: Float, width: Float): Float {
        val font = mc.font
        ctx.text(font, "${value.name}: ${value.get()}".asPlainText(), (x + PAD_X).toInt(), (y + (ROW_HEIGHT - 8f) / 2f).toInt(), ClickGuiColors.TEXT_DIMMED.argb, false)
        return ROW_HEIGHT
    }

    private fun renderGeneric(ctx: GuiGraphicsExtractor, value: Value<*>, x: Float, y: Float, width: Float): Float {
        val font = mc.font
        ctx.text(font, "${value.name}: ${value.get()}".asPlainText(), (x + PAD_X).toInt(), (y + (ROW_HEIGHT - 8f) / 2f).toInt(), ClickGuiColors.TEXT_DIMMED.argb, false)
        return ROW_HEIGHT
    }
}

fun collectModuleSettings(module: ClientModule): List<Value<*>> {
    return module.containedValues.filter {
        it.visibleCondition.asBoolean && !it.notAnOption
    }
}
