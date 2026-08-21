/*
 * ModulePlayerTrail — 玩家身后竖直 2D 平面丝带
 * 路径采样 + 竖直薄片（非方块堆）
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
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ModulePlayerTrail : ClientModule(
    "PlayerTrail",
    ModuleCategories.RENDER,
    aliases = listOf("RibbonTrail", "CapeTrail"),
) {

    private enum class TargetMode(override val tag: String) : Tagged {
        SELF("Self"),
        OTHERS("Others"),
        ALL("All"),
    }

    private val targets by enumChoice("Targets", TargetMode.SELF)
    private val maxPoints by int("Max Points", 48, 8..120)
    private val sampleDist by float("Sample Distance", 0.18f, 0.05f..1f)
    private val lifetimeMs by int("Lifetime Ms", 1200, 300..5000)

    /** 丝带竖直高度（米） */
    private val ribbonHeight by float("Ribbon Height", 1.4f, 0.3f..3f)
    /** 丝带厚度（越薄越像 2D 平面） */
    private val ribbonThickness by float("Thickness", 0.035f, 0.01f..0.15f)
    /** 相对脚底上抬 */
    private val yOffset by float("Y Offset", 0.1f, -0.5f..1.5f)
    /** 身后起点额外后退 */
    private val backOffset by float("Back Offset", 0.15f, 0f..1f)

    private val colorStart by color("Color Head", Color4b(120, 220, 255, 220))
    private val colorEnd by color("Color Tail", Color4b(80, 100, 255, 20))
    private val rainbow by boolean("Rainbow", false)
    private val rainbowSpeed by float("Rainbow Speed", 0.4f, 0.05f..3f)
    private val onlyWhenMoving by boolean("Only When Moving", true)
    private val minSpeed by float("Min Speed", 0.02f, 0f..0.5f)
    private val throughWalls by boolean("Through Walls", true)

    private data class Pt(val x: Double, val y: Double, val z: Double, val time: Long)

    private val trails = HashMap<Int, ArrayDeque<Pt>>()
    private var hue = 0f
    private var lastSampleNs = 0L

    private fun shouldTrack(p: Player): Boolean {
        val self = mc.player ?: return false
        return when (targets) {
            TargetMode.SELF -> p === self
            TargetMode.OTHERS -> p !== self
            TargetMode.ALL -> true
        }
    }

    private fun speed(p: Player) =
        hypot(hypot(p.x - p.xo, p.z - p.zo), p.y - p.yo).toFloat()

    private fun sample() {
        val now = System.currentTimeMillis()
        val world = mc.level ?: return
        val seen = HashSet<Int>()
        val list = ArrayList<Player>()
        mc.player?.let { list.add(it) }
        runCatching {
            for (e in world.players()) if (e is Player && list.none { it.id == e.id }) list.add(e)
        }
        for (p in list) {
            if (!shouldTrack(p) || !p.isAlive) continue
            seen += p.id
            val q = trails.getOrPut(p.id) { ArrayDeque() }
            if (onlyWhenMoving && speed(p) < minSpeed) {
                while (q.isNotEmpty() && now - q.first().time > lifetimeMs) q.removeFirst()
                continue
            }
            // 身后一点：沿水平朝向后退
            val yaw = Math.toRadians(p.yRot.toDouble())
            val bx = -kotlin.math.sin(yaw) * backOffset
            val bz = kotlin.math.cos(yaw) * backOffset
            val x = p.x - bx
            val y = p.y + yOffset
            val z = p.z - bz
            val last = q.lastOrNull()
            val d = if (last == null) Double.MAX_VALUE
            else hypot(hypot(x - last.x, z - last.z), y - last.y)
            if (last == null || d >= sampleDist) q.addLast(Pt(x, y, z, now))
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

    private fun lerpC(a: Color4b, b: Color4b, t: Float): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            Mth.lerp(u, a.r.toFloat(), b.r.toFloat()).roundToInt(),
            Mth.lerp(u, a.g.toFloat(), b.g.toFloat()).roundToInt(),
            Mth.lerp(u, a.b.toFloat(), b.b.toFloat()).roundToInt(),
            Mth.lerp(u, a.a.toFloat(), b.a.toFloat()).roundToInt(),
        )
    }

    private fun colorAt(age: Float, i: Float): Color4b {
        val base = if (rainbow) {
            val h = (hue + i * 0.35f) % 1f
            val rgb = java.awt.Color.HSBtoRGB(h, 0.75f, 1f)
            Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
        } else lerpC(colorStart, colorEnd, age)
        val fade = (1f - age).let { it * it }
        return Color4b(base.r, base.g, base.b, (base.a * fade).roundToInt().coerceIn(0, 255))
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val ns = System.nanoTime()
        if (lastSampleNs == 0L || ns - lastSampleNs > 14_000_000L) {
            sample()
            lastSampleNs = ns
            if (rainbow) hue = (hue + rainbowSpeed * 0.012f) % 1f
        }
        if (trails.isEmpty()) return@handler

        val now = System.currentTimeMillis()
        val life = lifetimeMs.toFloat().coerceAtLeast(1f)
        val h = ribbonHeight.toDouble()
        val th = ribbonThickness.toDouble() * 0.5

        event.renderEnvironment {
            for ((_, q) in trails) {
                if (q.size < 2) continue
                val pts = q.toList()
                val n = pts.size
                for (i in 0 until n - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    val age = (((now - a.time) + (now - b.time)) * 0.5f / life).coerceIn(0f, 1f)
                    val col = colorAt(age, i / max(1f, n - 1f))
                    if (col.a < 5) continue

                    // 竖直 2D 平面：沿路径方向拉长，Y 向为高度，水平垂直方向极薄
                    val dx = b.x - a.x
                    val dz = b.z - a.z
                    val len = hypot(dx, dz).coerceAtLeast(0.02)
                    val mx = (a.x + b.x) * 0.5
                    val my = (a.y + b.y) * 0.5
                    val mz = (a.z + b.z) * 0.5

                    // 轴对齐近似：按主方向选薄轴，做成「竖片」
                    val absDx = kotlin.math.abs(dx)
                    val absDz = kotlin.math.abs(dz)
                    val halfLen = len * 0.52
                    val minX: Double
                    val maxX: Double
                    val minZ: Double
                    val maxZ: Double
                    if (absDx >= absDz) {
                        // 主要沿 X：Z 向极薄
                        minX = -halfLen; maxX = halfLen
                        minZ = -th; maxZ = th
                    } else {
                        minX = -th; maxX = th
                        minZ = -halfLen; maxZ = halfLen
                    }
                    withPositionRelativeToCamera(mx, my, mz) {
                        drawBox(
                            AABB(minX, 0.0, minZ, maxX, h, maxZ),
                            faceColor = col,
                            outlineColor = null,
                            noDepthTest = throughWalls,
                        )
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
