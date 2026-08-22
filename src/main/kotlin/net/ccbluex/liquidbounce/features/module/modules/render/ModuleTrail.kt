/*
 * ModuleTrail — 还原 Rise Trail（Rise 粒子点 / Line 折线）
 * WorldRenderEvent + 原生 drawBox 近似
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import kotlin.math.hypot
import kotlin.math.roundToInt

object ModuleTrail : ClientModule(
    "Trail",
    ModuleCategories.RENDER,
    aliases = listOf("RiseTrail", "Breadcrumbs"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        RISE("Rise"),
        LINE("Line"),
    }

    private val mode by enumChoice("Mode", Mode.RISE)
    private val particleAmount by int("Particle Amount", 15, 1..200)
    private val throughWalls by boolean("Walls", true)
    private val color1 by color("Color 1", Color4b(255, 255, 255, 200))
    private val color2 by color("Color 2", Color4b(100, 180, 255, 200))
    private val onlyWhenMoving by boolean("Only When Moving", true)
    private val minMove by float("Min Move", 0.01f, 0f..0.5f)
    private val yOffset by float("Y Offset", 0.1f, -1f..2f)
    private val particleScale by float("Particle Scale", 1f, 0.3f..3f)
    private val lineThickness by float("Line Thickness", 0.04f, 0.01f..0.2f)
    private val rainbow by boolean("Rainbow", false)

    private data class Pt(val x: Double, val y: Double, val z: Double)

    private val path = ArrayList<Pt>()

    private fun lerpC(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun colorAt(i: Int, n: Int): Color4b {
        if (rainbow) {
            val h = ((System.currentTimeMillis() / 20L + i * 8) % 360) / 360f
            val rgb = java.awt.Color.HSBtoRGB(h, 0.8f, 1f)
            return Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, color1.a)
        }
        val t = if (n <= 1) 0f else i / (n - 1f)
        // 来回插值
        val wave = ((kotlin.math.sin(i * 0.35) + 1) * 0.5).toFloat()
        return lerpC(color1, color2, wave * (1f - t * 0.3f))
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val p = mc.player ?: return@handler
        val moved = hypot(hypot(p.x - p.xo, p.z - p.zo), p.y - p.yo)
        if (!onlyWhenMoving || moved >= minMove) {
            path.add(Pt(p.x, p.y + yOffset, p.z))
        }
        while (path.size > particleAmount) path.removeAt(0)
        if (path.isEmpty()) return@handler

        val n = path.size
        event.renderEnvironment {
            when (mode) {
                Mode.RISE -> {
                    for ((i, v) in path.withIndex()) {
                        val dist = hypot(hypot(p.x - v.x, p.z - v.z), p.y - 1.0 - v.y)
                        var draw = true
                        if (i % 10 != 0 && dist > 25.0) draw = false
                        if (i % 3 == 0 && dist > 15.0) draw = false
                        if (!draw) continue
                        val c = colorAt(i, n)
                        val base = 0.06 * particleScale
                        // 软光：多层
                        for (L in 0 until 3) {
                            val t = L / 3f
                            val s = base * (1.0 + t * 1.4)
                            val a = (c.a * (0.55f - t * 0.18f)).roundToInt().coerceIn(0, 255)
                            if (a < 4) continue
                            val col = Color4b(c.r, c.g, c.b, a)
                            withPositionRelativeToCamera(v.x, v.y, v.z) {
                                drawBox(
                                    AABB(-s, -s * 0.35, -s, s, s * 0.35, s),
                                    faceColor = col,
                                    outlineColor = null,
                                    noDepthTest = throughWalls,
                                )
                            }
                        }
                    }
                }
                Mode.LINE -> {
                    val th = lineThickness.toDouble()
                    for (i in 0 until path.size - 1) {
                        val a = path[i]
                        val b = path[i + 1]
                        val mx = (a.x + b.x) * 0.5
                        val my = (a.y + b.y) * 0.5
                        val mz = (a.z + b.z) * 0.5
                        val dx = kotlin.math.abs(b.x - a.x)
                        val dy = kotlin.math.abs(b.y - a.y)
                        val dz = kotlin.math.abs(b.z - a.z)
                        val fade = ((i + 1).toFloat() / n).coerceIn(0.05f, 1f)
                        val c = colorAt(i, n).let {
                            Color4b(it.r, it.g, it.b, (it.a * fade * (if (i < 15) i / 15f else 1f)).roundToInt().coerceIn(0, 255))
                        }
                        if (c.a < 4) continue
                        withPositionRelativeToCamera(mx, my, mz) {
                            drawBox(
                                AABB(-maxOf(dx * 0.5, th), -maxOf(dy * 0.5, th), -maxOf(dz * 0.5, th),
                                    maxOf(dx * 0.5, th), maxOf(dy * 0.5, th), maxOf(dz * 0.5, th)),
                                faceColor = c,
                                outlineColor = null,
                                noDepthTest = throughWalls,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDisabled() {
        path.clear()
    }
}
