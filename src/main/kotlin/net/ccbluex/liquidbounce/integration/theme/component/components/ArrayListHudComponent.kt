/*
 * Port of the legacy LiquidBounce "Arraylist" CustomHUD element
 * (ui/client/hud/element/elements/Arraylist.kt) to the nextgen native HUD component system.
 *
 * Structure, settings, animation and drawing order follow the legacy element. Features whose
 * backend does not exist in nextgen are marked "APPROX" or "GAP" below and in PORT_NOTES.md.
 */
package net.ccbluex.liquidbounce.integration.theme.component.components.arraylist

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
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
import java.awt.Color
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

enum class ArrayListAnimation(override val tag: String) : Tagged {
    SLIDE("Slide"),
    SMOOTH("Smooth"),
}

enum class ArrayListColorMode(override val tag: String) : Tagged {
    CUSTOM("Custom"),
    FADE("Fade"),
    RANDOM("Random"),
    RAINBOW("Rainbow"),
    GRADIENT("Gradient"),
    THEME("Theme"),
    SKY("Sky"),
    MIXER("Mixer"),
}

enum class ArrayListRectMode(override val tag: String) : Tagged {
    NONE("None"),
    LEFT("Left"),
    RIGHT("Right"),
    OUTLINE("Outline"),
}

enum class ArrayListCase(override val tag: String) : Tagged {
    NORMAL("Normal"),
    UPPERCASE("Uppercase"),
    LOWERCASE("Lowercase"),
}

enum class ArrayListTagStyle(override val tag: String) : Tagged {
    BRACKETS("[]"),
    PARENS("()"),
    ANGLE("<>"),
    DASH("-"),
    PIPE("|"),
    SPACE("Space"),
}

enum class ArrayListGlowMode(override val tag: String) : Tagged {
    TEXT("Text"),
    BACKGROUND("Background"),
    BOTH("Both"),
}

enum class ArrayListInactiveStyle(override val tag: String) : Tagged {
    NORMAL("Normal"),
    COLOR("Color"),
    HIDE("Hide"),
}

enum class ArrayListIconColorMode(override val tag: String) : Tagged {
    CUSTOM("Custom"),
    FADE("Fade"),
}

/**
 * Per-module runtime animation state. Kept here rather than on the module, because the
 * legacy element stored these on the module object and nextgen modules do not carry them.
 */
private class ArrayListModuleState(val hue: Float) {
    var slide = 0f
    var slideStep = 0f
    var yAnim = 0f
}

/** Legacy `Element` default side is (RIGHT, UP). UP anchors the top and grows downward, so TOP. */
object ArrayListHudComponent : NativeHudComponent(
    "Arraylist",
    true,
    Alignment(
        horizontalAlignment = Alignment.ScreenAxisX.RIGHT,
        horizontalOffset = 0,
        verticalAlignment = Alignment.ScreenAxisY.TOP,
        verticalOffset = 0,
    ),
    description = "Shows a list of enabled modules.",
) {

    // ===== Size =====
    private val sizeScale by float("SizeScale", 1f, 0.5f, 3f)

    // ===== Text colour =====
    private val textColorMode by enumChoice("Text-Mode", ArrayListColorMode.THEME)
    private val textColor by color("TextColor", Color4b(41, 75, 255))
    private val textFadeColor by color("Text-Fade", Color4b(0, 111, 255))
    private val textFadeDistance by int("Text-Fade-Distance", 50, 0, 100)
    private val gradientTextSpeed by float("Text-Gradient-Speed", 1f, 0.5f, 10f)

    // ===== Rect =====
    private val rectMode by enumChoice("Rect-Mode", ArrayListRectMode.RIGHT)
    private val roundedRectRadius by float("RoundedRect-Radius", 0f, 0f, 2f)
    private val rectColorMode by enumChoice("Rect-ColorMode", ArrayListColorMode.THEME)
    private val rectColor by color("RectColor", Color4b(41, 75, 255))
    private val rectFadeColor by color("Rect-Fade", Color4b(0, 111, 255))
    private val rectFadeDistance by int("Rect-Fade-Distance", 50, 0, 100)
    private val gradientRectSpeed by float("Rect-Gradient-Speed", 1f, 0.5f, 10f)

    // ===== Background =====
    private val roundedBackgroundRadius by float("RoundedBackGround-Radius", 1f, 0f, 5f)
    private val backgroundMode by enumChoice("Background-Mode", ArrayListColorMode.CUSTOM)
    private val backgroundColor by color("BackgroundColor", Color4b(0, 0, 0, 150))
    private val backgroundFadeColor by color("Background-Fade", Color4b(0, 111, 255))
    private val backgroundFadeDistance by int("Background-Fade-Distance", 50, 0, 100)
    private val gradientBackgroundSpeed by float("Background-Gradient-Speed", 1f, 0.5f, 10f)

    // ===== Gradient endpoints (APPROX: legacy used a multi-colour list) =====
    private val gradientColorA by color("Gradient-ColorA", Color4b(0, 111, 255))
    private val gradientColorB by color("Gradient-ColorB", Color4b(41, 75, 255))

    // ===== Random / rainbow / sky / mixer =====
    private val saturation by float("Random-Saturation", 0.9f, 0f, 1f)
    private val brightness by float("Random-Brightness", 1f, 0f, 1f)
    private val skySaturation by float("Sky-Saturation", 0.9f, 0f, 1f)
    private val skyBrightness by float("Sky-Brightness", 1f, 0f, 1f)
    private val skySpeed by float("Sky-Speed", 1f, 0.1f, 5f)
    private val mixerSeconds by int("Mixer-Seconds", 2, 1, 10)
    private val mixerColor1 by color("Mixer-Color1", Color4b(255, 255, 255))
    private val mixerColor2 by color("Mixer-Color2", Color4b(41, 75, 255))

    // ===== Glow (APPROX: layered translucent rects, not a blurred glow) =====
    private val enableGlow by boolean("EnableGlow", false)
    private val glowMode by enumChoice("GlowMode", ArrayListGlowMode.TEXT)
    private val glowColor by color("GlowColor", Color4b(0, 111, 255, 100))
    private val glowBlurRadius by int("GlowBlurRadius", 10, 1, 30)
    private val glowStrength by int("GlowStrength", 1, 1, 2)

    // ===== Blur (GAP: nextgen blur is a global HUD overlay, not per-element) =====
    private val blur by boolean("Blur", false)
    private val blurStrength by float("Blur-Strength", 10f, 1f, 50f)

    // ===== Icons =====
    private val displayIcons by boolean("DisplayIcons", true)
    private val iconShadows by boolean("IconShadows", true)
    private val shadowXDistance by float("ShadowXDistance", 0f, -2f, 2f)
    private val shadowYDistance by float("ShadowYDistance", 0f, -2f, 2f)
    private val shadowColor by color("ShadowColor", Color4b(0, 0, 0, 128))
    private val iconColorMode by enumChoice("IconColorMode", ArrayListIconColorMode.CUSTOM)
    private val iconColor by color("IconColor", Color4b(255, 255, 255))
    private val iconFadeColor by color("IconFadeColor", Color4b(255, 255, 255))
    private val iconFadeDistance by int("IconFadeDistance", 50, 0, 100)

    // ===== Tags =====
    private val tags by boolean("Tags", true)
    private val tagsStyle by enumChoice("TagsStyle", ArrayListTagStyle.SPACE)
    private val tagsCase by enumChoice("TagsCase", ArrayListCase.NORMAL)
    private val tagsColorMode by enumChoice("Tags-ColorMode", ArrayListColorMode.CUSTOM)
    private val tagsColor by color("TagsColor", Color4b(128, 128, 128))
    private val tagsFadeColor by color("Tags-Fade", Color4b(192, 192, 192))
    private val tagsFadeDistance by int("Tags-Fade-Distance", 50, 0, 100)
    private val gradientTagsSpeed by float("Tags-Gradient-Speed", 1f, 0.5f, 10f)

    // ===== Text layout =====
    private val moduleCase by enumChoice("ModuleCase", ArrayListCase.NORMAL)
    private val space by float("Space", 1f, 0f, 5f)
    private val textHeight by float("TextHeight", 11f, 1f, 20f)
    private val textY by float("TextY", 3.25f, 0f, 20f)
    private val textShadow by boolean("ShadowText", true)

    // ===== Animation =====
    private val animation by enumChoice("Animation", ArrayListAnimation.SMOOTH)
    private val animationSpeed by float("AnimationSpeed", 0.2f, 0.01f, 1f)

    // ===== Inactive modules (APPROX: legacy gated on GameDetector, which nextgen lacks) =====
    private val inactiveStyle by enumChoice("InactiveModulesStyle", ArrayListInactiveStyle.COLOR)

    private val inactiveColor = Color(255, 255, 255, 100).rgb

    private val states = HashMap<ClientModule, ArrayListModuleState>()
    private var visible: List<ClientModule> = emptyList()
    private var listWidth = 0f
    private var listHeight = 0f
    private var lastFrameNanos = System.nanoTime()
    private var deltaMs = 16f

    override val guiScaledWidth: Float
        get() = listWidth * sizeScale

    override val guiScaledHeight: Float
        get() = listHeight * sizeScale

    private val renderHandler = handler<OverlayRenderEvent> { event ->
        render(event.context)
    }

    // ===== Helpers =====

    private fun Int.rgbOf(): Color = Color(this, true)

    private fun Color4b.toArgb(): Int = argb

    /** Port of `ColorUtils.fade(color, index, count)` (legacy). */
    private fun fadeColor(color: Int, index: Int, count: Int): Int {
        val rgb = color.rgbOf()
        val hsb = FloatArray(3)
        Color.RGBtoHSB(rgb.red, rgb.green, rgb.blue, hsb)
        var b = abs(
            ((System.currentTimeMillis() % 2000L).toFloat() / 1000f + index.toFloat() / count.toFloat() * 2f) % 2f - 1f
        )
        b = 0.5f + 0.5f * b
        hsb[2] = b % 2f
        return Color.HSBtoRGB(hsb[0], hsb[1], hsb[2])
    }

    /** Port of `ColorUtils.skyRainbow` (legacy). */
    private fun skyRainbow(index: Int, sat: Float, bright: Float, speed: Float): Int {
        var v1 = kotlin.math.ceil((System.currentTimeMillis() + (index * 109 * speed).toLong()).toDouble()) / 5
        v1 %= 360.0
        val hue = if ((v1 / 360.0).toFloat() < 0.5f) -((v1 / 360.0).toFloat()) else (v1 / 360.0).toFloat()
        return Color.getHSBColor(hue, sat, bright).rgb
    }

    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = ((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** APPROX: legacy GradientShader is per-pixel; this interpolates per element. */
    private fun gradientAt(index: Int, speed: Float): Int {
        val t = abs(((System.currentTimeMillis() * 0.001f * speed) + index * 0.1f) % 2f - 1f)
        return lerpArgb(gradientColorA.toArgb(), gradientColorB.toArgb(), t)
    }

    /** APPROX: legacy RainbowShader is per-pixel; this hue-cycles per element. */
    private fun rainbowAt(index: Int): Int {
        val hue = ((System.currentTimeMillis() % 10000L) / 10000f + index * 0.05f) % 1f
        return Color.getHSBColor(hue, 1f, 1f).rgb
    }

    /** Port of `getMixedColor(index, seconds)` with the mixer colours exposed on this component. */
    private fun mixedAt(index: Int, seconds: Int): Int {
        val period = (seconds * 1000).toFloat()
        val t = ((System.currentTimeMillis() + index) % (seconds * 1000)).toFloat() / period
        return lerpArgb(mixerColor1.toArgb(), mixerColor2.toArgb(), t)
    }

    /**
     * Resolves a colour for an element, matching the legacy `when (mode)` blocks.
     * GAP: `THEME` falls back to [custom] because nextgen has no ClientThemesUtils equivalent.
     */
    private fun resolveColor(
        mode: ArrayListColorMode,
        index: Int,
        state: ArrayListModuleState,
        custom: Int,
        fade: Int,
        fadeDistance: Int,
        gradientSpeed: Float,
    ): Int = when (mode) {
        ArrayListColorMode.GRADIENT -> gradientAt(index, gradientSpeed)
        ArrayListColorMode.RAINBOW -> rainbowAt(index)
        ArrayListColorMode.RANDOM -> Color.getHSBColor(state.hue, saturation, brightness).rgb
        ArrayListColorMode.FADE -> fadeColor(fade, index * fadeDistance, 100)
        ArrayListColorMode.THEME -> custom
        ArrayListColorMode.SKY -> skyRainbow(index, skySaturation, skyBrightness, skySpeed)
        ArrayListColorMode.MIXER -> mixedAt(index, mixerSeconds)
        ArrayListColorMode.CUSTOM -> custom
    }

    private fun applyCase(text: String, case: ArrayListCase) = when (case) {
        ArrayListCase.UPPERCASE -> text.uppercase()
        ArrayListCase.LOWERCASE -> text.lowercase()
        ArrayListCase.NORMAL -> text
    }

    private fun tagPrefix(): String = " " + when (tagsStyle) {
        ArrayListTagStyle.BRACKETS -> "["
        ArrayListTagStyle.PARENS -> "("
        ArrayListTagStyle.ANGLE -> "<"
        ArrayListTagStyle.DASH -> "- "
        ArrayListTagStyle.PIPE -> "| "
        ArrayListTagStyle.SPACE -> ""
    }

    private fun tagSuffix(): String = when (tagsStyle) {
        ArrayListTagStyle.BRACKETS -> "]"
        ArrayListTagStyle.PARENS -> ")"
        ArrayListTagStyle.ANGLE -> ">"
        else -> ""
    }

    private fun displayName(module: ClientModule) = applyCase(module.name, moduleCase)

    private fun tagText(module: ClientModule): String {
        val raw = module.tag
        if (!tags || raw.isNullOrEmpty()) return ""
        return tagPrefix() + applyCase(raw, tagsCase) + tagSuffix()
    }

    private fun displayString(module: ClientModule) = displayName(module) + tagText(module)

    private fun textWidth(text: String): Float =
        FontManager.FONT_RENDERER.getStringWidth(text.asPlainText())

    private fun drawText(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, argb: Int) {
        FontManager.FONT_RENDERER.draw(ctx, text.asPlainText(), x, y, Color4b(argb), textShadow)
    }

    /** Glow approximation: expanding translucent layers around a box. APPROX. */
    private fun drawGlow(ctx: GuiGraphicsExtractor, x1: Float, y1: Float, x2: Float, y2: Float, index: Int) {
        if (!enableGlow) return
        val base = when (glowMode) {
            ArrayListGlowMode.TEXT -> return
            else -> glowColor.argb
        }
        val radius = glowStrength * glowBlurRadius
        for (i in 1..radius) {
            val a = ((1f - i.toFloat() / (radius + 1)).pow(2) * (base ushr 24)).toInt().coerceIn(0, 255)
            val c = (a shl 24) or (base and 0x00FFFFFF)
            ctx.drawRoundedRect(x1 - i, y1 - i, x2 + i, y2 + i, roundedBackgroundRadius + i, Color4b(c), null)
        }
    }

    // ===== Animation =====

    /** Port of `AnimationUtil.base` (legacy), frame-rate scaled. */
    private fun animBase(current: Double, target: Double, speed: Double): Double {
        val factor = speed * (deltaMs.toDouble() * 0.06)
        return ((current + (target - current) * factor) * 1000).toInt() / 1000.0
    }

    /** Port of `AnimationUtils.easeOut(t, d)` (legacy). Guarded against d == 0. */
    private fun easeOut(t: Float, d: Float): Float = if (d <= 0f) 1f else (t / d - 1f).pow(3) + 1f

    private fun updateAnimations() {
        val now = System.nanoTime()
        deltaMs = ((now - lastFrameNanos) / 1_000_000f).coerceIn(0f, 100f)
        lastFrameNanos = now

        states.keys.retainAll { it in ModuleManager }

        for (module in ModuleManager) {
            val state = states.getOrPut(module) { ArrayListModuleState(Random.nextFloat()) }
            val shouldShow = !module.hidden && module.enabled &&
                (inactiveStyle != ArrayListInactiveStyle.HIDE || module.running)
            if (!shouldShow && state.slide <= 0f) continue

            val width = textWidth(displayString(module)) + if (displayIcons) 15 else 0

            when (animation) {
                ArrayListAnimation.SLIDE -> {
                    state.slideStep += if (shouldShow) deltaMs / 4f else -deltaMs / 4f
                    if (shouldShow) {
                        if (state.slide < width) {
                            state.slide = easeOut(state.slideStep, width) * width
                        }
                    } else {
                        state.slide = easeOut(state.slideStep, width) * width
                    }
                    state.slide = state.slide.coerceIn(0f, width)
                    state.slideStep = state.slideStep.coerceIn(0f, width)
                }

                ArrayListAnimation.SMOOTH -> {
                    val target = if (shouldShow) width.toDouble() else -width / 5.0
                    state.slide = animBase(state.slide.toDouble(), target, animationSpeed.toDouble()).toFloat()
                }
            }
        }
    }

    /** Port of the legacy "Outline" rect mode for both anchoring branches. */
    private fun drawOutline(
        ctx: GuiGraphicsExtractor,
        index: Int,
        yTop: Float,
        yBottom: Float,
        xPos: Float,
        displayWidth: Float,
        leftAnchored: Boolean,
        argb: Int,
    ) {
        val color = Color4b(argb)
        val last = index == visible.lastIndex
        val first = index == 0
        val previousIndex = if (index > 0) index - 1 else 0
        val previousWidth = textWidth(displayString(visible[previousIndex]))
        val widthDiff = previousWidth - displayWidth
        if (!leftAnchored) {
            ctx.drawQuad(-1f, yTop - 1f, 0f, yBottom, color, null)
            ctx.drawQuad(xPos - 3f, yTop, xPos - 2f, yBottom, color, null)
            if (first) ctx.drawQuad(xPos - 3f, yTop - 1f, 0f, yTop, color, null)
            ctx.drawQuad(xPos - 3f - widthDiff, yTop, xPos - 2f, yTop + 1f, color, null)
            if (last) ctx.drawQuad(xPos - 3f, yBottom, 0f, yBottom + 1f, color, null)
        } else {
            val right = xPos + displayWidth
            ctx.drawQuad(-1f, yTop - 1f, 0f, yBottom, color, null)
            ctx.drawQuad(right + 1f, yTop - 1f, right + 2f, yBottom, color, null)
            if (first) {
                ctx.drawQuad(right + 2f, yTop - 1f, right + 2f, yTop, color, null)
                ctx.drawQuad(-1f, yTop - 1f, right + 2f, yTop, color, null)
            }
            ctx.drawQuad(right + 1f, yTop - 1f, right + 2f + widthDiff, yTop, color, null)
            if (last) {
                ctx.drawQuad(right + 1f, yBottom, right + 2f, yBottom + 1f, color, null)
                ctx.drawQuad(-1f, yBottom, right + 2f, yBottom + 1f, color, null)
            }
        }
    }

    private fun updateLayout() {
        visible = ModuleManager
            .filter { val s = states[it]; s != null && s.slide > 0f && !it.hidden }
            .sortedBy { -textWidth(displayString(it)) }

        var maxWidth = 0f
        for (module in visible) {
            val state = states[module] ?: continue
            val w = state.slide + if (displayIcons) 15f else 0f
            if (w > maxWidth) maxWidth = w
        }
        val spacer = textHeight + space
        listWidth = maxWidth
        listHeight = if (visible.isEmpty()) 0f else spacer * visible.size
    }

    // ===== Render =====

    private fun render(ctx: GuiGraphicsExtractor) {
        updateAnimations()
        updateLayout()
        if (visible.isEmpty()) return

        val bounds = getGuiScaledBounds(guiScaledWidth, guiScaledHeight)
        val leftAnchored = alignment.horizontalAlignment == Alignment.ScreenAxisX.LEFT
        val bottomAnchored = alignment.verticalAlignment == Alignment.ScreenAxisY.BOTTOM
        val originX = if (leftAnchored) bounds.xMin else bounds.xMax
        val originY = if (bottomAnchored) bounds.yMax else bounds.yMin

        val spacer = textHeight + space
        val time = System.currentTimeMillis()

        ctx.pose().withPush {
            translate(originX, originY)
            scale(sizeScale, sizeScale)

            visible.forEachIndexed { index, module ->
                val state = states[module] ?: return@forEachIndexed

                var yPos = if (bottomAnchored) -spacer * (index + 1) else spacer * index
                if (animation == ArrayListAnimation.SMOOTH) {
                    state.yAnim = animBase(state.yAnim.toDouble(), yPos.toDouble(), 0.2).toFloat()
                    yPos = state.yAnim
                }

                val markAsInactive = inactiveStyle == ArrayListInactiveStyle.COLOR && !module.running

                val moduleName = displayName(module)
                val tag = tagText(module)
                val display = moduleName + tag
                val nameWidth = textWidth(moduleName)
                val displayWidth = textWidth(display)

                val rectPad = if (rectMode == ArrayListRectMode.RIGHT) 5f else 2f
                val textX: Float
                val boxX1: Float
                val boxX2: Float
                var xPos = 0f

                if (!leftAnchored) {
                    // RIGHT: grows leftward from the right edge (legacy side RIGHT).
                    xPos = -state.slide - if (displayIcons) 2f else 3f
                    textX = xPos + 1f - if (rectMode == ArrayListRectMode.RIGHT) 3f else 0f
                    boxX1 = xPos - rectPad
                    boxX2 = if (rectMode == ArrayListRectMode.RIGHT) -3f else -1f
                } else {
                    // LEFT: slides in from the left edge (legacy side LEFT).
                    xPos = -(displayWidth - state.slide) + if (rectMode == ArrayListRectMode.LEFT) 6f else 3f
                    textX = xPos - 1f
                    boxX1 = if (rectMode == ArrayListRectMode.LEFT) 1f else 0f
                    boxX2 = xPos + displayWidth + if (rectMode == ArrayListRectMode.RIGHT) 4f else 1f
                }

                val textArgb = if (markAsInactive) inactiveColor else resolveColor(
                    textColorMode, index, state, textColor.argb, textFadeColor.argb, textFadeDistance,
                    gradientTextSpeed,
                )
                val rectArgb = if (markAsInactive) inactiveColor else resolveColor(
                    rectColorMode, index, state, rectColor.argb, rectFadeColor.argb, rectFadeDistance,
                    gradientRectSpeed,
                )
                val bgArgb = if (markAsInactive) inactiveColor else resolveColor(
                    backgroundMode, index, state, backgroundColor.argb, backgroundFadeColor.argb,
                    backgroundFadeDistance, gradientBackgroundSpeed,
                )
                val tagArgb = if (markAsInactive) inactiveColor else resolveColor(
                    tagsColorMode, index, state, tagsColor.argb, tagsFadeColor.argb, tagsFadeDistance,
                    gradientTagsSpeed,
                )

                // Background (and glow behind it)
                val yTop = yPos
                val yBottom = yPos + spacer
                if (glowMode != ArrayListGlowMode.TEXT) {
                    drawGlow(ctx, minOf(boxX1, boxX2), yTop, maxOf(boxX1, boxX2), yBottom, index)
                }
                if (bgArgb ushr 24 > 0) {
                    ctx.drawRoundedRect(
                        minOf(boxX1, boxX2), yTop, maxOf(boxX1, boxX2), yBottom,
                        roundedBackgroundRadius, Color4b(bgArgb), null,
                    )
                }

                // Name, then tag
                drawText(ctx, moduleName, textX, yPos + textY, textArgb)
                if (tag.isNotEmpty()) {
                    drawText(ctx, tag, textX + nameWidth, yPos + textY, tagArgb)
                }

                // Rect decoration, using the legacy coordinates for each anchoring branch.
                when (rectMode) {
                    ArrayListRectMode.NONE -> Unit
                    ArrayListRectMode.LEFT -> if (!leftAnchored) {
                        ctx.drawRoundedRect(xPos - 5f, yTop, xPos - 2f, yBottom, roundedRectRadius, Color4b(rectArgb), null)
                    } else {
                        ctx.drawRoundedRect(0f, yTop, 3f, yBottom, roundedRectRadius, Color4b(rectArgb), null)
                    }
                    ArrayListRectMode.RIGHT -> if (!leftAnchored) {
                        ctx.drawRoundedRect(-3f, yTop, 0f, yBottom, roundedRectRadius, Color4b(rectArgb), null)
                    } else {
                        ctx.drawRoundedRect(
                            xPos + displayWidth + 2f, yTop, xPos + displayWidth + 5f, yBottom,
                            roundedRectRadius, Color4b(rectArgb), null,
                        )
                    }
                    ArrayListRectMode.OUTLINE -> drawOutline(
                        ctx, index, yTop, yBottom, xPos, displayWidth, leftAnchored, rectArgb,
                    )
                }

                // Icon with optional shadow
                if (displayIcons) {
                    val iconX = if (leftAnchored) {
                        (-displayWidth + state.slide) / 6f + if (rectMode == ArrayListRectMode.LEFT) 3f else 0f
                    } else {
                        -state.slide - 2f + displayWidth + if (rectMode == ArrayListRectMode.RIGHT) 0f else 2f
                    }
                    module.category.icon?.let { icon ->
                        val texture = Minecraft.getInstance().textureManager.getTexture(icon)
                        val setup = TextureSetup.singleTexture(texture.textureView, texture.sampler)
                        if (iconShadows) {
                            ctx.drawTexQuad(
                                setup,
                                iconX + shadowXDistance, yPos + shadowYDistance,
                                iconX + shadowXDistance + 12f, yPos + shadowYDistance + 12f,
                                argb = shadowColor.argb,
                            )
                        }
                        val iconArgb = if (markAsInactive) inactiveColor else when (iconColorMode) {
                            ArrayListIconColorMode.FADE -> fadeColor(iconFadeColor.argb, index * iconFadeDistance, 100)
                            ArrayListIconColorMode.CUSTOM -> iconColor.argb
                        }
                        ctx.drawTexQuad(
                            setup, iconX, yPos, iconX + 12f, yPos + 12f, argb = iconArgb,
                        )
                    }
                }
            }
        }
    }
}
