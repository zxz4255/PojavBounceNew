/*
 * ModuleOpalNameTag — 还原 Opal NameTag 样式
 * 头顶多段圆角标签：距离 | 名字 | 血量 | 吸收 |（可选装备提示）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ModuleOpalNameTag : ClientModule(
    "OpalNameTag",
    ModuleCategories.RENDER,
    aliases = listOf("OpalTags", "NameTagsOpal"),
) {

    private val scale by float("Scale", 1f, 0.5f..2.5f)
    private val distance by float("Distance", 64f, 8f..256f)
    private val showSelf by boolean("Show Self", false)
    private val showHealth by boolean("Show Health", true)
    private val showAbsorption by boolean("Show Absorption", true)
    private val showDistance by boolean("Show Distance", true)
    private val showArmor by boolean("Show Armor Durability", false)
    private val hideInvisible by boolean("Hide Invisible", false)
    private val throughWalls by boolean("Through Walls", true)

    private val bgColor by color("Background", Color4b(0, 0, 0, 120))
    private val distColor by color("Distance Color", Color4b(200, 200, 200, 255))
    private val nameColor by color("Name Color", Color4b(255, 255, 255, 255))
    private val healthColor by color("Health Color", Color4b(85, 255, 85, 255))
    private val absorbColor by color("Absorb Color", Color4b(255, 215, 0, 255))
    private val lowHealthColor by color("Low Health Color", Color4b(255, 85, 85, 255))
    private val radius by float("Corner Radius", 6f, 0f..12f)
    private val gap by float("Gap", 4f, 0f..12f)
    private val padding by float("Padding", 4f, 1f..12f)
    private val yOffset by float("Y Offset", 0.5f, 0f..1.5f)
    private val textShadow by boolean("Text Shadow", true)

    private data class Tag(val sx: Float, val sy: Float, val player: Player)

    private fun worldToScreen(x: Double, y: Double, z: Double, sw: Float, sh: Float): Pair<Float, Float>? {
        runCatching {
            val cls = Class.forName("net.ccbluex.liquidbounce.utils.render.WorldToScreen")
            val m = cls.methods.firstOrNull {
                it.name.contains("calculateScreenPos", true) && it.parameterCount in 1..2
            } ?: return@runCatching
            val r = if (m.parameterCount == 1) m.invoke(null, Vec3(x, y, z)) else m.invoke(null, Vec3(x, y, z), null)
            if (r != null) {
                val fx = r.javaClass.methods.firstOrNull { it.name == "x" || it.name == "getX" }
                val fy = r.javaClass.methods.firstOrNull { it.name == "y" || it.name == "getY" }
                val sx = (fx?.invoke(r) as? Number)?.toFloat()
                val sy = (fy?.invoke(r) as? Number)?.toFloat()
                if (sx != null && sy != null) return sx to sy
                val fxs = r.javaClass.fields.firstOrNull { it.name == "x" }
                val fys = r.javaClass.fields.firstOrNull { it.name == "y" }
                if (fxs != null && fys != null) return fxs.getFloat(r) to fys.getFloat(r)
            }
        }
        return runCatching {
            val p = mc.player ?: return@runCatching null
            var camX = p.x
            var camY = p.y + p.eyeHeight
            var camZ = p.z
            var yawDeg = p.yRot
            var pitchDeg = p.xRot
            runCatching {
                val gr = mc.gameRenderer
                val cam = gr.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.returnType.name.contains("Camera")
                }?.invoke(gr)
                    ?: gr.javaClass.declaredFields.firstOrNull { it.type.name.contains("Camera") }
                        ?.also { it.isAccessible = true }?.get(gr)
                if (cam != null) {
                    val pos = cam.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "position" || it.name == "getPosition")
                    }?.invoke(cam)
                    if (pos is Vec3) { camX = pos.x; camY = pos.y; camZ = pos.z }
                    yawDeg = (cam.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "yRot" || it.name == "getYRot")
                    }?.invoke(cam) as? Number)?.toFloat() ?: yawDeg
                    pitchDeg = (cam.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && (it.name == "xRot" || it.name == "getXRot")
                    }?.invoke(cam) as? Number)?.toFloat() ?: pitchDeg
                }
            }
            val relX = (x - camX).toFloat()
            val relY = (y - camY).toFloat()
            val relZ = (z - camZ).toFloat()
            val view = Matrix4f()
            view.rotationY(-Math.toRadians(yawDeg.toDouble()).toFloat())
            view.rotateX(Math.toRadians(pitchDeg.toDouble()).toFloat())
            val world = Vector4f(relX, relY, relZ, 1f)
            view.transform(world)
            if (world.z >= 0f) return@runCatching null
            val fov = try { mc.options.fov().get().toFloat() } catch (_: Throwable) { 70f }
            val proj = Matrix4f().perspective(Math.toRadians(fov.toDouble()).toFloat(), sw / max(1f, sh), 0.05f, 512f)
            val clip = Vector4f(world.x, world.y, world.z, 1f)
            proj.transform(clip)
            if (clip.w == 0f) return@runCatching null
            val ndcX = clip.x / clip.w
            val ndcY = clip.y / clip.w
            ((ndcX * 0.5f + 0.5f) * sw) to ((1f - (ndcY * 0.5f + 0.5f)) * sh)
        }.getOrNull()
    }

    private fun box(
        ctx: net.minecraft.client.gui.GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float, col: Color4b,
    ) {
        if (radius > 0.5f) ctx.drawRoundedRect(x, y, x + w, y + h, radius, col)
        else ctx.drawRoundedRect(x, y, x + w, y + h, 0f, col)
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val self = mc.player ?: return@handler
        val world = mc.level ?: return@handler
        val ctx = event.context
        val font = mc.font
        val sw = try { ctx.guiWidth().toFloat() } catch (_: Throwable) { mc.window.guiScaledWidth.toFloat() }
        val sh = try { ctx.guiHeight().toFloat() } catch (_: Throwable) { mc.window.guiScaledHeight.toFloat() }
        val td = try { mc.deltaTracker.getGameTimeDeltaPartialTick(true) } catch (_: Throwable) { 1f }
        val rangeSq = distance * distance
        val tags = ArrayList<Tag>()

        runCatching {
            for (e in world.players()) {
                if (e !is Player || !e.isAlive) continue
                if (e === self && !showSelf) continue
                if (hideInvisible && e.isInvisible) continue
                if (self.distanceToSqr(e) > rangeSq) continue
                if (e.name.string.startsWith("CIT-")) continue
                val x = Mth.lerp(td, e.xo.toFloat(), e.x.toFloat()).toDouble()
                val y = Mth.lerp(td, e.yo.toFloat(), e.y.toFloat()).toDouble() + e.bbHeight + yOffset
                val z = Mth.lerp(td, e.zo.toFloat(), e.z.toFloat()).toDouble()
                val sp = worldToScreen(x, y, z, sw, sh) ?: continue
                tags += Tag(sp.first, sp.second - 2f, e)
            }
        }

        val pad = padding
        val g = gap
        val s = scale

        for (tag in tags) {
            val p = tag.player
            val name = p.displayName?.string ?: p.name.string
            val distM = hypot(hypot(self.x - p.x, self.z - p.z), self.y - p.y)
            val distText = String.format("%.1fm", distM)
            val maxHp = max(1f, p.maxHealth)
            val hp = p.health.coerceIn(0f, maxHp)
            val abs = try { p.absorptionAmount.coerceAtLeast(0f) } catch (_: Throwable) { 0f }
            val hpText = String.format("%.1f", hp)
            val absText = if (abs > 0.05f) String.format("%.1f", abs) else ""
            val hpCol = if (hp / maxHp < 0.35f) lowHealthColor else healthColor

            val segments = ArrayList<Pair<String, Color4b>>()
            if (showDistance) segments += distText to distColor
            segments += name to nameColor
            if (showHealth) segments += hpText to hpCol
            if (showAbsorption && absText.isNotEmpty()) segments += absText to absorbColor
            if (showArmor && p is AbstractClientPlayer) {
                var dmg = 0
                for (slot in 0..3) {
                    val stack = try { p.inventory.getArmor(slot) } catch (_: Throwable) {
                        try { p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.entries[slot + 2]) } catch (_: Throwable) { null }
                    }
                    if (stack != null && !stack.isEmpty && stack.isDamageableItem) {
                        dmg += stack.maxDamage - stack.damageValue
                    }
                }
                if (dmg > 0) segments += "${dmg}a" to Color4b(85, 255, 255, 255)
            }

            val heights = 12f
            val widths = segments.map { font.width(it.first) + pad * 2f }
            val fullW = widths.sum() + g * (segments.size - 1).coerceAtLeast(0)
            val originX = -fullW / 2f
            val originY = -heights

            // 手动缩放：坐标乘 scale
            var cursor = originX
            for (i in segments.indices) {
                val (text, col) = segments[i]
                val bw = widths[i]
                val bx = tag.sx + cursor * s
                val by = tag.sy + originY * s
                box(ctx, bx, by, bw * s, heights * s, bgColor)
                ctx.text(
                    font, text,
                    (bx + pad * s).roundToInt(),
                    (by + (heights * s - 9f) / 2f).roundToInt(),
                    col.argb, textShadow,
                )
                cursor += bw + g
            }
        }
    }
}
