/*
 * ModuleNovolineTargetInfo — 还原 Rise/Novoline TargetInfo 样式
 * 深灰背景 + 头像 + 名字 + 双层血条 + 百分比
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleNovolineTargetInfo : ClientModule(
    "NovolineTargetInfo",
    ModuleCategories.RENDER,
    aliases = listOf("NovolineTarget", "TargetInfoNovoline"),
) {

    private val posX by float("X", 40f, 0f..2000f)
    private val posY by float("Y", 40f, 0f..1200f)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val hideDelayMs by int("Hide Delay Ms", 1000, 200..5000)
    private val onlyKillAura by boolean("Only KillAura", false)
    private val showInChat by boolean("Show Self In Chat", true)

    private val bgColor by color("Background", Color4b(40, 40, 40, 255))
    private val barBgColor by color("Bar Background", Color4b(21, 21, 21, 150))
    private val healthFront by color("Health Front", Color4b(0, 160, 255, 255))
    private val healthBack by color("Health Back", Color4b(0, 80, 140, 255))
    private val nameColor by color("Name Color", Color4b.WHITE)
    private val percentColor by color("Percent Color", Color4b.WHITE)
    private val textShadow by boolean("Text Shadow", true)
    private val headSize by int("Head Size", 40, 24..64)
    private val animSpeed by float("Anim Speed", 8f, 1f..30f)
    private val widthAnimSpeed by float("Width Anim Speed", 10f, 1f..30f)

    private var target: LivingEntity? = null
    private var lastHitMs = 0L
    private var animHpWidth = 0f
    private var animPanelW = 74f
    private var lastFrameNs = 0L
    private var lastTargetId = Int.MIN_VALUE

    private fun smooth(frame: Float, speed: Float) =
        (1f - exp(-speed * frame)).coerceIn(0f, 1f)

    private fun isOk(e: LivingEntity?) = e != null && e.isAlive && !e.isDeadOrDying

    private fun resolve(): LivingEntity? {
        if (onlyKillAura) {
            val t = try { KillAuraTargetTracker.target } catch (_: Throwable) { null }
            if (isOk(t)) return t
        }
        if (isOk(target) && System.currentTimeMillis() - lastHitMs <= hideDelayMs) return target
        if (showInChat) {
            val scr = try { mc.gui.screen() } catch (_: Throwable) { null }
            if (scr != null && scr.javaClass.simpleName.contains("Chat", true)) return mc.player
        }
        return null
    }

    private fun resolveSkin(player: AbstractClientPlayer): Identifier {
        runCatching {
            val skin = player.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getSkin" || it.name == "skin")
            }?.invoke(player) ?: return@runCatching
            for (m in skin.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val n = m.name.lowercase()
                if (n == "texture" || n.contains("texture")) {
                    val r = runCatching { m.invoke(skin) }.getOrNull()
                    if (r is Identifier) return r
                }
            }
        }
        return Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")
    }

    private fun GuiGraphicsExtractor.blitFace(tex: Identifier, x: Int, y: Int, size: Int) {
        val g = this
        for (m in g.javaClass.methods) {
            if (!m.name.equals("blit", true)) continue
            try {
                when (m.parameterCount) {
                    11 -> {
                        m.invoke(g, tex, x, y, size, size, 8f, 8f, 8, 8, 64, 64)
                        runCatching { m.invoke(g, tex, x, y, size, size, 40f, 8f, 8, 8, 64, 64) }
                        return
                    }
                    9 -> {
                        m.invoke(g, tex, x, y, 8f, 8f, size, size, 64, 64)
                        return
                    }
                }
            } catch (_: Throwable) {}
        }
        drawQuad(x.toFloat(), y.toFloat(), (x + size).toFloat(), (y + size).toFloat(), Color4b(80, 80, 80, 255))
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { e ->
        val ent = e.entity
        if (ent is LivingEntity && ent.isAlive) {
            target = ent
            lastHitMs = System.currentTimeMillis()
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val nowNs = System.nanoTime()
        val ft = if (lastFrameNs != 0L) ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastFrameNs = nowNs

        val live = resolve()
        if (live == null) {
            animHpWidth = 0f
            lastTargetId = Int.MIN_VALUE
            return@handler
        }
        if (live.id != lastTargetId) {
            lastTargetId = live.id
            animPanelW = 74f
        }

        val ctx = event.context
        val font = mc.font
        val name = live.displayName?.string ?: live.name.string
        val maxHp = max(1f, live.maxHealth)
        val hp = live.health.coerceIn(0f, maxHp)
        val healthPer = (hp / maxHp) * 100f
        val nameW = font.width(name).toFloat()
        val baseW = 74f
        val targetW = baseW + nameW
        val hs = headSize.toFloat()
        val height = 42f

        animPanelW += (targetW - animPanelW) * smooth(ft, widthAnimSpeed)
        val barW = 26f + nameW
        val targetBar = barW * (hp / maxHp)
        animHpWidth += (targetBar - animHpWidth) * smooth(ft, animSpeed)

        val x = posX
        val y = posY
        val s = scale

        // 背景
        ctx.drawQuad(x, y, x + animPanelW * s, y + height * s, bgColor)

        // 名字
        ctx.text(font, name, (x + 44f * s).roundToInt(), (y + 10f * s).roundToInt(), nameColor.argb, textShadow)

        // 血条底
        val barX = x + 44f * s
        val barY = y + 22f * s
        val barH = 11f * s
        ctx.drawQuad(barX, barY, barX + barW * s, barY + barH, barBgColor)
        // 后层动画条
        if (animHpWidth > 0.5f) {
            val back = Color4b(
                (healthBack.r * 0.5f).roundToInt(),
                (healthBack.g * 0.5f).roundToInt(),
                (healthBack.b * 0.5f).roundToInt(),
                healthBack.a,
            )
            ctx.drawQuad(barX, barY, barX + animHpWidth * s, barY + barH, back)
        }
        // 前层
        if (targetBar > 0.5f) {
            ctx.drawQuad(barX, barY, barX + targetBar * s, barY + barH, healthFront)
        }

        val pct = String.format("%.1f%%", healthPer)
        val pctW = font.width(pct)
        ctx.text(
            font, pct,
            (barX + barW * s / 2f - pctW / 2f).roundToInt(),
            (y + 24.5f * s).roundToInt(),
            percentColor.argb, textShadow,
        )

        // 头像
        if (live is AbstractClientPlayer) {
            ctx.blitFace(resolveSkin(live), (x + 1f * s).roundToInt(), (y + 1f * s).roundToInt(), (hs * s).roundToInt())
        } else {
            ctx.drawQuad(x + 1f * s, y + 1f * s, x + (1f + hs) * s, y + (1f + hs) * s, Color4b(70, 70, 70, 255))
        }
    }
}
