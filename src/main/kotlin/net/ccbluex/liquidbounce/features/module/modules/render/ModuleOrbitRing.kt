/*
 * ModuleOrbitRing —— 绕身光环（Ring / Halo / Cone / Spiral）
 * LiquidBounce Nextgen 0.39 · WorldRender · 原生渲染 · 无 Web
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawLine
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object ModuleOrbitRing : ClientModule(
    "OrbitRing",
    ModuleCategories.RENDER,
    aliases = listOf("ChinaHat", "Halo", "AuraRing", "绕身光环"),
) {

    private fun Color4b.toArgbInt(): Int = runCatching {
        // 新 API
        javaClass.getMethod("getArgb").invoke(this) as Int
    }.getOrElse {
        runCatching {
            val f = javaClass.getDeclaredField("argb")
            f.isAccessible = true
            f.getInt(this)
        }.getOrElse {
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    // ---------- 目标 ----------
    private val self by boolean("Self", true)
    private val players by boolean("Players", false)
    private val mobs by boolean("Mobs", false)
    private val range by float("Target Range", 24f, 4f..64f)

    // ---------- 形状 ----------
    private enum class Shape(override val tag: String) : Tagged {
        RING("Ring"),
        DOUBLE("Double Ring"),
        HALO("Halo"),
        CONE("Cone Hat"),
        SPIRAL("Spiral"),
    }

    private val shape by enumChoice("Shape", Shape.RING)
    private val radius by float("Radius", 0.7f, 0.2f..2.5f)
    private val height by float("Height Offset", 0.05f, -1.5f..2.5f)
    private val wave by float("Wave", 0.02f, 0f..0.3f)
    private val segments by int("Segments", 56, 16..128)

    // ---------- 动画 ----------
    private val spin by boolean("Spin", true)
    private val spinSpeed by float("Spin Speed", 0.85f, -5f..5f)
    private val bob by boolean("Bob", true)
    private val bobAmount by float("Bob Amount", 0.05f, 0f..0.4f)
    private val bobSpeed by float("Bob Speed", 1.5f, 0.2f..6f)
    private val pulse by boolean("Pulse", false)
    private val pulseSpeed by float("Pulse Speed", 2.0f, 0.3f..8f)
    private val pulseScale by float("Pulse Scale", 0.1f, 0f..0.5f)

    // ---------- 颜色 ----------
    private enum class ColorMode(override val tag: String) : Tagged {
        SOLID("Solid"),
        GRADIENT("Gradient"),
        RAINBOW("Rainbow"),
        HEALTH("Health"),
    }

    private val colorMode by enumChoice("Color Mode", ColorMode.GRADIENT)
    private val colorA by color("Color A", Color4b(0x6E, 0xC8, 0xF1, 230))
    private val colorB by color("Color B", Color4b(0xE9, 0xA8, 0xBC, 230))
    private val rainbowSpeed by float("Rainbow Speed", 0.45f, 0.05f..2.5f)
    private val alpha by float("Alpha", 0.92f, 0.15f..1f)

    private var time = 0f
    private var lastNs = 0L

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun hsv(h: Float, s: Float, v: Float, a: Int): Color4b {
        val hh = ((h % 1f) + 1f) % 1f * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Color4b(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255),
            a,
        )
    }

    private fun colorAt(u: Float, entity: Entity, a: Int): Color4b = when (colorMode) {
        ColorMode.SOLID -> colorA.alpha(a)
        ColorMode.GRADIENT -> Color4b(
            lerp(colorA.r.toFloat(), colorB.r.toFloat(), u).toInt().coerceIn(0, 255),
            lerp(colorA.g.toFloat(), colorB.g.toFloat(), u).toInt().coerceIn(0, 255),
            lerp(colorA.b.toFloat(), colorB.b.toFloat(), u).toInt().coerceIn(0, 255),
            a,
        )
        ColorMode.RAINBOW -> hsv(u + time * rainbowSpeed, 0.85f, 1f, a)
        ColorMode.HEALTH -> {
            val hp = if (entity is LivingEntity) {
                (entity.health / entity.maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
            } else 1f
            when {
                hp > 0.5f -> {
                    val t = (hp - 0.5f) * 2f
                    Color4b(lerp(255f, 80f, t).toInt(), lerp(200f, 255f, t).toInt(), 60, a)
                }
                else -> {
                    val t = hp * 2f
                    Color4b(255, lerp(40f, 200f, t).toInt(), 40, a)
                }
            }
        }
    }

    private fun shouldDraw(e: Entity, selfEntity: Entity): Boolean {
        if (e === selfEntity) return self
        if (e is Player) return players
        if (e is LivingEntity) return mobs
        return false
    }

    private fun entityPos(entity: Entity, partial: Float, camX: Double, camY: Double, camZ: Double): Triple<Float, Float, Float> {
        val x = (entity.xo + (entity.x - entity.xo) * partial) - camX
        val y = (entity.yo + (entity.y - entity.yo) * partial) - camY
        val z = (entity.zo + (entity.z - entity.zo) * partial) - camZ
        return Triple(x.toFloat(), y.toFloat(), z.toFloat())
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val world = mc.level ?: return@handler
        val player = mc.player ?: return@handler

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        time += dt

        val partial = event.partialTicks
        val cam = event.camera.position
        val segs = segments.coerceIn(16, 128)
        val a = (255 * alpha).toInt().coerceIn(20, 255)
        val spinAng = if (spin) time * spinSpeed * 2f * PI.toFloat() else 0f
        val bobOff = if (bob) sin(time * bobSpeed * 2f * PI.toFloat()) * bobAmount else 0f
        val pulseMul = if (pulse) 1f + sin(time * pulseSpeed * 2f * PI.toFloat()) * pulseScale else 1f
        val rad = radius * pulseMul

        val targets = ArrayList<Entity>(16)
        if (self) targets.add(player)
        if (players || mobs) {
            val r2 = range * range
            for (e in world.entitiesForRendering()) {
                if (e === player) continue
                if (!shouldDraw(e, player)) continue
                if (e.distanceToSqr(player) > r2) continue
                targets.add(e)
            }
        }
        if (targets.isEmpty()) return@handler

        event.renderEnvironment {
            for (entity in targets) {
                val (ex, ey, ez) = entityPos(entity, partial, cam.x, cam.y, cam.z)
                val midY = ey + entity.bbHeight * 0.5f + height + bobOff
                when (shape) {
                    Shape.RING ->
                        strokeRing(ex, midY, ez, rad, segs, spinAng, a, entity, 0f)
                    Shape.DOUBLE -> {
                        strokeRing(ex, midY, ez, rad, segs, spinAng, a, entity, 0f)
                        strokeRing(ex, midY + 0.14f, ez, rad * 0.82f, segs, -spinAng * 1.15f, a, entity, 0.5f)
                    }
                    Shape.HALO -> {
                        val hy = ey + entity.bbHeight + 0.28f + height + bobOff
                        strokeRing(ex, hy, ez, rad * 0.5f, segs, spinAng, a, entity, 0f)
                    }
                    Shape.CONE -> {
                        val topY = ey + entity.bbHeight + 0.4f + height + bobOff
                        val botY = topY - 0.5f
                        strokeRing(ex, botY, ez, rad * 0.72f, segs, spinAng, a, entity, 0f)
                        // 锥面辐条
                        val spokes = (segs / 8).coerceIn(6, 16)
                        for (i in 0 until spokes) {
                            val t = i / spokes.toFloat()
                            val ang = t * 2f * PI.toFloat() + spinAng
                            val px = ex + cos(ang) * rad * 0.72f
                            val pz = ez + sin(ang) * rad * 0.72f
                            val col = colorAt(t, entity, a)
                            drawLine(
                                Vec3f(ex, topY, ez),
                                Vec3f(px, botY, pz),
                                col.toArgbInt(),
                            )
                        }
                    }
                    Shape.SPIRAL -> strokeSpiral(ex, midY, ez, rad, segs, spinAng, a, entity)
                }
            }
        }
    }

    private fun net.ccbluex.liquidbounce.render.WorldRenderEnvironment.strokeRing(
        cx: Float, cy: Float, cz: Float,
        rad: Float, segs: Int, spin: Float,
        a: Int, entity: Entity, hueShift: Float,
    ) {
        var prev: Vec3f? = null
        for (i in 0..segs) {
            val t = i / segs.toFloat()
            val ang = t * 2f * PI.toFloat() + spin
            val py = cy + if (wave > 0.001f) sin(ang * 3f + time * 3f) * wave else 0f
            val p = Vec3f(cx + cos(ang) * rad, py, cz + sin(ang) * rad)
            val last = prev
            if (last != null) {
                val col = colorAt((t + hueShift) % 1f, entity, a)
                drawLine(last, p, col.toArgbInt())
            }
            prev = p
        }
    }

    private fun net.ccbluex.liquidbounce.render.WorldRenderEnvironment.strokeSpiral(
        cx: Float, cy: Float, cz: Float,
        rad: Float, segs: Int, spin: Float,
        a: Int, entity: Entity,
    ) {
        val turns = 2.6f
        var prev: Vec3f? = null
        for (i in 0..segs) {
            val t = i / segs.toFloat()
            val ang = t * turns * 2f * PI.toFloat() + spin
            val r = rad * (0.3f + 0.7f * t)
            val p = Vec3f(
                cx + cos(ang) * r,
                cy + (t - 0.5f) * 0.6f,
                cz + sin(ang) * r,
            )
            val last = prev
            if (last != null) {
                drawLine(last, p, colorAt(t, entity, a).toArgbInt())
            }
            prev = p
        }
    }

    override fun onEnabled() {
        lastNs = 0L
        time = 0f
    }
}
