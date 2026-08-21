/*
 * ModuleCombatVisuals — 还原 FDP CombatVisuals 各标记样式
 * 每种模式独立几何，避免「一堆正方体」观感
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleCombatVisuals : ClientModule(
    "CombatVisuals",
    ModuleCategories.RENDER,
    aliases = listOf("TargetESP", "CombatMark"),
) {

    private enum class MarkMode(override val tag: String) : Tagged {
        NONE("None"), POINTS("Points"), IMAGE("Image"), ZAVZ("Zavz"),
        CIRCLE("Circle"), JELLO("Jello"), LIES("Lies"), FDP("FDP"),
        SIMS("Sims"), BOX("Box"), ROUND_BOX("RoundBox"), HEAD("Head"), MARK("Mark"),
    }

    private enum class EntityFilter(override val tag: String) : Tagged {
        ALL("All"), PLAYERS("Players"), MOBS("Mobs"), ANIMALS("Animals"),
    }

    private enum class ParticleMode(override val tag: String) : Tagged {
        NONE("None"), BLOOD("Blood"), LIGHTING("Lighting"), FIRE("Fire"),
        HEART("Heart"), WATER("Water"), SMOKE("Smoke"), MAGIC("Magic"), CRITS("Crits"),
    }

    private enum class SoundMode(override val tag: String) : Tagged {
        NONE("None"), HIT("Hit"), EXPLODE("Explode"), ORB("Orb"),
        POP("Pop"), SPLASH("Splash"), LIGHTNING("Lightning"),
    }

    private val markMode by enumChoice("Mark Mode", MarkMode.CIRCLE)
    private val filterEntityType by enumChoice("Filter Entity", EntityFilter.ALL)
    private val colorPrimary by color("Color Primary", Color4b(0, 90, 255, 255))
    private val colorSecondary by color("Color Secondary", Color4b(0, 200, 255, 200))
    private val rainbow by boolean("Rainbow", false)
    private val hurtFlash by boolean("Hurt Flash", true)
    private val boxOutline by boolean("Box Outline", true)

    private val pointsSpeed by float("Points Speed", 2f, 0.5f..5f)
    private val pointsRadius by float("Points Radius", 0.6f, 0.2f..1.2f)
    private val pointsScale by float("Points Scale", 0.2f, 0.05f..0.5f)
    private val pointsLayers by int("Points Layers", 3, 1..5)

    private val circleStart by color("Circle Start", Color4b(0, 90, 255, 220))
    private val circleEnd by color("Circle End", Color4b(0, 255, 255, 40))
    private val fillInner by boolean("Fill Inner Circle", false)
    private val withHeight by boolean("With Height", true)
    private val animateHeight by boolean("Animate Height", true)
    private val heightMin by float("Height Min", 0.05f, 0f..2f)
    private val heightMax by float("Height Max", 0.45f, 0f..2f)
    private val extraWidth by float("Extra Width", 0.1f, 0f..2f)
    private val animateCircleY by boolean("Animate Circle Y", true)
    private val circleYMin by float("Circle Y Min", 0f, 0f..2f)
    private val circleYMax by float("Circle Y Max", 0.6f, 0f..2f)
    private val animDuration by float("Anim Duration Sec", 1.5f, 0.5f..3f)

    private val imageScale by float("Image Scale", 0.7f, 0.2f..2f)
    private val imageSpin by boolean("Image Spin", true)
    private val imageSpinSpeed by float("Image Spin Speed", 1.2f, 0.1f..5f)

    private val particleMode by enumChoice("Particle", ParticleMode.BLOOD)
    private val particleAmount by int("Particle Amount", 5, 1..20)
    private val soundMode by enumChoice("Sound", SoundMode.POP)
    private val volume by float("Volume", 1f, 0.1f..5f)
    private val pitch by float("Pitch", 1f, 0.1f..5f)
    private val fakeSharp by boolean("Fake Sharp", false)

    private val onlyKillAura by boolean("Only KillAura Target", true)
    private val range by float("Range", 48f, 8f..128f)
    private val throughWalls by boolean("Through Walls", true)

    private fun matchesFilter(e: LivingEntity) = when (filterEntityType) {
        EntityFilter.ALL -> true
        EntityFilter.PLAYERS -> e is Player
        EntityFilter.MOBS -> e is Enemy
        EntityFilter.ANIMALS -> e is Animal
    }

    private fun currentTarget(): LivingEntity? {
        if (onlyKillAura) {
            val t = try { KillAuraTargetTracker.target } catch (_: Throwable) { null }
            if (t is LivingEntity && t.isAlive && matchesFilter(t)) return t
            return null
        }
        val self = mc.player ?: return null
        val world = mc.level ?: return null
        var best: LivingEntity? = null
        var bestD = range.toDouble()
        runCatching {
            for (e in world.entitiesForRendering()) {
                if (e !is LivingEntity || !e.isAlive || e === self || !matchesFilter(e)) continue
                val d = self.distanceTo(e).toDouble()
                if (d < bestD) { bestD = d; best = e }
            }
        }
        return best
    }

    private fun baseColor(e: LivingEntity): Color4b {
        if (hurtFlash && e.hurtTime > 3) return Color4b(255, 55, 55, 210)
        if (rainbow) {
            val h = ((System.currentTimeMillis() % 3000) / 3000f)
            val rgb = java.awt.Color.HSBtoRGB(h, 0.85f, 1f)
            return Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, colorPrimary.a)
        }
        return colorPrimary
    }

    private fun Color4b.a(v: Int) = Color4b(r, g, b, v.coerceIn(0, 255))

    private fun lerpC(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun pos(e: LivingEntity, td: Float): Triple<Double, Double, Double> {
        val x = Mth.lerp(td, e.xo.toFloat(), e.x.toFloat()).toDouble()
        val y = Mth.lerp(td, e.yo.toFloat(), e.y.toFloat()).toDouble()
        val z = Mth.lerp(td, e.zo.toFloat(), e.z.toFloat()).toDouble()
        return Triple(x, y, z)
    }

    private fun WorldRenderEnvironment.dot(x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double, c: Color4b) {
        withPositionRelativeToCamera(x, y, z) {
            drawBox(AABB(-sx, -sy, -sz, sx, sy, sz), faceColor = c, outlineColor = null, noDepthTest = throughWalls)
        }
    }

    private fun WorldRenderEnvironment.ring(
        cx: Double, cy: Double, cz: Double, rad: Double,
        segs: Int, thickness: Double, height: Double, col: Color4b, yWave: (Int) -> Double = { 0.0 },
    ) {
        for (i in 0 until segs) {
            val a0 = i * Math.PI * 2.0 / segs
            val a1 = (i + 1) * Math.PI * 2.0 / segs
            val mx = (cos(a0) + cos(a1)) * 0.5 * rad
            val mz = (sin(a0) + sin(a1)) * 0.5 * rad
            val chord = hypotApprox(cos(a1) - cos(a0), sin(a1) - sin(a0)) * rad
            val yy = cy + yWave(i)
            // 沿切线的扁盒：环带感
            val absC = kotlin.math.abs(cos((a0 + a1) * 0.5))
            val absS = kotlin.math.abs(sin((a0 + a1) * 0.5))
            val hx = if (absS > absC) chord * 0.55 else thickness
            val hz = if (absS > absC) thickness else chord * 0.55
            withPositionRelativeToCamera(cx + mx, yy, cz + mz) {
                drawBox(
                    AABB(-hx, 0.0, -hz, hx, height, hz),
                    faceColor = col,
                    outlineColor = null,
                    noDepthTest = throughWalls,
                )
            }
        }
    }

    private fun hypotApprox(a: Double, b: Double) = kotlin.math.sqrt(a * a + b * b)

    private fun WorldRenderEnvironment.drawBoxMark(e: LivingEntity, td: Float, rounded: Boolean) {
        val (x, y, z) = pos(e, td)
        val w = e.bbWidth / 2.0 + if (rounded) 0.06 else 0.02
        val h = e.bbHeight.toDouble() + if (rounded) 0.06 else 0.02
        val col = baseColor(e).a(55)
        val out = if (boxOutline) baseColor(e).a(200) else null
        withPositionRelativeToCamera(x, y, z) {
            drawBox(AABB(-w, 0.0, -w, w, h, w), faceColor = col, outlineColor = out, noDepthTest = throughWalls)
        }
    }

    private fun WorldRenderEnvironment.drawPlatformMark(e: LivingEntity, td: Float, head: Boolean) {
        val (x, y, z) = pos(e, td)
        val w = e.bbWidth / 2.0 + 0.08
        val yy = if (head) y + e.bbHeight else y
        val col = baseColor(e).a(100)
        withPositionRelativeToCamera(x, yy, z) {
            drawBox(
                AABB(-w, -0.015, -w, w, 0.03, w),
                faceColor = col,
                outlineColor = baseColor(e).a(180),
                noDepthTest = throughWalls,
            )
        }
    }

    private fun WorldRenderEnvironment.drawPointsMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val t = System.currentTimeMillis() / 1000.0 * pointsSpeed
        val baseY = y + e.bbHeight * 0.5
        val col = baseColor(e)
        val s = pointsScale.toDouble() * 0.12
        for (layer in 0 until pointsLayers) {
            val lr = pointsRadius * (1.0 - layer * 0.2) * e.bbWidth
            val n = 14 + layer * 3
            for (i in 0 until n) {
                val a = t + i * (Math.PI * 2.0 / n) + layer * 0.5
                val px = x + cos(a) * lr
                val pz = z + sin(a) * lr
                val py = baseY + sin(t * 2.2 + i + layer) * 0.1
                dot(px, py, pz, s, s * 0.35, s, col.a((200 - layer * 40).coerceAtLeast(40)))
            }
        }
    }

    private fun WorldRenderEnvironment.drawCircleMark(e: LivingEntity, td: Float) {
        val (x, y0, z) = pos(e, td)
        val period = (animDuration * 1000f).toLong().coerceAtLeast(200L)
        val phase = (System.currentTimeMillis() % period) / period.toFloat()
        val yOff = if (animateCircleY) Mth.lerp(phase, circleYMin, circleYMax).toDouble() else 0.0
        val h = if (withHeight) {
            if (animateHeight) Mth.lerp(if (phase < 0.5f) phase * 2f else (1f - phase) * 2f, heightMin, heightMax).toDouble()
            else heightMax.toDouble()
        } else 0.04
        val rad = e.bbWidth / 2.0 + 0.2 + extraWidth
        val segs = 36
        for (i in 0 until segs) {
            val t = i / segs.toFloat()
            val col = lerpC(circleStart, circleEnd, t)
            ring(x, y0 + yOff, z, rad, 1, 0.04, max(0.03, h), col) // single segment via custom
        }
        // continuous ring
        ring(x, y0 + yOff, z, rad, segs, 0.035, max(0.03, h), lerpC(circleStart, circleEnd, phase))
        if (fillInner) {
            ring(x, y0 + yOff, z, rad * 0.55, segs / 2, 0.025, 0.03, circleStart.a(80))
        }
    }

    private fun WorldRenderEnvironment.drawZavzMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val t = System.currentTimeMillis() / 1000.0
        val mid = y + e.bbHeight * 0.5
        for (ringI in 0 until 2) {
            val r = e.bbWidth * (0.75 + ringI * 0.28)
            val dir = if (ringI == 0) 1.0 else -1.0
            val col = if (ringI == 0) baseColor(e) else colorSecondary
            ring(
                x, mid, z, r, 28, 0.04, 0.05, col.a(210),
                yWave = { i -> sin(t * 2.2 * dir + i * 0.4) * 0.28 },
            )
        }
    }

    private fun WorldRenderEnvironment.drawJelloMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val t = System.currentTimeMillis() / 1000.0
        val h = e.bbHeight.toDouble()
        val w0 = e.bbWidth / 2.0
        val col = baseColor(e)
        val layers = 12
        for (i in 0 until layers) {
            val p = i / (layers - 1.0)
            val yy = y + p * h
            val wave = 1.0 + sin(t * 3.5 + p * Math.PI * 2) * 0.18
            val rw = w0 * wave + 0.05
            // 扁环层而非实心立方
            ring(x, yy, z, rw, 20, 0.03, 0.025, col.a((200 * (1 - p * 0.4)).roundToInt()))
        }
    }

    private fun WorldRenderEnvironment.drawLiesMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val t = System.currentTimeMillis() / 700.0
        val h = e.bbHeight.toDouble()
        val col = baseColor(e)
        for (i in 0 until 4) {
            val p = (sin(t + i * 1.2) * 0.5 + 0.5)
            val yy = y + p * h
            val r = e.bbWidth * (0.35 + i * 0.08)
            ring(x, yy, z, r, 24, 0.03, 0.03, col.a(180 - i * 25))
        }
    }

    private fun WorldRenderEnvironment.drawFdpMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val t = System.currentTimeMillis() / 1000.0
        val col = baseColor(e)
        val r = e.bbWidth * 0.85
        for (i in 0 until 24) {
            val a = t * 2.5 + i * Math.PI * 2.0 / 24
            val py = y + e.bbHeight * (0.15 + 0.7 * ((sin(t * 1.5 + i * 0.4) + 1) * 0.5))
            dot(x + cos(a) * r, py, z + sin(a) * r, 0.04, 0.04, 0.04, col.a(210))
        }
    }

    private fun WorldRenderEnvironment.drawSimsMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val top = y + e.bbHeight + 0.4
        val col = if (e.hurtTime > 0) Color4b(255, 40, 40, 220) else Color4b(80, 255, 100, 220)
        // 菱形：中心 + 上下左右
        val s = 0.11
        withPositionRelativeToCamera(x, top, z) {
            drawBox(AABB(-s, -s * 1.2, -s, s, s * 1.2, s), faceColor = col, outlineColor = col.a(255), noDepthTest = throughWalls)
        }
        withPositionRelativeToCamera(x, top + 0.22, z) {
            drawBox(AABB(-0.02, -0.12, -0.02, 0.02, 0.12, 0.02), faceColor = col, outlineColor = null, noDepthTest = throughWalls)
        }
    }

    private fun WorldRenderEnvironment.drawImageMark(e: LivingEntity, td: Float) {
        val (x, y, z) = pos(e, td)
        val t = System.currentTimeMillis() / 1000.0
        val spin = if (imageSpin) t * imageSpinSpeed else 0.0
        val s = imageScale.toDouble() * 0.4
        val cy = y + e.bbHeight * 0.5
        val col = baseColor(e)
        // 四向尖角（类似 capture mark）
        for (i in 0 until 4) {
            val a = spin + i * Math.PI / 2
            val tip = s * 0.95
            val base = s * 0.35
            val tx = x + sin(a) * tip
            val tz = z + cos(a) * tip
            val bx = x + sin(a) * base
            val bz = z + cos(a) * base
            for (k in 0..8) {
                val u = k / 8.0
                dot(
                    bx + (tx - bx) * u, cy, bz + (tz - bz) * u,
                    0.03, 0.03, 0.03, col.a(230),
                )
            }
        }
        ring(x, cy, z, s * 0.32, 16, 0.025, 0.03, col.a(120))
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        if (markMode == MarkMode.NONE) return@handler
        val target = currentTarget() ?: return@handler
        val td = try { mc.deltaTracker.getGameTimeDeltaPartialTick(true) } catch (_: Throwable) { 1f }

        event.renderEnvironment {
            when (markMode) {
                MarkMode.BOX -> drawBoxMark(target, td, false)
                MarkMode.ROUND_BOX -> drawBoxMark(target, td, true)
                MarkMode.HEAD -> drawPlatformMark(target, td, true)
                MarkMode.MARK -> drawPlatformMark(target, td, false)
                MarkMode.POINTS -> drawPointsMark(target, td)
                MarkMode.CIRCLE -> drawCircleMark(target, td)
                MarkMode.ZAVZ -> drawZavzMark(target, td)
                MarkMode.JELLO -> drawJelloMark(target, td)
                MarkMode.LIES -> drawLiesMark(target, td)
                MarkMode.FDP -> drawFdpMark(target, td)
                MarkMode.SIMS -> drawSimsMark(target, td)
                MarkMode.IMAGE -> drawImageMark(target, td)
                MarkMode.NONE -> {}
            }
        }
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val target = event.entity as? LivingEntity ?: return@handler
        if (!matchesFilter(target)) return@handler
        repeat(particleAmount) { spawnParticle(target) }
        playHitSound()
        if (fakeSharp) {
            mc.level?.addParticle(
                ParticleTypes.ENCHANTED_HIT,
                target.x, target.y + target.bbHeight * 0.5, target.z,
                0.0, 0.1, 0.0,
            )
        }
    }

    private fun spawnParticle(target: LivingEntity) {
        val w = mc.level ?: return
        val type = when (particleMode) {
            ParticleMode.NONE -> return
            ParticleMode.BLOOD -> ParticleTypes.DAMAGE_INDICATOR
            ParticleMode.CRITS -> ParticleTypes.CRIT
            ParticleMode.MAGIC -> ParticleTypes.ENCHANTED_HIT
            ParticleMode.SMOKE -> ParticleTypes.SMOKE
            ParticleMode.WATER -> ParticleTypes.FALLING_WATER
            ParticleMode.HEART -> ParticleTypes.HEART
            ParticleMode.FIRE -> ParticleTypes.LAVA
            ParticleMode.LIGHTING -> ParticleTypes.ELECTRIC_SPARK
        }
        runCatching {
            w.addParticle(
                type, target.x, target.y + target.bbHeight * 0.5, target.z,
                (Math.random() - 0.5) * 0.3, Math.random() * 0.2, (Math.random() - 0.5) * 0.3,
            )
        }
    }

    private fun playHitSound() {
        val p = mc.player ?: return
        val snd = when (soundMode) {
            SoundMode.NONE -> return
            SoundMode.HIT -> SoundEvents.ARROW_HIT
            SoundMode.ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP
            SoundMode.POP -> SoundEvents.BUBBLE_COLUMN_BUBBLE_POP
            SoundMode.SPLASH -> SoundEvents.GENERIC_SPLASH
            SoundMode.LIGHTNING -> SoundEvents.LIGHTNING_BOLT_THUNDER
            SoundMode.EXPLODE -> SoundEvents.GENERIC_EXPLODE.value()
        }
        runCatching { p.playSound(snd, volume, pitch) }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { }
}
