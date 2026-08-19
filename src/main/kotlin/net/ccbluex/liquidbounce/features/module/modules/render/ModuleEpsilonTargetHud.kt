/*
 * ModuleEpsilonTargetHud —— 还原 Epsilon TargetHUD.java
 * 原生 OverlayRender + GuiGraphicsExtractor，无 Web
 * 血条延迟动画 / 受伤头像缩放 / 玩家皮肤 UV / 装备栏
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleEpsilonTargetHud : ClientModule(
    "EpsilonTargetHud",
    ModuleCategories.RENDER,
    aliases = listOf("EpsilonTargetHUD", "TargetHUD"),
) {

    /* ============================= 可调节 ============================= */

    private val posX by float("Position X", 40f, 0f..2000f)
    private val posY by float("Position Y", 40f, 0f..1200f)
    private val scale by float("Scale", 0.9f, 0.5f..2.0f)
    private val width by float("Width", 150f, 100f..300f)
    private val height by float("Height", 52f, 30f..100f)
    private val radius by float("Radius", 5f, 0f..20f)
    private val blurStrength by float("Blur Strength", 5f, 0f..20f)
    private val healthBarHeight by float("Bar Height", 3f, 2f..20f)
    private val healthBarRadius by float("Bar Radius", 1.2f, 0f..15f)
    private val nameSize by float("Name Size", 10.5f, 8f..18f)

    private val delayBar by boolean("Delay Bar", true)
    private val delayWait by boolean("Delay Wait", true)
    private val delayTime by float("Delay Time", 250f, 0f..500f)
    private val delaySpeed by float("Delay Speed", 2f, 0.1f..10f)

    private val barOutline by boolean("Bar Outline", true)
    private val barOutlineWidth by float("Bar Outline Width", 1f, 0.5f..5f)

    private val backgroundColor by color("Background Color", Color4b(15, 15, 15, 145))
    private val barBackgroundColor by color("Bar Background Color", Color4b(255, 255, 255, 55))
    private val barFillColor by color("Bar Fill Color", Color4b(255, 236, 248, 235))
    private val delayBarColor by color("Delay Bar Color", Color4b(190, 190, 190, 100))
    private val barOutlineColor by color("Bar Outline Color", Color4b(255, 255, 255, 85))
    private val textColor by color("Text Color", Color4b(255, 255, 255, 235))

    private val drawShadow by boolean("Drop Shadow", true)
    private val shadowBlur by float("Shadow Blur", 8f, 0f..24f)
    private val shadowColor by color("Shadow Color", Color4b(0, 0, 0, 120))

    private val showEquipment by boolean("Show Equipment", true)
    private val showInChat by boolean("Show In Chat", true)

    /* ============================= 状态 ============================= */

    private const val VISIBILITY_MS = 300L
    private const val HEAD_DAMAGE_SCALE = 0.15f
    private const val EQUIP_SCALE = 0.85f

    private var lastTargetId = Int.MIN_VALUE
    private var displayedHealth = 0f
    private var delayedHealth = 0f
    private var lastKnownHealth = -1f
    private var lastKnownMaxHealth = 1f
    private var lastDamageTimeMs = 0L
    private var renderedTarget: LivingEntity? = null
    private var visibilityProgress = 0f
    private var lastVisibilityUpdateMs = 0L
    private var lastFrameNs = 0L

    /* ============================= 工具 ============================= */

    private fun easeOutSine(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return sin((x * Math.PI) / 2.0).toFloat()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun withAlpha(c: Color4b, scale: Float): Color4b {
        val a = (c.a * scale.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return Color4b(c.r, c.g, c.b, a)
    }

    private fun tintDamage(base: Color4b, damageProgress: Float): Color4b {
        val d = damageProgress.coerceIn(0f, 1f)
        val greenBlue = (255f - 155f * d).roundToInt().coerceIn(100, 255)
        val red = (base.r + (255 - base.r) * d).roundToInt().coerceIn(0, 255)
        val green = (base.g * greenBlue / 255f).roundToInt().coerceIn(0, 255)
        val blue = (base.b * greenBlue / 255f).roundToInt().coerceIn(0, 255)
        return Color4b(red, green, blue, base.a)
    }

    private fun isRenderable(t: LivingEntity?): Boolean =
        t != null && t.isAlive && !t.isDeadOrDying

    private fun resolveTarget(): LivingEntity? {
        val fromKa = try {
            KillAuraTargetTracker.target
        } catch (_: Throwable) {
            null
        }
        if (isRenderable(fromKa)) return fromKa
        // 聊天界面预览自身（对应原版 HudEditor）
        if (showInChat && mc.screen is ChatScreen) {
            return mc.player
        }
        return null
    }

    private fun updateRenderedTarget(live: LivingEntity?): LivingEntity? {
        val now = System.currentTimeMillis()
        if (lastVisibilityUpdateMs == 0L) lastVisibilityUpdateMs = now
        val delta = ((now - lastVisibilityUpdateMs) / VISIBILITY_MS.toFloat()).coerceIn(0f, 1f)
        lastVisibilityUpdateMs = now

        if (live != null) {
            renderedTarget = live
            visibilityProgress = min(1f, visibilityProgress + delta)
            return renderedTarget
        }
        if (renderedTarget == null) {
            visibilityProgress = 0f
            return null
        }
        visibilityProgress = max(0f, visibilityProgress - delta)
        if (visibilityProgress <= 0.01f) {
            renderedTarget = null
            resetAnimatedState()
            return null
        }
        return renderedTarget
    }

    private fun resetAnimatedState() {
        lastTargetId = Int.MIN_VALUE
        displayedHealth = 0f
        delayedHealth = 0f
        lastKnownHealth = -1f
        lastKnownMaxHealth = 1f
        lastDamageTimeMs = 0L
    }

    private fun updateAnimatedHealth(
        target: LivingEntity,
        currentHealth: Float,
        maxHealth: Float,
        frameTime: Float,
    ): Float {
        val id = target.id
        if (id != lastTargetId) {
            lastTargetId = id
            displayedHealth = currentHealth
            delayedHealth = currentHealth
            lastKnownHealth = currentHealth
            lastKnownMaxHealth = maxHealth
            lastDamageTimeMs = 0L
        } else {
            if (lastKnownHealth >= 0f && currentHealth < lastKnownHealth) {
                lastDamageTimeMs = System.currentTimeMillis()
            }
            val speed = (frameTime * 10f).coerceIn(0f, 1f)
            displayedHealth = lerp(displayedHealth, currentHealth, speed)
            delayedHealth = updateDelayedHealth(currentHealth, frameTime)
            lastKnownHealth = currentHealth
            lastKnownMaxHealth = maxHealth
        }
        displayedHealth = displayedHealth.coerceIn(0f, maxHealth)
        delayedHealth = delayedHealth.coerceIn(0f, maxHealth)
        return (displayedHealth / maxHealth).coerceIn(0f, 1f)
    }

    private fun updateDelayedHealth(currentHealth: Float, frameTime: Float): Float {
        if (!delayBar) return currentHealth
        if (currentHealth >= delayedHealth) return currentHealth
        if (delayWait && System.currentTimeMillis() - lastDamageTimeMs < delayTime.toLong()) {
            return delayedHealth
        }
        val speed = (frameTime * delaySpeed * 2f).coerceIn(0f, 1f)
        return lerp(delayedHealth, currentHealth, speed)
    }

    private fun playerSkin(player: AbstractClientPlayer): Identifier? {
        runCatching { player.skin.body().texturePath() }.getOrNull()?.let { return it }
        runCatching {
            val skin = player.skin
            for (m in skin.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val n = m.name.lowercase()
                if (n.contains("texture") || n == "body") {
                    val r = m.invoke(skin) ?: continue
                    if (r is Identifier) return r
                    val tp = r.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.name.lowercase().contains("texture")
                    }?.invoke(r)
                    if (tp is Identifier) return tp
                }
            }
            null
        }.getOrNull()?.let { return it }
        return Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")
    }

    /**
     * Epsilon 原 UV（u0,v0,u1,v1）：
     * 脸 8/64..16/64, 8/64..16/64；帽 40/64..48/64, 8/64..16/64
     * 本 fork blit 多为 (u,v,uw,vh) → 转为 8/64,8/64,8/64,8/64
     */
    private fun GuiGraphicsExtractor.drawPlayerHead(
        player: AbstractClientPlayer,
        x: Float, y: Float, size: Float,
        tint: Color4b,
    ) {
        val tex = playerSkin(player) ?: return
        val x0 = x.roundToInt()
        val y0 = y.roundToInt()
        val x1 = (x + size).roundToInt()
        val y1 = (y + size).roundToInt()
        if (x1 <= x0 || y1 <= y0) return
        // 脸
        runCatching {
            blit(tex, x0, y0, x1, y1, 8f / 64f, 8f / 64f, 8f / 64f, 8f / 64f)
        }
        // 帽子
        runCatching {
            blit(tex, x0, y0, x1, y1, 40f / 64f, 8f / 64f, 8f / 64f, 8f / 64f)
        }
        // 受伤红染
        if (tint.a > 4 && (tint.r > tint.g + 20 || tint.r > tint.b + 20)) {
            val overlay = Color4b(255, 40, 40, (tint.a * 0.35f).roundToInt().coerceIn(0, 120))
            drawQuad(x, y, x + size, y + size, overlay)
        }
    }

    private fun GuiGraphicsExtractor.drawSoftShadow(
        x: Float, y: Float, w: Float, h: Float, r: Float, blur: Float, col: Color4b, alphaScale: Float,
    ) {
        if (blur < 0.5f || alphaScale < 0.02f) return
        val layers = 5
        for (i in 1..layers) {
            val t = i / layers.toFloat()
            val expand = blur * t
            val a = (col.a * alphaScale * (1f - t) * (1f - t) * 0.55f).roundToInt().coerceIn(0, 60)
            if (a < 2) continue
            drawRoundedRect(
                x - expand, y - expand, x + w + expand, y + h + expand,
                r + expand * 0.2f,
                Color4b(col.r, col.g, col.b, a),
            )
        }
    }

    private fun GuiGraphicsExtractor.drawOutlineRect(
        x: Float, y: Float, w: Float, h: Float, r: Float, line: Float, col: Color4b,
    ) {
        if (line <= 0f || col.a < 2) return
        // 四边近似描边
        drawRoundedRect(x, y, x + w, y + line, min(r, line), col)
        drawRoundedRect(x, y + h - line, x + w, y + h, min(r, line), col)
        drawRoundedRect(x, y, x + line, y + h, min(r, line), col)
        drawRoundedRect(x + w - line, y, x + w, y + h, min(r, line), col)
    }

    private fun entityHealth(entity: LivingEntity): Float {
        return try {
            entity.health + entity.absorptionAmount.coerceAtLeast(0f)
        } catch (_: Throwable) {
            entity.health
        }
    }

    private fun entityMaxHealth(entity: LivingEntity): Float {
        return max(1f, entity.maxHealth + try {
            entity.absorptionAmount.coerceAtLeast(0f)
        } catch (_: Throwable) {
            0f
        })
    }

    private fun equipmentList(target: LivingEntity): List<ItemStack> {
        val list = ArrayList<ItemStack>(5)
        fun add(s: ItemStack) {
            if (!s.isEmpty) list += s
        }
        add(target.mainHandItem)
        add(target.getItemBySlot(EquipmentSlot.HEAD))
        add(target.getItemBySlot(EquipmentSlot.CHEST))
        add(target.getItemBySlot(EquipmentSlot.LEGS))
        add(target.getItemBySlot(EquipmentSlot.FEET))
        return list
    }

    private fun GuiGraphicsExtractor.drawEquipment(
        target: LivingEntity,
        startX: Float,
        y: Float,
        itemScale: Float,
        gap: Float,
    ) {
        val items = equipmentList(target)
        if (items.isEmpty()) return
        val itemSize = 16f * itemScale
        var ix = startX
        for ((i, stack) in items.withIndex()) {
            runCatching {
                // 1.21+ GuiGraphicsExtractor.item / renderItem
                val methods = javaClass.methods
                val rendered = methods.firstOrNull {
                    (it.name == "renderItem" || it.name == "item") && it.parameterCount in 3..6
                }?.let { m ->
                    when (m.parameterCount) {
                        3 -> m.invoke(this, stack, ix.roundToInt(), y.roundToInt())
                        4 -> m.invoke(this, stack, ix.roundToInt(), y.roundToInt(), target.id + i)
                        5 -> m.invoke(this, target, stack, ix.roundToInt(), y.roundToInt(), target.id + i)
                        else -> m.invoke(this, target, stack, ix.roundToInt(), y.roundToInt(), target.id + i, 0)
                    }
                    true
                } ?: false
                if (!rendered) {
                    // 占位小方块
                    drawRoundedRect(ix, y, ix + itemSize * 0.9f, y + itemSize * 0.9f, 2f, Color4b(40, 40, 45, 180))
                }
            }
            ix += itemSize + gap
        }
    }

    /* ============================= 渲染 ============================= */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val nowNs = System.nanoTime()
        val frameTime = if (lastFrameNs != 0L) {
            ((nowNs - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        } else 0.016f
        lastFrameNs = nowNs

        val live = resolveTarget()
        val target = updateRenderedTarget(live) ?: return@handler
        val anim = easeOutSine(visibilityProgress)
        if (anim <= 0.01f) return@handler

        val ctx = event.context
        val font = mc.font
        val panelScale = scale
        val panelW = width * panelScale
        val panelH = height * panelScale
        val baseX = posX
        val baseY = posY

        val maxHp = if (live == target) {
            val hp = entityHealth(target)
            val mh = entityMaxHealth(target)
            lastKnownMaxHealth = mh
            updateAnimatedHealth(target, hp, mh, frameTime)
            mh
        } else {
            displayedHealth = displayedHealth.coerceIn(0f, lastKnownMaxHealth)
            delayedHealth = delayedHealth.coerceIn(0f, lastKnownMaxHealth)
            lastKnownMaxHealth
        }
        val healthPercent = (displayedHealth / max(1f, maxHp)).coerceIn(0f, 1f)
        val delayPercent = (delayedHealth / max(1f, maxHp)).coerceIn(0f, 1f)

        val pad = 5f * panelScale
        val cornerR = radius * panelScale
        val barH = healthBarHeight * panelScale
        val barR = healthBarRadius * panelScale
        val barW = max(1f, panelW - pad * 2f)
        val delayedBarW = (barW * delayPercent).coerceIn(0f, barW)
        val filledBarW = (barW * healthPercent).coerceIn(0f, barW)

        val innerH = max(1f, panelH - pad * 2f)
        val contentAreaH = max(1f, innerH - pad - barH)
        val headSize = min(contentAreaH, max(26f * panelScale, panelH * 0.6f) * 1.05f)
        val textScale = max(0.45f, nameSize / 14f) * panelScale
        val textH = 9f * textScale
        val contentRowH = max(headSize, textH)
        val contentBlockH = contentRowH + pad + barH
        val contentStartY = baseY + pad + max(0f, (innerH - contentBlockH) / 2f)
        val headY = contentStartY + (contentRowH - headSize) / 2f
        val headX = baseX + pad
        val barY = contentStartY + contentRowH + pad
        val textStartX = headX + headSize + pad
        val contentY = headY + 2f * panelScale
        val healthText = String.format(Locale.ROOT, "%.1f", displayedHealth)
        val healthTextW = font.width(healthText) * textScale
        val healthTextX = baseX + panelW - pad - healthTextW
        val equipY = contentY + textH + 2.8f * panelScale
        val equipScale = EQUIP_SCALE * panelScale
        val equipGap = 1.5f * panelScale

        // 可见性缩放（中心插值）
        val centerX = baseX + panelW / 2f
        val centerY = baseY + panelH / 2f
        val sx = lerp(centerX, baseX, anim)
        val sy = lerp(centerY, baseY, anim)
        val sW = panelW * anim
        val sH = panelH * anim
        val sR = cornerR * anim
        val sBarH = barH * anim
        val sBarR = barR * anim
        val sOutline = barOutlineWidth * panelScale * anim
        val sTextScale = textScale * anim
        val sHeadR = headSize * 0.23f * anim
        val sPadX = lerp(centerX, baseX + pad, anim)
        val sBarY = lerp(centerY, barY, anim)
        val sBarW = barW * anim
        val sDelayedW = delayedBarW * anim
        val sFilledW = filledBarW * anim
        val sHeadX = lerp(centerX, headX, anim)
        val sHeadY = lerp(centerY, headY, anim)
        val sHeadSize = headSize * anim
        val sTextX = lerp(centerX, textStartX, anim)
        val sContentY = lerp(centerY, contentY, anim)
        val sHealthTextX = lerp(centerX, healthTextX, anim)
        val sEquipX = lerp(centerX, textStartX, anim)
        val sEquipY = lerp(centerY, equipY, anim)
        val sEquipScale = equipScale * anim
        val sEquipGap = equipGap * anim

        val hurtFrac = (target.hurtTime / 10f).coerceIn(0f, 1f)
        val damageProgress = easeOutSine(hurtFrac)
        val headDamageScale = 1f - damageProgress * HEAD_DAMAGE_SCALE
        val finalHeadSize = sHeadSize * headDamageScale
        val finalHeadX = sHeadX + (sHeadSize - finalHeadSize) / 2f
        val finalHeadY = sHeadY + (sHeadSize - finalHeadSize) / 2f
        val headTint = withAlpha(tintDamage(Color4b(255, 255, 255, 255), damageProgress), anim)

        // 模糊近似：多层半透明 + 阴影
        if (blurStrength > 0.5f) {
            val layers = min(6, (blurStrength / 2f).roundToInt().coerceAtLeast(2))
            for (i in 1..layers) {
                val t = i / layers.toFloat()
                val e = blurStrength * 0.35f * t
                val a = (18 * anim * (1f - t)).roundToInt().coerceIn(0, 40)
                ctx.drawRoundedRect(sx - e, sy - e, sx + sW + e, sy + sH + e, sR + e * 0.15f, Color4b(0, 0, 0, a))
            }
        }
        if (drawShadow) {
            ctx.drawSoftShadow(sx, sy, sW, sH, sR, shadowBlur * anim, shadowColor, anim)
        }

        // 背景
        ctx.drawRoundedRect(sx, sy, sx + sW, sy + sH, sR, withAlpha(backgroundColor, anim))

        // 血条底
        ctx.drawRoundedRect(sPadX, sBarY, sPadX + sBarW, sBarY + sBarH, sBarR, withAlpha(barBackgroundColor, anim))
        // 延迟血条
        if (delayBar && delayedHealth > displayedHealth + 0.05f) {
            ctx.drawRoundedRect(sPadX, sBarY, sPadX + sDelayedW, sBarY + sBarH, sBarR, withAlpha(delayBarColor, anim))
        }
        // 当前血条
        if (sFilledW > 0.5f) {
            ctx.drawRoundedRect(sPadX, sBarY, sPadX + sFilledW, sBarY + sBarH, sBarR, withAlpha(barFillColor, anim))
        }
        // 描边
        if (barOutline && sOutline > 0.2f) {
            ctx.drawOutlineRect(sPadX, sBarY, sBarW, sBarH, sBarR, sOutline, withAlpha(barOutlineColor, anim))
        }

        // 头像
        if (target is AbstractClientPlayer) {
            ctx.drawPlayerHead(target, finalHeadX, finalHeadY, finalHeadSize, headTint)
        } else {
            val gray = withAlpha(tintDamage(Color4b(80, 80, 80, 200), damageProgress), anim)
            ctx.drawRoundedRect(finalHeadX, finalHeadY, finalHeadX + finalHeadSize, finalHeadY + finalHeadSize, sHeadR * headDamageScale, gray)
        }

        // 文字
        val name = try {
            target.name.string
        } catch (_: Throwable) {
            target.displayName?.string ?: "?"
        }
        val tc = withAlpha(textColor, anim)
        // 简单字号：用矩阵缩放近似 nameSize
        val ty = sContentY.roundToInt()
        if (sTextScale > 0.55f) {
            ctx.text(font, name, sTextX.roundToInt(), ty, tc.argb, true)
            ctx.text(font, healthText, sHealthTextX.roundToInt(), ty, tc.argb, true)
        } else {
            // 偏小字号仍用默认字体
            ctx.text(font, name, sTextX.roundToInt(), ty, tc.argb, false)
            ctx.text(font, healthText, sHealthTextX.roundToInt(), ty, tc.argb, false)
        }

        // 装备
        if (showEquipment && sEquipScale > 0.2f) {
            ctx.drawEquipment(target, sEquipX, sEquipY, sEquipScale, sEquipGap)
        }
    }
}
