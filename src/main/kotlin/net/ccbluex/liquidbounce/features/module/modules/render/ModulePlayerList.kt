/*
 * ModulePlayerList — 还原 Rise PlayerList HUD
 * 圆角列表 + 头像 + 血量颜色名 + [Me]/队友前缀
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

object ModulePlayerList : ClientModule(
    "PlayerList",
    ModuleCategories.RENDER,
    aliases = listOf("PlayerListHud", "TabListHud"),
) {

    private val posX by float("X", 8f, 0f..2000f)
    private val posY by float("Y", 40f, 0f..1200f)
    private val showTitle by boolean("Show Title", true)
    private val titleText by text("Title", "PlayerList")
    private val radius by float("Radius", 6f, 0f..16f)
    private val bgColor by color("Background", Color4b(20, 20, 20, 160))
    private val titleColor1 by color("Title Color 1", Color4b(80, 180, 255, 255))
    private val titleColor2 by color("Title Color 2", Color4b(180, 80, 255, 255))
    private val rowHeight by float("Row Height", 14f, 10f..28f)
    private val maxPlayers by int("Max Players", 16, 1..40)
    private val showSelf by boolean("Show Self", true)
    private val showHeads by boolean("Show Heads", true)
    private val headSize by float("Head Size", 11f, 6f..20f)
    private val animSpeed by float("Anim Speed", 12f, 1f..40f)
    private val textShadow by boolean("Text Shadow", true)
    private val range by float("Range", 128f, 16f..512f)

    private var animH = 0f
    private var lastNs = 0L

    private fun healthColor(ratio: Float): Color4b = when {
        ratio > 0.75f -> Color4b(66, 246, 123, 255)
        ratio > 0.5f -> Color4b(228, 255, 105, 255)
        ratio > 0.35f -> Color4b(236, 100, 64, 255)
        else -> Color4b(255, 65, 68, 255)
    }

    private fun resolveSkin(player: AbstractClientPlayer): Identifier {
        runCatching {
            val skin = player.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "getSkin" || it.name == "skin")
            }?.invoke(player) ?: return@runCatching
            for (m in skin.javaClass.methods) {
                if (m.parameterCount != 0) continue
                if (!m.name.lowercase().contains("texture")) continue
                val r = runCatching { m.invoke(skin) }.getOrNull()
                if (r is Identifier) return r
            }
        }
        return Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")
    }

    private fun GuiGraphicsExtractor.blitFace(tex: Identifier, x: Int, y: Int, size: Int) {
        for (m in javaClass.methods) {
            if (!m.name.equals("blit", true)) continue
            try {
                when (m.parameterCount) {
                    11 -> {
                        m.invoke(this, tex, x, y, size, size, 8f, 8f, 8, 8, 64, 64)
                        runCatching { m.invoke(this, tex, x, y, size, size, 40f, 8f, 8, 8, 64, 64) }
                        return
                    }
                    9 -> {
                        m.invoke(this, tex, x, y, 8f, 8f, size, size, 64, 64)
                        return
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val self = mc.player ?: return@handler
        val world = mc.level ?: return@handler
        val now = System.nanoTime()
        val ft = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        val sm = (1f - exp(-animSpeed * ft)).coerceIn(0f, 1f)

        val players = ArrayList<Player>()
        runCatching {
            for (p in world.players()) {
                if (p !is Player || !p.isAlive) continue
                if (!showSelf && p === self) continue
                if (self.distanceTo(p) > range) continue
                players += p
            }
        }
        if (players.isEmpty() && showSelf) players += self
        val list = players.take(maxPlayers)

        val font = mc.font
        val ctx = event.context
        var maxW = 80f
        for (p in list) {
            val name = p.displayName?.string ?: p.name.string
            val prefix = when {
                p === self -> "[Me] "
                else -> ""
            }
            val hp = ((p.health + (try { p.absorptionAmount } catch (_: Throwable) { 0f })) /
                max(1f, p.maxHealth + (try { p.absorptionAmount } catch (_: Throwable) { 0f })) * 100f)
                .roundToInt()
            val line = "$prefix$name $hp%"
            maxW = max(maxW, font.width(line) + 28f)
        }

        val targetH = 8f + list.size * rowHeight + if (showTitle) 14f else 0f
        animH += (targetH - animH) * sm

        val x = posX
        val y = posY
        if (radius > 0.5f) {
            ctx.drawRoundedRect(x, y, x + maxW, y + animH, radius, bgColor)
        } else {
            ctx.drawQuad(x, y, x + maxW, y + animH, bgColor)
        }

        var cy = y + 4f
        if (showTitle) {
            val title = titleText
            // 简单双色标题：前半色1后半色2
            val mid = title.length / 2
            if (mid > 0) {
                ctx.text(font, title.substring(0, mid), (x + 5).roundToInt(), cy.roundToInt(), titleColor1.argb, textShadow)
                val w0 = font.width(title.substring(0, mid))
                ctx.text(font, title.substring(mid), (x + 5 + w0).roundToInt(), cy.roundToInt(), titleColor2.argb, textShadow)
            } else {
                ctx.text(font, title, (x + 5).roundToInt(), cy.roundToInt(), titleColor1.argb, textShadow)
            }
            cy += 14f
        }

        for ((i, p) in list.withIndex()) {
            val name = p.displayName?.string ?: p.name.string
            val abs = try { p.absorptionAmount } catch (_: Throwable) { 0f }
            val ratio = ((p.health + abs) / max(1f, p.maxHealth + abs)).coerceIn(0f, 1f)
            val col = healthColor(ratio)
            val prefix = if (p === self) "[Me] " else ""
            val hpText = "${(ratio * 100).roundToInt()}%"
            val text = "$prefix$name $hpText"
            val rowY = cy + i * rowHeight

            if (showHeads && p is AbstractClientPlayer) {
                ctx.blitFace(resolveSkin(p), (x + 3).roundToInt(), (rowY).roundToInt(), headSize.roundToInt())
            }
            ctx.text(
                font, text,
                (x + 5 + if (showHeads) headSize + 2f else 0f).roundToInt(),
                (rowY + 1f).roundToInt(),
                col.argb, textShadow,
            )
        }
    }
}
