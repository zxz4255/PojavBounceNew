/*
 * ModuleOpalTargetInfo — 复刻 Opal TargetInfoElement / TargetInfoSettings
 * LiquidBounce Nextgen 0.39 · Overlay 原生渲染（无 NVG/Web）
 *
 * 布局：圆角底 + 头像 + 名字 + 血条(主题渐变/动画) + HP 数字 + 装备栏
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

object ModuleOpalTargetInfo : ClientModule(
    "OpalTargetInfo",
    ModuleCategories.RENDER,
    aliases = listOf("TargetInfo", "OpalHUD"),
) {

    private val scale by float("Scale", 1f, 0.5f..2f)
    private val posX by float("X", 0.5f, 0f..1f)
    private val posY by float("Y", 0.65f, 0f..1f)
    private val range by float("Range", 12f, 3f..64f)
    private val onlyPlayers by boolean("Only Players", false)
    private val showArmor by boolean("Show Armor", true)
    private val showHand by boolean("Show Hand Item", true)
    private val showAbsorption by boolean("Show Absorption", true)
    private val animSpeed by float("Anim Speed", 12f, 4f..30f)
    private val healthAnimSpeed by float("Health Anim Speed", 8f, 2f..20f)

    private val bgColor by color("Background", Color4b(9, 9, 9, 128))
    private val glass by boolean("Glass Effect", true)
    private val glassLayers by int("Glass Layers", 4, 1..8)
    private val glassTint by color("Glass Tint", Color4b(255, 255, 255, 28))
    private val glassBorder by color("Glass Border", Color4b(255, 255, 255, 40))
    private val glassBlurFake by boolean("Glass Soft Edge", true)
    private val themeA by color("Theme A", Color4b(110, 200, 241, 255))
    private val themeB by color("Theme B", Color4b(233, 168, 188, 255))
    private val nameColor by color("Name Color", Color4b(255, 255, 255, 255))
    private val hpTextColor by color("HP Text", Color4b(255, 255, 255, 255))
    private val hurtTint by boolean("Hurt Tint", true)

    private var displayAlpha = 0f
    private var healthAnim = 0f
    private var lastTargetId = -1
    private var lastNs = 0L

    private fun dt(): Float {
        val now = System.nanoTime()
        val t = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now
        return t
    }

    private fun smooth(cur: Float, target: Float, speed: Float, frame: Float): Float =
        cur + (target - cur) * (1f - exp(-speed * frame))

    private fun lerpColor(a: Color4b, b: Color4b, t: Float, alpha: Int = 255): Color4b {
        val u = t.coerceIn(0f, 1f)
        return Color4b(
            (a.r + (b.r - a.r) * u).roundToInt().coerceIn(0, 255),
            (a.g + (b.g - a.g) * u).roundToInt().coerceIn(0, 255),
            (a.b + (b.b - a.b) * u).roundToInt().coerceIn(0, 255),
            alpha.coerceIn(0, 255),
        )
    }

    private fun darker(c: Color4b, f: Float): Color4b =
        Color4b(
            (c.r * f).roundToInt().coerceIn(0, 255),
            (c.g * f).roundToInt().coerceIn(0, 255),
            (c.b * f).roundToInt().coerceIn(0, 255),
            c.a,
        )

    private fun findTarget(): LivingEntity? {
        val self = mc.player ?: return null
        val world = mc.level ?: return null

        // 受伤目标优先
        val cross = mc.crosshairPickEntity
        if (cross is LivingEntity && cross !== self && cross.isAlive) {
            if (!onlyPlayers || cross is Player) {
                if (self.distanceTo(cross) <= range) return cross
            }
        }

        var best: LivingEntity? = null
        var bestD = range.toDouble()
        runCatching {
            for (e in world.entitiesForRendering()) {
                if (e !is LivingEntity || e === self || !e.isAlive) continue
                if (onlyPlayers && e !is Player) continue
                val d = self.distanceTo(e).toDouble()
                if (d > bestD) continue
                // 最近受击或最近实体
                val score = d - if (e.hurtTime > 0) 2.0 else 0.0
                if (score < bestD) {
                    bestD = score
                    best = e
                }
            }
        }
        return best
    }

    private fun healthPercent(e: LivingEntity): Float {
        val max = (e.maxHealth + if (showAbsorption) e.absorptionAmount else 0f).coerceAtLeast(1f)
        val cur = e.health + if (showAbsorption) e.absorptionAmount else 0f
        return Mth.clamp(cur / max, 0f, 1f)
    }

    private fun equipmentList(e: LivingEntity): List<ItemStack> {
        val list = ArrayList<ItemStack>()
        if (showArmor) {
            // 脚→头 再反转为头→脚显示（对齐 Opal reverse）
            runCatching {
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
            }
        }
        if (showHand) {
            list += e.mainHandItem
        }
        return list
    }

    private fun drawFace(
        graphics: GuiGraphics,
        entity: LivingEntity,
        x: Int,
        y: Int,
        size: Int,
        alpha: Float,
    ) {
        if (entity !is Player) {
            // 非玩家：色块占位
            return
        }
        runCatching {
            // 优先 PlayerFaceRenderer（若存在）
            val clazz = Class.forName("net.minecraft.client.gui.components.PlayerFaceRenderer")
            val skin = runCatching {
                entity.javaClass.methods.firstOrNull {
                    it.name == "getSkin" || it.name == "getSkinTextures" || it.parameterCount == 0 && it.name.contains("Skin")
                }?.invoke(entity)
            }.getOrNull()
            if (skin != null) {
                val m = clazz.methods.firstOrNull {
                    it.name == "draw" && it.parameterCount >= 5
                }
                if (m != null) {
                    when (m.parameterCount) {
                        5 -> m.invoke(null, graphics, skin, x, y, size)
                        6 -> m.invoke(null, graphics, skin, x, y, size, true)
                        else -> m.invoke(null, graphics, skin, x, y, size)
                    }
                    return
                }
            }
        }
        // 回退：用 entity render 略过，画深色圆角占位
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val self = mc.player ?: return@handler
        val frame = dt()
        val target = findTarget()

        val wantShow = target != null
        displayAlpha = smooth(displayAlpha, if (wantShow) 1f else 0f, animSpeed, frame)
        if (displayAlpha < 0.02f) {
            if (target == null) lastTargetId = -1
            return@handler
        }

        val entity = target ?: return@handler
        if (entity.id != lastTargetId) {
            lastTargetId = entity.id
            healthAnim = healthPercent(entity)
        }

        val trueHp = healthPercent(entity)
        healthAnim = smooth(healthAnim, trueHp, healthAnimSpeed, frame)

        val ctx = event.context
        val font = mc.font
        val sw = try {
            ctx.guiWidth().toFloat()
        } catch (_: Throwable) {
            mc.window.guiScaledWidth.toFloat()
        }
        val sh = try {
            ctx.guiHeight().toFloat()
        } catch (_: Throwable) {
            mc.window.guiScaledHeight.toFloat()
        }

        val s = scale
        val padding = 3f * s
        val headSize = 19.5f * s
        val headOffset = 22.5f * s
        val equipmentWidth = if (showArmor || showHand) 55f * s else 0f

        val name = runCatching { entity.displayName?.string ?: entity.name.string }
            .getOrDefault(entity.name.string)
        val nameW = font.width(name).toFloat()

        val width = (padding * 2) + max(50f * s, max(equipmentWidth, nameW)) + headOffset + 1f * s
        val height = (padding * 2) + 25.5f * s

        val x = (sw - width) * posX
        val y = (sh - height) * posY
        val a = (displayAlpha * 255).roundToInt().coerceIn(0, 255)

        val rr = 4f * s
        // Opal: roundedRect BLUR_PAINT + 0x80090909  → 原生用多层半透明模拟毛玻璃
        if (glass) {
            // 外层柔边（假模糊扩散）
            if (glassBlurFake) {
                for (i in glassLayers downTo 1) {
                    val e = i * 1.15f * s
                    val la = ((10 + i * 4) * displayAlpha).roundToInt().coerceIn(0, 50)
                    ctx.drawRoundedRect(
                        x - e, y - e, x + width + e, y + height + e,
                        rr + e * 0.35f,
                        Color4b(bgColor.r, bgColor.g, bgColor.b, la),
                    )
                }
            }
            // 主玻璃底
            val baseA = (bgColor.a * displayAlpha).roundToInt().coerceIn(0, 255)
            ctx.drawRoundedRect(x, y, x + width, y + height, rr, Color4b(bgColor.r, bgColor.g, bgColor.b, baseA))
            // 亮色罩（玻璃感）
            val tintA = (glassTint.a * displayAlpha).roundToInt().coerceIn(0, 80)
            ctx.drawRoundedRect(
                x, y, x + width, y + height, rr,
                Color4b(glassTint.r, glassTint.g, glassTint.b, tintA),
            )
            // 顶缘高光条
            val hiA = (glassBorder.a * displayAlpha * 0.9f).roundToInt().coerceIn(0, 70)
            ctx.drawRoundedRect(
                x + 1f * s, y + 1f * s, x + width - 1f * s, y + 3.2f * s, 2f * s,
                Color4b(glassBorder.r, glassBorder.g, glassBorder.b, hiA),
            )
            // 细边框
            val borderA = (glassBorder.a * displayAlpha).roundToInt().coerceIn(0, 90)
            // 顶
            ctx.drawQuad(x + rr, y, x + width - rr, y + 1f * s, Color4b(255, 255, 255, borderA / 2))
            // 底
            ctx.drawQuad(x + rr, y + height - 1f * s, x + width - rr, y + height, Color4b(0, 0, 0, borderA / 3))
        } else {
            val bg = Color4b(bgColor.r, bgColor.g, bgColor.b, (bgColor.a * displayAlpha).roundToInt().coerceIn(0, 255))
            ctx.drawRoundedRect(x, y, x + width, y + height, rr, bg)
        }

        // 头像区域
        val headX = x + padding
        val headY = y + padding
        ctx.drawRoundedRect(headX, headY, headX + headSize, headY + headSize, 2f * s, Color4b(30, 30, 30, a))

        val graphics = runCatching {
            ctx.javaClass.getDeclaredField("original").also { it.isAccessible = true }.get(ctx) as? GuiGraphics
                ?: ctx.javaClass.methods.firstOrNull { it.name == "getOriginal" || it.name == "guiGraphics" }
                    ?.invoke(ctx) as? GuiGraphics
        }.getOrNull()

        if (graphics != null && entity is Player) {
            val hx = headX.roundToInt()
            val hy = headY.roundToInt()
            val hs = headSize.roundToInt().coerceAtLeast(8)
            drawFace(graphics, entity, hx, hy, hs, displayAlpha)
        }

        // 受伤染色叠层
        if (hurtTint && entity.hurtTime > 0) {
            val factor = entity.hurtTime / entity.hurtDuration.toFloat().coerceAtLeast(1f)
            val redA = (a * 0.35f * factor).roundToInt().coerceIn(0, 120)
            ctx.drawRoundedRect(headX, headY, headX + headSize, headY + headSize, 2f * s, Color4b(255, 40, 40, redA))
        }

        // 名字
        val nameX = (x + padding + headOffset).roundToInt()
        val nameY = (y + 6f * s).roundToInt()
        ctx.text(
            font,
            name,
            nameX,
            nameY,
            Color4b(nameColor.r, nameColor.g, nameColor.b, a).argb,
            true,
        )

        // 装备小格
        val items = equipmentList(entity)
        if (items.isNotEmpty()) {
            for (i in items.indices) {
                val boxX = x + (i * 11.5f * s) + padding + headOffset - 0.5f * s
                val boxY = y + padding + 8.5f * s
                ctx.drawRoundedRect(
                    boxX, boxY, boxX + 10.5f * s, boxY + 10.5f * s, 1f * s,
                    Color4b(0, 0, 0, (a * 0.35f).roundToInt()),
                )
                if (graphics != null && !items[i].isEmpty) {
                    val ix = boxX.roundToInt()
                    val iy = boxY.roundToInt()
                    runCatching {
                        graphics.pose().pushMatrix()
                        // 简化：直接 drawItem
                        graphics.renderItem(items[i], ix, iy)
                        graphics.pose().popMatrix()
                    }.onFailure {
                        runCatching { graphics.renderItem(items[i], ix, iy) }
                    }
                }
            }
        }

        // 血量文字
        val hpVal = entity.health + if (showAbsorption) entity.absorptionAmount else 0f
        val hpStr = if (hpVal >= 10) "%.0f".format(hpVal) else "%.1f".format(hpVal)
        val hpW = font.width(hpStr).toFloat()
        val heartReserve = 8f * s
        ctx.text(
            font,
            hpStr,
            (x + width - padding - hpW - heartReserve).roundToInt(),
            (y + height - padding - 10f * s).roundToInt(),
            Color4b(hpTextColor.r, hpTextColor.g, hpTextColor.b, a).argb,
            true,
        )
        // 心形用字符近似
        ctx.text(
            font,
            "H",
            (x + width - padding - heartReserve + 1f * s).roundToInt(),
            (y + height - padding - 10f * s).roundToInt(),
            Color4b(255, 194, 71, a).argb,
            false,
        )

        // 血条
        val barX = x + padding
        val barY = y + height - padding - 5.5f * s
        val barH = 4f * s
        val barW = width - (padding * 2.75f) - max(hpW, font.width("88.").toFloat()) - heartReserve

        // 背景
        val barBg = darker(themeB, 0.8f).let {
            Color4b(it.r, it.g, it.b, (a * 0.6f).roundToInt())
        }
        ctx.drawRoundedRect(barX, barY, barX + barW, barY + barH, (5f / 3f) * s, barBg)

        // 动画血量（较暗渐变）
        if (healthAnim > 0.01f) {
            val segs = 10
            val wAnim = healthAnim * barW
            for (i in 0 until segs) {
                val t0 = i / segs.toFloat()
                val t1 = (i + 1) / segs.toFloat()
                val x0 = barX + wAnim * t0
                val x1 = barX + wAnim * t1
                if (x1 <= x0) continue
                val col = lerpColor(darker(themeA, 0.6f), darker(themeB, 0.6f), (t0 + t1) * 0.5f, (a * 0.85f).roundToInt())
                ctx.drawQuad(x0, barY, x1, barY + barH, col)
            }
        }

        // 真实血量（亮渐变）
        if (trueHp > 0.01f) {
            val segs = 12
            val wTrue = trueHp * barW
            for (i in 0 until segs) {
                val t0 = i / segs.toFloat()
                val t1 = (i + 1) / segs.toFloat()
                val x0 = barX + wTrue * t0
                val x1 = barX + wTrue * t1
                if (x1 <= x0) continue
                val col = lerpColor(themeA, themeB, (t0 + t1) * 0.5f, a)
                ctx.drawQuad(x0, barY, x1, barY + barH, col)
            }
        }
    }
}
