/*
 * ModulePlayerTrail — 纯 2D 扁平连续丝带（Overlay）
 * 世界采样 → 投影到屏幕 → 一条连贯的扁平带（非 3D、非碎块拼接）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ModulePlayerTrail : ClientModule(
    "PlayerTrail",
    ModuleCategories.RENDER,
    aliases = listOf("RibbonTrail", "FlatTrail2D"),
) {

    private enum class TargetMode(override val tag: String) : Tagged {
        SELF("Self"),
        OTHERS("Others"),
        ALL("All"),
    }

    private val targets by enumChoice("Targets", TargetMode.SELF)
    private val maxPoints by int("Max Points", 40, 8..100)
    private val sampleDist by float("Sample Distance", 0.2f, 0.05f..1f)
    private val lifetimeMs by int("Lifetime Ms", 1400, 300..6000)

    /** 屏幕上丝带半宽（像素） */
    private val ribbonHalfWidth by float("Ribbon Width Px", 3.5f, 0.5f..40f)
    private val yOffset by float("World Y Offset", 0.9f, 0f..2.5f)
    private val backOffset by float("Back Offset", 0.2f, 0f..1.5f)

    private val colorHead by color("Color Head", Color4b(130, 230, 255, 230))
    private val colorTail by color("Color Tail", Color4b(90, 80, 255, 0))
    private val rainbow by boolean("Rainbow", false)
    private val rainbowSpeed by float("Rainbow Speed", 0.4f, 0.05f..3f)
    private val onlyWhenMoving by boolean("Only When Moving", true)
    private val minSpeed by float("Min Speed", 0.02f, 0f..0.5f)
    /** 细分：越大越圆滑（在投影点之间插值） */
    private val smoothSteps by int("Smooth Steps", 3, 1..8)

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
            for (e in world.players()) {
                if (e is Player && list.none { it.id == e.id }) list.add(e)
            }
        }
        for (p in list) {
            if (!shouldTrack(p) || !p.isAlive) continue
            seen += p.id
            val q = trails.getOrPut(p.id) { ArrayDeque() }
            if (onlyWhenMoving && speed(p) < minSpeed) {
                while (q.isNotEmpty() && now - q.first().time > lifetimeMs) q.removeFirst()
                continue
            }
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
            val h = (hue + i * 0.3f) % 1f
            val rgb = java.awt.Color.HSBtoRGB(h, 0.75f, 1f)
            Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
        } else {
            lerpC(colorHead, colorTail, age)
        }
        val fade = (1f - age).let { it * it }
        return Color4b(base.r, base.g, base.b, (base.a * fade).roundToInt().coerceIn(0, 255))
    }

    /**
     * 世界坐标 → 屏幕像素。优先 WorldToScreen，失败则用投影矩阵。
     * 返回 null 表示在相机后方或不可见。
     */
    private fun worldToScreen(x: Double, y: Double, z: Double, screenW: Float, screenH: Float): Pair<Float, Float>? {
        // 1) LB WorldToScreen
        runCatching {
            val cls = Class.forName("net.ccbluex.liquidbounce.utils.render.WorldToScreen")
            val m = cls.methods.firstOrNull {
                it.name.contains("calculateScreenPos", true) && it.parameterCount in 1..2
            } ?: return@runCatching
            val vec = Vec3(x, y, z)
            val r = if (m.parameterCount == 1) m.invoke(null, vec) else m.invoke(null, vec, null)
            if (r != null) {
                val fx = r.javaClass.methods.firstOrNull { it.name == "x" || it.name == "getX" }
                val fy = r.javaClass.methods.firstOrNull { it.name == "y" || it.name == "getY" }
                val sx = (fx?.invoke(r) as? Number)?.toFloat()
                val sy = (fy?.invoke(r) as? Number)?.toFloat()
                if (sx != null && sy != null) return sx to sy
                // Vec3f fields
                val fxs = r.javaClass.fields.firstOrNull { it.name == "x" }
                val fys = r.javaClass.fields.firstOrNull { it.name == "y" }
                if (fxs != null && fys != null) {
                    return fxs.getFloat(r) to fys.getFloat(r)
                }
            }
        }

        // 2) 手动投影：不访问 private mainCamera，用反射或玩家视角近似
        return runCatching {
            var camX = 0.0
            var camY = 0.0
            var camZ = 0.0
            var yawDeg = 0f
            var pitchDeg = 0f

            // 反射 GameRenderer 取 Camera
            val camObj = runCatching {
                val gr = mc.gameRenderer
                gr.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (
                        it.name.equals("getMainCamera", true)
                            || it.name.equals("mainCamera", true)
                            || it.returnType.name.contains("Camera")
                        )
                }?.invoke(gr)
                    ?: gr.javaClass.declaredFields.firstOrNull {
                        it.type.name.contains("Camera")
                    }?.also { it.isAccessible = true }?.get(gr)
            }.getOrNull()

            if (camObj != null) {
                runCatching {
                    val pos = camObj.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (
                            it.name == "position" || it.name == "getPosition" || it.name == "pos"
                            )
                    }?.invoke(camObj)
                    if (pos is Vec3) {
                        camX = pos.x; camY = pos.y; camZ = pos.z
                    }
                }
                runCatching {
                    yawDeg = (camObj.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "yRot" || it.name == "getYRot")
                    }?.invoke(camObj) as? Number)?.toFloat() ?: yawDeg
                    pitchDeg = (camObj.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "xRot" || it.name == "getXRot")
                    }?.invoke(camObj) as? Number)?.toFloat() ?: pitchDeg
                }
            } else {
                val p = mc.player ?: return@runCatching null
                camX = p.x
                camY = p.y + p.eyeHeight
                camZ = p.z
                yawDeg = p.yRot
                pitchDeg = p.xRot
            }

            val relX = (x - camX).toFloat()
            val relY = (y - camY).toFloat()
            val relZ = (z - camZ).toFloat()

            val view = Matrix4f()
            val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
            val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
            view.rotationY(-yaw)
            view.rotateX(pitch)

            val world = Vector4f(relX, relY, relZ, 1f)
            view.transform(world)
            if (world.z >= 0f) return@runCatching null

            val fov = try {
                mc.options.fov().get().toFloat()
            } catch (_: Throwable) {
                70f
            }
            val aspect = screenW / max(1f, screenH)
            val proj = Matrix4f().perspective(
                Math.toRadians(fov.toDouble()).toFloat(),
                aspect,
                0.05f,
                512f,
            )
            val clip = Vector4f(world.x, world.y, world.z, 1f)
            proj.transform(clip)
            if (clip.w == 0f) return@runCatching null
            val ndcX = clip.x / clip.w
            val ndcY = clip.y / clip.w
            val sx = (ndcX * 0.5f + 0.5f) * screenW
            val sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH
            sx to sy
        }.getOrNull()
    }

    private data class ScreenPt(val x: Float, val y: Float, val age: Float, val indexT: Float)

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ns = System.nanoTime()
        if (lastSampleNs == 0L || ns - lastSampleNs > 14_000_000L) {
            sample()
            lastSampleNs = ns
            if (rainbow) hue = (hue + rainbowSpeed * 0.012f) % 1f
        }
        if (trails.isEmpty()) return@handler

        val ctx = event.context
        val sw = try { ctx.guiWidth().toFloat() } catch (_: Throwable) {
            mc.window.guiScaledWidth.toFloat()
        }
        val sh = try { ctx.guiHeight().toFloat() } catch (_: Throwable) {
            mc.window.guiScaledHeight.toFloat()
        }

        val now = System.currentTimeMillis()
        val life = lifetimeMs.toFloat().coerceAtLeast(1f)
        val halfW = ribbonHalfWidth
        val steps = smoothSteps.coerceAtLeast(1)

        for ((_, q) in trails) {
            if (q.size < 2) continue
            val raw = q.toList()
            val n = raw.size

            // 投影 + 在点之间插值，得到更密的屏幕点列 → 丝滑连续
            val screen = ArrayList<ScreenPt>(n * steps)
            for (i in 0 until n - 1) {
                val a = raw[i]
                val b = raw[i + 1]
                for (s in 0 until steps) {
                    val u = s / steps.toFloat()
                    val wx = a.x + (b.x - a.x) * u
                    val wy = a.y + (b.y - a.y) * u
                    val wz = a.z + (b.z - a.z) * u
                    val age = (((now - a.time) * (1 - u) + (now - b.time) * u) / life).coerceIn(0f, 1f)
                    val idx = (i + u) / max(1f, n - 1f)
                    val sp = worldToScreen(wx, wy, wz, sw, sh) ?: continue
                    screen += ScreenPt(sp.first, sp.second, age, idx)
                }
            }
            // 末点
            val last = raw.last()
            worldToScreen(last.x, last.y, last.z, sw, sh)?.let {
                val age = ((now - last.time) / life).coerceIn(0f, 1f)
                screen += ScreenPt(it.first, it.second, age, 1f)
            }

            if (screen.size < 2) continue

            // 计算每点法线（屏幕平面垂直于切线）→ 左右边缘，整条带连贯
            val left = FloatArray(screen.size * 2)
            val right = FloatArray(screen.size * 2)
            for (i in screen.indices) {
                val p = screen[i]
                val (tx, ty) = when (i) {
                    0 -> {
                        val n0 = screen[1]
                        (n0.x - p.x) to (n0.y - p.y)
                    }
                    screen.lastIndex -> {
                        val p0 = screen[i - 1]
                        (p.x - p0.x) to (p.y - p0.y)
                    }
                    else -> {
                        val p0 = screen[i - 1]
                        val n0 = screen[i + 1]
                        (n0.x - p0.x) to (n0.y - p0.y)
                    }
                }
                val len = sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f)
                // 垂直：(-ty, tx)
                val nx = -ty / len * halfW
                val ny = tx / len * halfW
                left[i * 2] = p.x + nx
                left[i * 2 + 1] = p.y + ny
                right[i * 2] = p.x - nx
                right[i * 2 + 1] = p.y - ny
            }

            // 一条连续丝带：沿路径密铺扁平短条，段间重叠，看起来是一整条 2D 带
            for (i in 0 until screen.size - 1) {
                val c0 = colorAt(screen[i].age, screen[i].indexT)
                val c1 = colorAt(screen[i + 1].age, screen[i + 1].indexT)
                val col = lerpC(c0, c1, 0.5f)
                if (col.a < 4) continue

                val mx0 = (left[i * 2] + right[i * 2]) * 0.5f
                val my0 = (left[i * 2 + 1] + right[i * 2 + 1]) * 0.5f
                val mx1 = (left[(i + 1) * 2] + right[(i + 1) * 2]) * 0.5f
                val my1 = (left[(i + 1) * 2 + 1] + right[(i + 1) * 2 + 1]) * 0.5f
                drawFlatStrip(ctx, mx0, my0, mx1, my1, halfW, col)
            }
        }
    }

    /** 在两点之间画一段固定宽度的扁平 2D 条（屏幕空间，无 3D） */
    private fun drawFlatStrip(
        ctx: Any,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        halfW: Float,
        color: Color4b,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.2f) return
        val nx = -dy / len * halfW
        val ny = dx / len * halfW

        // 沿中线细分，每小段用轴对齐 quad 覆盖（重叠 30% 消缝）
        val parts = max(2, (len / 2.5f).toInt().coerceAtMost(24))
        for (i in 0 until parts) {
            val u0 = i / parts.toFloat()
            val u1 = ((i + 1.35f) / parts).coerceAtMost(1f) // 重叠
            val ax = x0 + dx * u0
            val ay = y0 + dy * u0
            val bx = x0 + dx * u1
            val by = y0 + dy * u1
            val minX = minOf(ax + nx, ax - nx, bx + nx, bx - nx)
            val maxX = maxOf(ax + nx, ax - nx, bx + nx, bx - nx)
            val minY = minOf(ay + ny, ay - ny, by + ny, by - ny)
            val maxY = maxOf(ay + ny, ay - ny, by + ny, by - ny)
            if (maxX - minX < 0.5f && maxY - minY < 0.5f) continue
            runCatching {
                val m = ctx.javaClass.methods.firstOrNull {
                    it.name == "drawQuad" && it.parameterCount >= 5
                }
                if (m != null) {
                    m.invoke(ctx, minX, minY, maxX, maxY, color)
                } else {
                    (ctx as? net.minecraft.client.gui.GuiGraphicsExtractor)
                        ?.drawQuad(minX, minY, maxX, maxY, color)
                }
            }
        }
    }

    override fun onDisabled() {
        trails.clear()
        lastSampleNs = 0L
    }
}
