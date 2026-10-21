/*
 * CubismModel —— MOC3 求解器（纯 Kotlin，零 native）。
 *
 * 更新管线与 Cubism Core 一致：
 *   parameters -> key tables -> bindings -> parts / deformers / meshes
 *   -> warp / rotation 变形 -> 顶点 -> 部件不透明度 -> Y 翻转 -> 绘制顺序
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/CubismModel.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class CubismModel(private val moc: CubismMoc3) {

    /* ------------------------------- ids ------------------------------- */

    val drawableCount: Int = moc.artMeshes
    val parameterCount: Int = moc.parameters
    val partCount: Int = moc.parts

    val parameterIds: Array<String> = Array(parameterCount) {
        moc.idAt(CubismMoc3.Section.PARAM_ID, it)
    }
    val drawableIds: Array<String> = Array(drawableCount) {
        moc.idAt(CubismMoc3.Section.AM_ID, it)
    }
    val partIds: Array<String> = Array(partCount) {
        moc.idAt(CubismMoc3.Section.PART_ID, it)
    }

    private val parameterIndex: Map<String, Int> =
        parameterIds.withIndex().associate { (i, id) -> id to i }

    fun indexOfParameter(id: String): Int = parameterIndex[id] ?: -1

    private val partIndex: Map<String, Int> = partIds.withIndex().associate { (i, id) -> id to i }

    fun indexOfPart(id: String): Int = partIndex[id] ?: -1

    /* ---------------------------- parameters ---------------------------- */

    private val paramMin = FloatArray(parameterCount) {
        moc.floatAt(CubismMoc3.Section.PARAM_MIN, it)
    }
    private val paramMax = FloatArray(parameterCount) {
        moc.floatAt(CubismMoc3.Section.PARAM_MAX, it)
    }
    private val paramDefault = FloatArray(parameterCount) {
        moc.floatAt(CubismMoc3.Section.PARAM_DEFAULT, it)
    }
    private val paramRepeat = BooleanArray(parameterCount) {
        moc.intAt(CubismMoc3.Section.PARAM_REPEAT, it) != 0
    }

    /** 参数吸附精度：0.1^decimalPlaces，至少 0.1。 */
    private val paramSnapEps = FloatArray(parameterCount) {
        val decimals = max(1, moc.intAt(CubismMoc3.Section.PARAM_DECIMAL_PLACES, it))
        0.1f.pow(decimals.toFloat())
    }
    private val paramInterpEps = FloatArray(parameterCount) { paramSnapEps[it] * 1.5f }
    private val paramKeyTableOff = IntArray(parameterCount) {
        moc.intAt(CubismMoc3.Section.PARAM_KEY_TABLE_OFF, it)
    }
    private val paramKeyTableLen = IntArray(parameterCount) {
        moc.intAt(CubismMoc3.Section.PARAM_KEY_TABLE_LEN, it)
    }

    /** 调用方直接写入的原始参数。 */
    val parameterValues = FloatArray(parameterCount) { paramDefault[it] }

    private val paramResolved = FloatArray(parameterCount) { paramDefault[it] }
    private val paramDirty = BooleanArray(parameterCount) { true }

    /** 首帧以及模型重载后强制全量求值（对齐 Core 的 force_update）。 */
    private var forceUpdate = true

    fun parameterMin(index: Int): Float = paramMin[index]
    fun parameterMax(index: Int): Float = paramMax[index]
    fun parameterDefault(index: Int): Float = paramDefault[index]

    fun parameterValueAt(index: Int): Float =
        if (index in 0 until parameterCount) parameterValues[index] else 0f

    fun setParameterValue(index: Int, value: Float) {
        if (index in 0 until parameterCount) parameterValues[index] = value
    }

    fun resetParameters() {
        paramDefault.copyInto(parameterValues)
        paramDirty.fill(true)
    }

    /* ---------------------------- key tables ---------------------------- */

    private val keyTableKeysOff = IntArray(moc.keyTables) {
        moc.intAt(CubismMoc3.Section.KEY_TABLE_KEYS_OFF, it)
    }
    private val keyTableKeysLen = IntArray(moc.keyTables) {
        moc.intAt(CubismMoc3.Section.KEY_TABLE_KEYS_LEN, it)
    }
    private val keyTableIndex = IntArray(moc.keyTables)
    private val keyTableWeight = FloatArray(moc.keyTables)
    private val keyTableIdxDirty = BooleanArray(moc.keyTables) { true }
    private val keyTableWeightDirty = BooleanArray(moc.keyTables) { true }

    /** 参数落在全部 key 之外时置位；Core 会据此停用整棵子树。 */
    private val keyTableOutOfRange = BooleanArray(moc.keyTables)

    /* ----------------------------- bindings ----------------------------- */

    private val bindingTableOff = IntArray(moc.bindings) {
        moc.intAt(CubismMoc3.Section.BINDING_KEY_TABLE_IDX_OFF, it)
    }
    private val bindingTableLen = IntArray(moc.bindings) {
        moc.intAt(CubismMoc3.Section.BINDING_KEY_TABLE_IDX_LEN, it)
    }
    private val bindingKeyformIdx: Array<IntArray> = Array(moc.bindings) {
        IntArray(1 shl min(bindingTableLen[it], MAX_KEY_TABLES))
    }
    private val bindingWeights: Array<FloatArray> = Array(moc.bindings) {
        FloatArray(1 shl min(bindingTableLen[it], MAX_KEY_TABLES))
    }
    private val bindingBlendCount = IntArray(moc.bindings) { 1 }
    private val bindingOutOfRange = BooleanArray(moc.bindings) { true }

    /* ------------------------------- parts ------------------------------ */

    private val partBindingIdx = IntArray(partCount) {
        moc.intAt(CubismMoc3.Section.PART_BINDING_IDX, it)
    }
    private val partEnable = BooleanArray(partCount) {
        moc.intAt(CubismMoc3.Section.PART_ENABLE, it) != 0
    }
    private val partParent = IntArray(partCount) {
        moc.intAt(CubismMoc3.Section.PART_PARENT_PART_IDX, it)
    }
    private val partEnabled = BooleanArray(partCount)

    /** 部件不透明度：外部（pose3）可写，update 时沿父链相乘。 */
    val partOpacity = FloatArray(partCount) { 1f }

    private val partOpacityInput = FloatArray(partCount) { 1f }

    fun resetPartOpacityInput() {
        partOpacityInput.fill(1f)
    }

    fun setPartOpacityInput(index: Int, value: Float) {
        if (index in 0 until partCount) partOpacityInput[index] = value
    }

    /* ----------------------------- deformers ---------------------------- */

    private val deformerCount = moc.deformers
    private val deformerType = IntArray(deformerCount) {
        moc.intAt(CubismMoc3.Section.DEFORMER_TYPE, it)
    }
    private val deformerLocal = IntArray(deformerCount) {
        moc.intAt(CubismMoc3.Section.DEFORMER_LOCAL_IDX, it)
    }
    private val deformerParentDeformer = IntArray(deformerCount) {
        moc.intAt(CubismMoc3.Section.DEFORMER_PARENT_DEFORMER_IDX, it)
    }
    private val deformerParentPart = IntArray(deformerCount) {
        moc.intAt(CubismMoc3.Section.DEFORMER_PARENT_PART_IDX, it)
    }
    private val deformerLocalEnable = BooleanArray(deformerCount) {
        moc.intAt(CubismMoc3.Section.DEFORMER_ENABLE, it) != 0
    }
    private val deformerEnabled = BooleanArray(deformerCount)
    private val deformerOpacity = FloatArray(deformerCount) { 1f }
    private val deformerScale = FloatArray(deformerCount) { 1f }

    /* ------------------------------- warps ------------------------------ */

    private val warpCount = moc.warps
    private val warpBindingIdx = IntArray(warpCount) {
        moc.intAt(CubismMoc3.Section.WARP_BINDING_IDX, it)
    }
    private val warpKeyformOff = IntArray(warpCount) {
        moc.intAt(CubismMoc3.Section.WARP_KEYFORM_OFF, it)
    }
    private val warpVertexCount = IntArray(warpCount) {
        moc.intAt(CubismMoc3.Section.WARP_VERTEX_COUNT, it)
    }
    private val warpRow = IntArray(warpCount) { moc.intAt(CubismMoc3.Section.WARP_ROW, it) }
    private val warpCol = IntArray(warpCount) { moc.intAt(CubismMoc3.Section.WARP_COL, it) }
    private val warpQuadTransform = BooleanArray(warpCount) {
        moc.version >= CubismMoc3.VERSION_33 &&
            moc.intAt(CubismMoc3.Section.WARP_QUAD_TRANSFORM, it) != 0
    }
    private val warpOpacity = FloatArray(warpCount)
    private val warpPositions: Array<FloatArray> = Array(warpCount) {
        FloatArray(max(0, warpVertexCount[it]) * 2)
    }

    /* ----------------------------- rotations ---------------------------- */

    private val rotationCount = moc.rotations
    private val rotationBindingIdx = IntArray(rotationCount) {
        moc.intAt(CubismMoc3.Section.ROTATION_BINDING_IDX, it)
    }
    private val rotationKeyformOff = IntArray(rotationCount) {
        moc.intAt(CubismMoc3.Section.ROTATION_KEYFORM_OFF, it)
    }
    private val rotationBaseAngle = FloatArray(rotationCount) {
        moc.floatAt(CubismMoc3.Section.ROTATION_BASE_ANGLE, it)
    }
    private val rotationOpacity = FloatArray(rotationCount)
    private val rotationAngle = FloatArray(rotationCount)
    private val rotationOriginX = FloatArray(rotationCount)
    private val rotationOriginY = FloatArray(rotationCount)
    private val rotationScale = FloatArray(rotationCount) { 1f }
    private val rotationReflectX = BooleanArray(rotationCount)
    private val rotationReflectY = BooleanArray(rotationCount)

    /* ---------------------------- art meshes ---------------------------- */

    private val amBindingIdx = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_BINDING_IDX, it)
    }
    private val amKeyformOff = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_KEYFORM_OFF, it)
    }
    private val amEnable = BooleanArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_ENABLE, it) != 0
    }
    private val amParentPart = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_PARENT_PART_IDX, it)
    }
    private val amParentDeformer = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_PARENT_DEFORMER_IDX, it)
    }
    private val amTextureIndex = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_TEXTURE_NO, it)
    }
    private val amConstantFlags = IntArray(drawableCount) {
        moc.byteAt(CubismMoc3.Section.AM_DRAWABLE_FLAG, it)
    }
    private val amVertexCount = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_VERTEX_COUNT, it)
    }
    private val amUvOff = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_UV_OFF, it)
    }
    private val amIdxOff = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_IDX_OFF, it)
    }
    private val amIdxLen = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_IDX_LEN, it)
    }
    private val amMaskOff = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_MASK_OFF, it)
    }
    private val amMaskLen = IntArray(drawableCount) {
        moc.intAt(CubismMoc3.Section.AM_MASK_LEN, it)
    }

    private val amEnabled = BooleanArray(drawableCount)

    /** 最终 drawable 不透明度（已乘 deformer / part）。 */
    val drawableOpacity = FloatArray(drawableCount)

    private val amDrawOrder = FloatArray(drawableCount)
    private val amPositions: Array<FloatArray> = Array(drawableCount) {
        FloatArray(max(0, amVertexCount[it]) * 2)
    }

    /** 由 update() 刷新的绘制顺序（小 → 大）。 */
    val renderOrder = IntArray(drawableCount) { it }

    /* ----------------------------- geometry ----------------------------- */

    private val uvCache: Array<FloatArray> = Array(drawableCount) { d ->
        FloatArray(amVertexCount[d] * 2) { moc.floatAt(CubismMoc3.Section.UVS, amUvOff[d] + it) }
    }
    private val indexCache: Array<IntArray> = Array(drawableCount) { d ->
        IntArray(amIdxLen[d]) { moc.shortAt(CubismMoc3.Section.IDX, amIdxOff[d] + it) }
    }

    fun drawableUvs(index: Int): FloatArray = uvCache[index]
    fun drawableIndices(index: Int): IntArray = indexCache[index]
    fun drawableVertexCount(index: Int): Int = amVertexCount[index]
    fun drawableIndexCount(index: Int): Int = amIdxLen[index]
    fun drawableTextureIndex(index: Int): Int = amTextureIndex[index]
    fun drawableConstantFlags(index: Int): Int = amConstantFlags[index]
    fun drawablePositions(index: Int): FloatArray = amPositions[index]
    fun drawableParentPartIndex(index: Int): Int = amParentPart[index]
    fun drawableMaskCount(index: Int): Int = amMaskLen[index]

    fun drawableMask(index: Int, slot: Int): Int =
        moc.intAt(CubismMoc3.Section.MASKS, amMaskOff[index] + slot)

    fun drawableVisible(index: Int): Boolean = amEnabled[index] && drawableOpacity[index] > 0f

    fun drawableDrawOrder(index: Int): Float = amDrawOrder[index]

    /** 加法混合标志位。 */
    fun drawableAdditive(index: Int): Boolean = (amConstantFlags[index] and FLAG_ADDITIVE) != 0

    /** 乘法混合标志位。 */
    fun drawableMultiplicative(index: Int): Boolean = (amConstantFlags[index] and FLAG_MULTIPLICATIVE) != 0

    /* ------------------------------ bounds ------------------------------ */

    var boundsMinX = 0f
        private set
    var boundsMinY = 0f
        private set
    var boundsMaxX = 0f
        private set
    var boundsMaxY = 0f
        private set

    private val ppu = max(1f, moc.pixelsPerUnit)
    val canvasWidth: Float = moc.canvasWidth / ppu
    val canvasHeight: Float = moc.canvasHeight / ppu
    val originX: Float = moc.originX / ppu
    val originY: Float = moc.originY / ppu

    val bboxAspect: Float get() = canvasWidth / max(1f, canvasHeight)

    /* =============================== update =============================== */

    fun update() {
        resolveParameters()
        resolveKeyTables()
        resolveBindings()

        enableParts()
        enableDeformers()
        interpolateWarps()
        interpolateRotations()
        enableArtMeshes()
        interpolateArtMeshes()

        applyDeformerTransforms()
        applyTransformsToMeshes()
        applyPartOpacity()
        applyPartsToMeshes()

        if (!moc.yReversed) flipY()

        sortRenderOrder()
        refreshBounds()

        forceUpdate = false
    }

    /* ---------------------------- parameters ---------------------------- */

    private fun resolveParameters() {
        for (i in 0 until parameterCount) {
            val raw = parameterValues[i]
            val minValue = paramMin[i]
            val maxValue = paramMax[i]

            val value = if (paramRepeat[i]) {
                val length = maxValue - minValue
                if (length <= 0f) minValue
                else {
                    val normalized = (raw - minValue) / length
                    val wrapped = normalized - floor(normalized)
                    wrapped * length + minValue
                }
            } else {
                raw.coerceIn(minValue, maxValue)
            }

            // 首帧（forceUpdate）必须视为脏，否则 key table 永不求值。
            paramDirty[i] = forceUpdate || abs(value - paramResolved[i]) > 1e-7f
            paramResolved[i] = value
            parameterValues[i] = value
        }
    }

    /* ---------------------------- key tables ---------------------------- */

    private fun resolveKeyTables() {
        for (p in 0 until parameterCount) {
            val tableCount = paramKeyTableLen[p]
            if (tableCount <= 0) continue

            if (!paramDirty[p]) {
                for (j in 0 until tableCount) {
                    val kt = paramKeyTableOff[p] + j
                    if (kt in 0 until moc.keyTables) {
                        keyTableIdxDirty[kt] = false
                        keyTableWeightDirty[kt] = false
                    }
                }
                continue
            }

            val value = paramResolved[p]
            val snapEps = paramSnapEps[p]
            val interpEps = paramInterpEps[p]

            for (j in 0 until tableCount) {
                val kt = paramKeyTableOff[p] + j
                if (kt !in 0 until moc.keyTables) continue
                val keyCount = keyTableKeysLen[kt]
                if (keyCount <= 0) continue

                val segment = findKeySegment(value, keyTableKeysOff[kt], keyCount, snapEps, interpEps)

                val wasOutOfRange = keyTableOutOfRange[kt]
                var outOfRange = segment.outside

                val idxDirty: Boolean
                val weightDirty: Boolean
                if (!outOfRange && wasOutOfRange) {
                    // 从越界回到范围内：强制刷新。
                    idxDirty = true
                    weightDirty = true
                } else {
                    idxDirty = keyTableIndex[kt] != segment.index
                    weightDirty = keyTableWeight[kt] != segment.weight
                }

                keyTableIdxDirty[kt] = idxDirty
                keyTableWeightDirty[kt] = weightDirty
                keyTableIndex[kt] = segment.index
                keyTableWeight[kt] = segment.weight
                keyTableOutOfRange[kt] = outOfRange
            }
        }
    }

    private class KeySegment(val index: Int, val weight: Float, val outside: Boolean)

    private fun findKeySegment(
        value: Float,
        keysOff: Int,
        keyCount: Int,
        snapEps: Float,
        interpEps: Float,
    ): KeySegment {
        fun key(i: Int) = moc.floatAt(CubismMoc3.Section.KEYS, keysOff + i)

        if (keyCount == 1) {
            val k0 = key(0)
            return KeySegment(0, 0f, value <= k0 - snapEps || value >= k0 + snapEps)
        }

        var k0 = key(0)
        if (value < k0 - snapEps) return KeySegment(0, 0f, true)
        if (value < k0 + snapEps) return KeySegment(0, 0f, false)

        var k1 = key(1)
        if (value < k1 - snapEps) {
            val diff = k1 - k0
            return KeySegment(0, if (diff >= interpEps) (value - k0) / diff else 0f, false)
        }
        if (value < k1 + snapEps) return KeySegment(1, 0f, false)

        for (k in 2 until keyCount) {
            k0 = k1
            k1 = key(k)
            if (value < k1 - snapEps) {
                val diff = k1 - k0
                return KeySegment(k - 1, if (diff >= interpEps) (value - k0) / diff else 0f, false)
            }
            if (value < k1 + snapEps) return KeySegment(k, 0f, false)
        }

        return KeySegment(keyCount - 1, 0f, true)
    }

    /* ----------------------------- bindings ----------------------------- */

    private fun resolveBindings() {
        for (bi in 0 until moc.bindings) {
            val tableCount = bindingTableLen[bi]
            val off = bindingTableOff[bi]

            if (tableCount <= 0) {
                bindingBlendCount[bi] = 1
                bindingKeyformIdx[bi][0] = 0
                bindingWeights[bi][0] = 1f
                bindingOutOfRange[bi] = false
                continue
            }

            var idxDirty = false
            var weightDirty = false
            var outOfRange = false
            var activeCount = 0

            for (i in 0 until tableCount) {
                val kt = moc.intAt(CubismMoc3.Section.KEY_TABLE_IDX, off + i)
                if (kt !in 0 until moc.keyTables) {
                    outOfRange = true
                    break
                }
                if (keyTableOutOfRange[kt]) {
                    outOfRange = true
                    break
                }
                weightDirty = weightDirty || keyTableWeightDirty[kt]
                idxDirty = idxDirty || keyTableIdxDirty[kt]
                if (keyTableWeight[kt] != 0f) activeCount++
            }

            if (outOfRange) {
                // 任一 key table 越界 → 整个 binding 停用，对应的部件/网格被隐藏。
                bindingOutOfRange[bi] = true
                continue
            }

            if (!idxDirty && !weightDirty && !forceUpdate) {
                bindingOutOfRange[bi] = false
                continue
            }

            val blendCount = 1 shl min(activeCount, MAX_KEY_TABLES)
            bindingBlendCount[bi] = blendCount

            val keyformIdx = bindingKeyformIdx[bi]
            val weights = bindingWeights[bi]
            java.util.Arrays.fill(keyformIdx, 0, blendCount, 0)
            java.util.Arrays.fill(weights, 0, blendCount, 1f)

            var indexStride = 1
            var comboStride = 1

            for (i in 0 until tableCount) {
                val kt = moc.intAt(CubismMoc3.Section.KEY_TABLE_IDX, off + i)
                if (kt !in 0 until moc.keyTables) continue
                val index = keyTableIndex[kt]
                val keyCount = keyTableKeysLen[kt]
                val weight = keyTableWeight[kt]
                val indexOffset = index * indexStride

                if (weight != 0f) {
                    val nextIndexOffset = (index + 1) * indexStride
                    val invWeight = 1f - weight
                    for (j in 0 until blendCount) {
                        if (j and comboStride == 0) {
                            keyformIdx[j] += indexOffset
                            weights[j] *= invWeight
                        } else {
                            keyformIdx[j] += nextIndexOffset
                            weights[j] *= weight
                        }
                    }
                    comboStride *= 2
                } else {
                    for (j in 0 until blendCount) keyformIdx[j] += indexOffset
                }

                indexStride *= max(1, keyCount)
            }

            bindingOutOfRange[bi] = false
        }
    }

    /** binding 可用 = 下标有效且未越界。 */
    private fun bindingUsable(binding: Int): Boolean =
        binding in 0 until moc.bindings && !bindingOutOfRange[binding]

    /* --------------------------- interpolation -------------------------- */

    private fun interpolateScalar(binding: Int, keyformOff: Int, source: (Int) -> Float): Float {
        if (!bindingUsable(binding)) return source(keyformOff)
        val keyformIdx = bindingKeyformIdx[binding]
        val weights = bindingWeights[binding]
        val count = bindingBlendCount[binding]
        if (count <= 1) return source(keyformOff + keyformIdx[0])

        var sum = 0f
        for (j in 0 until count) {
            sum += source(keyformOff + keyformIdx[j]) * weights[j]
        }
        return sum
    }

    private fun interpolatePositions(
        binding: Int,
        keyformOff: Int,
        posOff: (Int) -> Int,
        out: FloatArray,
        elementCount: Int,
    ) {
        java.util.Arrays.fill(out, 0, elementCount * 2, 0f)
        if (!bindingUsable(binding)) {
            val base = posOff(keyformOff)
            for (k in 0 until elementCount * 2) {
                out[k] = moc.floatAt(CubismMoc3.Section.KEY_POS_XY, base + k)
            }
            return
        }
        val keyformIdx = bindingKeyformIdx[binding]
        val weights = bindingWeights[binding]
        val combos = bindingBlendCount[binding]

        for (j in 0 until combos) {
            val base = posOff(keyformOff + keyformIdx[j])
            val w = weights[j]
            if (w == 0f) continue
            for (k in 0 until elementCount * 2) {
                out[k] += moc.floatAt(CubismMoc3.Section.KEY_POS_XY, base + k) * w
            }
        }
    }

    /* ------------------------------- parts ------------------------------ */

    private fun enableParts() {
        for (i in 0 until partCount) {
            var en = partEnable[i]
            val parent = partParent[i]
            if (en && parent in 0 until partCount) en = partEnabled[parent]
            if (en) en = bindingUsable(partBindingIdx[i])
            partEnabled[i] = en
        }
    }

    private fun applyPartOpacity() {
        for (i in 0 until partCount) {
            if (!partEnabled[i]) {
                partOpacity[i] = 0f
                continue
            }
            var opacity = partOpacityInput[i]
            val parent = partParent[i]
            if (parent in 0 until partCount) opacity *= partOpacity[parent]
            partOpacity[i] = opacity
        }
    }

    private fun applyPartsToMeshes() {
        for (i in 0 until drawableCount) {
            if (!amEnabled[i]) continue
            val parent = amParentPart[i]
            if (parent in 0 until partCount) drawableOpacity[i] *= partOpacity[parent]
        }
    }

    /* ----------------------------- deformers ---------------------------- */

    private fun enableDeformers() {
        for (i in 0 until deformerCount) {
            var en = deformerLocalEnable[i]
            val parentPart = deformerParentPart[i]
            val parentDeformer = deformerParentDeformer[i]
            if (en && parentPart in 0 until partCount) en = partEnabled[parentPart]
            if (en && parentDeformer in 0 until deformerCount) en = deformerEnabled[parentDeformer]
            if (en) en = bindingUsable(deformerBindingIdx(i))
            deformerEnabled[i] = en
        }
    }

    private fun deformerBindingIdx(index: Int): Int {
        val local = deformerLocal[index]
        return when (deformerType[index]) {
            DEFORMER_WARP -> if (local in 0 until warpCount) warpBindingIdx[local] else -1
            DEFORMER_ROTATION -> if (local in 0 until rotationCount) rotationBindingIdx[local] else -1
            else -> -1
        }
    }

    private fun interpolateWarps() {
        for (i in 0 until warpCount) {
            warpOpacity[i] = interpolateScalar(warpBindingIdx[i], warpKeyformOff[i]) {
                moc.floatAt(CubismMoc3.Section.WARP_KEY_OPACITY, it)
            }
            interpolatePositions(
                binding = warpBindingIdx[i],
                keyformOff = warpKeyformOff[i],
                posOff = { moc.intAt(CubismMoc3.Section.WARP_KEY_POS_OFF, it) },
                out = warpPositions[i],
                elementCount = warpVertexCount[i],
            )
        }
    }

    private fun interpolateRotations() {
        for (i in 0 until rotationCount) {
            val binding = rotationBindingIdx[i]
            val off = rotationKeyformOff[i]

            rotationOpacity[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.ROT_KEY_OPACITY, it)
            }
            rotationAngle[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.ROT_KEY_ANGLE, it)
            }
            rotationOriginX[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.ROT_KEY_ORIGIN_X, it)
            }
            rotationOriginY[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.ROT_KEY_ORIGIN_Y, it)
            }
            rotationScale[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.ROT_KEY_SCALE, it)
            }

            val firstKeyform = off + if (binding in 0 until moc.bindings) bindingKeyformIdx[binding][0] else 0
            rotationReflectX[i] = moc.intAt(CubismMoc3.Section.ROT_KEY_REFLECT_X, firstKeyform) != 0
            rotationReflectY[i] = moc.intAt(CubismMoc3.Section.ROT_KEY_REFLECT_Y, firstKeyform) != 0
        }
    }

    private fun enableArtMeshes() {
        for (i in 0 until drawableCount) {
            var en = amEnable[i]
            val parentPart = amParentPart[i]
            val parentDeformer = amParentDeformer[i]
            if (en && parentPart in 0 until partCount) en = partEnabled[parentPart]
            if (en && parentDeformer in 0 until deformerCount) en = deformerEnabled[parentDeformer]
            if (en) en = bindingUsable(amBindingIdx[i])
            amEnabled[i] = en
        }
    }

    private fun interpolateArtMeshes() {
        for (i in 0 until drawableCount) {
            val binding = amBindingIdx[i]
            val off = amKeyformOff[i]

            drawableOpacity[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.AM_KEY_OPACITY, it)
            }
            amDrawOrder[i] = interpolateScalar(binding, off) {
                moc.floatAt(CubismMoc3.Section.AM_KEY_DRAW_ORDER, it)
            }
            interpolatePositions(
                binding = binding,
                keyformOff = off,
                posOff = { moc.intAt(CubismMoc3.Section.AM_KEY_POS_OFF, it) },
                out = amPositions[i],
                elementCount = amVertexCount[i],
            )
        }
    }

    /* ---------------------------- transforms ---------------------------- */

    private fun applyDeformerTransforms() {
        for (i in 0 until deformerCount) {
            if (!deformerEnabled[i]) continue
            when (deformerType[i]) {
                DEFORMER_WARP -> applyWarp(i)
                DEFORMER_ROTATION -> applyRotation(i)
            }
        }
    }

    private fun applyWarp(index: Int) {
        val local = deformerLocal[index]
        if (local !in 0 until warpCount) return
        val parent = deformerParentDeformer[index]
        val positions = warpPositions[local]
        val vertexCount = warpVertexCount[local]

        if (parent == -1) {
            deformerOpacity[index] = warpOpacity[local]
            deformerScale[index] = 1f
            return
        }
        if (parent in 0 until deformerCount) {
            transformPositions(parent, positions, vertexCount)
            deformerOpacity[index] = warpOpacity[local] * deformerOpacity[parent]
            deformerScale[index] = deformerScale[parent]
        }
    }

    private fun applyRotation(index: Int) {
        val local = deformerLocal[index]
        if (local !in 0 until rotationCount) return
        val parent = deformerParentDeformer[index]

        if (parent == -1) {
            deformerOpacity[index] = rotationOpacity[local]
            deformerScale[index] = rotationScale[local]
            return
        }
        if (parent !in 0 until deformerCount) return

        val originX = rotationOriginX[local]
        val originY = rotationOriginY[local]

        // 用父变换下局部 Y 轴的方向修正子旋转角，使子级保持贴合。
        val directionDelta = if (deformerType[parent] == DEFORMER_ROTATION) -10f else -0.1f

        val transformedOrigin = transformPoint(parent, originX, originY)

        var directionX = 0f
        var directionY = 0f
        var probe = 1f
        for (attempt in 0 until 16) {
            val plus = transformPoint(parent, originX, originY + probe * directionDelta)
            var dx = plus[0] - transformedOrigin[0]
            var dy = plus[1] - transformedOrigin[1]
            if (dx != 0f || dy != 0f) {
                directionX = dx
                directionY = dy
                break
            }
            val minus = transformPoint(parent, originX, originY - probe * directionDelta)
            dx = minus[0] - transformedOrigin[0]
            dy = minus[1] - transformedOrigin[1]
            if (dx != 0f || dy != 0f) {
                directionX = -dx
                directionY = -dy
                break
            }
            probe *= 0.1f
        }

        val angleAdjustment = signedAngleRadians(directionDelta, directionX, directionY) * -180f / PI_F

        rotationOriginX[local] = transformedOrigin[0]
        rotationOriginY[local] = transformedOrigin[1]
        rotationAngle[local] += angleAdjustment

        deformerOpacity[index] = rotationOpacity[local] * deformerOpacity[parent]

        val combinedScale = rotationScale[local] * deformerScale[parent]
        deformerScale[index] = combinedScale
        rotationScale[local] = combinedScale
    }

    private fun transformPoint(deformer: Int, x: Float, y: Float): FloatArray {
        val out = floatArrayOf(x, y)
        transformPositions(deformer, out, 1)
        return out
    }

    private fun transformPositions(deformer: Int, positions: FloatArray, count: Int) {
        if (deformer !in 0 until deformerCount || count <= 0) return
        val local = deformerLocal[deformer]
        when (deformerType[deformer]) {
            DEFORMER_WARP -> if (local in 0 until warpCount) warpTransform(local, positions, count)
            DEFORMER_ROTATION -> if (local in 0 until rotationCount) rotationTransform(local, positions, count)
        }
    }

    private fun rotationTransform(local: Int, positions: FloatArray, count: Int) {
        val angleRad = (rotationBaseAngle[local] + rotationAngle[local]) * PI_F / 180f
        val sinA = sin(angleRad)
        val cosA = cos(angleRad)

        val reflectX = if (rotationReflectX[local]) -1f else 1f
        val reflectY = if (rotationReflectY[local]) -1f else 1f

        val scale = rotationScale[local]
        val m00 = scale * cosA * reflectX
        val m01 = scale * -sinA * reflectY
        val m10 = scale * sinA * reflectX
        val m11 = scale * cosA * reflectY

        val originX = rotationOriginX[local]
        val originY = rotationOriginY[local]

        for (i in 0 until count) {
            val px = positions[i * 2]
            val py = positions[i * 2 + 1]
            positions[i * 2] = originX + m00 * px + m01 * py
            positions[i * 2 + 1] = originY + m10 * px + m11 * py
        }
    }

    /**
     * 网格变形：输入是 [0,1] 归一化坐标，输出是变形后网格点。
     * 单元索引夹取到边界、保留越界的小数部分，从而在网格外线性外推。
     */
    private fun warpTransform(local: Int, positions: FloatArray, count: Int) {
        val row = warpRow[local]
        val col = warpCol[local]
        val grid = warpPositions[local]
        if (row <= 0 || col <= 0) return
        if (grid.size < (row + 1) * (col + 1) * 2) return

        val stride = col + 1
        val rowF = row.toFloat()
        val colF = col.toFloat()
        val quad = warpQuadTransform[local]

        for (i in 0 until count) {
            val u = positions[i * 2]
            val v = positions[i * 2 + 1]
            if (!u.isFinite() || !v.isFinite()) continue

            val cu = floor(u * colF).toInt().coerceIn(0, col - 1)
            val cv = floor(v * rowF).toInt().coerceIn(0, row - 1)
            val fu = u * colF - cu
            val fv = v * rowF - cv

            val bi = cv * stride + cu
            val p00x = grid[bi * 2]
            val p00y = grid[bi * 2 + 1]
            val p10x = grid[(bi + 1) * 2]
            val p10y = grid[(bi + 1) * 2 + 1]
            val p01x = grid[(bi + stride) * 2]
            val p01y = grid[(bi + stride) * 2 + 1]
            val p11x = grid[(bi + stride + 1) * 2]
            val p11y = grid[(bi + stride + 1) * 2 + 1]

            if (quad) {
                val x0 = lerp(p00x, p10x, fu)
                val y0 = lerp(p00y, p10y, fu)
                val x1 = lerp(p01x, p11x, fu)
                val y1 = lerp(p01y, p11y, fu)
                positions[i * 2] = lerp(x0, x1, fv)
                positions[i * 2 + 1] = lerp(y0, y1, fv)
            } else {
                val wx: Float
                val wy: Float
                if (fu + fv <= 1f) {
                    val w00 = 1f - fu - fv
                    wx = w00 * p00x + fu * p10x + fv * p01x
                    wy = w00 * p00y + fu * p10y + fv * p01y
                } else {
                    val w10 = 1f - fv
                    val w11 = fu + fv - 1f
                    val w01 = 1f - fu
                    wx = w10 * p10x + w11 * p11x + w01 * p01x
                    wy = w10 * p10y + w11 * p11y + w01 * p01y
                }
                positions[i * 2] = wx
                positions[i * 2 + 1] = wy
            }
        }
    }

    private fun applyTransformsToMeshes() {
        for (i in 0 until drawableCount) {
            if (!amEnabled[i]) continue
            val parent = amParentDeformer[i]
            if (parent == -1) continue
            drawableOpacity[i] *= deformerOpacity[parent]
            transformPositions(parent, amPositions[i], amVertexCount[i])
        }
    }

    private fun flipY() {
        for (i in 0 until drawableCount) {
            if (!amEnabled[i]) continue
            val positions = amPositions[i]
            var k = 1
            while (k < positions.size) {
                positions[k] = -positions[k]
                k += 2
            }
        }
    }

    private fun sortRenderOrder() {
        val order = renderOrder
        for (i in order.indices) order[i] = i
        for (i in 1 until order.size) {
            val current = order[i]
            val key = amDrawOrder[current]
            var j = i - 1
            while (j >= 0 && amDrawOrder[order[j]] > key) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = current
        }
    }

    private fun refreshBounds() {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (i in 0 until drawableCount) {
            if (!amEnabled[i] || drawableOpacity[i] <= 0f) continue
            val positions = amPositions[i]
            var k = 0
            while (k + 1 < positions.size) {
                val x = positions[k]
                val y = positions[k + 1]
                if (x.isFinite() && y.isFinite()) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                k += 2
            }
        }

        if (minX == Float.POSITIVE_INFINITY) {
            minX = originX - canvasWidth * 0.5f
            maxX = originX + canvasWidth * 0.5f
            minY = originY - canvasHeight * 0.5f
            maxY = originY + canvasHeight * 0.5f
        }

        boundsMinX = minX
        boundsMinY = minY
        boundsMaxX = maxX
        boundsMaxY = maxY
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = t * (b - a) + a

    /**
     * 与 Core 的 psm__signed_angle 完全一致：
     * atan2(v1.y, v1.x) - atan2(v2.y, v2.x)，再对 2π 取余。
     */
    private fun signedAngleRadians(v1y: Float, v2x: Float, v2y: Float): Float {
        val a1 = atan2(v1y, 0f)
        val a2 = atan2(v2y, v2x)
        val diff = a1 - a2
        val twoPi = (PI_F * 2f)
        return diff - (diff / twoPi).roundToInt() * twoPi
    }

    private companion object {
        const val DEFORMER_WARP = 0
        const val DEFORMER_ROTATION = 1
        const val MAX_KEY_TABLES = 16

        const val FLAG_ADDITIVE = 1
        const val FLAG_MULTIPLICATIVE = 2

        const val PI_F = Math.PI.toFloat()
    }
}