/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.ciallo.live2d.cubism.Live2dModel
import kotlin.math.max
import kotlin.math.min

class Live2dRasterizer {

    private var frame: FloatArray = FloatArray(0)
    private var width = 0
    private var height = 0
    private var out: IntArray = IntArray(0)
    private val maskCache = HashMap<Int, ByteArray>()

    fun rasterize(
        model: Live2dModelLoader.LoadedModel,
        w: Int,
        h: Int,
        globalOpacity: Float,
    ): IntArray {
        if (w <= 0 || h <= 0) return IntArray(0)
        ensureBuffers(w, h)
        java.util.Arrays.fill(frame, 0f)
        maskCache.clear()

        val m = model.runtime.raw()
        val bounds = computeModelBounds(m)
        val bw = max(1e-4f, bounds[2] - bounds[0])
        val bh = max(1e-4f, bounds[3] - bounds[1])
        val scale = min(w / bw, h / bh)
        val offsetX = (w - bw * scale) * 0.5f
        val offsetY = (h - bh * scale) * 0.5f
        val minX = bounds[0]
        val maxY = bounds[3]

        val count = m.drawableCount
        val order = Array(count) { it }
        order.sortBy { if (it < m.renderOrder.size) m.renderOrder[it] else it }

        for (i in order) {
            if (i < 0 || i >= count) continue
            if (m.artMeshes == null || m.artMeshes[i] == null) continue
            if (!m.artMeshEnable[i]) continue
            val opacity = m.artMeshOpacity[i] * globalOpacity
            if (opacity <= 0.001f) continue
            if (m.artMeshes[i].vertexCount <= 0) continue

            var coverage: ByteArray? = null
            val maskLen = if (m.amMaskLen != null && i < m.amMaskLen.size) m.amMaskLen[i] else 0
            if (maskLen > 0) {
                coverage = buildMask(m, i, minX, maxY, scale, offsetX, offsetY)
                if (coverage == null || isCoverageEmpty(coverage)) coverage = null
            }
            drawMesh(m, model, i, offsetX, offsetY, scale, minX, maxY, opacity, coverage)
        }
        return toArgb(w, h)
    }

    private fun isCoverageEmpty(buf: ByteArray): Boolean {
        for (b in buf) if ((b.toInt() and 0xFF) != 0) return false
        return true
    }

    private fun ensureBuffers(w: Int, h: Int) {
        if (width == w && height == h && frame.isNotEmpty()) return
        width = w
        height = h
        frame = FloatArray(w * h * 4)
        out = IntArray(w * h)
    }

    private fun drawMesh(
        m: Live2dModel,
        model: Live2dModelLoader.LoadedModel,
        index: Int,
        offsetX: Float,
        offsetY: Float,
        scale: Float,
        minX: Float,
        maxY: Float,
        opacity: Float,
        coverage: ByteArray?,
    ) {
        val mesh = m.artMeshes[index] ?: return
        val pos = m.artMeshPos[index] ?: return
        val uvOff = m.amUvOff[index]
        val idxOff = m.amIdxOff[index]
        val idxLen = m.amIdxLen[index]
        if (uvOff < 0 || idxOff < 0 || idxLen < 3) return
        val uvs = m.uvs ?: return
        val indices = m.indices ?: return
        val texIndex = m.amTextureNo[index]
        if (texIndex < 0 || texIndex >= model.textureCount()) return
        val tex = model.textures[texIndex]
        val texW = model.textureWidths[texIndex]
        val texH = model.textureHeights[texIndex]
        if (texW <= 0 || texH <= 0) return

        val mul = m.artMeshMulColor
        val scr = m.artMeshScrColor
        val mr = mul[index * 4]
        val mg = mul[index * 4 + 1]
        val mb = mul[index * 4 + 2]
        val sr = scr[index * 4]
        val sg = scr[index * 4 + 1]
        val sb = scr[index * 4 + 2]
        val mode = normalizeBlendMode(m.artMeshBlendMode[index])
        val vc = mesh.vertexCount

        for (t in 0 until idxLen / 3) {
            val i0 = indices[idxOff + t * 3]
            val i1 = indices[idxOff + t * 3 + 1]
            val i2 = indices[idxOff + t * 3 + 2]
            if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= vc || i1 >= vc || i2 >= vc) continue
            if (i0 * 2 + 1 >= pos.size || i1 * 2 + 1 >= pos.size || i2 * 2 + 1 >= pos.size) continue
            val x0 = offsetX + (pos[i0 * 2] - minX) * scale
            val y0 = offsetY + (maxY - pos[i0 * 2 + 1]) * scale
            val x1 = offsetX + (pos[i1 * 2] - minX) * scale
            val y1 = offsetY + (maxY - pos[i1 * 2 + 1]) * scale
            val x2 = offsetX + (pos[i2 * 2] - minX) * scale
            val y2 = offsetY + (maxY - pos[i2 * 2 + 1]) * scale
            val uBase0 = uvOff + i0 * 2
            val uBase1 = uvOff + i1 * 2
            val uBase2 = uvOff + i2 * 2
            if (uBase0 + 1 >= uvs.size || uBase1 + 1 >= uvs.size || uBase2 + 1 >= uvs.size) continue
            rasterTriangle(
                tex, texW, texH,
                x0, y0, x1, y1, x2, y2,
                uvs[uBase0], uvs[uBase0 + 1],
                uvs[uBase1], uvs[uBase1 + 1],
                uvs[uBase2], uvs[uBase2 + 1],
                mr, mg, mb, sr, sg, sb, opacity, mode, coverage,
            )
        }
    }

    private fun normalizeBlendMode(mode: Int): Int = when (mode) {
        1, 3, 4, 9, 10, 11 -> BLEND_ADD
        2, 5, 6, 7, 8 -> BLEND_MUL
        else -> BLEND_NORMAL
    }

    private fun rasterTriangle(
        tex: IntArray, texW: Int, texH: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
        u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float,
        mr: Float, mg: Float, mb: Float,
        sr: Float, sg: Float, sb: Float,
        opacity: Float, mode: Int, coverage: ByteArray?,
    ) {
        val minX = max(0, kotlin.math.floor(min(x0, min(x1, x2))).toInt())
        val maxX = min(width - 1, kotlin.math.ceil(max(x0, max(x1, x2))).toInt())
        val minY = max(0, kotlin.math.floor(min(y0, min(y1, y2))).toInt())
        val maxY = min(height - 1, kotlin.math.ceil(max(y0, max(y1, y2))).toInt())
        if (minX > maxX || minY > maxY) return
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area == 0f) return
        val invArea = 1f / area
        for (py in minY..maxY) {
            val fy = py + 0.5f
            for (px in minX..maxX) {
                val fx = px + 0.5f
                var w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) * invArea
                var w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) * invArea
                var w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) {
                    w0 = -w0; w1 = -w1; w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                }
                val u = u0 * w0 + u1 * w1 + u2 * w2
                val v = v0 * w0 + v1 * w1 + v2 * w2
                val src = sample(tex, texW, texH, u, v)
                if (src == 0) continue
                var a = ((src ushr 24) and 0xFF) / 255f * opacity
                if (a <= 0.001f) continue
                if (coverage != null) {
                    val cov = coverage[py * width + px].toInt() and 0xFF
                    if (cov == 0) continue
                    a *= cov / 255f
                }
                var r = ((src ushr 16) and 0xFF) / 255f * mr
                var g = ((src ushr 8) and 0xFF) / 255f * mg
                var b = (src and 0xFF) / 255f * mb
                r = r + sr - r * sr
                g = g + sg - g * sg
                b = b + sb - b * sb
                val o = (py * width + px) * 4
                val dr = frame[o]; val dg = frame[o + 1]; val db = frame[o + 2]; val da = frame[o + 3]
                when (mode) {
                    BLEND_ADD -> {
                        frame[o] = dr + r * a; frame[o + 1] = dg + g * a; frame[o + 2] = db + b * a
                        frame[o + 3] = min(1f, da + a)
                    }
                    BLEND_MUL -> {
                        frame[o] = dr * (r + 1f - a); frame[o + 1] = dg * (g + 1f - a); frame[o + 2] = db * (b + 1f - a)
                        frame[o + 3] = min(1f, da + a)
                    }
                    else -> {
                        val inv = 1f - a
                        frame[o] = r * a + dr * inv; frame[o + 1] = g * a + dg * inv; frame[o + 2] = b * a + db * inv
                        frame[o + 3] = a + da * inv
                    }
                }
            }
        }
    }

    /** ImageIO: y=0 top. Cubism V often 0=bottom; flip V once only. */
    private fun sample(tex: IntArray, texW: Int, texH: Int, u: Float, v: Float): Int {
        val uu = u.coerceIn(0f, 1f) * (texW - 1)
        val vv = (1f - v.coerceIn(0f, 1f)) * (texH - 1)
        val x0 = uu.toInt().coerceIn(0, texW - 1)
        val y0 = vv.toInt().coerceIn(0, texH - 1)
        val x1 = min(x0 + 1, texW - 1)
        val y1 = min(y0 + 1, texH - 1)
        val fx = uu - x0
        val fy = vv - y0
        fun px(x: Int, y: Int) = tex[y * texW + x]
        val c00 = px(x0, y0); val c10 = px(x1, y0); val c01 = px(x0, y1); val c11 = px(x1, y1)
        fun ch(c: Int, s: Int) = ((c ushr s) and 0xFF).toFloat()
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val a = lerp(lerp(ch(c00, 24), ch(c10, 24), fx), lerp(ch(c01, 24), ch(c11, 24), fx), fy)
        val r = lerp(lerp(ch(c00, 16), ch(c10, 16), fx), lerp(ch(c01, 16), ch(c11, 16), fx), fy)
        val g = lerp(lerp(ch(c00, 8), ch(c10, 8), fx), lerp(ch(c01, 8), ch(c11, 8), fx), fy)
        val b = lerp(lerp(ch(c00, 0), ch(c10, 0), fx), lerp(ch(c01, 0), ch(c11, 0), fx), fy)
        return (a.toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    private fun buildMask(
        m: Live2dModel, drawableIndex: Int,
        minX: Float, maxY: Float, scale: Float, offsetX: Float, offsetY: Float,
    ): ByteArray? {
        maskCache[drawableIndex]?.let { return it }
        val maskIds = try { m.getDrawableMasks(drawableIndex) } catch (_: Throwable) { return null }
        if (maskIds == null || maskIds.isEmpty()) return null
        val acc = ByteArray(width * height)
        var any = false
        for (mi in maskIds) {
            if (mi < 0 || mi >= m.drawableCount) continue
            if (m.artMeshes[mi] == null || !m.artMeshEnable[mi]) continue
            rasterizeMaskDrawable(m, mi, acc, minX, maxY, scale, offsetX, offsetY)
            any = true
        }
        if (!any) return null
        maskCache[drawableIndex] = acc
        return acc
    }

    private fun rasterizeMaskDrawable(
        m: Live2dModel, index: Int, buf: ByteArray,
        minX: Float, maxY: Float, scale: Float, offsetX: Float, offsetY: Float,
    ) {
        val mesh = m.artMeshes[index] ?: return
        val vc = mesh.vertexCount
        if (vc <= 0) return
        val opacity = m.artMeshOpacity[index].coerceIn(0f, 1f)
        if (opacity <= 0.001f) return
        val alphaByte = (opacity * 255).toInt().coerceIn(0, 255).toByte()
        val pos = m.artMeshPos[index] ?: return
        val idxOff = m.amIdxOff[index]
        val idxLen = m.amIdxLen[index]
        val indices = m.indices ?: return
        if (idxOff < 0 || idxLen < 3) return
        for (t in 0 until idxLen / 3) {
            val i0 = indices[idxOff + t * 3]
            val i1 = indices[idxOff + t * 3 + 1]
            val i2 = indices[idxOff + t * 3 + 2]
            if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= vc || i1 >= vc || i2 >= vc) continue
            val x0 = offsetX + (pos[i0 * 2] - minX) * scale
            val y0 = offsetY + (maxY - pos[i0 * 2 + 1]) * scale
            val x1 = offsetX + (pos[i1 * 2] - minX) * scale
            val y1 = offsetY + (maxY - pos[i1 * 2 + 1]) * scale
            val x2 = offsetX + (pos[i2 * 2] - minX) * scale
            val y2 = offsetY + (maxY - pos[i2 * 2 + 1]) * scale
            fillTriangleCoverage(buf, alphaByte, x0, y0, x1, y1, x2, y2)
        }
    }

    private fun computeModelBounds(m: Live2dModel): FloatArray {
        val cw = m.canvasWidth
        val ch = m.canvasHeight
        if (cw > 1e-4f && ch > 1e-4f) {
            val ox = m.canvasOriginX
            val oy = m.canvasOriginY
            return floatArrayOf(ox - cw * 0.5f, oy - ch * 0.5f, ox + cw * 0.5f, oy + ch * 0.5f)
        }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until m.drawableCount) {
            if (m.artMeshes[i] == null || !m.artMeshEnable[i]) continue
            val verts = m.artMeshPos[i] ?: continue
            val vc = m.artMeshes[i].vertexCount
            for (j in 0 until vc) {
                if (j * 2 + 1 >= verts.size) break
                val x = verts[j * 2]; val y = verts[j * 2 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (minX == Float.POSITIVE_INFINITY) {
            minX = -0.5f; maxX = 0.5f; minY = -0.5f; maxY = 0.5f
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun fillTriangleCoverage(
        buf: ByteArray, value: Byte,
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
    ) {
        val minX = max(0, kotlin.math.floor(min(x0, min(x1, x2))).toInt())
        val maxX = min(width - 1, kotlin.math.ceil(max(x0, max(x1, x2))).toInt())
        val minY = max(0, kotlin.math.floor(min(y0, min(y1, y2))).toInt())
        val maxY = min(height - 1, kotlin.math.ceil(max(y0, max(y1, y2))).toInt())
        if (minX > maxX || minY > maxY) return
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area == 0f) return
        val invArea = 1f / area
        for (py in minY..maxY) {
            val fy = py + 0.5f
            for (px in minX..maxX) {
                val fx = px + 0.5f
                var w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) * invArea
                var w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) * invArea
                var w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) {
                    w0 = -w0; w1 = -w1; w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                }
                val idx = py * width + px
                if ((buf[idx].toInt() and 0xFF) < (value.toInt() and 0xFF)) buf[idx] = value
            }
        }
    }

    private fun toArgb(w: Int, h: Int): IntArray {
        val result = out
        for (i in 0 until w * h) {
            val o = i * 4
            val a = frame[o + 3].coerceIn(0f, 1f)
            if (a <= 0.001f) { result[i] = 0; continue }
            val r = (frame[o] / a).coerceIn(0f, 1f)
            val g = (frame[o + 1] / a).coerceIn(0f, 1f)
            val b = (frame[o + 2] / a).coerceIn(0f, 1f)
            result[i] = ((a * 255).toInt() shl 24) or ((r * 255).toInt() shl 16) or
                ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }
        return result
    }

    companion object {
        private const val BLEND_NORMAL = 0
        private const val BLEND_ADD = 1
        private const val BLEND_MUL = 2
    }
}
