/*
 * ModuleFireflyESP — FireflyESP.java 还原
 * 适配: AABB + withPositionRelativeToCamera(x,y,z) + drawBox
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleFireflyESP : ClientModule(
    "FireflyESP",
    ModuleCategories.RENDER,
    aliases = listOf("Firefly", "ParticleESP"),
) {

    private enum class ColorMode(override val tag: String) : Tagged {
        SOLID("Solid"),
        BLEND("Blend"),
        RAINBOW("Rainbow"),
    }

    private val espLength by int("Length", 12, 4..48)
    private val factor by int("Factor", 6, 1..24)
    private val shaking by float("Shaking", 4f, 0.5f..20f)
    private val amplitude by float("Amplitude", 1f, 0.1f..5f)

    private val colorMode by enumChoice("Color Mode", ColorMode.BLEND)
    private val primaryColor by color("Primary", Color4b(255, 255, 255, 230))
    private val secondColor by color("Secondary", Color4b(120, 220, 255, 220))
    private val colorMix by float("Color Mix", 1f, 0f..1f)
    private val colorSpeed by float("Color Speed", 4f, 0.1f..20f)
    private val rainbowSpeed by float("Rainbow Speed", 1.2f, 0.1f..8f)
    private val rainbowSaturation by float("Rainbow Saturation", 0.9f, 0f..1f)
    private val rainbowBrightness by float("Rainbow Brightness", 1f, 0f..1f)

    private val onlyKillAura by boolean("Only KillAura Target", false)
    private val playersOnly by boolean("Players Only", true)
    private val range by float("Range", 48f, 8f..128f)
    private val throughWalls by boolean("Through Walls", true)
    private val particleScale by float("Particle Scale", 1f, 0.3f..3f)
    private val softGlow by boolean("Soft Glow Layers", true)

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun resolveColor(age: Float, index: Int, ringIndex: Int, length: Int): Color4b {
        val progress = if (length <= 0) 0f else index.toFloat() / length.toFloat()
        return when (colorMode) {
            ColorMode.BLEND -> {
                val wave = (sin((age * colorSpeed * 0.25f) + (progress * 6.2831855f) + ringIndex) + 1f) * 0.5f
                lerpColor(primaryColor, secondColor, (wave * colorMix).coerceIn(0f, 1f))
            }
            ColorMode.RAINBOW -> {
                val hue = Mth.frac((age * 0.01f * rainbowSpeed) + progress + ringIndex * 0.17f)
                val rgb = java.awt.Color.HSBtoRGB(
                    hue,
                    rainbowSaturation.coerceIn(0f, 1f),
                    rainbowBrightness.coerceIn(0f, 1f),
                )
                Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, primaryColor.a)
            }
            ColorMode.SOLID -> primaryColor
        }
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
                    if (!playersOnly || e is Player) list.add(e)
                }
            }
        }
        return list
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val ents = targets()
        if (ents.isEmpty()) return@handler

        val tickDelta = try {
            mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        } catch (_: Throwable) {
            1f
        }

        renderEnvironmentForWorld(event.matrixStack) {
            for (target in ents) {
                val tPosX = Mth.lerp(tickDelta, target.xo.toFloat(), target.x.toFloat()).toDouble()
                val tPosY = Mth.lerp(tickDelta, target.yo.toFloat(), target.y.toFloat()).toDouble()
                val tPosZ = Mth.lerp(tickDelta, target.zo.toFloat(), target.z.toFloat()).toDouble()
                val iAge = (target.tickCount - 1).toFloat() + tickDelta
                val bbW = target.bbWidth.toDouble()
                val bbH = target.bbHeight.toDouble()
                val len = espLength

                for (j in 0 until 3) {
                    for (i in 0..len) {
                        val deg = (((i / 1.5f + iAge) * factor + (j * 120)) % (factor * 360))
                        val radians = Math.toRadians(deg.toDouble())
                        val sinQuad = sin(
                            Math.toRadians((iAge * 2.5f + i * (j + 1)).toDouble()) * amplitude,
                        ) / shaking

                        val offset = i.toFloat() / len.toFloat()
                        val px = tPosX + cos(radians) * bbW
                        val py = tPosY + bbH * 0.5 + sinQuad
                        val pz = tPosZ + sin(radians) * bbW
                        val col = resolveColor(iAge, i, j, len)
                        if (col.a < 3) continue

                        val base = max(0.24f * offset, 0.2f) * 0.5f * particleScale
                        val layers = if (softGlow) 3 else 1
                        for (L in 0 until layers) {
                            val t = L / layers.toFloat()
                            val s = (base * (1f + t * 1.1f)).toDouble()
                            val c = Color4b(
                                col.r, col.g, col.b,
                                (col.a * (1f - t * 0.7f)).roundToInt().coerceIn(0, 255),
                            )
                            if (c.a < 3) continue
                            withPositionRelativeToCamera(px, py, pz) {
                                drawBox(
                                    AABB(-s, -s * 0.35, -s, s, s * 0.35, s),
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
    }
}
