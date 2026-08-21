package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleCaptureMarkESP : ClientModule(
    "CaptureMarkESP",
    ModuleCategories.RENDER,
    aliases = listOf("CaptureMark", "TargetMark"),
) {

    private val espSize by float("Size", 1.0f, 0.3f..3f)
    private val rotSpeed by float("Rotation Speed", 1.2f, 0.1f..8f)
    private val waveSpeed by float("Wave Speed", 2.5f, 0.1f..12f)
    private val color1 by color("Color 1", Color4b(255, 255, 255, 240))
    private val color2 by color("Color 2", Color4b(100, 200, 255, 240))
    private val onlyKillAura by boolean("Only KillAura Target", false)
    private val playersOnly by boolean("Players Only", false)
    private val range by float("Range", 64f, 4f..128f)
    private val throughWalls by boolean("Through Walls", true)
    private val segments by int("Segments", 10, 4..24)

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun colorFor(progress: Float, time: Double): Color4b {
        var w = sin(progress * Math.PI * 2.0 + time * waveSpeed).toFloat()
        w = (w + 1f) / 2f
        return lerpColor(color1, color2, w)
    }

    private fun targets(): List<LivingEntity> {
        val self = mc.player ?: return emptyList()
        val world = mc.level ?: return emptyList()
        if (onlyKillAura) {
            val t = try { KillAuraTargetTracker.target } catch (_: Throwable) { null }
            return if (t is LivingEntity && t.isAlive) listOf(t) else emptyList()
        }
        val out = ArrayList<LivingEntity>()
        runCatching {
            for (e in world.players()) {
                if (e is LivingEntity && e !== self && e.isAlive && self.distanceTo(e) <= range) out.add(e)
            }
        }
        if (!playersOnly) {
            runCatching {
                for (e in world.entitiesForRendering()) {
                    if (e is LivingEntity && e.isAlive && e !== self && self.distanceTo(e) <= range) {
                        if (out.none { it.id == e.id }) out.add(e)
                    }
                }
            }
        }
        if (out.isEmpty()) out.add(self)
        return out
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val ents = targets()
        if (ents.isEmpty()) return@handler
        val time = System.nanoTime() * 1e-9
        val rot = -((time * rotSpeed * 60.0) % 360.0)
        val rotRad = Math.toRadians(rot)
        val size = (espSize * 0.5f).toDouble()
        val tickDelta = try {
            mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        } catch (_: Throwable) { 1f }

        data class Dot(val x: Double, val y: Double, val z: Double, val c: Color4b, val s: Double)
        val dots = ArrayList<Dot>(256)

        for (target in ents) {
            val x = Mth.lerp(tickDelta, target.xo.toFloat(), target.x.toFloat()).toDouble()
            val y = Mth.lerp(tickDelta, target.yo.toFloat(), target.y.toFloat()).toDouble() + target.bbHeight * 0.5
            val z = Mth.lerp(tickDelta, target.zo.toFloat(), target.z.toFloat()).toDouble()

            // 四向箭头（target.png 轮廓）
            for (t in 0 until 4) {
                val base = t * Math.PI / 2.0 + rotRad
                val col = colorFor(t / 4f, time)
                val tipR = size * 0.95
                val innerR = size * 0.42
                val wing = size * 0.32
                val tipX = x + sin(base) * tipR
                val tipZ = z + cos(base) * tipR
                val cx = x + sin(base) * innerR
                val cz = z + cos(base) * innerR
                val wx = cos(base) * wing
                val wz = -sin(base) * wing
                val segs = segments
                for (s in 0..segs) {
                    val u = s / segs.toDouble()
                    dots += Dot(cx + wx + (tipX - cx - wx) * u, y, cz + wz + (tipZ - cz - wz) * u, col, 0.028)
                    dots += Dot(cx - wx + (tipX - cx + wx) * u, y, cz - wz + (tipZ - cz + wz) * u, col, 0.028)
                }
                for (s in 0..segs) {
                    val u = s / segs.toDouble()
                    dots += Dot(
                        (cx + wx) + ((cx - wx) - (cx + wx)) * u, y,
                        (cz + wz) + ((cz - wz) - (cz + wz)) * u, col, 0.024,
                    )
                }
            }
            // 内菱形环
            val d = size * 0.38
            for (i in 0 until 20) {
                val a = rotRad + i * Math.PI / 10.0
                dots += Dot(x + sin(a) * d, y, z + cos(a) * d, colorFor((i % 4) / 4f, time), 0.022)
            }
        }

        event.renderEnvironment {
            for (d in dots) {
                withPositionRelativeToCamera(d.x, d.y, d.z) {
                    val s = d.s
                    drawBox(
                        AABB(-s, -s * 0.25, -s, s, s * 0.25, s),
                        faceColor = d.c,
                        outlineColor = null,
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }
}
