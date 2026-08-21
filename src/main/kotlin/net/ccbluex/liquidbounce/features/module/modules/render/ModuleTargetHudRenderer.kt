/*
 * ModuleTargetHudRenderer —— 还原 TargetHudRenderer.java (Lite + New 近似)
 * 原生 Overlay，无 Web / 无 Skia
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ModuleTargetHudRenderer : ClientModule(
    "TargetHudRenderer",
    ModuleCategories.RENDER,
    aliases = listOf("TargetHUD", "PvpTargetHud"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        LITE("Lite"), NEW("New")
    }

    private val mode by enumChoice("Mode", Mode.NEW)
    private val posX by float("Offset X", 0f, -600f..600f)
    private val posY by float("Offset Y", 40f, -400f..400f)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val radius by float("Radius", 10f, 0f..20f)

    private val bgColor by color("Background", Color4b(18, 18, 22, 200))
    private val borderColor by color("Border", Color4b(255, 255, 255, 90))
    private val barBg by color("Bar Background", Color4b(255, 255, 255, 40))
    private val barFill by color("Bar Fill", Color4b(255, 236, 248, 230))
    private val absorbFill by color("Absorption", Color4b(255, 210, 80, 200))
    private val textColor by color("Text", Color4b(255, 255, 255, 240))
    private val winColor by color("Winning", Color4b(0x55, 0xFF, 0x55, 255))
    private val loseColor by color("Losing", Color4b(0xFF, 0x55, 0x55, 255))

    private val showDistance by boolean("Show Distance", true)
    private val showStatus by boolean("Show HP Status", true)
    private val hurtFlash by boolean("Hurt Flash", true)
    private val animDurationMs by int("Anim Duration Ms", 220, 50..800)
    private val hideDelayMs by int("Hide Delay Ms", 2500, 500..8000)
    private val showInChat by boolean("Preview In Chat", true)

    private const val LITE_W = 160f
    private const val LITE_H = 42f
    private const val NEW_W = 190f
    private const val NEW_H = 58f
    private const val AVATAR = 28f
    private const val PAD = 6f

    private var target: LivingEntity? = null
    private var lastHitTime = 0L
    private var appearanceTime = 0L
    private var fullyHidden = true
    private var animHp = 1f
    private var animAbs = 0f
    private var lastFrameNs = 0L

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t.coerceIn(0f, 1f)
        return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
    }

    private fun withA(c: Color4b, a: Float) =
        Color4b(c.r, c.g, c.b, (c.a * a.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255))

    private fun resolveTarget(): LivingEntity? {
        val ka = try { KillAuraTargetTracker.target } catch (_: Throwable) { null }
        if (ka != null && ka.isAlive) return ka
        if (target != null && target!!.isAlive) return target
        if (showInChat && (try { mc.gui.screen() } catch (_: Throwable) { null }) is ChatScreen) return mc.player
        return null
    }



    /* ===================== 头像：PlayerFaceRenderer / 实体预览（禁止错误 UV blit） ===================== */

    private fun playerSkinObject(player: AbstractClientPlayer): Any? {
        return runCatching { player.skin }.getOrNull()
    }

    private fun GuiGraphicsExtractor.rawGuiGraphics(): Any? {
        return runCatching {
            javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (
                    it.name.equals("getGuiGraphics", true)
                        || it.name.equals("guiGraphics", true)
                        || it.name.equals("graphics", true)
                        || it.returnType.name.contains("GuiGraphics")
                    )
            }?.invoke(this)
        }.getOrNull() ?: runCatching {
            javaClass.fields.firstOrNull { it.type.name.contains("GuiGraphics") }?.also { it.isAccessible = true }?.get(this)
        }.getOrNull() ?: this
    }

    /** 玩家：优先 PlayerFaceRenderer；生物：InventoryScreen 实体预览；再不行才色块 */
    private fun GuiGraphicsExtractor.drawEntityFace(entity: LivingEntity, x: Float, y: Float, size: Float) {
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        val si = size.roundToInt().coerceAtLeast(8)
        val g = rawGuiGraphics() ?: this

        if (entity is AbstractClientPlayer) {
            val skin = playerSkinObject(entity)
            if (skin != null) {
                runCatching {
                    val cls = Class.forName("net.minecraft.client.gui.components.PlayerFaceRenderer")
                    for (m in cls.methods) {
                        if (!m.name.equals("draw", true) && !m.name.equals("render", true)) continue
                        if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                        val n = m.parameterCount
                        try {
                            when (n) {
                                5 -> { m.invoke(null, g, skin, xi, yi, si); return }
                                6 -> { m.invoke(null, g, skin, xi, yi, si, true); return }
                                7 -> { m.invoke(null, g, skin, xi, yi, si, true, true); return }
                                4 -> { m.invoke(null, g, skin, xi, yi); return }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            // 用实体预览画玩家头/上半身
            if (drawEntityPreview(g, entity, xi, yi, si)) return
            drawFaceFallback(entity, x, y, size)
            return
        }

        if (drawEntityPreview(g, entity, xi, yi, si)) return
        drawFaceFallback(entity, x, y, size)
    }

    private fun drawEntityPreview(g: Any, entity: LivingEntity, x: Int, y: Int, size: Int): Boolean {
        return runCatching {
            val cls = Class.forName("net.minecraft.client.gui.screens.inventory.InventoryScreen")
            // renderEntityInInventoryFollowsMouse / renderEntityInInventory
            for (m in cls.methods) {
                if (!m.name.lowercase().contains("renderentity")) continue
                if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                val n = m.parameterCount
                try {
                    // 常见: (GuiGraphics, x1,y1,x2,y2, scale, ..., entity)
                    if (n >= 7) {
                        val x1 = x
                        val y1 = y
                        val x2 = x + size
                        val y2 = y + size
                        val scale = (size * 0.9).toInt().coerceAtLeast(8)
                        when (n) {
                            7 -> m.invoke(null, g, x1, y1, x2, y2, scale, entity)
                            8 -> m.invoke(null, g, x1, y1, x2, y2, scale, 0f, entity)
                            9 -> m.invoke(null, g, x1, y1, x2, y2, scale, 0f, 0f, entity)
                            10 -> m.invoke(null, g, x1, y1, x2, y2, scale, 0.0625f, 0f, 0f, entity)
                            11 -> m.invoke(null, g, x1, y1, x2, y2, scale, 0.0625f, 0f, 0f, entity)
                            else -> {
                                // 尝试按参数类型填
                                val args = Array<Any?>(n) { null }
                                args[0] = g
                                // fill ints
                                var intIdx = 0
                                val ints = intArrayOf(x1, y1, x2, y2, scale)
                                for (i in 1 until n) {
                                    val t = m.parameterTypes[i]
                                    when {
                                        t == Int::class.javaPrimitiveType || t == Integer::class.java -> {
                                            if (intIdx < ints.size) args[i] = ints[intIdx++]
                                        }
                                        t == Float::class.javaPrimitiveType || t == java.lang.Float::class.java -> args[i] = 0f
                                        t.isAssignableFrom(entity.javaClass) -> args[i] = entity
                                    }
                                }
                                m.invoke(null, *args)
                            }
                        }
                        return@runCatching true
                    }
                } catch (_: Throwable) {}
            }
            false
        }.getOrDefault(false)
    }

    private fun GuiGraphicsExtractor.drawFaceFallback(entity: LivingEntity, x: Float, y: Float, size: Float) {
        val key = entity.type.descriptionId.hashCode()
        val r = 60 + (key and 0x7F)
        val g = 60 + ((key shr 7) and 0x7F)
        val b = 60 + ((key shr 14) and 0x7F)
        drawRoundedRect(
            x, y, x + size, y + size, size * 0.2f,
            Color4b(r.coerceIn(40, 200), g.coerceIn(40, 200), b.coerceIn(40, 200), 255),
        )
        val ch = (entity.displayName?.string ?: entity.name.string)
            .firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val font = mc.font
        val tw = font.width(ch)
        text(font, ch, (x + (size - tw) / 2f).roundToInt(), (y + (size - 9f) / 2f).roundToInt(), 0xFFFFFFFF.toInt(), false)
    }

    private fun healthColor(ratio: Float, a: Float): Color4b {
        val r = (255 * (1f - ratio * 0.35f)).roundToInt().coerceIn(80, 255)
        val g = (80 + 160 * ratio).roundToInt().coerceIn(40, 255)
        val b = (80 + 40 * ratio).roundToInt().coerceIn(40, 200)
        return Color4b(r, g, b, (230 * a).roundToInt())
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { e ->
        val ent = e.entity
        if (ent is LivingEntity && ent.isAlive) {
            val n = System.currentTimeMillis()
            if (target == null || fullyHidden) {
                appearanceTime = n
                fullyHidden = false
            }
            target = ent
            lastHitTime = n
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val now = System.currentTimeMillis()
        val nowNs = System.nanoTime()
        val dt = if (lastFrameNs != 0L) ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastFrameNs = nowNs

        val live = resolveTarget()
        if (live != null) {
            if (target != live) {
                if (target == null || fullyHidden) appearanceTime = now
                target = live
                fullyHidden = false
            }
            lastHitTime = now
        }

        val t = target
        if (t == null) return@handler

        val fadeIn = (now - appearanceTime).toFloat() / animDurationMs
        val fadeOut = 1f - (now - (lastHitTime + hideDelayMs)).toFloat() / animDurationMs
        val alpha = min(fadeIn, fadeOut).coerceIn(0f, 1f)
        if (alpha <= 0.01f) {
            if (now - lastHitTime > hideDelayMs || !t.isAlive) {
                fullyHidden = true
                target = null
                animHp = 1f
                animAbs = 0f
            }
            return@handler
        }

        val maxHp = max(1f, t.maxHealth)
        val hp = t.health.coerceIn(0f, maxHp)
        val abs = try { t.absorptionAmount.coerceAtLeast(0f) } catch (_: Throwable) { 0f }
        val ratio = (hp / maxHp).coerceIn(0f, 1f)
        val absRatio = (abs / maxHp).coerceIn(0f, 1f)
        animHp += (ratio - animHp) * min(1f, dt * 10f)
        animAbs += (absRatio - animAbs) * min(1f, dt * 10f)

        val ctx = event.context
        val font = mc.font
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        val s = scale

        if (mode == Mode.LITE) {
            renderLite(ctx, font, sw, sh, s, alpha, t, hp)
        } else {
            renderNew(ctx, font, sw, sh, s, alpha, t, hp, abs)
        }
    }

    private fun renderLite(
        ctx: GuiGraphicsExtractor, font: Font, sw: Float, sh: Float, s: Float, a: Float,
        t: LivingEntity, hp: Float,
    ) {
        val w = LITE_W * s
        val h = LITE_H * s
        val x = (sw * 0.5f + posX - w * 0.5f).coerceIn(0f, max(0f, sw - w))
        val y = (sh * 0.5f + posY - h * 0.5f).coerceIn(0f, max(0f, sh - h))

        ctx.drawRoundedRect(x, y, x + w, y + h, 4f * s, withA(bgColor, a))
        // border
        val b = withA(borderColor, a)
        ctx.drawQuad(x, y, x + w, y + 1f * s, b)
        ctx.drawQuad(x, y + h - 1f * s, x + w, y + h, b)
        ctx.drawQuad(x, y, x + 1f * s, y + h, b)
        ctx.drawQuad(x + w - 1f * s, y, x + w, y + h, b)

        val av = AVATAR * s
        val ax = x + PAD * s
        val ay = y + (h - av) / 2f
        ctx.drawEntityFace(t, ax, ay, av)

        val tx = ax + av + PAD * s
        val name = try { t.name.string } catch (_: Throwable) { "?" }
        ctx.text(font, name, tx.roundToInt(), (y + PAD * s).roundToInt(), withA(textColor, a).argb, true)

        if (showDistance) {
            val dist = mc.player?.distanceTo(t) ?: 0f
            val dw = font.width(name)
            ctx.text(
                font, String.format(Locale.ROOT, "%.1fm", dist),
                (tx + dw + 4f * s).roundToInt(), (y + PAD * s).roundToInt(),
                Color4b(255, 170, 0, (200 * a).roundToInt()).argb, false,
            )
        }

        val barY = y + h - PAD * s - 6f * s
        val barX = tx
        val barW = w - (tx - x) - PAD * s
        ctx.drawQuad(barX, barY, barX + barW, barY + 5f * s, withA(barBg, a))
        val fillW = barW * animHp
        if (fillW > 0.5f) {
            ctx.drawQuad(barX, barY, barX + fillW, barY + 5f * s, healthColor(animHp, a))
        }

        if (showStatus) {
            val self = mc.player?.health ?: 0f
            val status = if (self > hp) "W" else "L"
            val sc = if (self > hp) withA(winColor, a) else withA(loseColor, a)
            val stw = font.width(status)
            ctx.text(font, status, (x + w - PAD * s - stw).roundToInt(), (y + PAD * s).roundToInt(), sc.argb, true)
        }
    }

    private fun renderNew(
        ctx: GuiGraphicsExtractor, font: Font, sw: Float, sh: Float, s: Float, a: Float,
        t: LivingEntity, hp: Float, abs: Float,
    ) {
        val baseW = NEW_W * s
        val baseH = NEW_H * s
        val anim = easeOutBack(a)
        val w = baseW * anim
        val h = baseH * anim
        val cx = sw * 0.5f + posX
        val cy = sh * 0.5f + posY
        val x = (cx - w * 0.5f).coerceIn(0f, max(0f, sw - w))
        val y = (cy - h * 0.5f).coerceIn(0f, max(0f, sh - h))
        val r = radius * s * anim

        // soft shadow
        for (i in 1..4) {
            val e = i * 1.6f
            ctx.drawRoundedRect(
                x - e, y - e * 0.4f, x + w + e, y + h + e, r + 1f,
                Color4b(0, 0, 0, (12 * a * (1f - i / 5f)).roundToInt()),
            )
        }
        ctx.drawRoundedRect(x, y, x + w, y + h, r, withA(bgColor, a))
        // top highlight
        ctx.drawRoundedRect(x + 2f, y + 1f, x + w - 2f, y + h * 0.42f, r * 0.7f, Color4b(255, 255, 255, (18 * a).roundToInt()))

        val av = 32f * s * anim
        val ax = x + 10f * s
        val ay = y + (h - av) / 2f
        ctx.drawEntityFace(t, ax, ay, av)

        val name = try { t.name.string } catch (_: Throwable) { "?" }
        val tx = ax + av + 10f * s
        ctx.text(font, name, tx.roundToInt(), (y + 12f * s).roundToInt(), withA(textColor, a).argb, true)

        val hpText = String.format(Locale.ROOT, "%.1f", hp)
        val htw = font.width(hpText)
        ctx.text(font, hpText, (x + w - 12f * s - htw).roundToInt(), (y + 12f * s).roundToInt(), withA(textColor, a).argb, true)

        val barX = tx
        val barY = y + h - 16f * s
        val barW = w - (barX - x) - 12f * s
        val barH = 6f * s
        ctx.drawRoundedRect(barX, barY, barX + barW, barY + barH, barH * 0.5f, withA(barBg, a))
        val fillW = barW * animHp.coerceIn(0f, 1f)
        if (fillW > 0.5f) {
            ctx.drawRoundedRect(barX, barY, barX + fillW, barY + barH, barH * 0.5f, withA(barFill, a))
        }
        val absW = barW * animAbs.coerceIn(0f, 1f)
        if (absW > 0.5f) {
            ctx.drawRoundedRect(barX, barY, barX + absW, barY + barH, barH * 0.5f, withA(absorbFill, a * 0.85f))
        }
    }
}
