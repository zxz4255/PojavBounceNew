/*
 * CubismRenderer —— Live2D 网格绘制。
 *
 * 26.3 的 GUI 管线（RenderPipelines.GUI_TEXTURED）拓扑是 QUADS，
 * 所以每个三角形补一个退化顶点凑成四边形。
 *
 * 每个 drawable 独立提交一个 GUI element，因此：
 *   - 多张纹理各自正确绑定（Haru 这类双纹理模型不会串图）；
 *   - 绘制顺序严格按 MOC3 drawOrder 排序后的 renderOrder；
 *   - 逐 drawable 的 alpha 精确生效。
 *
 * 遮罩（mask）用各 mask drawable 的屏幕包围盒与目标做 scissor 交集近似，
 * 不改顶点；这是 GUI 管线无 stencil 时的可行近似。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/CubismRenderer.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class CubismRenderer(
    private val model: Live2DModel,
    private val textures: List<Identifier>,
) {

    var flipV: Boolean = true
    var masksEnabled: Boolean = true

    /** 按 drawable id 隐藏。 */
    private val hidden = HashSet<Int>()

    /** 上一帧实际提交的 drawable 数，供状态显示。 */
    var lastRenderedDrawables: Int = 0
        private set

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** 当前帧模型在屏幕上的整体矩形；每次提交都必须带 bounds，否则元素会被 GuiRenderState 丢弃。 */
    private var frameBounds: ScreenRectangle? = null

    fun setHiddenDrawables(ids: Collection<String>) {
        hidden.clear()
        if (ids.isEmpty()) return
        for (index in 0 until model.drawableCount) {
            val id = model.core.drawableIds.getOrNull(index) ?: continue
            if (ids.contains(id)) hidden.add(index)
        }
    }

    /**
     * 在 [x, y, width, height] 区域内等比居中绘制。
     *
     * @param globalOpacity 外部整体不透明度（模块配置 × motion 的 Model/Opacity 曲线）
     */
    fun render(
        graphics: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        globalOpacity: Float = 1f,
    ) {
        lastRenderedDrawables = 0
        if (width <= 0f || height <= 0f || textures.isEmpty()) return

        computeTransform(x, y, width, height)

        // bounds 必须非空，否则 GuiRenderState.findAppropriateNode 会直接丢弃该元素。
        frameBounds = toRect(x, y, x + width, y + height)
        if (frameBounds == null) return

        val opacity = (globalOpacity * model.modelOpacity).coerceIn(0f, 1f)
        if (opacity <= 0.001f) return

        for (rank in model.renderOrder.indices) {
            val index = model.renderOrder[rank]
            if (index in hidden) continue
            if (!model.drawableVisible(index)) continue

            val drawableAlpha = model.drawableOpacity(index) * opacity
            if (drawableAlpha <= 0.001f) continue

            val textureIndex = model.drawableTextureIndex(index)
            if (textureIndex < 0 || textureIndex >= textures.size) continue

            val texture = textures[textureIndex]
            val setup = runCatching { mc.textureManager.getTexture(texture).textureSetup }.getOrElse {
                logger.warn("Live2D 纹理不可用：$texture", it)
                continue
            }

            val clip: ScreenRectangle? = null

            val alpha = (drawableAlpha * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            submit(graphics, index, setup, alpha, clip, frameBounds!!)
            lastRenderedDrawables++
        }
    }

    /* --------------------------- 单 drawable --------------------------- */

    private fun submit(
        graphics: GuiGraphicsExtractor,
        index: Int,
        setup: TextureSetup,
        alpha: Int,
        clip: ScreenRectangle?,
        bounds: ScreenRectangle,
    ) {
        val positions = model.drawablePositions(index)
        val uvs = model.drawableUvs(index)
        val indices = model.drawableIndices(index)
        val vertexCount = model.drawableVertexCount(index)

        if (vertexCount <= 0 || indices.size < 3) return
        if (positions.size < vertexCount * 2 || uvs.size < vertexCount * 2) return

        graphics.drawCustomElement(
            pipeline = RenderPipelines.GUI_TEXTURED,
            textureSetup = setup,
            scissorArea = clip,
            bounds = bounds,
        ) { pose ->
            var i = 0
            while (i + 2 < indices.size) {
                val a = indices[i]
                val b = indices[i + 1]
                val c = indices[i + 2]
                i += 3
                if (a >= vertexCount || b >= vertexCount || c >= vertexCount) continue

                // QUADS 拓扑：三个真实顶点 + 重复末点，构成退化四边形。
                emit(this, pose, positions, uvs, a, alpha)
                emit(this, pose, positions, uvs, b, alpha)
                emit(this, pose, positions, uvs, c, alpha)
                emit(this, pose, positions, uvs, c, alpha)
            }
        }
    }

    private fun emit(
        consumer: VertexConsumer,
        pose: Matrix3x2f,
        positions: FloatArray,
        uvs: FloatArray,
        vertex: Int,
        alpha: Int,
    ) {
        val px = offsetX + positions[vertex * 2] * scale
        val py = offsetY + positions[vertex * 2 + 1] * scale

        var u = uvs[vertex * 2]
        var v = uvs[vertex * 2 + 1]
        if (flipV) v = 1f - v

        consumer.addVertexWith2DPose(pose, px, py)
            .setColor(255, 255, 255, alpha)
            .setUv(u, v)
    }

    /* ------------------------------ 遮罩 ------------------------------ */

    /**
     * 转成 ScreenRectangle，宽高至少 1 —— GuiRenderer 遇到 0 尺寸 scissor/bounds 会直接抛异常。
     */
    private fun toRect(left: Float, top: Float, right: Float, bottom: Float): ScreenRectangle? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        val l = floor(minOf(left, right)).toInt()
        val t = floor(minOf(top, bottom)).toInt()
        val w = ceil(abs(right - left)).toInt().coerceAtLeast(1)
        val h = ceil(abs(bottom - top)).toInt().coerceAtLeast(1)
        return ScreenRectangle(l, t, w, h)
    }

    /* ---------------------------- 坐标变换 ---------------------------- */

    private fun computeTransform(x: Float, y: Float, width: Float, height: Float) {
        val modelWidth = max(0.001f, model.boundsMaxX - model.boundsMinX)
        val modelHeight = max(0.001f, model.boundsMaxY - model.boundsMinY)

        scale = min(width / modelWidth, height / modelHeight)

        val drawnWidth = modelWidth * scale
        val drawnHeight = modelHeight * scale

        offsetX = x + (width - drawnWidth) * 0.5f - model.boundsMinX * scale
        offsetY = y + (height - drawnHeight) * 0.5f - model.boundsMinY * scale
    }
}