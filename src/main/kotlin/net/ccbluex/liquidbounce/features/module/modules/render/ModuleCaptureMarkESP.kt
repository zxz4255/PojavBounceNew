/*
 * ModuleCaptureMarkESP — CaptureMarkESP.java 还原
 * 适配 LiquidBounce Nextgen：AABB + withPositionRelativeToCamera(x,y,z)
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
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

    private val espSize by float("Size", 0.9f, 0.2f..3f)
    private val rotSpeed by float("Rotation Speed", 1.2f, 0.1f..8f)
    private val waveSpeed by float("Wave Speed", 2.5f, 0.1f..12f)
    private val color1 by color("Color 1", Color4b(255, 255, 255, 240))
    private val color2 by color("Color 2", Color4b(120, 200, 255, 240))
    private val onlyKillAura by boolean("Only KillAura Target", true)
    private val playersOnly by boolean("Players Only", false)
    private val range by float("Range", 32f, 4f..128f)
    private val throughWalls by boolean("Through Walls", true)

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun colorForProgress(progress: Float, timeSec: Double): Color4b {
        var wave = sin((progress * Math.PI * 2.0) + (timeSec * waveSpeed)).toFloat()
        wave = (wave + 1f) / 2f
        return lerpColor(color1, color2, wave)
    }

    private fun targets(): List<LivingEntity> {
        val self = mc.player ?: return emptyList()
        val world = mc.level ?: return emptyList()
        if (onlyKillAura) {
            val t = try { KillAuraTargetTracker.target } catch (_: Throwable) { null }
            return if (t is LivingEntity && t.isAlive) listOf(t) else emptyList()
        }
        val list = ArrayList<LivingEntity>()
        runCatching {
            for (e in world.entitiesForRendering()) {
                if (e !is LivingEntity || !e.isAlive || e === self) continue
                if (playersOnly && e !is Player) continue
                if (self.distanceTo(e) > range) continue
                list.add(e)
            }
        }.onFailure {
            for (e in world.players()) {
                if (e is LivingEntity && e !== self && e.isAlive && self.distanceTo(e) <= range) {
                    list.add(e)
                }
            }
        }
        return list
    }

    private data class Seg(
        val x0: Double, val y0: Double, val z0: Double,
        val x1: Double, val y1: Double, val z1: Double,
        val color: Color4b,
    )

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val ents = targets()
        if (ents.isEmpty()) return@handler

        val timeSec = System.nanoTime() * 1.0e-9
        val rotation = -((timeSec * rotSpeed * 60.0) % 360.0)
        val rotRad = Math.toRadians(rotation)
        val size = (espSize * 0.5f).toDouble()

        val segs = ArrayList<Seg>(64)
        val tickDelta = try {
            mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        } catch (_: Throwable) {
            1f
        }

        for (target in ents) {
            val x = Mth.lerp(tickDelta, target.xo.toFloat(), target.x.toFloat()).toDouble()
            val y = Mth.lerp(tickDelta, target.yo.toFloat(), target.y.toFloat()).toDouble() + target.bbHeight * 0.5
            val z = Mth.lerp(tickDelta, target.zo.toFloat(), target.z.toFloat()).toDouble()

            // 四向箭头（还原 target.png 轮廓）
            for (t in 0 until 4) {
                val base = t * Math.PI / 2.0 + rotRad
                val col = colorForProgress(t / 4f, timeSec)
                val tipR = size * 0.95
                val innerR = size * 0.42
                val wing = size * 0.32
                val tx = x + sin(base) * tipR
                val tz = z + cos(base) * tipR
                val cx = x + sin(base) * innerR
                val cz = z + cos(base) * innerR
                val px = cos(base) * wing
                val pz = -sin(base) * wing
                segs += Seg(cx + px, y, cz + pz, tx, y, tz, col)
                segs += Seg(cx - px, y, cz - pz, tx, y, tz, col)
                segs += Seg(cx + px, y, cz + pz, cx - px, y, cz - pz, col)
            }
            // 内菱形
            val d = size * 0.38
            for (i in 0 until 4) {
                val a0 = rotRad + i * Math.PI / 2.0
                val a1 = rotRad + (i + 1) * Math.PI / 2.0
                val col = colorForProgress(0.1f * i, timeSec)
                segs += Seg(
                    x + sin(a0) * d, y, z + cos(a0) * d,
                    x + sin(a1) * d, y, z + cos(a1) * d,
                    col,
                )
            }
        }

        renderEnvironmentForWorld(event.matrixStack) {
            for (seg in segs) {
                withPositionRelativeToCamera(seg.x0, seg.y0, seg.z0) {
                    drawLineStrip(
                        seg.color,
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(seg.x1 - seg.x0, seg.y1 - seg.y0, seg.z1 - seg.z0),
                    )
                    val s = 0.025
                    drawBox(
                        AABB(-s, -s * 0.3, -s, s, s * 0.3, s),
                        faceColor = seg.color,
                        outlineColor = null,
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }
}
