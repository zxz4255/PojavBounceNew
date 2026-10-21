/*
 * Live2DModel —— 可渲染模型：几何来自 CubismModel，附加表情 / 动作 / 参数覆盖 / 物理 / 姿态。
 *
 * 每帧用法：
 *   model.setParameter("ParamAngleX", x)
 *   model.updateTransientAnimations(dt)
 *   model.update()
 *   // 读取 renderOrder / drawablePositions / drawableOpacity
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DModel.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

class Live2DModel private constructor(val core: CubismModel) : Closeable {

    val drawableCount: Int get() = core.drawableCount

    val expressions = LinkedHashMap<String, CubismExpression>()
    val motions = LinkedHashMap<String, Live2DMotion>()

    var pose: CubismPose? = null
    var physics: CubismPhysics? = null

    /** 模型级不透明度（motion 的 Model/Opacity 曲线会写入这里）。 */
    var modelOpacity = 1f

    private var playingMotion: String? = null
    private var motionTime = 0f
    private var motionFadeIn = 0f
    private var motionFadeOut = 0f
    private var motionLoop = false
    private var motionDuration = 1f

    private var playingExpression: String? = null
    private var expressionHoldRemaining = 0f
    private var expressionFade = 0f
    private var expressionWeight = 0f

    /** 常驻表情（由外部设定，逐帧以权重 1 叠加）。 */
    private var persistentExpression: String? = null

    private class ParamOverride(val value: Float, val hold: Float, val fade: Float) {
        var elapsed = 0f
    }

    private val paramOverrides = LinkedHashMap<String, ParamOverride>()

    /* ----------------------------- geometry ----------------------------- */

    val renderOrder: IntArray get() = core.renderOrder

    fun drawablePositions(index: Int): FloatArray = core.drawablePositions(index)
    fun drawableUvs(index: Int): FloatArray = core.drawableUvs(index)
    fun drawableIndices(index: Int): IntArray = core.drawableIndices(index)
    fun drawableVertexCount(index: Int): Int = core.drawableVertexCount(index)
    fun drawableIndexCount(index: Int): Int = core.drawableIndexCount(index)
    fun drawableOpacity(index: Int): Float = core.drawableOpacity[index]
    fun drawableTextureIndex(index: Int): Int = core.drawableTextureIndex(index)
    fun drawableConstantFlags(index: Int): Int = core.drawableConstantFlags(index)
    fun drawableVisible(index: Int): Boolean = core.drawableVisible(index)
    fun drawableAdditive(index: Int): Boolean = core.drawableAdditive(index)
    fun drawableMultiplicative(index: Int): Boolean = core.drawableMultiplicative(index)
    fun drawableMaskCount(index: Int): Int = core.drawableMaskCount(index)
    fun drawableMask(index: Int, slot: Int): Int = core.drawableMask(index, slot)

    val boundsMinX: Float get() = core.boundsMinX
    val boundsMinY: Float get() = core.boundsMinY
    val boundsMaxX: Float get() = core.boundsMaxX
    val boundsMaxY: Float get() = core.boundsMaxY

    val canvasWidth: Float get() = core.canvasWidth
    val canvasHeight: Float get() = core.canvasHeight

    fun getBboxAspect(): Float = core.bboxAspect

    /* ---------------------------- parameters ---------------------------- */

    fun hasParameter(id: String): Boolean = core.indexOfParameter(id) >= 0

    fun setParameter(id: String, value: Float) {
        val index = core.indexOfParameter(id)
        if (index >= 0) core.setParameterValue(index, value)
    }

    fun parameterValue(id: String): Float {
        val index = core.indexOfParameter(id)
        return if (index >= 0) core.parameterValueAt(index) else 0f
    }

    fun resetParametersToDefault() {
        core.resetParameters()
    }

    /* ---------------------------- expressions ---------------------------- */

    fun hasExpression(name: String): Boolean = expressions.containsKey(name)

    fun addExpression(name: String, expression: CubismExpression) {
        expressions[name] = expression
    }

    /** 常驻表情：每帧以权重 1 叠加，null 表示关闭。 */
    fun setPersistentExpression(name: String?) {
        persistentExpression = if (name != null && expressions.containsKey(name)) name else null
    }

    fun getPersistentExpression(): String? = persistentExpression

    /** 按表达式自身的 Blend 模式叠加（Add / Multiply / Overwrite）。 */
    fun applyExpression(name: String, weight: Float) {
        val expression = expressions[name] ?: return
        for (parameter in expression.parameters) {
            val index = core.indexOfParameter(parameter.id)
            if (index < 0) continue

            val base = core.parameterValueAt(index)
            val blended = when (parameter.blend) {
                CubismExpression.Blend.ADD -> base + parameter.value * weight
                CubismExpression.Blend.MULTIPLY -> base * (1f + (parameter.value - 1f) * weight)
                CubismExpression.Blend.OVERWRITE -> base + (parameter.value - base) * weight
            }
            core.setParameterValue(index, blended)
        }
    }

    /* ------------------------------- motion ------------------------------ */

    fun hasMotion(name: String): Boolean = motions.containsKey(name)

    fun addMotion(name: String, motion: Live2DMotion) {
        motions[name] = motion
    }

    fun playMotion(name: String) {
        val motion = motions[name] ?: return
        playingMotion = name
        motionTime = 0f
        motionFadeIn = motion.fadeIn
        motionFadeOut = motion.fadeOut
        motionLoop = motion.loop
        motionDuration = motion.duration
    }

    fun stopMotion() {
        playingMotion = null
    }

    fun getPlayingMotion(): String? = playingMotion

    fun playExpression(name: String, holdSeconds: Float, fadeSeconds: Float) {
        if (!expressions.containsKey(name)) return
        playingExpression = name
        expressionHoldRemaining = max(0f, holdSeconds)
        expressionFade = max(0.05f, fadeSeconds)
        expressionWeight = 0f
    }

    fun setParameterOverride(id: String, value: Float, hold: Float, fade: Float) {
        if (!hasParameter(id)) return
        paramOverrides[id] = ParamOverride(value, max(0f, hold), max(0.05f, fade))
    }

    fun clearTransients() {
        playingMotion = null
        playingExpression = null
        expressionWeight = 0f
        paramOverrides.clear()
    }

    /** 推进动作、瞬时表情与参数覆盖，dt 单位为秒。 */
    fun updateTransientAnimations(dt: Float) {
        updateMotion(dt)
        updateExpressionOverlay(dt)
        updateParamOverrides(dt)
    }

    private fun updateMotion(dt: Float) {
        val name = playingMotion ?: return
        val motion = motions[name] ?: run {
            playingMotion = null
            return
        }

        motionTime += dt
        if (motionLoop) {
            motionTime %= motionDuration
        } else if (motionTime >= motionDuration) {
            playingMotion = null
            return
        }

        var weight = 1f
        if (motionFadeIn > 0f && motionTime < motionFadeIn) weight *= motionTime / motionFadeIn
        val remaining = motionDuration - motionTime
        if (motionFadeOut > 0f && remaining < motionFadeOut) weight *= remaining / motionFadeOut
        if (weight <= 1e-4f) return

        for (curve in motion.curves) {
            val value = curve.valueAt(motionTime)
            if (value.isNaN()) continue

            when (curve.target) {
                "Parameter" -> {
                    val index = core.indexOfParameter(curve.id)
                    if (index < 0) continue
                    val base = core.parameterValueAt(index)
                    core.setParameterValue(index, base + (value - base) * weight)
                }
                "Model" -> if (curve.id == "Opacity") {
                    modelOpacity += (value - modelOpacity) * weight
                }
            }
        }
    }

    private fun updateExpressionOverlay(dt: Float) {
        val name = playingExpression ?: return

        if (expressionWeight < 1f) expressionWeight = min(1f, expressionWeight + dt / expressionFade)

        if (expressionHoldRemaining > 0f) {
            expressionHoldRemaining -= dt
            if (expressionHoldRemaining <= 0f) expressionHoldRemaining = 0f
        } else if (expressionWeight > 0f) {
            expressionWeight = max(0f, expressionWeight - dt / expressionFade)
        }

        applyExpression(name, expressionWeight)
        if (expressionWeight <= 0f) playingExpression = null
    }

    private fun updateParamOverrides(dt: Float) {
        val iterator = paramOverrides.iterator()
        while (iterator.hasNext()) {
            val (id, override) = iterator.next()
            override.elapsed += dt

            val index = core.indexOfParameter(id)
            if (index < 0) {
                iterator.remove()
                continue
            }

            val weight = when {
                override.elapsed <= override.fade -> override.elapsed / override.fade
                override.elapsed <= override.fade + override.hold -> 1f
                override.elapsed <= override.fade + override.hold + override.fade ->
                    1f - (override.elapsed - override.fade - override.hold) / override.fade
                else -> {
                    iterator.remove()
                    continue
                }
            }

            val base = core.parameterValueAt(index)
            core.setParameterValue(index, base + (override.value - base) * weight.coerceIn(0f, 1f))
        }
    }

    /* -------------------------------- solve ------------------------------ */

    /** 重算所有 drawable 的顶点、不透明度与绘制顺序。 */
    fun update() {
        pose?.apply(core)
        core.update()
    }

    override fun close() {
        expressions.clear()
        motions.clear()
        paramOverrides.clear()
        pose = null
        physics = null
    }

    companion object {
        fun loadBytes(mocBytes: ByteArray): Live2DModel {
            val moc = CubismMoc3.parse(mocBytes)
            val model = Live2DModel(CubismModel(moc))
            model.update()
            return model
        }
    }
}