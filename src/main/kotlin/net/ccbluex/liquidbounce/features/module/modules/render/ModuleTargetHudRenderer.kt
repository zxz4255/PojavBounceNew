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


    /* ===================== 头像（多签名兼容，避免 UV 乱线） ===================== */

    private fun playerSkinId(player: AbstractClientPlayer): Identifier {
        runCatching { player.skin.body().texturePath() }.getOrNull()?.let { return it }
        runCatching {
            val skin = player.skin
            for (m in skin.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val n = m.name.lowercase()
                if (!n.contains("texture") && n != "body") continue
                val r = m.invoke(skin) ?: continue
                if (r is Identifier) return r
                val tp = r.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.lowercase().contains("texture")
                }?.invoke(r)
                if (tp is Identifier) return tp
            }
            null
        }.getOrNull()?.let { return it }
        return Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")
    }


    /**
     * 按「像素 UV 优先」尝试多种 blit，避免归一化 UV 被当成像素导致花屏乱线。
     * 标准 64×64 皮肤: 脸 (8,8) 尺寸 8；帽 (40,8) 尺寸 8。
     */
    private fun GuiGraphicsExtractor.blitSkinFace(
        texture: Identifier,
        x: Int,
        y: Int,
        size: Int,
        uPx: Float,
        vPx: Float,
    ) {
        if (size <= 0) return
        val x1 = x + size
        val y1 = y + size
        val region = 8f
        val tex = 64

        // A) 反射: blit(Identifier, x, y, u, v, width, height, textureWidth, textureHeight)
        for (m in javaClass.methods) {
            if (!m.name.equals("blit", true)) continue
            val pts = m.parameterTypes
            try {
                if (pts.size == 9
                    && (pts[0] == Identifier::class.java || pts[0].name.endsWith("Identifier"))
                    && pts[1] == Int::class.javaPrimitiveType
                ) {
                    m.invoke(this, texture, x, y, uPx, vPx, size, size, tex, tex)
                    return
                }
                // blit(id, x, y, z, u, v, w, h) 等
                if (pts.size == 8 && pts[0] == Identifier::class.java) {
                    m.invoke(this, texture, x, y, 0, uPx, vPx, size, size)
                    return
                }
            } catch (_: Throwable) {
            }
        }

        // B) 本 fork 常见: (texture, x0,y0,x1,y1, u0,v0,u1,v1) 归一化
        val okB = runCatching {
            blit(
                texture, x, y, x1, y1,
                uPx / 64f, vPx / 64f,
                (uPx + region) / 64f, (vPx + region) / 64f,
            )
        }.isSuccess
        if (okB) return

        // C) (texture, x0,y0,x1,y1, u,v,uw,vh) 归一化
        val okC = runCatching {
            blit(texture, x, y, x1, y1, uPx / 64f, vPx / 64f, region / 64f, region / 64f)
        }.isSuccess
        if (okC) return

        // D) 像素值直接塞进四 float（少数错误映射的 fork）
        runCatching {
            blit(texture, x, y, x1, y1, uPx, vPx, region, region)
        }
    }

    private fun GuiGraphicsExtractor.drawEntityFace(
        entity: LivingEntity,
        x: Float,
        y: Float,
        size: Float,
    ) {
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        val si = size.roundToInt().coerceAtLeast(1)

        if (entity is AbstractClientPlayer) {
            val tex = playerSkinId(entity)
            // 正面脸
            blitSkinFace(tex, xi, yi, si, 8f, 8f)
            // 帽子层（半透明叠不上就再画一次）
            runCatching { blitSkinFace(tex, xi, yi, si, 40f, 8f) }
            return
        }

        // 生物：不用 64 皮肤 UV（会花屏）。尝试实体贴图左上，失败则色块+首字
        val tex = runCatching {
            val renderer = mc.entityRenderDispatcher.getRenderer(entity) ?: return@runCatching null
            for (m in renderer.javaClass.methods) {
                if (!m.name.lowercase().contains("texture")) continue
                if (m.parameterCount == 1) {
                    val r = m.invoke(renderer, entity)
                    if (r is Identifier) return@runCatching r
                }
                if (m.parameterCount == 0) {
                    val r = m.invoke(renderer)
                    if (r is Identifier) return@runCatching r
                }
            }
            null
        }.getOrNull()

        if (tex != null) {
            // 许多实体贴图头在左上 8×8（相对 64 图集），仍可能不准 → 再兜底
            val drew = runCatching {
                blitSkinFace(tex, xi, yi, si, 8f, 8f)
                true
            }.getOrDefault(false)
            if (drew) return
        }

        val key = entity.type.descriptionId.hashCode()
        val r = 70 + (key and 0x7F)
        val g = 70 + ((key shr 7) and 0x7F)
        val b = 70 + ((key shr 14) and 0x7F)
        drawRoundedRect(
            x, y, x + size, y + size, size * 0.22f,
            Color4b(r.coerceIn(40, 220), g.coerceIn(40, 220), b.coerceIn(40, 220), 255),
        )
        val ch = (entity.displayName?.string ?: entity.name.string).firstOrNull()?.uppercaseChar()?.toString() ?: "?"
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
        ctx.drawEntityFace(t, ax, ay)

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
        ctx.drawEntityFace(t, ax, ay)

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
