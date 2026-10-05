/*
 * ModuleProjectilePredict — 投掷物轨迹预测（修复映射/API）
 * 不依赖易变类名：用 EntityType / Item 判断
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
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object ModuleProjectilePredict : ClientModule(
    "ProjectilePredict",
    ModuleCategories.RENDER,
    aliases = listOf("Trajectories", "PearlLine", "ArrowPredict"),
) {

    private enum class ShowMode(override val tag: String) : Tagged {
        HELD("Held Item"),
        WORLD("World Projectiles"),
        BOTH("Both"),
    }

    private val showMode by enumChoice("Show", ShowMode.BOTH)
    private val predictSelf by boolean("Self", true)
    private val predictOthers by boolean("Others", true)
    private val maxTicks by int("Max Ticks", 80, 20..200)
    private val stepSize by float("Step Size", 1f, 0.25f..2f)
    private val lineWidth by float("Line Width", 0.04f, 0.015f..0.12f)
    private val landMarker by boolean("Land Marker", true)
    private val landSize by float("Land Size", 0.25f, 0.1f..0.6f)
    private val throughWalls by boolean("Through Walls Draw", true)

    private val pearl by boolean("Ender Pearl", true)
    private val arrow by boolean("Arrow / Bow", true)
    private val snowball by boolean("Snowball", true)
    private val egg by boolean("Egg", true)
    private val potion by boolean("Potion", true)
    private val trident by boolean("Trident", true)
    private val otherThrowable by boolean("Other Throwable", true)

    private val colorPearl by color("Pearl Color", Color4b(180, 80, 255, 200))
    private val colorArrow by color("Arrow Color", Color4b(255, 220, 80, 200))
    private val colorSnow by color("Snowball Color", Color4b(200, 230, 255, 200))
    private val colorEgg by color("Egg Color", Color4b(255, 200, 160, 200))
    private val colorPotion by color("Potion Color", Color4b(120, 220, 120, 200))
    private val colorTrident by color("Trident Color", Color4b(80, 200, 255, 200))
    private val colorOther by color("Other Color", Color4b(255, 255, 255, 180))
    private val colorLand by color("Land Color", Color4b(255, 80, 80, 220))

    private data class SimType(val gravity: Double, val drag: Double, val speed: Double, val color: Color4b)
    private data class Point(val x: Double, val y: Double, val z: Double)

    private fun typeName(e: Entity): String =
        runCatching { e.type.toString().lowercase() }.getOrDefault(e.javaClass.simpleName.lowercase())

    private fun heldSim(stack: ItemStack): SimType? {
        if (stack.isEmpty) return null
        val item = stack.item
        return when {
            pearl && item == Items.ENDER_PEARL ->
                SimType(0.03, 0.99, 1.5, colorPearl)
            snowball && item == Items.SNOWBALL ->
                SimType(0.03, 0.99, 1.5, colorSnow)
            egg && item == Items.EGG ->
                SimType(0.03, 0.99, 1.5, colorEgg)
            potion && (item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) ->
                SimType(0.05, 0.99, 0.5, colorPotion)
            trident && item == Items.TRIDENT ->
                SimType(0.05, 0.99, 2.5, colorTrident)
            arrow && (item is BowItem || item == Items.ARROW || item == Items.TIPPED_ARROW || item == Items.SPECTRAL_ARROW) ->
                SimType(0.05, 0.99, bowSpeed(stack), colorArrow)
            arrow && item is CrossbowItem ->
                SimType(0.05, 0.99, 3.15, colorArrow)
            otherThrowable && item == Items.EXPERIENCE_BOTTLE ->
                SimType(0.07, 0.99, 0.7, colorOther)
            else -> null
        }
    }

    private fun bowSpeed(stack: ItemStack): Double {
        val p = mc.player ?: return 3.0
        return runCatching {
            if (!p.isUsingItem) return@runCatching 3.0
            // 兼容不同映射的拉弓进度
            val remain = p.useItemRemainingTicks
            val maxUse = runCatching {
                stack.javaClass.methods.firstOrNull {
                    it.parameterCount == 1 && it.name.lowercase().contains("useduration")
                }?.invoke(stack, p) as? Int
            }.getOrNull() ?: 20
            val used = (maxUse - remain).coerceAtLeast(0)
            val power = (used / 20.0).coerceIn(0.1, 1.0)
            // 近似 BowItem.getPowerForTime
            val pwr = ((power * power + power * 2.0) / 3.0).coerceAtMost(1.0)
            pwr * 3.0
        }.getOrDefault(3.0)
    }

    private fun entitySim(e: Entity): SimType? {
        if (e !is Projectile) return null
        val n = typeName(e)
        return when {
            (n.contains("enderpearl") || n.contains("ender_pearl")) && pearl ->
                SimType(0.03, 0.99, 0.0, colorPearl)
            (n.contains("arrow") || n.contains("spectral")) && arrow ->
                SimType(0.05, 0.99, 0.0, colorArrow)
            n.contains("potion") && potion ->
                SimType(0.05, 0.99, 0.0, colorPotion)
            n.contains("snowball") && snowball ->
                SimType(0.03, 0.99, 0.0, colorSnow)
            n.contains("egg") && !n.contains("dragon") && egg ->
                SimType(0.03, 0.99, 0.0, colorEgg)
            n.contains("trident") && trident ->
                SimType(0.05, 0.99, 0.0, colorTrident)
            otherThrowable && (n.contains("experience") || n.contains("throwable")) ->
                SimType(0.03, 0.99, 0.0, colorOther)
            otherThrowable && e is Projectile ->
                SimType(0.05, 0.99, 0.0, colorOther)
            else -> null
        }
    }

    private fun simulate(start: Vec3, vel: Vec3, gravity: Double, drag: Double): Pair<List<Point>, Point?> {
        val world = mc.level ?: return emptyList<Point>() to null
        val self = mc.player ?: return emptyList<Point>() to null
        val points = ArrayList<Point>(maxTicks)
        var x = start.x
        var y = start.y
        var z = start.z
        var vx = vel.x
        var vy = vel.y
        var vz = vel.z
        val step = stepSize.toDouble().coerceAtLeast(0.25)
        var land: Point? = null
        var t = 0f
        while (t < maxTicks) {
            val x0 = x
            val y0 = y
            val z0 = z
            x += vx * step
            y += vy * step
            z += vz * step
            vy -= gravity * step
            vx *= drag
            vy *= drag
            vz *= drag
            points += Point(x, y, z)

            val hit = runCatching {
                world.clip(
                    ClipContext(
                        Vec3(x0, y0, z0),
                        Vec3(x, y, z),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        self as Entity,
                    ),
                )
            }.getOrNull()

            if (hit != null && hit.type != HitResult.Type.MISS) {
                if (hit is BlockHitResult) {
                    land = Point(hit.location.x, hit.location.y, hit.location.z)
                    points[points.lastIndex] = land
                }
                break
            }
            if (y < world.minY - 16) break
            t += step.toFloat()
        }
        return points to land
    }

    private fun lookVelocity(yawDeg: Float, pitchDeg: Float, speed: Double): Vec3 {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val x = -sin(yaw) * cos(pitch) * speed
        val y = -sin(pitch) * speed
        val z = cos(yaw) * cos(pitch) * speed
        return Vec3(x, y, z)
    }

    private fun drawPath(points: List<Point>, land: Point?, color: Color4b, event: WorldRenderEvent) {
        if (points.size < 2) return
        val th = lineWidth.toDouble()
        event.renderEnvironment {
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val mx = (a.x + b.x) * 0.5
                val my = (a.y + b.y) * 0.5
                val mz = (a.z + b.z) * 0.5
                val dx = kotlin.math.abs(b.x - a.x)
                val dy = kotlin.math.abs(b.y - a.y)
                val dz = kotlin.math.abs(b.z - a.z)
                val fade = 1f - i / points.size.toFloat() * 0.35f
                val c = Color4b(color.r, color.g, color.b, (color.a * fade).toInt().coerceIn(20, 255))
                withPositionRelativeToCamera(mx, my, mz) {
                    drawBox(
                        AABB(
                            -maxOf(dx * 0.5, th), -maxOf(dy * 0.5, th), -maxOf(dz * 0.5, th),
                            maxOf(dx * 0.5, th), maxOf(dy * 0.5, th), maxOf(dz * 0.5, th),
                        ),
                        faceColor = c,
                        outlineColor = null,
                        noDepthTest = throughWalls,
                    )
                }
            }
            if (landMarker && land != null) {
                val s = landSize.toDouble()
                withPositionRelativeToCamera(land.x, land.y, land.z) {
                    drawBox(
                        AABB(-s, 0.0, -s, s, s * 0.15, s),
                        faceColor = colorLand,
                        outlineColor = Color4b(colorLand.r, colorLand.g, colorLand.b, 255),
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val self = mc.player ?: return@handler
        val world = mc.level ?: return@handler

        if (showMode == ShowMode.HELD || showMode == ShowMode.BOTH) {
            if (predictSelf) {
                val sim = heldSim(self.mainHandItem)
                if (sim != null && sim.speed > 0.0) {
                    val (pts, land) = simulate(self.eyePosition, lookVelocity(self.yRot, self.xRot, sim.speed), sim.gravity, sim.drag)
                    drawPath(pts, land, sim.color, event)
                }
            }
            if (predictOthers) {
                runCatching {
                    for (p in world.players()) {
                        if (p === self || !p.isAlive) continue
                        val sim = heldSim(p.mainHandItem) ?: continue
                        if (sim.speed <= 0.0) continue
                        val (pts, land) = simulate(
                            p.eyePosition,
                            lookVelocity(p.yRot, p.xRot, sim.speed),
                            sim.gravity,
                            sim.drag,
                        )
                        drawPath(pts, land, Color4b(sim.color.r, sim.color.g, sim.color.b, (sim.color.a * 0.7f).toInt()), event)
                    }
                }
            }
        }

        if (showMode == ShowMode.WORLD || showMode == ShowMode.BOTH) {
            runCatching {
                for (e in world.entitiesForRendering()) {
                    if (e !is Projectile) continue
                    val owner = runCatching { e.owner }.getOrNull()
                    if (!predictSelf && owner === self) continue
                    if (!predictOthers && owner !== self && owner != null) continue
                    val sim = entitySim(e) ?: continue
                    val (pts, land) = simulate(e.position(), e.deltaMovement, sim.gravity, sim.drag)
                    drawPath(pts, land, sim.color, event)
                }
            }
        }
    }
}
