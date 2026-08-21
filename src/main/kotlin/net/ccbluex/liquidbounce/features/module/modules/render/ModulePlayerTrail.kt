/*
 * ModulePlayerTrail — 世界空间平滑丝带拖尾 (Ribbon)
 * LiquidBounce Nextgen 0.39 · 原生 Render · 无 Web
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.Box
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3 as McVec3
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ModulePlayerTrail : ClientModule(
    "PlayerTrail",
    ModuleCategories.RENDER,
    aliases = listOf("Breadcrumbs", "TrailRibbon", "Trail3D"),
) {

    private enum class TargetMode(override val tag: String) : Tagged {
        SELF("Self"),
        OTHERS("Others"),
        ALL("All"),
    }

    private val targets by enumChoice("Targets", TargetMode.SELF)

    private val maxPoints by int("Max Points", 72, 12..256)
    private val sampleDist by float("Sample Distance", 0.12f, 0.03f..1.0f)
    private val lifetimeMs by int("Lifetime Ms", 1400, 300..8000)
    private val heightOffset by float("Height Offset", 0.06f, -0.5f..1.5f)

    /** 丝带半宽（米） */
    private val ribbonWidth by float("Ribbon Width", 0.28f, 0.05f..1.2f)
    /** 丝带厚度（竖向，越小越扁） */
    private val ribbonThickness by float("Ribbon Thickness", 0.03f, 0.005f..0.2f)
    /** 每段细分，越大越圆滑 */
    private val segmentSteps by int("Segment Steps", 4, 1..10)

    private val colorStart by color("Color Start", Color4b(90, 230, 255, 230))
    private val colorEnd by color("Color End", Color4b(170, 70, 255, 25))
    private val rainbow by boolean("Rainbow", false)
    private val rainbowSpeed by float("Rainbow Speed", 0.35f, 0.05f..3f)

    private val onlyWhenMoving by boolean("Only When Moving", true)
    private val minSpeed by float("Min Speed", 0.03f, 0f..0.5f)
    private val throughWalls by boolean("Through Walls", true)
    private val doubleSided by boolean("Double Sided", true)

    private data class TrailPoint(
        val x: Double,
        val y: Double,
        val z: Double,
        val time: Long,
        /** 水平朝向（采样时的 yaw），用于丝带侧向 */
        val yawRad: Double,
    )

    private val trails = HashMap<Int, ArrayDeque<TrailPoint>>()
    private var huePhase = 0f
    private var lastSampleNs = 0L

    private fun shouldTrack(p: Player): Boolean {
        val self = mc.player ?: return false
        return when (targets) {
            TargetMode.SELF -> p === self
            TargetMode.OTHERS -> p !== self
            TargetMode.ALL -> true
        }
    }

    private fun speedOf(p: Player): Float =
        hypot(hypot(p.x - p.xo, p.z - p.zo), p.y - p.yo).toFloat()

    private fun allPlayers(): List<Player> {
        val world = mc.level ?: return emptyList()
        val out = ArrayList<Player>()
        mc.player?.let { out.add(it) }
        runCatching {
            for (e in world.players()) {
                if (e is Player && out.none { it.id == e.id }) out.add(e)
            }
        }
        return out
    }

    private fun samplePlayers() {
        val now = System.currentTimeMillis()
        val seen = HashSet<Int>()
        for (p in allPlayers()) {
            if (!shouldTrack(p) || !p.isAlive) continue
            seen += p.id
            val q = trails.getOrPut(p.id) { ArrayDeque() }
            if (onlyWhenMoving && speedOf(p) < minSpeed) {
                while (q.isNotEmpty() && now - q.first().time > lifetimeMs) q.removeFirst()
                continue
            }
            val x = p.x
            val y = p.y + heightOffset
            val z = p.z
            val yaw = Math.toRadians(p.yRot.toDouble())
            val last = q.lastOrNull()
            val dist = if (last == null) Double.MAX_VALUE
            else hypot(hypot(x - last.x, z - last.z), y - last.y)
            if (last == null || dist >= sampleDist) {
                q.addLast(TrailPoint(x, y, z, now, yaw))
            }
            while (q.size > maxPoints) q.removeFirst()
            while (q.isNotEmpty() && now - q.first().time > lifetimeMs) q.removeFirst()
        }
        val it = trails.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (id in seen) continue
            val q = trails[id] ?: continue
            while (q.isNotEmpty() && now - q.first().time > lifetimeMs) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
    }

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun colorAt(ageT: Float, indexT: Float): Color4b {
        val base = if (rainbow) {
            val h = (huePhase + indexT * 0.45f) % 1f
            val rgb = java.awt.Color.HSBtoRGB(h, 0.8f, 1f)
            Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
        } else {
            lerpColor(colorStart, colorEnd, ageT)
        }
        // 尾部更透明，头端更实 → 平滑消散
        val fade = (1f - ageT)
        val soft = fade * fade // ease
        val a = (base.a * soft).roundToInt().coerceIn(0, 255)
        return Color4b(base.r, base.g, base.b, a)
    }

    /** 路径切线 × 上方向 → 侧向，得到丝带左右偏移 */
    private fun sideOffset(
        dx: Double, dy: Double, dz: Double,
        yawFallback: Double,
        halfW: Double,
    ): Triple<Double, Double, Double> {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val fx: Double
        val fy: Double
        val fz: Double
        if (len > 1e-6) {
            fx = dx / len
            fy = dy / len
            fz = dz / len
        } else {
            fx = -kotlin.math.sin(yawFallback)
            fy = 0.0
            fz = kotlin.math.cos(yawFallback)
        }
        // up × forward = side (水平为主)
        var sx = fy * 0.0 - 1.0 * fz
        var sy = 1.0 * fx - fx * 0.0
        var sz = fx * 0.0 - fy * 0.0
        // up=(0,1,0) cross f = (fz, 0, -fx) wait
        sx = fz
        sy = 0.0
        sz = -fx
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < 1e-6) {
            sx = kotlin.math.cos(yawFallback)
            sy = 0.0
            sz = kotlin.math.sin(yawFallback)
        } else {
            sx /= sl
            sz /= sl
        }
        return Triple(sx * halfW, sy * halfW, sz * halfW)
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val nowNs = System.nanoTime()
        if (lastSampleNs == 0L || nowNs - lastSampleNs > 12_000_000L) {
            samplePlayers()
            lastSampleNs = nowNs
            if (rainbow) huePhase = (huePhase + rainbowSpeed * 0.012f) % 1f
        }
        if (trails.isEmpty()) return@handler

        val now = System.currentTimeMillis()
        val life = lifetimeMs.toFloat().coerceAtLeast(1f)
        val halfW = ribbonWidth.toDouble() * 0.5
        val halfT = ribbonThickness.toDouble() * 0.5

        renderEnvironmentForWorld(event.matrixStack) {
            if (throughWalls) {
                runCatching {
                    javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name.contains("Depth", true)
                    }?.invoke(this)
                }
            }

            for ((_, q) in trails) {
                if (q.size < 2) continue
                val pts = q.toList()
                val n = pts.size

                for (i in 0 until n - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val age = (((now - a.time) + (now - b.time)) * 0.5f / life).coerceIn(0f, 1f)
                    val col = colorAt(age, i / max(1f, n - 1f))
                    if (col.a < 4) continue

                    // 内联丝带段（不依赖 WorldEnvDraw）
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val dz = b.z - a.z
                    val len = sqrt(dx * dx + dy * dy + dz * dz)
                    if (len < 1e-4) continue
                    val steps = segmentSteps.coerceAtLeast(1)
                    for (s in 0 until steps) {
                        val t0 = s / steps.toDouble()
                        val t1 = (s + 1) / steps.toDouble()
                        val x0 = a.x + dx * t0
                        val y0 = a.y + dy * t0
                        val z0 = a.z + dz * t0
                        val x1 = a.x + dx * t1
                        val y1 = a.y + dy * t1
                        val z1 = a.z + dz * t1
                        val mx = (x0 + x1) * 0.5
                        val my = (y0 + y1) * 0.5
                        val mz = (z0 + z1) * 0.5
                        val segLen = hypot(hypot(x1 - x0, z1 - z0), y1 - y0).coerceAtLeast(0.01)
                        val yaw = a.yawRad + (b.yawRad - a.yawRad) * ((t0 + t1) * 0.5)
                        val (sx, _, sz) = sideOffset(x1 - x0, y1 - y0, z1 - z0, yaw, 1.0)
                        val spreads = 4
                        for (k in -spreads..spreads) {
                            val u = k / spreads.toDouble()
                            val ox = sx * halfW * u
                            val oz = sz * halfW * u
                            val edgeFade = (1f - kotlin.math.abs(u).toFloat() * 0.5f)
                            val c = Color4b(
                                col.r, col.g, col.b,
                                (col.a * edgeFade).roundToInt().coerceIn(0, 255),
                            )
                            if (c.a < 3) continue
                            val halfF = segLen * 0.52
                            val center = McVec3(mx + ox, my, mz + oz)
                            runCatching {
                                withPositionRelativeToCamera(center) {
                                    drawBox(
                                        Box(
                                            -halfF * 0.4, -halfT, -halfF * 0.4,
                                            halfF * 0.4, halfT, halfF * 0.4,
                                        ),
                                        c,
                                    )
                                }
                            }.recoverCatching {
                                drawBox(
                                    Box(
                                        mx + ox - halfF * 0.4, my - halfT, mz + oz - halfF * 0.4,
                                        mx + ox + halfF * 0.4, my + halfT, mz + oz + halfF * 0.4,
                                    ),
                                    c,
                                )
                            }
                        }
                        runCatching {
                            withPositionRelativeToCamera(McVec3(x0, y0, z0)) {
                                drawLineStrip(
                                    col,
                                    Vec3(0.0, 0.0, 0.0),
                                    Vec3(x1 - x0, y1 - y0, z1 - z0),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDisabled() {
        trails.clear()
        lastSampleNs = 0L
    }
}
