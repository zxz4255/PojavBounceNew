/*
 * ModuleSessionInfo — 会话信息 HUD（zip 内仅为 Mode 壳，补全常用 Session 样式）
 * 模式: Classic / Compact / Card / Stats
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.roundToInt

object ModuleSessionInfo : ClientModule(
    "SessionInfo",
    ModuleCategories.RENDER,
    aliases = listOf("SessionStats", "SessionHud"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        CLASSIC("Classic"),
        COMPACT("Compact"),
        CARD("Card"),
        STATS("Stats"),
    }

    private val mode by enumChoice("Mode", Mode.CLASSIC)
    private val posX by float("X", 8f, 0f..2000f)
    private val posY by float("Y", 8f, 0f..1200f)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val radius by float("Radius", 5f, 0f..16f)
    private val bgColor by color("Background", Color4b(20, 20, 25, 170))
    private val accent by color("Accent", Color4b(80, 180, 255, 255))
    private val textColor by color("Text", Color4b(230, 230, 230, 255))
    private val textShadow by boolean("Text Shadow", true)
    private val showUser by boolean("Show User", true)
    private val showServer by boolean("Show Server", true)
    private val showTime by boolean("Show Time", true)
    private val showFps by boolean("Show FPS", true)
    private val showKills by boolean("Show Kills", true)
    private val showPing by boolean("Show Ping", false)

    private var sessionStartMs = 0L
    private var kills = 0
    private var fpsEstimate = 0
    private var frames = 0
    private var fpsWindow = 0L

    override fun onEnabled() {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
    }

    private fun sessionTime(): String {
        val sec = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt().coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun serverName(): String = try {
        mc.currentServer?.ip ?: "Singleplayer"
    } catch (_: Throwable) {
        "Unknown"
    }

    private fun userName(): String = try {
        mc.user.name
    } catch (_: Throwable) {
        mc.player?.name?.string ?: "Player"
    }

    private fun ping(): Int = try {
        val self = mc.player ?: return -1
        mc.connection?.getPlayerInfo(self.uuid)?.latency ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun lines(): List<String> {
        val out = ArrayList<String>()
        when (mode) {
            Mode.COMPACT -> {
                if (showUser) out += userName()
                if (showTime) out += sessionTime()
                if (showFps) out += "$fpsEstimate fps"
            }
            Mode.STATS -> {
                if (showTime) out += "Time  ${sessionTime()}"
                if (showKills) out += "Kills $kills"
                if (showFps) out += "FPS   $fpsEstimate"
                if (showPing) out += "Ping  ${ping()}ms"
            }
            Mode.CARD, Mode.CLASSIC -> {
                if (showUser) out += "User   ${userName()}"
                if (showServer) out += "Server ${serverName()}"
                if (showTime) out += "Time   ${sessionTime()}"
                if (showFps) out += "FPS    $fpsEstimate"
                if (showKills) out += "Kills  $kills"
                if (showPing) {
                    val p = ping()
                    if (p >= 0) out += "Ping   ${p}ms"
                }
            }
        }
        return out
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { e ->
        val ent = e.entity
        if (ent is Player && ent !== mc.player && ent is LivingEntity) {
            // 粗略：攻击玩家时若目标即将死亡计一次（简化）
            if (ent.health <= 0f || ent.isDeadOrDying) kills++
        }
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldChangeEvent> {
        // 换世界不重置 session，只保证 start 有值
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        frames++
        val now = System.currentTimeMillis()
        if (fpsWindow == 0L) fpsWindow = now
        if (now - fpsWindow >= 1000) {
            fpsEstimate = frames
            frames = 0
            fpsWindow = now
        }
        if (sessionStartMs == 0L) sessionStartMs = now

        val ls = lines()
        if (ls.isEmpty()) return@handler
        val font = mc.font
        val ctx = event.context
        val s = scale
        var maxW = 0
        for (line in ls) maxW = maxOf(maxW, font.width(line))
        val pad = 6f * s
        val lineH = 11f * s
        val titleH = if (mode == Mode.CARD) 14f * s else 0f
        val w = maxW * s + pad * 2
        val h = titleH + ls.size * lineH + pad * 2
        val x = posX
        val y = posY

        if (radius > 0.5f) {
            ctx.drawRoundedRect(x, y, x + w, y + h, radius * s, bgColor)
        } else {
            ctx.drawQuad(x, y, x + w, y + h, bgColor)
        }

        if (mode == Mode.CARD) {
            ctx.drawQuad(x, y, x + w, y + 3f * s, accent)
            ctx.text(font, "Session", (x + pad).roundToInt(), (y + 4f * s).roundToInt(), accent.argb, textShadow)
        }

        var ty = y + pad + titleH
        for (line in ls) {
            ctx.text(font, line, (x + pad).roundToInt(), ty.roundToInt(), textColor.argb, textShadow)
            ty += lineH
        }
    }

    fun resetSession() {
        sessionStartMs = System.currentTimeMillis()
        kills = 0
    }
}
