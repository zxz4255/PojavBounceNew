/*
 * ModuleCaptureMarkESP — 还原 CaptureMarkESP.java + target.png
 *
 * 贴图请放到:
 *   src/main/resources/assets/liquidbounce/textures/esp/target.png
 * （可用本仓库 artifacts/esp_textures/target.png）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.Box
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3 as McVec3
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleCaptureMarkESP : ClientModule(
    "CaptureMarkESP",
    ModuleCategories.RENDER,
    aliases = listOf("CaptureMark", "TargetMark"),
) {

    private val espSize by float("Size", 0.9f, 0.2f..3f)
    private val rotSpeed by float("Rotation Speed", 1.2f, 0.1f..8f)
    private val waveSpeed by float("Wave Speed", 2.5f, 0.1f..12f)
    private val color1 by color("Color 1", Color4b(255, 255, 255, 240))
    private val color2 by color("Color 2", Color4b(120, 200, 255, 240))
    private val onlyKillAura by boolean("Only KillAura Target", true)
    private val playersOnly by boolean("Players Only", false)
    private val range by float("Range", 32f, 4f..128f)
    private val throughWalls by boolean("Through Walls", true)
    private val ringSegments by int("Ring Segments", 32, 12..80)
    /** 资源路径: assets/<ns>/textures/esp/target.png */
    private val texturePath by text("Texture Path", "liquidbounce:textures/esp/target.png")

    private fun texId(): Identifier = try {
        Identifier.parse(texturePath)
    } catch (_: Throwable) {
        Identifier.fromNamespaceAndPath("liquidbounce", "textures/esp/target.png")
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

    private fun colorForProgress(progress: Float, timeSec: Double): Color4b {
        var wave = sin((progress * Math.PI * 2.0) + (timeSec * waveSpeed)).toFloat()
        wave = (wave + 1f) / 2f
        return lerpColor(color1, color2, wave)
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
                if (playersOnly && e !is net.minecraft.world.entity.player.Player) continue
                if (self.distanceTo(e) > range) continue
                list.add(e)
            }
        }.onFailure {
            for (e in world.players()) {
                if (e is LivingEntity && e !== self && e.isAlive && self.distanceTo(e) <= range) list.add(e)
            }
        }
        return list
    }

    /**
     * 四向箭头框：用线段还原 target.png 的 ◇ 箭头轮廓，并整体旋转 + 波形着色。
     * （世界空间贴图 billboard 需自定义 RenderLayer；无层时用几何还原外形）
     */
    private fun drawCaptureMark(
        env: Any,
        x: Double, y: Double, z: Double,
        size: Float, rotRad: Double, timeSec: Double,
    ) {
        val s = size.toDouble()
        // target.png: 上下左右四个尖角，外轮廓约在 0.85 * radius
        val tips = arrayOf(
            doubleArrayOf(0.0, 1.0),   // up
            doubleArrayOf(1.0, 0.0),   // right
            doubleArrayOf(0.0, -1.0),  // down
            doubleArrayOf(-1.0, 0.0),  // left
        )
        // 每个箭头：外尖 + 两侧内点
        for (t in 0 until 4) {
            val base = t * Math.PI / 2.0 + rotRad
            val prog = t / 4f
            val col = colorForProgress(prog, timeSec)
            val tipR = s * 0.95
            val innerR = s * 0.42
            val wing = s * 0.32
            val tx = x + sin(base) * tipR
            val tz = z + cos(base) * tipR
            val cx = x + sin(base) * innerR
            val cz = z + cos(base) * innerR
            val px = cos(base) * wing
            val pz = -sin(base) * wing
            runCatching {
                // 三角箭头三条边
                with(env) {
                    // use extension via outer render block only — drawn in handler
                }
            }
            // 实际绘制在 handler 的 renderEnvironment 内完成
            markSegs.add(MarkSeg(cx + px, y, cz + pz, tx, y, tz, col))
            markSegs.add(MarkSeg(cx - px, y, cz - pz, tx, y, tz, col))
            markSegs.add(MarkSeg(cx + px, y, cz + pz, cx - px, y, cz - pz, col))
        }
        // 中心菱形框
        val d = s * 0.38
        val diamond = arrayOf(
            doubleArrayOf(0.0, d), doubleArrayOf(d, 0.0),
            doubleArrayOf(0.0, -d), doubleArrayOf(-d, 0.0),
        )
        for (i in 0 until 4) {
            val a0 = rotRad + i * Math.PI / 2.0
            val a1 = rotRad + (i + 1) * Math.PI / 2.0
            val col = colorForProgress(0.1f * i, timeSec)
            markSegs.add(
                MarkSeg(
                    x + sin(a0) * d, y, z + cos(a0) * d,
                    x + sin(a1) * d, y, z + cos(a1) * d,
                    col,
                ),
            )
        }
    }

    private data class MarkSeg(
        val x0: Double, val y0: Double, val z0: Double,
        val x1: Double, val y1: Double, val z1: Double,
        val color: Color4b,
    )

    private val markSegs = ArrayList<MarkSeg>(64)

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        val ents = targets()
        if (ents.isEmpty()) return@handler

        val timeSec = System.nanoTime() * 1.0e-9
        val rotation = -((timeSec * rotSpeed * 60.0) % 360.0).toFloat()
        val rotRad = Math.toRadians(rotation.toDouble())
        val size = espSize * 0.5f

        // 预注册贴图（若资源存在，便于其它系统引用）
        runCatching { texId() }

        markSegs.clear()
        for (target in ents) {
            val tickDelta = try {
                mc.deltaTracker.getGameTimeDeltaPartialTick(true)
            } catch (_: Throwable) { 1f }
            val x = Mth.lerp(tickDelta, target.xo, target.x.toFloat()).toDouble()
            val y = Mth.lerp(tickDelta, target.yo, target.y.toFloat()).toDouble() + target.bbHeight * 0.5
            val z = Mth.lerp(tickDelta, target.zo, target.z.toFloat()).toDouble()
            drawCaptureMark(this, x, y, z, size, rotRad, timeSec)
        }

        renderEnvironmentForWorld(event.matrixStack) {
            if (throughWalls) {
                runCatching {
                    javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name.contains("Depth", true)
                    }?.invoke(this)
                }
            }
            for (seg in markSegs) {
                runCatching {
                    withPositionRelativeToCamera(McVec3(seg.x0, seg.y0, seg.z0)) {
                        drawLineStrip(
                            seg.color,
                            Vec3(0.0, 0.0, 0.0),
                            Vec3(seg.x1 - seg.x0, seg.y1 - seg.y0, seg.z1 - seg.z0),
                        )
                    }
                }
                // 端点小发光
                runCatching {
                    withPositionRelativeToCamera(McVec3(seg.x0, seg.y0, seg.z0)) {
                        val s = 0.02
                        drawBox(Box(-s, -s * 0.25, -s, s, s * 0.25, s), seg.color)
                    }
                }
            }
        }
    }
}
