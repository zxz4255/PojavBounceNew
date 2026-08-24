/*
 * ModuleOpalTargetInfo — Opal TargetInfo 布局复刻（无 GuiGraphics 依赖）
 * LiquidBounce 0.39 Overlay API only
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

    private fun lerpColor(a: Color4b, b: Color4b, t: Float, alpha: Int): Color4b {
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

        val cross = mc.crosshairPickEntity
        if (cross is LivingEntity && cross !== self && cross.isAlive) {
            if ((!onlyPlayers || cross is Player) && self.distanceTo(cross) <= range) {
                return cross
            }
        }

        var best: LivingEntity? = null
        var bestScore = range.toDouble()
        runCatching {
            for (e in world.entitiesForRendering()) {
                if (e !is LivingEntity || e === self || !e.isAlive) continue
                if (onlyPlayers && e !is Player) continue
                val d = self.distanceTo(e).toDouble()
                if (d > range) continue
                val score = d - if (e.hurtTime > 0) 2.0 else 0.0
                if (score < bestScore) {
                    bestScore = score
                    best = e
                }
            }
        }
        return best
    }

    private fun healthPercent(e: LivingEntity): Float {
        val abs = if (showAbsorption) e.absorptionAmount else 0f
        val maxH = (e.maxHealth + abs).coerceAtLeast(1f)
        return Mth.clamp((e.health + abs) / maxH, 0f, 1f)
    }

    private fun equipmentList(e: LivingEntity): List<ItemStack> {
        val list = ArrayList<ItemStack>()
        if (showArmor) {
            runCatching {
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)
                list += e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
            }
        }
        if (showHand) list += e.mainHandItem
        return list
    }

    /** 装备格颜色：按物品名哈希，避免 GuiGraphics.renderItem */
    private fun itemColor(stack: ItemStack, a: Int): Color4b {
        if (stack.isEmpty) return Color4b(40, 40, 40, a / 2)
        val h = stack.item.toString().hashCode()
        return Color4b(
            80 + (h and 0x3F),
            80 + ((h shr 6) and 0x3F),
            80 + ((h shr 12) and 0x3F),
            a,
        )
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val frame = dt()
        val target = findTarget()

        displayAlpha = smooth(displayAlpha, if (target != null) 1f else 0f, animSpeed, frame)
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

        val name = runCatching {
            entity.displayName?.string ?: entity.name.string
        }.getOrDefault("Target")
        val nameW = font.width(name).toFloat()

        val width = (padding * 2) + max(50f * s, max(equipmentWidth, nameW)) + headOffset + 1f * s
        val height = (padding * 2) + 25.5f * s
        val x = (sw - width) * posX
        val y = (sh - height) * posY
        val a = (displayAlpha * 255).roundToInt().coerceIn(0, 255)
        val rr = 4f * s

        // —— 毛玻璃底 ——
        if (glass) {
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
            val baseA = (bgColor.a * displayAlpha).roundToInt().coerceIn(0, 255)
            ctx.drawRoundedRect(x, y, x + width, y + height, rr, Color4b(bgColor.r, bgColor.g, bgColor.b, baseA))
            val tintA = (glassTint.a * displayAlpha).roundToInt().coerceIn(0, 80)
            ctx.drawRoundedRect(x, y, x + width, y + height, rr, Color4b(glassTint.r, glassTint.g, glassTint.b, tintA))
            val hiA = (glassBorder.a * displayAlpha * 0.9f).roundToInt().coerceIn(0, 70)
            ctx.drawRoundedRect(
                x + 1f * s, y + 1f * s, x + width - 1f * s, y + 3.2f * s, 2f * s,
                Color4b(glassBorder.r, glassBorder.g, glassBorder.b, hiA),
            )
        } else {
            ctx.drawRoundedRect(
                x, y, x + width, y + height, rr,
                Color4b(bgColor.r, bgColor.g, bgColor.b, (bgColor.a * displayAlpha).roundToInt().coerceIn(0, 255)),
            )
        }

        // 头像占位（圆角 + 主题色块 / 名字首字母）
        val headX = x + padding
        val headY = y + padding
        ctx.drawRoundedRect(headX, headY, headX + headSize, headY + headSize, 2f * s, Color4b(30, 30, 35, a))
        val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val iw = font.width(initial)
        ctx.text(
            font,
            initial,
            (headX + headSize / 2f - iw / 2f).roundToInt(),
            (headY + headSize / 2f - 4f * s).roundToInt(),
            Color4b(themeA.r, themeA.g, themeA.b, a).argb,
            true,
        )
        if (hurtTint && entity.hurtTime > 0) {
            val factor = entity.hurtTime / max(entity.hurtDuration, 1).toFloat()
            val redA = (a * 0.35f * factor).roundToInt().coerceIn(0, 120)
            ctx.drawRoundedRect(headX, headY, headX + headSize, headY + headSize, 2f * s, Color4b(255, 40, 40, redA))
        }

        // 名字
        ctx.text(
            font,
            name,
            (x + padding + headOffset).roundToInt(),
            (y + 6f * s).roundToInt(),
            Color4b(nameColor.r, nameColor.g, nameColor.b, a).argb,
            true,
        )

        // 装备格（色块 + 首字母，不调用 renderItem）
        val items = equipmentList(entity)
        for (i in items.indices) {
            val boxX = x + (i * 11.5f * s) + padding + headOffset - 0.5f * s
            val boxY = y + padding + 8.5f * s
            val stack = items[i]
            ctx.drawRoundedRect(
                boxX, boxY, boxX + 10.5f * s, boxY + 10.5f * s, 1f * s,
                Color4b(0, 0, 0, (a * 0.35f).roundToInt()),
            )
            if (!stack.isEmpty) {
                ctx.drawRoundedRect(
                    boxX + 1.5f * s, boxY + 1.5f * s,
                    boxX + 9f * s, boxY + 9f * s, 1f * s,
                    itemColor(stack, (a * 0.9f).roundToInt()),
                )
            }
        }

        // HP 文字
        val hpVal = entity.health + if (showAbsorption) entity.absorptionAmount else 0f
        val hpStr = if (hpVal >= 10f) "%.0f".format(hpVal) else "%.1f".format(hpVal)
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
        ctx.text(
            font,
            "+",
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

        ctx.drawRoundedRect(
            barX, barY, barX + barW, barY + barH, (5f / 3f) * s,
            Color4b(darker(themeB, 0.8f).r, darker(themeB, 0.8f).g, darker(themeB, 0.8f).b, (a * 0.6f).roundToInt()),
        )

        if (healthAnim > 0.01f) {
            val wAnim = healthAnim * barW
            val segs = 10
            for (i in 0 until segs) {
                val t0 = i / segs.toFloat()
                val t1 = (i + 1) / segs.toFloat()
                val x0 = barX + wAnim * t0
                val x1 = barX + wAnim * t1
                if (x1 <= x0) continue
                ctx.drawQuad(
                    x0, barY, x1, barY + barH,
                    lerpColor(darker(themeA, 0.6f), darker(themeB, 0.6f), (t0 + t1) * 0.5f, (a * 0.85f).roundToInt()),
                )
            }
        }
        if (trueHp > 0.01f) {
            val wTrue = trueHp * barW
            val segs = 12
            for (i in 0 until segs) {
                val t0 = i / segs.toFloat()
                val t1 = (i + 1) / segs.toFloat()
                val x0 = barX + wTrue * t0
                val x1 = barX + wTrue * t1
                if (x1 <= x0) continue
                ctx.drawQuad(x0, barY, x1, barY + barH, lerpColor(themeA, themeB, (t0 + t1) * 0.5f, a))
            }
        }
    }
}
