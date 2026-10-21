/*
 * Live2DHudRenderer —— 模型生命周期、每帧驱动与 HUD 绘制。
 *
 * 每帧顺序与原实现一致：
 *   ensureModelLoaded -> updateTracking -> applyIdleAnimation -> applyExpressions
 *   -> updateTransientAnimations -> update -> 提交网格
 *
 * 模型名不写死：默认取 config/model 或扫描到的第一个模型。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DHudRenderer.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Live2DHudRenderer(
    private val config: Live2DConfig,
    private val configPath: Path,
) {

    private val loader = Live2DModelLoader()
    private val eventSystem = Live2DEventSystem(this, config)

    private var loaded: Live2DModelLoader.LoadedModel? = null
    private var renderer: CubismRenderer? = null
    private var loadedName: String? = null
    private var loadError: String? = null
    private var loadAttempted = false

    /* ---------------------------- 动画状态 ---------------------------- */

    private var firstFrame = true
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var angleX = 0f
    private var angleY = 0f
    private var angleZ = 0f
    private var bodyAngleX = 0f
    private var springX = 0f
    private var springVX = 0f
    private var springY = 0f
    private var springVY = 0f
    private var earSpring = 0f
    private var earVelocity = 0f

    private var nextBlinkAt = System.currentTimeMillis() + 1800L
    private var blinkStartedAt = -1L

    private var activeExpression: String? = null
    private var lastAppliedExpression: String? = null

    private var idleMotionStartedAt = 0L
    private var idleMotionCooldown = 0L

    private var lastConfigSave = 0L

    /** 上一帧模型在屏幕上的实际矩形，供鼠标命中测试。 */
    var lastElementBounds: net.minecraft.client.gui.navigation.ScreenRectangle? = null
        private set

    /* --------------------------- 鼠标命中测试 --------------------------- */

    /** 当前模型矩形（没有渲染过时按配置推算）。 */
    fun elementBounds(): net.minecraft.client.gui.navigation.ScreenRectangle {
        lastElementBounds?.let { return it }
        val height = config.size.coerceIn(40, 800)
        val width = (height * (loaded?.model?.getBboxAspect() ?: 1f)).toInt().coerceAtLeast(1)
        return net.minecraft.client.gui.navigation.ScreenRectangle(
            config.posX, config.posY, width, height,
        )
    }

    /* ------------------------------ 查询 ------------------------------ */

    fun isEnabled(): Boolean = config.enabled
    fun getLoadError(): String? = loadError
    fun getLoadedModelName(): String? = loadedName
    fun getAvailableModels(): List<String> = loader.availableModels()
    fun getAvailableMotions(): List<String> = loaded?.model?.motions?.keys?.toList() ?: emptyList()
    fun getAvailableExpressions(): List<String> = loaded?.model?.expressions?.keys?.toList() ?: emptyList()
    fun getConfig(): Live2DConfig = config
    fun getEventSystem(): Live2DEventSystem = eventSystem
    fun getActiveExpression(): String? = activeExpression
    fun getRenderer(): CubismRenderer? = renderer

    fun getStatus(): String = buildString {
        append("模型 ").append(loadedName ?: config.model.ifBlank { "(未选择)" })
        append(" | ").append(config.size).append("px")
        append(" | ").append(config.posX).append(',').append(config.posY)
        append(" | 表情 ").append(activeExpression ?: "normal")
        renderer?.let { append(" | drawables ").append(it.lastRenderedDrawables) }
        loadError?.let { append(" | 错误 ").append(it) }
    }

    /* ------------------------------ 操作 ------------------------------ */

    fun toggleEnabled() {
        config.enabled = !config.enabled
        saveConfigNow()
    }

    fun setEnabled(value: Boolean) {
        config.enabled = value
        saveConfigNow()
    }

    fun cycleModel() {
        val models = getAvailableModels()
        if (models.isEmpty()) return
        val index = models.indexOf(config.model)
        setModel(models[(index + 1) % models.size])
    }

    fun setModel(name: String) {
        if (name.isBlank()) return
        config.model = name
        loadAttempted = false
        unload()
        saveConfigNow()
    }

    fun cycleExpression() {
        val expressions = getAvailableExpressions()
        if (expressions.isEmpty()) return
        val options = listOf("normal") + expressions
        val current = activeExpression ?: "normal"
        val index = options.indexOf(current).coerceAtLeast(0)
        val next = options[(index + 1) % options.size]
        activeExpression = if (next == "normal") null else next
        saveConfigNow()
    }

    fun setActiveExpression(name: String?) {
        activeExpression = if (name.isNullOrBlank() || name.equals("normal", true)) null else name
        saveConfigNow()
    }

    fun setSize(height: Int) {
        config.size = Mth.clamp(height, 40, 800)
        saveConfig()
    }

    fun movePosition(dx: Int, dy: Int) {
        config.posX += dx
        config.posY += dy
        saveConfig()
    }

    fun triggerAction(action: Live2DConfig.EventAction) {
        val model = loaded?.model ?: return
        if (action.target.isEmpty()) return

        when (action.type) {
            "motion" -> {
                val key = model.motions.keys.firstOrNull { it == action.target || it.endsWith(action.target) }
                if (key != null) model.playMotion(key)
            }
            "expression" -> {
                if (!model.hasExpression(action.target)) return
                if (action.duration > 0f) {
                    model.playExpression(action.target, action.duration, action.fade)
                } else {
                    activeExpression = action.target
                }
            }
            "param" -> {
                if (!model.hasParameter(action.target)) return
                if (action.duration > 0f) {
                    model.setParameterOverride(action.target, action.value, action.duration, action.fade)
                } else {
                    model.setParameter(action.target, action.value)
                }
            }
        }
    }

    fun handleTick() {
        eventSystem.tick()
    }

    /* ------------------------------ 每帧 ------------------------------ */

    fun render(context: GuiGraphicsExtractor, tickDelta: Float) {
        if (!config.enabled) return
        if (mc.player == null || mc.level == null) return

        ensureModelLoaded()
        updateTracking(tickDelta)

        val model = loaded?.model
        if (model != null) {
            try {
                applyIdleAnimation(model)
                applyIdleMotion(model)
                applyExpressions(model)
                model.updateTransientAnimations(tickDelta / 20f)
                model.physics?.apply(model.core, tickDelta / 20f)
                model.update()
            } catch (error: Throwable) {
                loadError = "update: ${error.javaClass.simpleName}: ${error.message}"
                logger.error("Live2D 更新失败", error)
            }
        }

        val height = config.size.coerceIn(40, 800)
        val aspect = model?.getBboxAspect() ?: 1f
        val width = (height * aspect).toInt().coerceAtLeast(1)

        val x = config.posX + (springX * 0.55f).toInt()
        val y = config.posY + (springY * 0.3f + idleBob()).toInt()

        lastElementBounds = net.minecraft.client.gui.navigation.ScreenRectangle(x, y, width, height)

        val current = renderer
        if (current != null) {
            try {
                current.masksEnabled = config.masksEnabled
                current.render(context, x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
            } catch (error: Throwable) {
                loadError = "render: ${error.javaClass.simpleName}: ${error.message}"
                logger.error("Live2D 渲染失败", error)
            }
        } else {
            // 没有模型时画占位框并把错误直接画到屏幕上，便于定位问题。
            context.drawQuad(
                x.toFloat(), y.toFloat(), x + width.toFloat(), y + height.toFloat(),
                Color4b(0x30, 0x20, 0x28, 0x90),
            )
            val message = loadError ?: "Live2D: 模型未加载"
            context.text(mc.font, message, x, y + height + 2, 0xFFFF5555.toInt(), true)
        }
    }

    /* ---------------------------- 模型加载 ---------------------------- */

    private fun ensureModelLoaded() {
        if (loadAttempted || loaded != null) return
        loadAttempted = true

        val name = config.model.ifBlank { getAvailableModels().firstOrNull() ?: "" }
        if (name.isBlank()) {
            loadError = "config/live2d/ 下没有可用模型"
            return
        }
        config.model = name

        try {
            val result = loader.load(name)
            unload()
            loaded = result
            renderer = CubismRenderer(result.model, result.textures).apply {
                setHiddenDrawables(config.hiddenDrawables)
                masksEnabled = config.masksEnabled
            }
            loadedName = name
            loadError = null

            firstFrame = true
            activeExpression = null
            lastAppliedExpression = null
            nextBlinkAt = System.currentTimeMillis() + 1800L
            blinkStartedAt = -1L
            idleMotionStartedAt = 0L
            idleMotionCooldown = 0L
            result.model.update()
        } catch (error: Throwable) {
            loadError = "${error.javaClass.simpleName}: ${error.message}"
            logger.error("加载 Live2D 模型 $name 失败", error)
            unload()
        }
    }

    private fun unload() {
        loaded?.close()
        loaded = null
        renderer = null
        loadedName = null
    }

    fun close() {
        unload()
        loader.releaseTextures()
    }

    /* ---------------------------- 头部跟随 ---------------------------- */

    private fun updateTracking(tickDelta: Float) {
        val player = mc.player ?: return
        val yaw = player.yRot
        val pitch = player.xRot

        if (firstFrame) {
            lastYaw = yaw
            lastPitch = pitch
            firstFrame = false
            return
        }

        val dt = (tickDelta / 20f).coerceAtLeast(0.01f)
        val deltaYaw = Mth.wrapDegrees(yaw - lastYaw)
        val deltaPitch = pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch

        val strength = config.swayStrength
        val targetX = (-deltaYaw / dt * 0.018f * strength).coerceIn(-30f, 30f)
        val targetY = if (config.followPitch) (-deltaPitch / dt * 0.014f * strength).coerceIn(-20f, 20f) else 0f
        val targetZ = (deltaYaw / dt * 0.01f * strength).coerceIn(-18f, 18f)

        val smooth = config.smoothing.coerceIn(0.02f, 0.6f)
        angleX = lerp(angleX, targetX, smooth)
        angleY = lerp(angleY, targetY, smooth)
        angleZ = lerp(angleZ, targetZ, smooth * 0.75f)
        bodyAngleX = lerp(bodyAngleX, angleX * 0.35f, smooth * 0.55f)

        springX = updateSpring(springX, angleX + bodyAngleX, 0.12f, 0.78f, true)
        springY = updateSpring(springY, angleY, 0.08f, 0.82f, false)
    }

    private fun updateSpring(value: Float, target: Float, stiffness: Float, damping: Float, horizontal: Boolean): Float {
        var velocity = (if (horizontal) springVX else springVY) + (target - value) * stiffness
        velocity *= damping
        if (horizontal) springVX = velocity else springVY = velocity
        return value + velocity
    }

    /* ---------------------------- 待机动画 ---------------------------- */

    private fun applyIdleAnimation(model: Live2DModel) {
        val now = System.currentTimeMillis()
        val t = now / 1000.0

        var eyeOpen = 1f
        if (config.blinkEnabled) {
            if (blinkStartedAt < 0L && now >= nextBlinkAt) blinkStartedAt = now
            if (blinkStartedAt >= 0L) {
                val progress = ((now - blinkStartedAt) / 180f).coerceIn(0f, 1f)
                eyeOpen = if (progress < 0.5f) 1f - progress * 2f else (progress - 0.5f) * 2f
                if (progress >= 1f) {
                    blinkStartedAt = -1L
                    nextBlinkAt = now + 2200L + (sin(t * 1.7) * 500.0 + 650.0).toLong()
                }
            }
        }
        model.setParameter("ParamEyeLOpen", eyeOpen)
        model.setParameter("ParamEyeROpen", eyeOpen)

        val breath = ((sin(t * 2.1) + 1.0) * 0.5).toFloat()
        model.setParameter("ParamBreath", breath)

        model.setParameter("ParamAngleX", angleX)
        model.setParameter("ParamAngleY", angleY)
        model.setParameter("ParamAngleZ", angleZ)
        model.setParameter("ParamBodyAngleX", bodyAngleX)
        model.setParameter("ParamBodyAngleY", angleY * 0.2f)
        model.setParameter("ParamBodyAngleZ", angleZ * 0.3f)

        model.setParameter("ParamEyeBallX", (angleX / 30f).coerceIn(-1f, 1f))
        model.setParameter("ParamEyeBallY", (angleY / 20f).coerceIn(-1f, 1f))

        val hairX = angleX * 0.09f + sin(t * 2.8).toFloat() * 1.2f
        val hairY = angleY * 0.08f + sin(t * 2.1 + 1.0).toFloat() * 0.8f
        model.setParameter("ParamHairFront", hairX)
        model.setParameter("ParamHairSide", hairX)
        model.setParameter("ParamHairBack", hairX)
        model.setParameter("ParamHairAhoge", hairY)

        earVelocity += (angleZ * 0.55f + angleX * 0.18f - earSpring) * 0.16f
        earVelocity *= 0.74f
        earSpring += earVelocity
        val ear = earSpring.coerceIn(-12f, 12f)
        model.setParameter("Param10", ear)
        model.setParameter("Param14", -ear)

        if (config.soundBlinkEnabled && soundActivity >= config.soundBlinkThreshold) {
            model.setParameter("ParamEyeLOpen", 0f)
            model.setParameter("ParamEyeROpen", 0f)
        }
    }

    /** 待机动作：若模型带 Idle 组，则按冷却循环播放其中一条。 */
    private fun applyIdleMotion(model: Live2DModel) {
        if (!config.autoIdleMotion) return
        if (model.motions.isEmpty()) return
        if (model.getPlayingMotion() != null) return

        val now = System.currentTimeMillis()
        if (now < idleMotionCooldown) return

        val candidates = model.motions.keys
            .filter { it.contains(config.idleMotionGroup, ignoreCase = true) }
            .ifEmpty { model.motions.keys.toList() }
        if (candidates.isEmpty()) return

        val pick = candidates[(now / 1000L % candidates.size).toInt()]
        model.playMotion(pick)
        idleMotionStartedAt = now
        idleMotionCooldown = now + 5_000L
    }

    private fun applyExpressions(model: Live2DModel) {
        val target = activeExpression
        if (!config.expressionsEnabled || target == null || !model.hasExpression(target)) {
            if (lastAppliedExpression != null) {
                model.resetParametersToDefault()
                lastAppliedExpression = null
            }
            return
        }
        if (target != lastAppliedExpression) {
            model.resetParametersToDefault()
            lastAppliedExpression = target
        }
        model.applyExpression(target, 1f)
    }

    /** 声音活动度，由 ModuleLive2D 的声音监听写入。 */
    var soundActivity: Float = 0f

    private fun lerp(from: Float, to: Float, factor: Float): Float =
        from + (to - from) * factor.coerceIn(0f, 1f)

    private fun idleBob(): Float = (sin(System.currentTimeMillis() / 1200.0) * 1.2).toFloat()

    /* ---------------------------- 配置写盘 ---------------------------- */

    private fun saveConfig() {
        val now = System.currentTimeMillis()
        if (now - lastConfigSave < 300L) return
        lastConfigSave = now
        config.save(configPath)
    }

    private fun saveConfigNow() {
        lastConfigSave = System.currentTimeMillis()
        config.save(configPath)
    }
}