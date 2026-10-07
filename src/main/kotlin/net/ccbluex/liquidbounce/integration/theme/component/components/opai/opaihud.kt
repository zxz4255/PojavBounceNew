/*
 * Port of the Samsara "Opai" HUD (Hud.java: OpaiArraylistLayout / OpaiArraylistRenderer /
 * OpaiTargetHudHealth / OpaiTargetHudPainter / OpaiTargetHudSurface / NativePlayerFace)
 * to LiquidBounce-nextgen native HUD components.
 *
 * Geometry, constants, colours, motion springs and the target-health response are kept
 * from the Samsara source. Effects that only exist in NanoVG are approximated and marked
 * APPROX below.
 */
package net.ccbluex.liquidbounce.integration.theme.component.components.opai

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================
// Palette (Samsara OpaiStyle). Only the accent is used by the Opai HUD.
// ============================================================================

enum class OpaiTheme(override val tag: String, val accent: Int) : Tagged {
    LAVENDER("Lavender", 0xFFBBC3FF.toInt()),
    LIGHT_PINK("Light Pink", 0xFFFFB4AA.toInt()),
}

// ============================================================================
// Shared helpers
// ============================================================================

private const val BASE_FONT_SIZE = 9f

/** Draws text at an arbitrary size, using pose scaling exactly like the Samsara surface. */
private fun GuiGraphicsExtractor.scaledText(text: String, x: Float, y: Float, factor: Float, argb: Int) {
    if (((argb ushr 24) and 0xFF) < 4 || text.isEmpty()) return
    pose().withPush {
        translate(x, y)
        scale(factor, factor)
        FontManager.FONT_RENDERER.draw(this@scaledText, text.asPlainText(), 0f, 0f, Color4b(argb), false)
    }
}

private fun textWidth(text: String, scale: Float): Float =
    FontManager.FONT_RENDERER.getStringWidth(text.asPlainText()) * scale

/** Samsara ARGB tint helper: multiply alpha by opacity. */
private fun tint(argb: Int, opacity: Float): Int {
    val a = ((argb ushr 24) * opacity.coerceIn(0f, 1f)).toInt()
    return (a shl 24) or (argb and 0xFFFFFF)
}

// ============================================================================
// Arraylist (Opai)
// ============================================================================

/** Samsara OpaiArraylistLayout constants and text measurement. */
object OpaiArraylistLayout {
    const val FONT_SIZE = 9.08f
    const val ROW_HEIGHT = 12f
    const val LEFT_PAD = 3f
    const val RIGHT_PAD = 2f
    const val EDGE_WIDTH = 1f
    const val EDGE_INSET = 0.5f
    const val TOP_INSET = 0.25f
    const val RADIUS = 5f
    const val BASELINE = 8.5f
    const val BACKGROUND = 0xCC101010.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()

    /** Samsara displayName(): splits camelCase and acronyms, optionally lowercases. */
    fun displayName(name: String, lowercase: Boolean): String {
        val spaced = name
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        return if (lowercase) spaced.lowercase(Locale.ROOT) else spaced
    }

    data class Metrics(val name: String, val suffix: String, val nameWidth: Float, val gap: Float, val width: Float)

    fun measure(name: String, tag: String, lowercase: Boolean, showSuffix: Boolean, measure: (String) -> Float): Metrics {
        val label = displayName(name, lowercase)
        var suffix = if (showSuffix) tag.trim() else ""
        if (lowercase) suffix = suffix.lowercase(Locale.ROOT)
        val nameWidth = measure(label)
        val gap = if (suffix.isEmpty()) 0f else measure(" ")
        val width = LEFT_PAD + nameWidth + gap + measure(suffix) + RIGHT_PAD + EDGE_WIDTH
        return Metrics(label, suffix, nameWidth, gap, width)
    }

    data class Row(val text: Metrics, val x: Float, val y: Float, val radius: Float) {
        val right: Float get() = x + text.width
        val textX: Float get() = x + LEFT_PAD
        val suffixX: Float get() = textX + text.nameWidth + text.gap
    }

    fun row(text: Metrics, right: Float, y: Float, nextWidth: Float): Row {
        val radius = min(RADIUS, max(0f, text.width - nextWidth))
        return Row(text, right - text.width, y, radius)
    }
}

/** Samsara ArraylistMotion: analytic critically damped springs (horizontal slide and occupied space). */
class OpaiMotion {
    class Spring(initial: Double, private val rate: Double, private val immediateDeparture: Boolean = false) {
        var value = initial
        private var velocity = 0.0
        private var target = initial
        private var last = -1L

        fun to(target: Double, now: Long): Double {
            if (last >= 0 && now > last) {
                val dt = (now - last) / 1000.0
                val offset = value - this.target
                val linear = velocity + rate * offset
                val decay = exp(-rate * dt)
                value = this.target + (offset + linear * dt) * decay
                velocity = (velocity - rate * linear * dt) * decay
            }
            if (target != this.target && immediateDeparture && abs(value - this.target) < .002 && abs(velocity) < .02) {
                velocity = rate * (target - value)
            }
            last = now
            this.target = target
            return value
        }
    }

    private val horizontal = Spring(0.0, 25.0)
    private val occupied = Spring(0.0, 12.7, immediateDeparture = true)
    private var enabled = false
    private var changedAt = Long.MIN_VALUE / 2
    var y = 0f

    fun visibility(enabled: Boolean, now: Long) {
        if (this.enabled != enabled) {
            this.enabled = enabled
            changedAt = now
        }
        horizontal.to(if (enabled) 1.0 else 0.0, now)
        // The outgoing row starts moving before the gap closes under it.
        occupied.to(if (enabled || now - changedAt < 65) 1.0 else 0.0, now)
    }

    fun progress(): Float = horizontal.value.coerceIn(0.0, 1.0).toFloat()
    fun contribution(): Float = occupied.value.coerceIn(0.0, 1.0).toFloat()
    fun visible(): Boolean = enabled || progress() > .001f || contribution() > .001f
}

object OpaiArraylistHudComponent : NativeHudComponent(
    "Opai Arraylist",
    false,
    Alignment(
        horizontalAlignment = Alignment.ScreenAxisX.RIGHT,
        horizontalOffset = 0,
        verticalAlignment = Alignment.ScreenAxisY.TOP,
        verticalOffset = 0,
    ),
    description = "Opai style module list.",
) {
    private val theme by enumChoice("Theme", OpaiTheme.LAVENDER)
    private val lowercase by boolean("Lowercase", false)
    private val showSuffix by boolean("Show suffix", true)
    private val background by boolean("Background", true)
    private val shadow by boolean("Shadow", true)
    private val rightLine by boolean("Right line", true)
    private val combat by boolean("Combat", true)
    private val movement by boolean("Movement", true)
    private val playerCategory by boolean("Player", true)
    private val visual by boolean("Visual", true)
    private val misc by boolean("Misc", true)

    private class Entry(val module: ClientModule) {
        val motion = OpaiMotion()
        var metrics: OpaiArraylistLayout.Metrics? = null
        var width = 0f
        fun slide(): Float = (width + 8) * (1 - motion.progress())
    }

    private val entries = HashMap<ClientModule, Entry>()
    private var visibleEntries: List<Entry> = emptyList()
    private var widest = 0f
    private var totalHeight = 0f

    override val guiScaledWidth: Float get() = widest
    override val guiScaledHeight: Float get() = max(OpaiArraylistLayout.ROW_HEIGHT, totalHeight)

    private val renderHandler = handler<OverlayRenderEvent> { event ->
        render(event.context)
    }

    private fun categoryShown(category: ModuleCategory): Boolean = when (category.tag) {
        "Combat" -> combat
        "Movement" -> movement
        "Player" -> playerCategory
        "Render" -> visual
        "Misc" -> misc
        else -> false
    }

    private fun render(ctx: GuiGraphicsExtractor) {
        val now = System.nanoTime() / 1_000_000L
        val fontScale = OpaiArraylistLayout.FONT_SIZE / BASE_FONT_SIZE
        val measure: (String) -> Float = { textWidth(it, fontScale) }

        // Entries outlive their modules only for the animation; drop the rest.
        entries.keys.retainAll { it in ModuleManager }

        for (module in ModuleManager) {
            val entry = entries.getOrPut(module) { Entry(module) }
            val visible = module.enabled && !module.hidden && categoryShown(module.category)
            val metrics = OpaiArraylistLayout.measure(module.name, module.tag ?: "", lowercase, showSuffix, measure)
            entry.metrics = metrics
            entry.width = metrics.width
            entry.motion.visibility(visible, now)
        }

        // Samsara sorts by width, widest first, and stacks rows by their occupied contribution.
        val sorted = entries.values.sortedByDescending { it.width }
        var occupied = 0f
        val visibleList = ArrayList<Entry>(sorted.size)
        for (entry in sorted) {
            entry.motion.y = occupied
            if (entry.motion.visible()) {
                visibleList.add(entry)
                occupied += entry.motion.contribution() * OPAI_OFFSET
            }
        }
        visibleEntries = visibleList
        widest = visibleList.firstOrNull()?.width ?: 0f
        totalHeight = occupied
        if (visibleList.isEmpty()) return

        val rows = ArrayList<OpaiArraylistLayout.Row>(visibleList.size)
        for (i in visibleList.indices) {
            val entry = visibleList[i]
            val nextWidth = visibleList.getOrNull(i + 1)?.width ?: 0f
            rows.add(
                OpaiArraylistLayout.row(
                    entry.metrics ?: continue,
                    -OpaiArraylistLayout.EDGE_INSET + entry.slide(),
                    OpaiArraylistLayout.TOP_INSET + entry.motion.y,
                    nextWidth,
                )
            )
        }

        val bounds = getGuiScaledBounds(guiScaledWidth, guiScaledHeight)
        val accent = theme.accent
        ctx.pose().withPush {
            // Rows grow leftwards from the screen's right edge.
            translate(bounds.xMax, bounds.yMin)
            drawRows(ctx, rows, accent, fontScale)
        }
    }

    private fun drawRows(ctx: GuiGraphicsExtractor, rows: List<OpaiArraylistLayout.Row>, accent: Int, fontScale: Float) {
        // APPROX: NanoVG box-gradient shadow -> layered translucent rounded rects.
        if (shadow) {
            for (row in rows) {
                val w = row.text.width
                for (i in 6 downTo 1) {
                    val a = (0x65 * (1f - i / 7f) * 0.5f).roundToInt().coerceIn(0, 255)
                    ctx.drawRoundedRect(
                        row.x - i * 0.5f, row.y + 1f - i * 0.5f,
                        row.x + w + i * 0.5f, row.y + 1f + OpaiArraylistLayout.ROW_HEIGHT + i * 0.5f,
                        row.radius + i * 0.5f, Color4b((a shl 24)), null,
                    )
                }
            }
        }

        if (background) {
            for (row in rows) {
                // Left corners rounded (radius), right corners square: the Samsara shape.
                ctx.drawRoundedRect(
                    row.x, row.y, row.x + row.text.width, row.y + OpaiArraylistLayout.ROW_HEIGHT,
                    row.radius, Color4b(OpaiArraylistLayout.BACKGROUND), null,
                )
                val squareFrom = row.x + row.text.width - max(row.radius, 0f)
                ctx.drawQuad(
                    squareFrom, row.y, row.x + row.text.width, row.y + OpaiArraylistLayout.ROW_HEIGHT,
                    Color4b(OpaiArraylistLayout.BACKGROUND), null,
                )
            }
        }

        for (row in rows) {
            // APPROX: baseline placement uses a fixed offset; the global font has no baseline API.
            val top = row.y + OpaiArraylistLayout.BASELINE - 7f * fontScale
            ctx.scaledText(row.text.name, row.textX / fontScale, top / fontScale, fontScale, OpaiArraylistLayout.TEXT)
            if (row.text.suffix.isNotEmpty()) {
                ctx.scaledText(row.text.suffix, row.suffixX / fontScale, top / fontScale, fontScale, accent)
            }
        }

        if (rightLine) {
            for (row in rows) {
                ctx.drawQuad(
                    row.right - OpaiArraylistLayout.EDGE_WIDTH, row.y,
                    row.right, row.y + OpaiArraylistLayout.ROW_HEIGHT,
                    Color4b(accent), null,
                )
            }
        }
    }

    private const val OPAI_OFFSET = 12f
}

// ============================================================================
// Target HUD (Opai)
// ============================================================================

/** Samsara OpaiTargetHudHealth: fast fill plus a delayed damage trail, in wall-clock milliseconds. */
class OpaiHealthModel {
    data class Sample(val health: Float, val trail: Float, val maximum: Float) {
        val fraction: Float get() = health / maximum
        val trailFraction: Float get() = trail / maximum
        val label: String get() = format(health)
    }

    private var goal = 0f
    private var maximum = 20f
    private var fillFrom = 0f
    private var trailFrom = 0f
    private var changedAt = 0L
    private var damage = false
    private var initialized = false

    fun reset(health: Float, maximum: Float, now: Long) {
        this.maximum = maximumOf(maximum)
        this.goal = healthOf(health, this.maximum)
        this.fillFrom = goal
        this.trailFrom = goal
        this.changedAt = now
        this.damage = false
        this.initialized = true
    }

    fun update(health: Float, maximum: Float, now: Long): Sample {
        val max = maximumOf(maximum)
        val value = healthOf(health, max)
        if (!initialized) reset(value, max, now)
        if (value != goal || max != this.maximum) {
            val current = sample(now)
            damage = value < current.health
            fillFrom = current.health
            trailFrom = max(current.health, current.trail)
            goal = value
            this.maximum = max
            changedAt = now
        }
        return sample(now)
    }

    fun sample(now: Long): Sample {
        val elapsed = max(0L, now - changedAt).toDouble()
        val fill = response(fillFrom, goal, elapsed, 65.0)
        val trail = if (damage) max(fill, response(trailFrom, goal, max(0.0, elapsed - 100.0), 220.0)) else fill
        return Sample(healthOf(fill, maximum), healthOf(trail, maximum), maximum)
    }

    private fun response(from: Float, to: Float, elapsed: Double, decay: Double): Float {
        val value = (to + (from - to) * exp(-elapsed / decay)).toFloat()
        return if (abs(value - to) < .025f) to else value
    }

    companion object {
        fun maximumOf(value: Float): Float = if (value.isFinite()) max(1f, value) else 20f
        fun healthOf(value: Float, maximum: Float): Float = if (value.isFinite()) value.coerceIn(0f, maximum) else 0f
        fun format(health: Float): String {
            val rounded = (health * 10).roundToInt() / 10f
            return if (rounded == round(rounded)) rounded.roundToInt().toString()
            else String.format(Locale.ROOT, "%.1f", rounded)
        }
    }
}

object OpaiTargetHudComponent : NativeHudComponent(
    "Opai TargetHud",
    false,
    Alignment(
        horizontalAlignment = Alignment.ScreenAxisX.CENTER,
        horizontalOffset = 0,
        verticalAlignment = Alignment.ScreenAxisY.CENTER,
        verticalOffset = 0,
    ),
    description = "Opai style target HUD for the KillAura target.",
) {
    private val theme by enumChoice("Theme", OpaiTheme.LAVENDER)
    private val armor by boolean("Armor", true)

    // Samsara OpaiTargetHudPainter constants
    private const val WIDTH = 122
    private const val HEIGHT = 40
    private const val RADIUS = 6
    private const val SHADOW_MARGIN = 8
    private const val TEXTURE_SCALE = 4
    private const val FACE_SIZE = 26f
    private const val FACE_RADIUS = 4.5f
    private const val TEXT_SIZE = 8f
    private const val TEXT_SCALE = TEXT_SIZE / 10f
    private val PANEL_TEXTURE = Identifier.fromNamespaceAndPath("liquidbounce", "textures/hud/target-opai.png")
    private val BAR_TEXTURE = Identifier.fromNamespaceAndPath("liquidbounce", "textures/hud/target-opai-bar.png")

    private val health = OpaiHealthModel()
    private var shownTarget: Player? = null
    private var contentWidth = WIDTH
    private var visible = false

    override val guiScaledWidth: Float get() = contentWidth.toFloat()
    override val guiScaledHeight: Float get() = HEIGHT.toFloat()

    private val renderHandler = handler<OverlayRenderEvent> { event ->
        render(event.context)
    }

    private fun labelWidth(maximum: Float): Float {
        val label = OpaiHealthModel.format(maximum)
        return max(textWidth(if (label.contains('.')) label else "$label.0", TEXT_SCALE), textWidth("00.0", TEXT_SCALE))
    }

    /** Samsara OpaiTargetHudPainter.bounds(): width only, the component decides the position. */
    private fun shellWidth(name: String, maximum: Float): Int {
        val fitted = ceil(35 + textWidth(name, TEXT_SCALE) + labelWidth(maximum) + 4).toInt()
        val viewport = Minecraft.getInstance().window.guiScaledWidth
        return min(max(WIDTH, fitted), max(1, viewport - 4))
    }

    /** Samsara OpaiTargetHudPainter.fit(): ellipsize by code point. */
    private fun fit(text: String, width: Float): String {
        if (textWidth(text, TEXT_SCALE) <= width) return text
        var end = text.length
        while (end > 0 && textWidth(text.substring(0, end) + "…", TEXT_SCALE) > width) {
            end = text.offsetByCodePoints(end, -1)
        }
        return if (end == 0) "" else text.substring(0, end) + "…"
    }

    private fun render(ctx: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val level = mc.level
        val target = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target as? Player else null
        if (player == null || level == null || mc.gui.overlay() != null || target == null ||
            target.level() != level || target.isRemoved
        ) {
            shownTarget = null
            visible = false
            return
        }

        val now = System.nanoTime() / 1_000_000L
        if (target !== shownTarget) {
            shownTarget = target
            health.reset(target.health, target.maxHealth, now)
        }
        val sample = health.update(target.health, target.maxHealth, now)
        val name = target.name.string
        contentWidth = shellWidth(name, sample.maximum)
        visible = true

        val bounds = getGuiScaledBounds(guiScaledWidth, guiScaledHeight)
        ctx.pose().withPush {
            translate(bounds.xMin, bounds.yMin)
            paint(ctx, target, name, sample)
        }
    }

    private fun paint(ctx: GuiGraphicsExtractor, target: Player, name: String, sample: OpaiHealthModel.Sample) {
        val w = contentWidth.toFloat()
        val accent = theme.accent

        // Panel: nine-slice of the Samsara texture (stretched centre only).
        drawPanel(ctx, w)

        // Face
        drawFace(ctx, target, 3f, 3f, FACE_SIZE, FACE_RADIUS)

        val label = sample.label
        val labelW = labelWidth(sample.maximum)
        val fitted = fit(name, w - 35 - labelW - 3)
        ctx.scaledText(fitted, 32f, 5f, TEXT_SCALE, 0xFFFFFFFF.toInt())
        ctx.scaledText(label, 32f + textWidth(fitted, TEXT_SCALE) + 1.5f, 5f, TEXT_SCALE, accent)

        if (armor) {
            for (slot in 0 until 4) drawArmor(ctx, target, slot, 31.5f + slot * 15.5f, 14.5f)
        }

        val barWidth = max(0f, w - 7)
        drawBar(ctx, 3f, 32f, barWidth, 5f, 0x66000000)
        // Draw only the remaining damage interval: the theme fill covers the front of it.
        drawBar(ctx, 3f, 31.25f, barWidth * sample.trailFraction, 4.5f, 0x66FFFFFF)
        drawBar(ctx, 3f, 31.25f, barWidth * sample.fraction, 4.5f, accent)
    }

    private fun drawPanel(ctx: GuiGraphicsExtractor, w: Float) {
        val texture = Minecraft.getInstance().textureManager.getTexture(PANEL_TEXTURE)
        val setup = TextureSetup.singleTexture(texture.textureView, texture.sampler)
        val sourceWidth = (WIDTH + SHADOW_MARGIN * 2) * TEXTURE_SCALE
        val sourceHeight = (HEIGHT + SHADOW_MARGIN * 2) * TEXTURE_SCALE
        val edge = round((SHADOW_MARGIN + RADIUS) * TEXTURE_SCALE.toFloat())
        val destWidth = (w + SHADOW_MARGIN * 2) * TEXTURE_SCALE
        val src = floatArrayOf(0f, edge, sourceWidth - edge, sourceWidth.toFloat())
        val dst = floatArrayOf(0f, edge, destWidth - edge, destWidth)
        val originX = -SHADOW_MARGIN.toFloat()
        val originY = -SHADOW_MARGIN.toFloat()
        ctx.pose().withPush {
            translate(originX, originY)
            for (i in 0 until 3) {
                val x0 = dst[i] / TEXTURE_SCALE
                val x1 = dst[i + 1] / TEXTURE_SCALE
                ctx.drawTexQuad(
                    setup, x0, 0f, x1, sourceHeight / TEXTURE_SCALE.toFloat(),
                    u1 = src[i] / sourceWidth, v1 = 0f,
                    u2 = src[i + 1] / sourceWidth, v2 = 1f,
                )
            }
        }
    }

    private fun drawBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, width: Float, height: Float, argb: Int) {
        if (width <= 0f) return
        val texture = Minecraft.getInstance().textureManager.getTexture(BAR_TEXTURE)
        val setup = TextureSetup.singleTexture(texture.textureView, texture.sampler)
        val w = max(1, (width * TEXTURE_SCALE).roundToInt())
        val h = max(1, (height * TEXTURE_SCALE).roundToInt())
        val color = tint(argb, 1f)
        ctx.pose().withPush {
            translate(x, y)
            scale(1f / TEXTURE_SCALE, 1f / TEXTURE_SCALE)
            if (w <= h) {
                ctx.drawTexQuad(setup, 0f, 0f, w.toFloat(), h.toFloat(), argb = color)
            } else {
                val cap = h / 2
                // Left cap, stretched middle, right cap: the Samsara pill slicing.
                ctx.drawTexQuad(setup, 0f, 0f, cap.toFloat(), h.toFloat(), 0f, 0f, 0.5f, 1f, color)
                ctx.drawTexQuad(
                    setup, cap.toFloat(), 0f, (w - cap).toFloat(), h.toFloat(),
                    9f / 20f, 0f, 11f / 20f, 1f, color,
                )
                ctx.drawTexQuad(setup, (w - cap).toFloat(), 0f, w.toFloat(), h.toFloat(), 0.5f, 0f, 1f, 1f, color)
            }
        }
    }

    private fun drawArmor(ctx: GuiGraphicsExtractor, target: Player, slot: Int, x: Float, y: Float) {
        val stack = target.getItemBySlot(ARMOR[slot])
        if (stack.isEmpty) return
        ctx.pose().withPush {
            translate(x, y)
            scale(.95f, .95f)
            ctx.item(target, stack, 0, 0, slot)
        }
    }

    /** Samsara NativePlayerFace: rounded head from the face (u=8) and hat (u=40) UVs. */
    private fun drawFace(ctx: GuiGraphicsExtractor, target: Player, x: Float, y: Float, size: Float, radius: Float) {
        val playerSkin = (target as? AbstractClientPlayer)?.skin ?: DefaultPlayerSkin.get(target.gameProfile)
        val skinId = playerSkin.body().texturePath()
        val texture = Minecraft.getInstance().textureManager.getTexture(skinId)
        val setup = TextureSetup.singleTexture(texture.textureView, texture.sampler)
        val scale = TEXTURE_SCALE
        ctx.pose().withPush {
            translate(x, y)
            scale(1f / scale, 1f / scale)
            val pixels = (size * scale).roundToInt()
            val roundPx = (radius * scale).roundToInt().coerceIn(0, pixels / 2)
            val color = 0xFFFFFFFF.toInt()
            for (u in intArrayOf(8, 40)) {
                for (row in 0 until roundPx) {
                    val dy = roundPx - row - .5
                    val inset = round(roundPx - sqrt(roundPx.toDouble() * roundPx - dy * dy)).toInt()
                    strip(ctx, setup, u, pixels, inset, row, pixels - inset * 2, 1, color)
                    strip(ctx, setup, u, pixels, inset, pixels - row - 1, pixels - inset * 2, 1, color)
                }
                strip(ctx, setup, u, pixels, 0, roundPx, pixels, pixels - roundPx * 2, color)
            }
        }
    }

    private fun strip(
        ctx: GuiGraphicsExtractor, setup: TextureSetup, u: Int, pixels: Int,
        x: Int, y: Int, width: Int, height: Int, color: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val factor = 8f / pixels
        ctx.drawTexQuad(
            setup,
            x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(),
            u1 = (u + x * factor) / 64f, v1 = (8 + y * factor) / 64f,
            u2 = (u + (x + width) * factor) / 64f, v2 = (8 + (y + height) * factor) / 64f,
            argb = color,
        )
    }

    private val ARMOR = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
}
