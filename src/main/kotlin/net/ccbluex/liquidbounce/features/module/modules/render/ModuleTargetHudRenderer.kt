package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.GameRenderer
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.lwjgl.opengl.GL11
import java.util.*
import kotlin.math.*

object ModuleTargetHudRenderer : ClientModule(
    "TargetHUD",
    ModuleCategories.RENDER,
    aliases = listOf("TargetHud", "THUD"),
) {

    /* ============================= 可调节参数 ============================= */

    // 布局
    private val hudLayout by choice("Layout", arrayOf("New", "Old"), "New")
    private val hudTheme by choice("Theme", arrayOf("Dark", "Light"), "Dark")
    private val targetHudScale by float("Scale", 1.0f, 0.5f..2.0f)
    private val posX by float("Pos X", 100f, 0f..2000f)
    private val posY by float("Pos Y", 100f, 0f..1200f)
    private val attackReachDisplay by boolean("Attack Reach", true)
    private val blurMode by boolean("Blur Mode", false)

    // 动画
    private val healthAnimSpeed by float("Health Anim Speed", 8f, 1f..20f)
    private val fadeAnimSpeed by float("Fade Speed", 12f, 1f..30f)
    private val damageFlashDuration by int("Flash Duration", 500, 100..2000)
    private val healthTextAnimDuration by int("Text Anim Duration", 350, 50..1000)

    // 颜色 (Dark主题)
    private val darkCardColor by color("Dark Card", Color4b(0x11, 0x18, 0x27, 0xE6))
    private val darkTextPrimary by color("Dark Text", Color4b(0xF8, 0xFA, 0xFC, 0xFF))
    private val darkTextMuted by color("Dark Muted", Color4b(0xB8, 0xCB, 0xD5, 0xE1))
    private val darkAvatarBg by color("Dark Avatar BG", Color4b(0x2A, 0x33, 0x45, 0x33))

    // 颜色 (Light主题)
    private val lightCardColor by color("Light Card", Color4b(0xF7, 0xF8, 0xFA, 0xFC))
    private val lightTextPrimary by color("Light Text", Color4b(0x20, 0x20, 0x27, 0xFF))
    private val lightTextMuted by color("Light Muted", Color4b(0x5C, 0x58, 0x70, 0xAA))
    private val lightAvatarBg by color("Light Avatar BG", Color4b(0xFF, 0xFF, 0xFF, 0x66))

    // 血条颜色
    private val healthColorHigh by color("Health High", Color4b(0x22, 0xC5, 0x5E, 0xFF))
    private val healthColorMid by color("Health Mid", Color4b(0xFF, 0xC8, 0x57, 0xFF))
    private val healthColorLow by color("Health Low", Color4b(0xFF, 0x55, 0x55, 0xFF))
    private val absorptionColor by color("Absorption", Color4b(0xF5, 0xB8, 0x3D, 0xFF))

    /* ============================= 常量 ============================= */

    private const val NEW_HUD_WIDTH = 184f
    private const val NEW_HUD_HEIGHT = 66f
    private const val NEW_AVATAR_SIZE = 46f
    private const val NEW_AVATAR_RADIUS = 14f
    private const val NEW_OVERLAY_X = 60f
    private const val NEW_OVERLAY_Y = 10f
    private const val NEW_OVERLAY_WIDTH = 124f
    private const val NEW_OVERLAY_HEIGHT = 56f

    private const val OLD_HUD_WIDTH = 140f
    private const val OLD_HUD_HEIGHT = 50f
    private const val OLD_BAR_WIDTH = 118f
    private const val OLD_BAR_HEIGHT = 10f
    private const val OLD_AVATAR_SIZE = 38f

    private const val ATTACK_DISTANCE_DISPLAY_DURATION = 3000L

    /* ============================= 运行时状态 ============================= */

    private var target: LivingEntity? = null
    private var targetFade = 0f
    private var animatedHealthRatio = 1f
    private var animatedAbsorptionRatio = 0f
    private var lastObservedHealth = -1f
    private var lastDamageTime = 0L
    private var lastHealTime = 0L
    private var lastAttackDistance = -1f
    private var lastAttackDistanceTime = 0L

    // 文字动画
    private var currentHealthText = ""
    private var previousHealthText = ""
    private var healthTextAnimStart = 0L
    private var healthTextDirection = 0
    private var lastHealthTextValue = -1f

    private var currentDistText = ""
    private var previousDistText = ""
    private var distTextAnimStart = 0L
    private var distTextDirection = 0
    private var lastDistTextValue = -1f

    private var lastRenderTime = 0L
    private var lastRawName = ""
    private var lastTruncatedName = ""

    /* ============================= 工具方法 ============================= */

    private fun easeTo(current: Float, target: Float, speed: Float, dt: Float): Float {
        if (current < 0f) return target
        val t = (1f - (1f - 0.15f).pow(dt * speed)).coerceIn(0f, 1f)
        return current + (target - current) * t
    }

    private fun easeOutBack(t: Float): Float {
        val v = t.coerceIn(0f, 1f) - 1f
        return 1f + v * v * (1.55f * v + 0.55f)
    }

    private fun getHealthColor(ratio: Float): Color4b {
        return when {
            ratio > 0.5f -> {
                val t = (ratio - 0.5f) * 2f
                Color4b(
                    (255 * (1f - t)).roundToInt(),
                    255,
                    0,
                    255
                )
            }
            else -> {
                val t = ratio * 2f
                Color4b(
                    255,
                    (255 * t).roundToInt(),
                    0,
                    255
                )
            }
        }
    }

    private fun getThemeCardColor(): Color4b {
        return if (hudTheme == "Light") lightCardColor else darkCardColor
    }

    private fun getThemePrimaryText(): Color4b {
        return if (blurMode) {
            if (hudTheme == "Light") lightTextPrimary else darkTextPrimary
        } else {
            if (hudTheme == "Light") lightTextPrimary else darkTextPrimary
        }
    }

    private fun getThemeMutedText(): Color4b {
        return if (hudTheme == "Light") lightTextMuted else darkTextMuted
    }

    private fun getThemeAvatarBg(): Color4b {
        return if (hudTheme == "Light") lightAvatarBg else darkAvatarBg
    }

    private fun getFlashFactor(now: Long, startTime: Long): Float {
        if (startTime <= 0L) return 0f
        val elapsed = now - startTime
        if (elapsed < 0L || elapsed >= damageFlashDuration) return 0f
        return 1f - elapsed.toFloat() / damageFlashDuration
    }

    private fun truncateName(rawName: String): String {
        if (rawName == lastRawName) return lastTruncatedName
        lastRawName = rawName

        val textRenderer = mc.textRenderer
        val maxWidth = 95f * targetHudScale

        if (textRenderer.getWidth(rawName) <= maxWidth) {
            lastTruncatedName = rawName
            return lastTruncatedName
        }

        var low = 1
        var high = rawName.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (textRenderer.getWidth(rawName.substring(0, mid) + "...") <= maxWidth) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        lastTruncatedName = rawName.substring(0, low) + "..."
        return lastTruncatedName
    }

    /* ============================= 目标追踪 ============================= */

    private fun updateTarget() {
        val player = mc.player ?: return
        val world = mc.world ?: return

        // 优先获取最后攻击的目标
        var newTarget: LivingEntity? = null

        // 从交叉准星获取目标
        val reach = 6.0
        val eyePos = player.eyePos
        val lookVec = player.rotationVector
        val endPos = eyePos.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach)

        val hitResult = world.raycast(
            net.minecraft.world.RaycastContext(
                eyePos, endPos,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                player
            )
        )

        if (hitResult != null && hitResult.type == net.minecraft.util.hit.HitResult.Type.ENTITY) {
            val entity = (hitResult as net.minecraft.util.hit.EntityHitResult).entity
            if (entity is LivingEntity && entity != player) {
                newTarget = entity
            }
        }

        // 如果没有准星目标，找最近的目标
        if (newTarget == null) {
            var closestDist = Double.MAX_VALUE
            for (entity in world.entities) {
                if (entity is LivingEntity && entity != player && entity.isAlive) {
                    val dist = entity.squaredDistanceTo(player)
                    if (dist < closestDist && dist < reach * reach) {
                        closestDist = dist
                        newTarget = entity
                    }
                }
            }
        }

        target = newTarget
    }

    private fun updateHealthTransition(currentHealth: Float, now: Long) {
        if (lastObservedHealth < 0f) {
            lastObservedHealth = currentHealth
            return
        }
        if (currentHealth > lastObservedHealth + 0.001f) {
            lastHealTime = now
        } else if (currentHealth < lastObservedHealth - 0.001f) {
            lastDamageTime = now
        }
        lastObservedHealth = currentHealth
    }

    private fun updateAttackDistance(now: Long) {
        if (!attackReachDisplay || mc.player == null || target == null || lastAttackDistanceTime <= 0L) return
        if (now - lastAttackDistanceTime >= ATTACK_DISTANCE_DISPLAY_DURATION) return

        val playerPos = mc.player!!.eyePos
        val targetPos = target!!.pos.add(0.0, target!!.height * 0.5, 0.0)
        lastAttackDistance = playerPos.distanceTo(targetPos).toFloat()
    }

    private fun updateHealthTextAnimation(value: Float, now: Long) {
        val text = String.format(Locale.ROOT, "%.1f HP", value)
        if (currentHealthText.isEmpty()) {
            currentHealthText = text
            previousHealthText = text
            lastHealthTextValue = value
            return
        }
        if (text != currentHealthText) {
            previousHealthText = currentHealthText
            healthTextDirection = if (value > lastHealthTextValue) 1 else -1
            healthTextAnimStart = now
            currentHealthText = text
            lastHealthTextValue = value
        }
    }

    private fun updateDistTextAnimation(distance: Float, now: Long) {
        val text = String.format(Locale.ROOT, "%.2fm", distance)
        if (currentDistText.isEmpty()) {
            currentDistText = text
            previousDistText = text
            lastDistTextValue = distance
            return
        }
        if (text != currentDistText) {
            previousDistText = currentDistText
            distTextDirection = if (distance > lastDistTextValue) 1 else -1
            distTextAnimStart = now
            currentDistText = text
            lastDistTextValue = distance
        }
    }

    /* ============================= 渲染 ============================= */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled) return@handler

        val now = System.currentTimeMillis()
        val dt = if (lastRenderTime != 0L) ((now - lastRenderTime) / 1000f).coerceIn(0.001f, 0.05f) else 0.016f
        lastRenderTime = now

        updateTarget()
        updateAttackDistance(now)

        val currentTarget = target

        // 淡入淡出动画
        val targetFadeGoal = if (currentTarget != null && currentTarget.isAlive) 1f else 0f
        targetFade = easeTo(targetFade, targetFadeGoal, fadeAnimSpeed, dt)

        if (targetFade < 0.01f) return@handler

        val context = event.context

        if (hudLayout == "New") {
            renderNewHud(context, currentTarget, now, dt)
        } else {
            renderOldHud(context, currentTarget, now, dt)
        }
    }

    private fun renderNewHud(context: GuiGraphicsExtractor, entity: LivingEntity?, now: Long, dt: Float) {
        if (entity == null) return

        val scale = targetHudScale
        val baseX = posX
        val baseY = posY

        val scaledWidth = (NEW_HUD_WIDTH * scale).toInt()
        val scaledHeight = (NEW_HUD_HEIGHT * scale).toInt()

        // 动画进入
        val animProgress = easeOutBack(targetFade)
        val drawX = baseX
        val drawY = baseY - (1f - animProgress) * scaledHeight * 0.3f

        context.matrices.push()
        context.matrices.translate(drawX, drawY, 0f)
        context.matrices.scale(scale, scale, 1f)

        // 背景卡片
        val cardColor = getThemeCardColor()
        drawRoundedRect(
            context,
            0f, 0f,
            NEW_HUD_WIDTH, NEW_HUD_HEIGHT,
            16f,
            cardColor
        )

        // 头像背景
        val avatarBg = getThemeAvatarBg()
        drawRoundedRect(
            context,
            12f, 10f,
            NEW_AVATAR_SIZE, NEW_AVATAR_SIZE,
            NEW_AVATAR_RADIUS,
            avatarBg
        )

        // 渲染头像
        if (entity is AbstractClientPlayerEntity) {
            renderPlayerAvatar(context, entity, 12f, 10f, NEW_AVATAR_SIZE.toInt())
        } else {
            // 非玩家实体显示默认图标
            drawRoundedRect(
                context,
                12f, 10f,
                NEW_AVATAR_SIZE, NEW_AVATAR_SIZE,
                NEW_AVATAR_RADIUS,
                Color4b(0x44, 0x55, 0x66, 0xFF)
            )
        }

        // 伤害/治疗闪烁效果
        val hurtFlash = getFlashFactor(now, lastDamageTime)
        val healFlash = getFlashFactor(now, lastHealTime)
        if (hurtFlash > 0f) {
            drawRoundedRect(
                context,
                12f, 10f,
                NEW_AVATAR_SIZE, NEW_AVATAR_SIZE,
                NEW_AVATAR_RADIUS,
                Color4b(0xFF, 0x00, 0x00, (hurtFlash * 0.6f * 255).toInt())
            )
        }
        if (healFlash > 0f) {
            drawRoundedRect(
                context,
                12f, 10f,
                NEW_AVATAR_SIZE, NEW_AVATAR_SIZE,
                NEW_AVATAR_RADIUS,
                Color4b(0x55, 0xFF, 0x55, (healFlash * 0.62f * 255).toInt())
            )
        }

        // 名称
        val name = truncateName(entity.name.string)
        val primaryColor = getThemePrimaryText()
        context.drawText(
            mc.textRenderer,
            name,
            60, 24,
            primaryColor.toARGB(),
            true
        )

        // 生命值计算
        val maxHealth = entity.maxHealth.coerceAtLeast(1f)
        val currentHealth = entity.health.coerceIn(0f, maxHealth)
        val healthRatio = currentHealth / maxHealth

        // 吸收值
        val absorption = entity.absorptionAmount
        val absorptionRatio = absorption / maxHealth

        // 更新动画
        updateHealthTransition(currentHealth, now)
        animatedHealthRatio = easeTo(animatedHealthRatio, healthRatio, healthAnimSpeed, dt)
        animatedAbsorptionRatio = easeTo(animatedAbsorptionRatio, absorptionRatio, healthAnimSpeed, dt)

        // 更新文字动画
        updateHealthTextAnimation(currentHealth, now)

        // 渲染动画生命值文字
        renderAnimatedHealthText(context, now)

        // 血条
        val barX = 60f
        val barY = 45f
        val barW = 112f
        val barH = 7f

        // 血条背景
        val trackColor = if (hudTheme == "Light")
            Color4b(0x11, 0x18, 0x27, 0x22)
        else
            Color4b(0xFF, 0xFF, 0xFF, 0x33)

        drawRoundedRect(
            context,
            barX, barY,
            barW, barH,
            barH * 0.5f,
            trackColor
        )

        // 血量填充
        val fillW = max(barH, barW * animatedHealthRatio.coerceIn(0f, 1f))
        val healthCol = getHealthColor(animatedHealthRatio.coerceIn(0f, 1f))

        drawRoundedRect(
            context,
            barX, barY,
            fillW, barH,
            barH * 0.5f,
            healthCol
        )

        // 吸收值
        if (animatedAbsorptionRatio > 0.01f) {
            val absorbW = min(barW, barW * animatedAbsorptionRatio.coerceIn(0f, 1f))
            drawRoundedRect(
                context,
                barX + barW - absorbW, barY,
                absorbW, barH,
                barH * 0.5f,
                absorptionColor
            )
        }

        // 攻击距离显示
        if (attackReachDisplay && lastAttackDistance >= 0f && now - lastAttackDistanceTime < ATTACK_DISTANCE_DISPLAY_DURATION) {
            updateDistTextAnimation(lastAttackDistance, now)
            renderAnimatedDistText(context, now)
        }

        context.matrices.pop()
    }

    private fun renderOldHud(context: GuiGraphicsExtractor, entity: LivingEntity?, now: Long, dt: Float) {
        if (entity == null) return

        val scale = targetHudScale
        val baseX = posX
        val baseY = posY

        val scaledWidth = (OLD_HUD_WIDTH * scale).toInt()
        val scaledHeight = (OLD_HUD_HEIGHT * scale).toInt()

        val animProgress = easeOutBack(targetFade)
        val drawX = baseX
        val drawY = baseY - (1f - animProgress) * scaledHeight * 0.3f

        context.matrices.push()
        context.matrices.translate(drawX, drawY, 0f)
        context.matrices.scale(scale, scale, 1f)

        // 背景
        val cardColor = getThemeCardColor()
        drawRoundedRect(
            context,
            0f, 0f,
            OLD_HUD_WIDTH, OLD_HUD_HEIGHT,
            12f,
            cardColor
        )

        // 头像
        if (entity is AbstractClientPlayerEntity) {
            renderPlayerAvatar(context, entity, 6f, 6f, OLD_AVATAR_SIZE.toInt())
        }

        // 名称
        val name = entity.name.string
        val primaryColor = getThemePrimaryText()
        context.drawText(
            mc.textRenderer,
            if (name.length > 12) name.substring(0, 12) + "..." else name,
            48, 6,
            primaryColor.toARGB(),
            true
        )

        // 血条
        val maxHealth = entity.maxHealth.coerceAtLeast(1f)
        val currentHealth = entity.health
        val healthRatio = (currentHealth / maxHealth).coerceIn(0f, 1f)
        animatedHealthRatio = easeTo(animatedHealthRatio, healthRatio, healthAnimSpeed, dt)

        val barX = 48f
        val barY = 22f
        val barW = OLD_BAR_WIDTH
        val barH = OLD_BAR_HEIGHT

        // 背景
        drawRoundedRect(
            context,
            barX, barY,
            barW, barH,
            barH * 0.5f,
            Color4b(0x00, 0x00, 0x00, 0x44)
        )

        // 填充
        val fillW = max(barH, barW * animatedHealthRatio)
        val healthCol = getHealthColor(animatedHealthRatio)

        drawRoundedRect(
            context,
            barX, barY,
            fillW, barH,
            barH * 0.5f,
            healthCol
        )

        // 血量文字
        val healthText = "${currentHealth.roundToInt()}/${maxHealth.roundToInt()}"
        context.drawText(
            mc.textRenderer,
            healthText,
            (barX + barW / 2 - mc.textRenderer.getWidth(healthText) / 2).toInt(),
            (barY + barH / 2 - 4).toInt(),
            0xFFFFFFFF.toInt(),
            true
        )

        // 距离
        val player = mc.player ?: return
        val dist = player.distanceTo(entity)
        val distText = String.format(Locale.ROOT, "%.1fm", dist)
        context.drawText(
            mc.textRenderer,
            distText,
            48, 36,
            getThemeMutedText().toARGB(),
            false
        )

        context.matrices.pop()
    }

    private fun renderPlayerAvatar(context: GuiGraphicsExtractor, player: AbstractClientPlayerEntity, x: Float, y: Float, size: Int) {
        // 使用 Minecraft 原生方式渲染玩家皮肤头像
        val skinTexture = player.skinTextures.texture()

        context.enableScissor(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())

        // 绘制皮肤
        context.drawTexture(
            skinTexture,
            x.toInt(), y.toInt(),
            size, size,
            8f, 8f,  // 面部区域
            8, 8,
            64, 64
        )

        // 绘制帽子层
        context.drawTexture(
            skinTexture,
            x.toInt(), y.toInt(),
            size, size,
            40f, 8f,
            8, 8,
            64, 64
        )

        context.disableScissor()
    }

    private fun renderAnimatedHealthText(context: GuiGraphicsExtractor, now: Long) {
        if (currentHealthText.isEmpty()) return

        val progress = if (healthTextAnimStart == 0L) 1f else
            ((now - healthTextAnimStart).toFloat() / healthTextAnimDuration).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)

        val baseY = 40f
        val height = 14f
        var x = 60f

        val textRenderer = mc.textRenderer
        val mutedColor = getThemeMutedText()

        // 简化的逐字符动画
        val displayText = currentHealthText
        val fullWidth = textRenderer.getWidth(displayText)

        // 使用裁剪实现滚动效果
        context.enableScissor(58, 28, 58 + 72, 28 + 16)

        for (i in displayText.indices) {
            val ch = displayText[i].toString()
            val oldCh = if (i < previousHealthText.length) previousHealthText[i].toString() else ch
            val digit = ch[0].isDigit()
            val changed = digit && progress < 1f && healthTextDirection != 0 && ch != oldCh

            val w = textRenderer.getWidth(ch).toFloat()

            if (changed) {
                val oldY = baseY + if (healthTextDirection > 0) -height * eased else height * eased
                val newY = baseY + if (healthTextDirection > 0) height * (1f - eased) else -height * (1f - eased)

                context.drawText(textRenderer, oldCh, x.toInt(), oldY.toInt(), mutedColor.toARGB(), false)
                context.drawText(textRenderer, ch, x.toInt(), newY.toInt(), mutedColor.toARGB(), false)
            } else {
                context.drawText(textRenderer, ch, x.toInt(), baseY.toInt(), mutedColor.toARGB(), false)
            }
            x += w
        }

        context.disableScissor()
    }

    private fun renderAnimatedDistText(context: GuiGraphicsExtractor, now: Long) {
        if (currentDistText.isEmpty()) return

        val progress = if (distTextAnimStart == 0L) 1f else
            ((now - distTextAnimStart).toFloat() / healthTextAnimDuration).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)

        val baseY = 40f
        val height = 14f
        val startX = 60f + mc.textRenderer.getWidth(currentHealthText) + 8f

        val textRenderer = mc.textRenderer
        val mutedColor = getThemeMutedText()

        context.enableScissor(startX.toInt() - 2, 28, (startX + 80).toInt(), 28 + 16)

        var x = startX
        for (i in currentDistText.indices) {
            val ch = currentDistText[i].toString()
            val oldCh = if (i < previousDistText.length) previousDistText[i].toString() else ch
            val digit = ch[0].isDigit()
            val changed = digit && progress < 1f && distTextDirection != 0 && ch != oldCh

            val w = textRenderer.getWidth(ch).toFloat()

            if (changed) {
                val oldY = baseY + if (distTextDirection > 0) -height * eased else height * eased
                val newY = baseY + if (distTextDirection > 0) height * (1f - eased) else -height * (1f - eased)

                context.drawText(textRenderer, oldCh, x.toInt(), oldY.toInt(), mutedColor.toARGB(), false)
                context.drawText(textRenderer, ch, x.toInt(), newY.toInt(), mutedColor.toARGB(), false)
            } else {
                context.drawText(textRenderer, ch, x.toInt(), baseY.toInt(), mutedColor.toARGB(), false)
            }
            x += w
        }

        context.disableScissor()
    }

    /* ============================= 攻击事件监听 ============================= */

    fun onAttack(entity: Entity) {
        if (!enabled) return
        if (entity is LivingEntity) {
            target = entity
            lastAttackDistanceTime = System.currentTimeMillis()
            val player = mc.player ?: return
            val playerPos = player.eyePos
            val targetPos = entity.pos.add(0.0, entity.height * 0.5, 0.0)
            lastAttackDistance = playerPos.distanceTo(targetPos).toFloat()
        }
    }
}
