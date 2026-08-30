/*
 * ModuleEntityGlow — LiquidBounce Nextgen 0.39
 * 实体轮廓辉光 / 地面阴影 · 原生渲染 · 无 Web
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object ModuleEntityGlow : ClientModule(
    "EntityGlow",
    ModuleCategories.RENDER,
    aliases = listOf("EntityShadow", "OutlineGlow"),
) {

    private enum class Mode(override val tag: String) : Tagged {
        GLOW("Glow"),
        SHADOW("Shadow"),
        BOTH("Both"),
        BOX("Box Outline"),
    }

    private enum class TargetFilter(override val tag: String) : Tagged {
        ALL("All"),
        PLAYERS("Players"),
        MOBS("Mobs"),
        HOSTILE("Hostile"),
        ANIMALS("Animals"),
    }

    private val mode by enumChoice("Mode", Mode.BOTH)
    private val filter by enumChoice("Targets", TargetFilter.ALL)

    private val range by float("Range", 48f, 8f..128f)
    private val throughWalls by boolean("Through Walls", true)

    // Glow
    private val glowEnabled by boolean("Glow", true)
    private val glowRadius by float("Glow Radius", 0.35f, 0.05f..1.5f)
    private val glowLayers by int("Glow Layers", 6, 2..14)
    private val glowStrength by float("Glow Strength", 0.55f, 0.05f..1.5f)
    private val glowSoftness by float("Glow Softness", 1.4f, 0.5f..3f)
    private val glowColor by color("Glow Color", Color4b(120, 200, 255, 255))
    private val glowUseTeam by boolean("Glow By Type", true)
    private val glowPlayer by color("Player Glow", Color4b(80, 200, 255, 255))
    private val glowHostile by color("Hostile Glow", Color4b(255, 70, 70, 255))
    private val glowPassive by color("Passive Glow", Color4b(80, 255, 120, 255))
    private val glowPulse by boolean("Glow Pulse", false)
    private val glowPulseSpeed by float("Pulse Speed", 2.2f, 0.4f..8f)

    // Shadow
    private val shadowEnabled by boolean("Shadow", true)
    private val shadowSize by float("Shadow Size", 1.15f, 0.5f..2.5f)
    private val shadowAlpha by float("Shadow Alpha", 0.45f, 0.05f..0.9f)
    private val shadowColor by color("Shadow Color", Color4b(0, 0, 0, 255))
    private val shadowOffsetY by float("Shadow Y Offset", 0.02f, 0f..0.2f)
    private val shadowOval by boolean("Shadow Oval", true)

    // Box outline
    private val boxLineWidth by float("Box Width", 1.5f, 0.5f..4f)
    private val boxColor by color("Box Color", Color4b(255, 255, 255, 200))

    private val self by boolean("Render Self", false)
    private val invisible by boolean("Invisible", false)

    private fun isTarget(e: LivingEntity): Boolean {
        if (e === player && !self) return false
        if (!e.isAlive || e.isRemoved) return false
        if (!invisible && e.isInvisible) return false
        if (player.distanceTo(e) > range) return false
        if (e is Player && (e.isSpectator)) return false
        return when (filter) {
            TargetFilter.ALL -> true
            TargetFilter.PLAYERS -> e is Player
            TargetFilter.MOBS -> e is Mob
            TargetFilter.HOSTILE -> e is Mob && e !is Animal && e !is Player
            TargetFilter.ANIMALS -> e is Animal
        }
    }

    private fun colorFor(e: LivingEntity): Color4b {
        if (!glowUseTeam) return glowColor
        return when {
            e is Player -> glowPlayer
            e is Animal -> glowPassive
            e is Mob && e !is Player -> glowHostile
            else -> glowColor
        }
    }

    private fun pulseMul(): Float {
        if (!glowPulse) return 1f
        val t = (System.currentTimeMillis() % 100000L) / 1000.0
        return (0.78f + 0.22f * sin(t * glowPulseSpeed).toFloat())
    }

    private fun camPos(partial: Float): Vec3 {
        val p = player
        return Vec3(
            p.xo + (p.x - p.xo) * partial,
            p.yo + (p.y - p.yo) * partial + p.eyeHeight,
            p.zo + (p.z - p.zo) * partial,
        )
    }

    private fun entityBox(e: LivingEntity, partial: Float): AABB {
        val x = e.xo + (e.x - e.xo) * partial
        val y = e.yo + (e.y - e.yo) * partial
        val z = e.zo + (e.z - e.zo) * partial
        val dx = x - e.x
        val dy = y - e.y
        val dz = z - e.z
        return e.boundingBox.move(dx, dy, dz)
    }

    /** 相对相机坐标画填充盒（6 面） */
    private fun drawFilledBoxRel(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        try {
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND)
            org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
            org.lwjgl.opengl.GL11.glDepthMask(false)
            org.lwjgl.opengl.GL11.glColor4f(r, g, b, a)
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS)
            // bottom
            org.lwjgl.opengl.GL11.glVertex3d(minX, minY, minZ); org.lwjgl.opengl.GL11.glVertex3d(maxX, minY, minZ)
            org.lwjgl.opengl.GL11.glVertex3d(maxX, minY, maxZ); org.lwjgl.opengl.GL11.glVertex3d(minX, minY, maxZ)
            // top
            org.lwjgl.opengl.GL11.glVertex3d(minX, maxY, minZ); org.lwjgl.opengl.GL11.glVertex3d(minX, maxY, maxZ)
            org.lwjgl.opengl.GL11.glVertex3d(maxX, maxY, maxZ); org.lwjgl.opengl.GL11.glVertex3d(maxX, maxY, minZ)
            // north
            org.lwjgl.opengl.GL11.glVertex3d(minX, minY, minZ); org.lwjgl.opengl.GL11.glVertex3d(minX, maxY, minZ)
            org.lwjgl.opengl.GL11.glVertex3d(maxX, maxY, minZ); org.lwjgl.opengl.GL11.glVertex3d(maxX, minY, minZ)
            // south
            org.lwjgl.opengl.GL11.glVertex3d(minX, minY, maxZ); org.lwjgl.opengl.GL11.glVertex3d(maxX, minY, maxZ)
            org.lwjgl.opengl.GL11.glVertex3d(maxX, maxY, maxZ); org.lwjgl.opengl.GL11.glVertex3d(minX, maxY, maxZ)
            // west
            org.lwjgl.opengl.GL11.glVertex3d(minX, minY, minZ); org.lwjgl.opengl.GL11.glVertex3d(minX, minY, maxZ)
            org.lwjgl.opengl.GL11.glVertex3d(minX, maxY, maxZ); org.lwjgl.opengl.GL11.glVertex3d(minX, maxY, minZ)
            // east
            org.lwjgl.opengl.GL11.glVertex3d(maxX, minY, minZ); org.lwjgl.opengl.GL11.glVertex3d(maxX, maxY, minZ)
            org.lwjgl.opengl.GL11.glVertex3d(maxX, maxY, maxZ); org.lwjgl.opengl.GL11.glVertex3d(maxX, minY, maxZ)
            org.lwjgl.opengl.GL11.glEnd()
            org.lwjgl.opengl.GL11.glDepthMask(true)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND)
            org.lwjgl.opengl.GL11.glColor4f(1f, 1f, 1f, 1f)
        } catch (_: Throwable) {
        }
    }

    private fun drawBoxLinesRel(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        r: Float, g: Float, b: Float, a: Float, width: Float,
    ) {
        try {
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND)
            org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
            org.lwjgl.opengl.GL11.glLineWidth(width)
            org.lwjgl.opengl.GL11.glColor4f(r, g, b, a)
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_LINES)
            fun edge(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
                org.lwjgl.opengl.GL11.glVertex3d(x1, y1, z1); org.lwjgl.opengl.GL11.glVertex3d(x2, y2, z2)
            }
            // bottom
            edge(minX, minY, minZ, maxX, minY, minZ)
            edge(maxX, minY, minZ, maxX, minY, maxZ)
            edge(maxX, minY, maxZ, minX, minY, maxZ)
            edge(minX, minY, maxZ, minX, minY, minZ)
            // top
            edge(minX, maxY, minZ, maxX, maxY, minZ)
            edge(maxX, maxY, minZ, maxX, maxY, maxZ)
            edge(maxX, maxY, maxZ, minX, maxY, maxZ)
            edge(minX, maxY, maxZ, minX, maxY, minZ)
            // pillars
            edge(minX, minY, minZ, minX, maxY, minZ)
            edge(maxX, minY, minZ, maxX, maxY, minZ)
            edge(maxX, minY, maxZ, maxX, maxY, maxZ)
            edge(minX, minY, maxZ, minX, maxY, maxZ)
            org.lwjgl.opengl.GL11.glEnd()
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND)
            org.lwjgl.opengl.GL11.glColor4f(1f, 1f, 1f, 1f)
        } catch (_: Throwable) {
        }
    }

    /** 椭圆地面阴影（水平面扇形近似） */
    private fun drawShadowRel(
        cx: Double, cy: Double, cz: Double,
        rx: Double, rz: Double,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        try {
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND)
            org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
            org.lwjgl.opengl.GL11.glDepthMask(false)
            org.lwjgl.opengl.GL11.glColor4f(r, g, b, a)
            val segs = 24
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN)
            org.lwjgl.opengl.GL11.glVertex3d(cx, cy, cz)
            for (i in 0..segs) {
                val ang = i.toDouble() / segs * Math.PI * 2.0
                val x = cx + cos(ang) * rx
                val z = cz + sin(ang) * rz
                org.lwjgl.opengl.GL11.glVertex3d(x, cy, z)
            }
            org.lwjgl.opengl.GL11.glEnd()
            org.lwjgl.opengl.GL11.glDepthMask(true)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND)
            org.lwjgl.opengl.GL11.glColor4f(1f, 1f, 1f, 1f)
        } catch (_: Throwable) {
        }
    }

    private fun drawGlowBox(box: AABB, cam: Vec3, base: Color4b) {
        if (!glowEnabled && mode != Mode.GLOW && mode != Mode.BOTH) return
        if (mode == Mode.SHADOW || mode == Mode.BOX) return
        val layers = glowLayers.coerceIn(2, 14)
        val maxR = glowRadius.coerceAtLeast(0.05f)
        val strength = glowStrength.coerceIn(0.05f, 1.5f)
        val soft = glowSoftness.coerceIn(0.5f, 3f)
        val pulse = pulseMul()
        for (i in layers downTo 1) {
            val u = i / layers.toFloat()
            val expand = maxR * u
            val gauss = kotlin.math.exp((-(u * u) * (2.6f / soft)).toDouble()).toFloat()
            val a = (gauss * strength * pulse * 140f).toInt().coerceIn(0, 160)
            if (a < 3) continue
            val minX = box.minX - expand - cam.x
            val minY = box.minY - expand * 0.35 - cam.y
            val minZ = box.minZ - expand - cam.z
            val maxX = box.maxX + expand - cam.x
            val maxY = box.maxY + expand * 0.35 - cam.y
            val maxZ = box.maxZ + expand - cam.z
            drawFilledBoxRel(
                minX, minY, minZ, maxX, maxY, maxZ,
                base.r / 255f, base.g / 255f, base.b / 255f, a / 255f,
            )
        }
    }

    private fun drawShadowUnder(box: AABB, cam: Vec3) {
        if (!shadowEnabled && mode != Mode.SHADOW && mode != Mode.BOTH) return
        if (mode == Mode.GLOW || mode == Mode.BOX) return
        val cx = (box.minX + box.maxX) * 0.5 - cam.x
        val cz = (box.minZ + box.maxZ) * 0.5 - cam.z
        val cy = box.minY + shadowOffsetY - cam.y
        val hw = (box.maxX - box.minX) * 0.5 * shadowSize
        val hd = (box.maxZ - box.minZ) * 0.5 * shadowSize
        val a = shadowAlpha.coerceIn(0.05f, 0.9f)
        val col = shadowColor
        if (shadowOval) {
            drawShadowRel(
                cx, cy, cz, hw, hd,
                col.r / 255f, col.g / 255f, col.b / 255f, a,
            )
        } else {
            drawFilledBoxRel(
                cx - hw, cy, cz - hd,
                cx + hw, cy + 0.015, cz + hd,
                col.r / 255f, col.g / 255f, col.b / 255f, a,
            )
        }
    }

    private fun drawOutlineBox(box: AABB, cam: Vec3, col: Color4b) {
        if (mode != Mode.BOX && mode != Mode.BOTH) return
        // BOTH 时也可画细线轮廓
        if (mode == Mode.BOTH) {
            // optional thin edge
        }
        if (mode != Mode.BOX) return
        drawBoxLinesRel(
            box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
            box.maxX - cam.x, box.maxY - cam.y, box.maxZ - cam.z,
            col.r / 255f, col.g / 255f, col.b / 255f, col.a / 255f,
            boxLineWidth,
        )
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val partial = runCatching {
            event.partialTicks
        }.getOrElse {
            runCatching {
                event.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.name.contains("partial", true)
                }?.invoke(event) as? Float
            }.getOrNull() ?: 1f
        }

        val cam = camPos(partial)
        val list = ArrayList<LivingEntity>()
        runCatching {
            for (e in world.entitiesForRendering()) {
                if (e is LivingEntity && isTarget(e)) list.add(e)
            }
        }
        runCatching {
            val box = player.boundingBox.inflate(range.toDouble())
            for (e in world.getEntities(player, box) { it is LivingEntity }) {
                if (e is LivingEntity && isTarget(e) && e !in list) list.add(e)
            }
        }

        for (e in list) {
            if (!throughWalls) {
                if (!player.hasLineOfSight(e)) continue
            }
            val box = entityBox(e, partial)
            val gCol = colorFor(e)

            when (mode) {
                Mode.GLOW -> drawGlowBox(box, cam, gCol)
                Mode.SHADOW -> drawShadowUnder(box, cam)
                Mode.BOTH -> {
                    drawShadowUnder(box, cam)
                    drawGlowBox(box, cam, gCol)
                }
                Mode.BOX -> drawOutlineBox(box, cam, boxColor)
            }
        }
    }
}
