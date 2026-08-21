/*
 * ModuleCombatVisuals — 还原 FDPClient CombatVisuals.kt
 * LiquidBounce Nextgen 0.39 · 原生渲染 · 无 Web
 *
 * 路径:
 *   .../modules/render/ModuleCombatVisuals.kt
 *
 * 在 ModuleManager 注册: ModuleCombatVisuals
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
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
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

    /* ===================== Mark 模式 ===================== */

    private enum class MarkMode(override val tag: String) : Tagged {
        NONE("None"),
        POINTS("Points"),
        IMAGE("Image"),
        ZAVZ("Zavz"),
        CIRCLE("Circle"),
        JELLO("Jello"),
        LIES("Lies"),
        FDP("FDP"),
        SIMS("Sims"),
        BOX("Box"),
        ROUND_BOX("RoundBox"),
        HEAD("Head"),
        MARK("Mark"),
    }

    private enum class EntityFilter(override val tag: String) : Tagged {
        ALL("All"),
        PLAYERS("Players"),
        MOBS("Mobs"),
        ANIMALS("Animals"),
    }

    private enum class ParticleMode(override val tag: String) : Tagged {
        NONE("None"),
        BLOOD("Blood"),
        LIGHTING("Lighting"),
        FIRE("Fire"),
        HEART("Heart"),
        WATER("Water"),
        SMOKE("Smoke"),
        MAGIC("Magic"),
        CRITS("Crits"),
    }

    private enum class SoundMode(override val tag: String) : Tagged {
        NONE("None"),
        HIT("Hit"),
        EXPLODE("Explode"),
        ORB("Orb"),
        POP("Pop"),
        SPLASH("Splash"),
        LIGHTNING("Lightning"),
    }

    private val markMode by enumChoice("Mark Mode", MarkMode.POINTS)
    private val filterEntityType by enumChoice("Filter Entity", EntityFilter.ALL)

    private val colorPrimary by color("Color Primary", Color4b(0, 90, 255, 255))
    private val colorSecondary by color("Color Secondary", Color4b(0, 200, 255, 200))
    private val rainbow by boolean("Rainbow", false)
    private val hurtFlash by boolean("Hurt Flash", true)
    private val boxOutline by boolean("Box Outline", true)

    /* Points */
    private val pointsSpeed by float("Points Speed", 2.0f, 0.5f..5f)
    private val pointsRadius by float("Points Radius", 0.60f, 0.2f..1.2f)
    private val pointsScale by float("Points Scale", 0.25f, 0.05f..0.6f)
    private val pointsLayers by int("Points Layers", 3, 1..5)

    /* Circle */
    private val circleStart by color("Circle Start", Color4b(0, 90, 255, 220))
    private val circleEnd by color("Circle End", Color4b(0, 255, 255, 40))
    private val fillInner by boolean("Fill Inner Circle", false)
    private val withHeight by boolean("With Height", true)
    private val animateHeight by boolean("Animate Height", false)
    private val heightMin by float("Height Min", 0f, -2f..2f)
    private val heightMax by float("Height Max", 0.4f, -2f..2f)
    private val extraWidth by float("Extra Width", 0f, 0f..2f)
    private val animateCircleY by boolean("Animate Circle Y", true)
    private val circleYMin by float("Circle Y Min", 0f, 0f..2f)
    private val circleYMax by float("Circle Y Max", 0.5f, 0f..2f)
    private val animDuration by float("Anim Duration Sec", 1.5f, 0.5f..3f)

    /* Image-like billboard 近似 */
    private val imageScale by float("Image Scale", 0.6f, 0.1f..2f)
    private val imageSpin by boolean("Image Spin", false)
    private val imageSpinSpeed by float("Image Spin Speed", 1f, 0.1f..5f)

    /* Hit FX */
    private val particleMode by enumChoice("Particle", ParticleMode.BLOOD)
    private val particleAmount by int("Particle Amount", 5, 1..20)
    private val soundMode by enumChoice("Sound", SoundMode.POP)
    private val volume by float("Volume", 1f, 0.1f..5f)
    private val pitch by float("Pitch", 1f, 0.1f..5f)
    private val fakeSharp by boolean("Fake Sharp", false)

    private val onlyKillAura by boolean("Only KillAura Target", true)
    private val range by float("Range", 48f, 8f..128f)
    private val throughWalls by boolean("Through Walls", true)

    private var animPhase = 0.0

    private fun matchesFilter(e: LivingEntity): Boolean = when (filterEntityType) {
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
                if (e !is LivingEntity || !e.isAlive || e === self) continue
                if (!matchesFilter(e)) continue
                val d = self.distanceTo(e).toDouble()
                if (d < bestD) {
                    bestD = d
                    best = e
                }
            }
        }
        return best
    }

    private fun baseColor(entity: LivingEntity): Color4b {
        if (hurtFlash && entity.hurtTime > 3) {
            return Color4b(255, 50, 50, 200)
        }
        if (rainbow) {
            val h = ((System.currentTimeMillis() % 3000) / 3000.0).toFloat()
            val rgb = java.awt.Color.HSBtoRGB(h, 0.85f, 1f)
            return Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, colorPrimary.a)
        }
        return colorPrimary
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

    private fun entityPos(e: LivingEntity, tickDelta: Float): Triple<Double, Double, Double> {
        val x = Mth.lerp(tickDelta, e.xo.toFloat(), e.x.toFloat()).toDouble()
        val y = Mth.lerp(tickDelta, e.yo.toFloat(), e.y.toFloat()).toDouble()
        val z = Mth.lerp(tickDelta, e.zo.toFloat(), e.z.toFloat()).toDouble()
        return Triple(x, y, z)
    }

    /* ===================== 绘制各模式 ===================== */

    private fun WorldRenderEnvironment.drawBoxMode(e: LivingEntity, tickDelta: Float, rounded: Boolean) {
        val (x, y, z) = entityPos(e, tickDelta)
        val w = e.bbWidth / 2.0
        val h = e.bbHeight.toDouble()
        val pad = if (rounded) 0.05 else 0.02
        val col = baseColor(e).with(a = 70)
        val outline = if (boxOutline) baseColor(e).with(a = 180) else null
        withPositionRelativeToCamera(x, y, z) {
            drawBox(
                AABB(-w - pad, -pad, -w - pad, w + pad, h + pad, w + pad),
                faceColor = col,
                outlineColor = outline,
                noDepthTest = throughWalls,
            )
        }
        if (rounded) {
            // 圆角近似：角上额外小球
            val s = 0.08
            val c = baseColor(e).with(a = 120)
            for (ox in listOf(-w, w)) for (oz in listOf(-w, w)) {
                withPositionRelativeToCamera(x + ox, y + h * 0.5, z + oz) {
                    drawBox(AABB(-s, -s, -s, s, s, s), faceColor = c, outlineColor = null, noDepthTest = throughWalls)
                }
            }
        }
    }

    private fun WorldRenderEnvironment.drawPlatform(e: LivingEntity, tickDelta: Float, headOnly: Boolean) {
        val (x, y, z) = entityPos(e, tickDelta)
        val w = e.bbWidth / 2.0 + 0.05
        val col = baseColor(e).with(a = 90)
        val yy = if (headOnly) y + e.bbHeight - 0.05 else y
        withPositionRelativeToCamera(x, yy, z) {
            drawBox(
                AABB(-w, -0.02, -w, w, 0.04, w),
                faceColor = col,
                outlineColor = baseColor(e).with(a = 160),
                noDepthTest = throughWalls,
            )
        }
    }

    private fun WorldRenderEnvironment.drawPointsMode(e: LivingEntity, tickDelta: Float) {
        val (x, y, z) = entityPos(e, tickDelta)
        val t = System.currentTimeMillis() / 1000.0 * pointsSpeed
        val baseY = y + e.bbHeight * 0.5
        val col = baseColor(e)
        val layers = pointsLayers
        val r = pointsRadius.toDouble()
        val s = pointsScale.toDouble() * 0.15
        for (layer in 0 until layers) {
            val lr = r * (1.0 - layer * 0.18)
            val n = 12 + layer * 4
            for (i in 0 until n) {
                val a = t + i * (Math.PI * 2.0 / n) + layer * 0.4
                val px = x + cos(a) * lr * e.bbWidth
                val pz = z + sin(a) * lr * e.bbWidth
                val py = baseY + sin(t * 2 + i) * 0.08 * layer
                val c = col.with(a = (col.a * (1f - layer * 0.2f)).roundToInt().coerceIn(20, 255))
                withPositionRelativeToCamera(px, py, pz) {
                    drawBox(
                        AABB(-s, -s * 0.4, -s, s, s * 0.4, s),
                        faceColor = c,
                        outlineColor = null,
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }

    private fun WorldRenderEnvironment.drawCircleMode(e: LivingEntity, tickDelta: Float) {
        val (x, y0, z) = entityPos(e, tickDelta)
        val period = (animDuration * 1000f).coerceAtLeast(100f)
        val phase = ((System.currentTimeMillis() % period.toLong()) / period)
        val yOff = if (animateCircleY) {
            Mth.lerp(phase, circleYMin, circleYMax).toDouble()
        } else 0.0
        val h = if (withHeight) {
            if (animateHeight) Mth.lerp(phase, heightMin, heightMax).toDouble()
            else heightMax.toDouble()
        } else 0.02
        val rad = e.bbWidth / 2.0 + 0.15 + extraWidth
        val segs = 32
        val y = y0 + yOff
        for (i in 0 until segs) {
            val a0 = i * Math.PI * 2.0 / segs
            val a1 = (i + 1) * Math.PI * 2.0 / segs
            val t = i / segs.toFloat()
            val col = lerpColor(circleStart, circleEnd, t)
            val px = x + cos(a0) * rad
            val pz = z + sin(a0) * rad
            withPositionRelativeToCamera(px, y, pz) {
                drawBox(
                    AABB(-0.03, 0.0, -0.03, 0.03, max(0.02, h), 0.03),
                    faceColor = col,
                    outlineColor = null,
                    noDepthTest = throughWalls,
                )
            }
            if (fillInner) {
                val ir = rad * 0.5
                val ix = x + cos(a0) * ir
                val iz = z + sin(a0) * ir
                withPositionRelativeToCamera(ix, y, iz) {
                    drawBox(
                        AABB(-0.02, 0.0, -0.02, 0.02, 0.02, 0.02),
                        faceColor = col.with(a = col.a / 2),
                        outlineColor = null,
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }

    private fun WorldRenderEnvironment.drawZavz(e: LivingEntity, tickDelta: Float) {
        val (x, y, z) = entityPos(e, tickDelta)
        val t = System.currentTimeMillis() / 1000.0
        val baseY = y + e.bbHeight * 0.5
        val c1 = if (rainbow) baseColor(e) else colorPrimary
        val c2 = colorSecondary
        for (ring in 0 until 2) {
            val r = e.bbWidth * (0.7 + ring * 0.25)
            val n = 24
            for (i in 0 until n) {
                val a = t * (1.2 + ring * 0.4) * (if (ring == 0) 1 else -1) + i * Math.PI * 2.0 / n
                val py = baseY + sin(a * 2 + t) * 0.25
                val px = x + cos(a) * r
                val pz = z + sin(a) * r
                val col = if (i % 2 == 0) c1 else c2
                withPositionRelativeToCamera(px, py, pz) {
                    drawBox(
                        AABB(-0.04, -0.04, -0.04, 0.04, 0.04, 0.04),
                        faceColor = col.with(a = 200),
                        outlineColor = null,
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }

    private fun WorldRenderEnvironment.drawJello(e: LivingEntity, tickDelta: Float) {
        val (x, y, z) = entityPos(e, tickDelta)
        val t = System.currentTimeMillis() / 1000.0
        val h = e.bbHeight.toDouble()
        val w = e.bbWidth / 2.0
        val col = baseColor(e)
        val layers = 8
        for (i in 0 until layers) {
            val p = i / (layers - 1.0)
            val yy = y + p * h
            val wave = 1.0 + sin(t * 3 + p * Math.PI * 2) * 0.12
            val rw = w * wave
            val c = col.with(a = (180 * (1 - p * 0.5)).roundToInt())
            withPositionRelativeToCamera(x, yy, z) {
                drawBox(
                    AABB(-rw, -0.02, -rw, rw, 0.02, rw),
                    faceColor = c,
                    outlineColor = null,
                    noDepthTest = throughWalls,
                )
            }
        }
    }

    private fun WorldRenderEnvironment.drawLies(e: LivingEntity, tickDelta: Float) {
        val (x, y, z) = entityPos(e, tickDelta)
        val t = System.currentTimeMillis() / 800.0
        val h = e.bbHeight.toDouble()
        val col = baseColor(e)
        for (i in 0 until 3) {
            val yy = y + (sin(t + i) * 0.5 + 0.5) * h
            val s = 0.15 + i * 0.05
            withPositionRelativeToCamera(x, yy, z) {
                drawBox(
                    AABB(-s, -0.02, -s, s, 0.02, s),
                    faceColor = col.with(a = 160 - i * 30),
                    outlineColor = col.with(a = 220),
                    noDepthTest = throughWalls,
                )
            }
        }
    }

    private fun WorldRenderEnvironment.drawFdp(e: LivingEntity, tickDelta: Float) {
        val (x, y, z) = entityPos(e, tickDelta)
        val t = System.currentTimeMillis() / 1000.0
        val col = baseColor(e)
        val r = e.bbWidth * 0.8
        for (i in 0 until 20) {
            val a = t * 2 + i * Math.PI * 2.0 / 20
            val px = x + cos(a) * r
            val pz = z + sin(a) * r
            val py = y + e.bbHeight * (0.2 + 0.6 * ((sin(t + i) + 1) * 0.5))
            withPositionRelativeToCamera(px, py, pz) {
                drawBox(
                    AABB(-0.05, -0.05, -0.05, 0.05, 0.05, 0.05),
                    faceColor = col.with(a = 200),
                    outlineColor = null,
                    noDepthTest = throughWalls,
                )
            }
        }
    }

    private fun WorldRenderEnvironment.drawSims(e: LivingEntity, tickDelta: Float) {
        // 钻石/水晶：上下锥
        val (x, y, z) = entityPos(e, tickDelta)
        val top = y + e.bbHeight + 0.35
        val hurt = e.hurtTime > 0
        val col = if (hurt) Color4b(255, 0, 0, 200) else Color4b(80, 255, 80, 200)
        val s = 0.12
        // 垂直柱
        withPositionRelativeToCamera(x, top - 0.2, z) {
            drawBox(AABB(-0.02, -0.2, -0.02, 0.02, 0.2, 0.02), faceColor = col, outlineColor = null, noDepthTest = throughWalls)
        }
        // 菱形点
        for (dy in listOf(-0.15, 0.0, 0.15)) {
            withPositionRelativeToCamera(x, top + dy, z) {
                drawBox(AABB(-s, -s * 0.5, -s, s, s * 0.5, s), faceColor = col, outlineColor = col.with(a = 255), noDepthTest = throughWalls)
            }
        }
    }

    private fun WorldRenderEnvironment.drawImageMode(e: LivingEntity, tickDelta: Float) {
        val (x, y, z) = entityPos(e, tickDelta)
        val t = System.currentTimeMillis() / 1000.0
        val spin = if (imageSpin) t * imageSpinSpeed else 0.0
        val s = imageScale.toDouble() * 0.35
        val cy = y + e.bbHeight * 0.5
        val col = baseColor(e)
        // 十字/矩形 billboard 近似（无贴图时）
        val arms = 8
        for (i in 0 until arms) {
            val a = spin + i * Math.PI * 2.0 / arms
            val px = x + cos(a) * s
            val pz = z + sin(a) * s
            withPositionRelativeToCamera(px, cy, pz) {
                drawBox(
                    AABB(-0.04, -0.04, -0.04, 0.04, 0.04, 0.04),
                    faceColor = col.with(a = 220),
                    outlineColor = null,
                    noDepthTest = throughWalls,
                )
            }
        }
        withPositionRelativeToCamera(x, cy, z) {
            drawBox(
                AABB(-s * 0.3, -s * 0.3, -s * 0.3, s * 0.3, s * 0.3, s * 0.3),
                faceColor = col.with(a = 100),
                outlineColor = col,
                noDepthTest = throughWalls,
            )
        }
    }

    /* Color4b.with helper */
    private fun Color4b.with(a: Int = this.a): Color4b = Color4b(r, g, b, a.coerceIn(0, 255))

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        if (markMode == MarkMode.NONE) return@handler
        val target = currentTarget() ?: return@handler
        val tickDelta = try {
            mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        } catch (_: Throwable) { 1f }

        animPhase = (System.currentTimeMillis() % 10000) / 1000.0

        event.renderEnvironment {
            when (markMode) {
                MarkMode.BOX -> drawBoxMode(target, tickDelta, false)
                MarkMode.ROUND_BOX -> drawBoxMode(target, tickDelta, true)
                MarkMode.HEAD -> drawPlatform(target, tickDelta, true)
                MarkMode.MARK -> drawPlatform(target, tickDelta, false)
                MarkMode.POINTS -> drawPointsMode(target, tickDelta)
                MarkMode.CIRCLE -> drawCircleMode(target, tickDelta)
                MarkMode.ZAVZ -> drawZavz(target, tickDelta)
                MarkMode.JELLO -> drawJello(target, tickDelta)
                MarkMode.LIES -> drawLies(target, tickDelta)
                MarkMode.FDP -> drawFdp(target, tickDelta)
                MarkMode.SIMS -> drawSims(target, tickDelta)
                MarkMode.IMAGE -> drawImageMode(target, tickDelta)
                MarkMode.NONE -> {}
            }
        }
    }

    /* ===================== 攻击粒子 / 音效 ===================== */

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val target = event.entity as? LivingEntity ?: return@handler
        if (!matchesFilter(target)) return@handler

        repeat(particleAmount) { spawnParticle(target) }
        playHitSound()
        if (fakeSharp) {
            // 暴击粒子额外一次
            val w = mc.level ?: return@handler
            runCatching {
                w.addParticle(
                    ParticleTypes.ENCHANTED_HIT,
                    target.x, target.y + target.bbHeight * 0.5, target.z,
                    0.0, 0.1, 0.0,
                )
            }
        }
    }

    private fun spawnParticle(target: LivingEntity) {
        val w = mc.level ?: return
        val x = target.x
        val y = target.y + target.bbHeight * 0.5
        val z = target.z
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
            val rx = (Math.random() - 0.5) * 0.3
            val ry = Math.random() * 0.2
            val rz = (Math.random() - 0.5) * 0.3
            w.addParticle(type, x, y, z, rx, ry, rz)
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
        runCatching {
            p.playSound(snd, volume, pitch)
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        // 切世界清空状态
        animPhase = 0.0
    }
}
