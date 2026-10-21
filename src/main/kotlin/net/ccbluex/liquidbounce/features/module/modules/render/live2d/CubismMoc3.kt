/*
 * CubismMoc3 —— MOC3 二进制容器解析（纯 Kotlin，无 native）。
 *
 * 布局（与 Cubism Core / moc3_ref.py 对齐）：
 *   0x00  "MOC3"
 *   0x04  version (u8: 1=v3.0 2=v3.3 3=v4.0 4=v4.2 5=v5.0 6=v5.3)
 *   0x05  endian  (u8: 0=little 1=big)
 *   0x08  canvas info 前 8 字节（ppu/origin 之外的保留区，直接跳过）
 *   0x40  section offset 表：u32 × (160 或 480)
 *   之后 各 section 负载
 *
 * 所有 section 都是绝对文件偏移；字符串以 64 字节块内联存放。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/CubismMoc3.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import java.nio.ByteBuffer
import java.nio.ByteOrder

class CubismMoc3 private constructor(
    val data: ByteArray,
    val version: Int,
    val bigEndian: Boolean,
) {

    private val buffer: ByteBuffer = ByteBuffer.wrap(data).order(
        if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    )

    val sectionCount: Int = if (version >= VERSION_53) 480 else 160

    private val offsets: IntArray = IntArray(sectionCount) {
        buffer.getInt(HEADER_SIZE + it * 4)
    }

    fun offset(section: Int): Int = if (section in 0 until sectionCount) offsets[section] else 0

    /* --------------------------- typed readers --------------------------- */

    fun int(offset: Int): Int =
        if (offset < 0 || offset + 4 > data.size) 0 else buffer.getInt(offset)

    fun float(offset: Int): Float =
        if (offset < 0 || offset + 4 > data.size) 0f else buffer.getFloat(offset)

    fun short(offset: Int): Int =
        if (offset < 0 || offset + 2 > data.size) 0 else buffer.getShort(offset).toInt() and 0xFFFF

    fun byte(offset: Int): Int =
        if (offset < 0 || offset >= data.size) 0 else data[offset].toInt() and 0xFF

    /** 64 字节内联 id，读到 NUL 为止。 */
    fun id(offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var end = offset
        val limit = minOf(data.size, offset + ID_BLOCK_SIZE)
        while (end < limit && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }

    fun intAt(section: Int, index: Int): Int = int(offset(section) + index * 4)
    fun floatAt(section: Int, index: Int): Float = float(offset(section) + index * 4)
    fun shortAt(section: Int, index: Int): Int = short(offset(section) + index * 2)
    fun byteAt(section: Int, index: Int): Int = byte(offset(section) + index)
    fun idAt(section: Int, index: Int): String = id(offset(section) + index * ID_BLOCK_SIZE)

    /* ------------------------------ counts ------------------------------ */

    val countInfo: IntArray by lazy {
        val base = offset(Section.COUNT_INFO)
        val n = if (version >= VERSION_50) 64 else 32
        IntArray(n) { int(base + it * 4) }
    }

    fun count(index: Int): Int = countInfo.getOrElse(index) { 0 }

    val parts: Int get() = count(0)
    val deformers: Int get() = count(1)
    val warps: Int get() = count(2)
    val rotations: Int get() = count(3)
    val artMeshes: Int get() = count(4)
    val parameters: Int get() = count(5)
    val partKeyforms: Int get() = count(6)
    val warpKeyforms: Int get() = count(7)
    val rotationKeyforms: Int get() = count(8)
    val artMeshKeyforms: Int get() = count(9)
    val keyformPositions: Int get() = count(10)
    val keyTableIndices: Int get() = count(11)
    val bindings: Int get() = count(12)
    val keyTables: Int get() = count(13)
    val keys: Int get() = count(14)
    val uvs: Int get() = count(15)
    val indices: Int get() = count(16)
    val masks: Int get() = count(17)

    /* ------------------------------ canvas ------------------------------ */

    val pixelsPerUnit: Float by lazy { float(offset(Section.CANVAS_INFO)) }
    val originX: Float by lazy { float(offset(Section.CANVAS_INFO) + 4) }
    val originY: Float by lazy { float(offset(Section.CANVAS_INFO) + 8) }
    val canvasWidth: Float by lazy { float(offset(Section.CANVAS_INFO) + 12) }
    val canvasHeight: Float by lazy { float(offset(Section.CANVAS_INFO) + 16) }
    val canvasFlag: Int by lazy { byte(offset(Section.CANVAS_INFO) + 20) }

    /** 文件里的顶点 Y 轴已朝下时为 true。 */
    val yReversed: Boolean get() = (canvasFlag and CANVAS_FLAG_Y_REVERSED) != 0

    /** MOC3 section 索引，顺序即文件顺序。 */
    internal object Section {
        const val COUNT_INFO = 0
        const val CANVAS_INFO = 1

        const val PART_ID = 3
        const val PART_BINDING_IDX = 4
        const val PART_KEYFORM_OFF = 5
        const val PART_KEY_LEN = 6
        const val PART_VISIBLE = 7
        const val PART_ENABLE = 8
        const val PART_PARENT_PART_IDX = 9

        const val DEFORMER_ID = 11
        const val DEFORMER_BINDING_IDX = 12
        const val DEFORMER_VISIBLE = 13
        const val DEFORMER_ENABLE = 14
        const val DEFORMER_PARENT_PART_IDX = 15
        const val DEFORMER_PARENT_DEFORMER_IDX = 16
        const val DEFORMER_TYPE = 17
        const val DEFORMER_LOCAL_IDX = 18

        const val WARP_BINDING_IDX = 19
        const val WARP_KEYFORM_OFF = 20
        const val WARP_KEY_LEN = 21
        const val WARP_VERTEX_COUNT = 22
        const val WARP_ROW = 23
        const val WARP_COL = 24

        const val ROTATION_BINDING_IDX = 25
        const val ROTATION_KEYFORM_OFF = 26
        const val ROTATION_KEY_LEN = 27
        const val ROTATION_BASE_ANGLE = 28

        const val AM_ID = 33
        const val AM_BINDING_IDX = 34
        const val AM_KEYFORM_OFF = 35
        const val AM_KEY_LEN = 36
        const val AM_VISIBLE = 37
        const val AM_ENABLE = 38
        const val AM_PARENT_PART_IDX = 39
        const val AM_PARENT_DEFORMER_IDX = 40
        const val AM_TEXTURE_NO = 41
        const val AM_DRAWABLE_FLAG = 42
        const val AM_VERTEX_COUNT = 43
        const val AM_UV_OFF = 44
        const val AM_IDX_OFF = 45
        const val AM_IDX_LEN = 46
        const val AM_MASK_OFF = 47
        const val AM_MASK_LEN = 48

        const val PARAM_ID = 50
        const val PARAM_MAX = 51
        const val PARAM_MIN = 52
        const val PARAM_DEFAULT = 53
        const val PARAM_REPEAT = 54
        const val PARAM_DECIMAL_PLACES = 55
        const val PARAM_KEY_TABLE_OFF = 56
        const val PARAM_KEY_TABLE_LEN = 57

        const val PART_KEY_DRAW_ORDER = 58
        const val WARP_KEY_OPACITY = 59
        const val WARP_KEY_POS_OFF = 60
        const val ROT_KEY_OPACITY = 61
        const val ROT_KEY_ANGLE = 62
        const val ROT_KEY_ORIGIN_X = 63
        const val ROT_KEY_ORIGIN_Y = 64
        const val ROT_KEY_SCALE = 65
        const val ROT_KEY_REFLECT_X = 66
        const val ROT_KEY_REFLECT_Y = 67
        const val AM_KEY_OPACITY = 68
        const val AM_KEY_DRAW_ORDER = 69
        const val AM_KEY_POS_OFF = 70

        const val KEY_POS_XY = 71
        const val KEY_TABLE_IDX = 72
        const val BINDING_KEY_TABLE_IDX_OFF = 73
        const val BINDING_KEY_TABLE_IDX_LEN = 74
        const val KEY_TABLE_KEYS_OFF = 75
        const val KEY_TABLE_KEYS_LEN = 76
        const val KEYS = 77
        const val UVS = 78
        const val IDX = 79
        const val MASKS = 80

        /** v3.3+：warp 变形使用双线性（四边形）插值。 */
        const val WARP_QUAD_TRANSFORM = 101
    }

    companion object {
        const val HEADER_SIZE = 64
        const val ID_BLOCK_SIZE = 64

        const val VERSION_30 = 1
        const val VERSION_33 = 2
        const val VERSION_40 = 3
        const val VERSION_42 = 4
        const val VERSION_50 = 5
        const val VERSION_53 = 6

        private const val CANVAS_FLAG_Y_REVERSED = 1

        private val MAGIC = byteArrayOf(
            'M'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), '3'.code.toByte(),
        )

        fun parse(bytes: ByteArray): CubismMoc3 {
            require(bytes.size >= HEADER_SIZE) { "MOC3 文件过小（${bytes.size} 字节）" }
            for (i in MAGIC.indices) {
                require(bytes[i] == MAGIC[i]) { "不是 MOC3 文件：magic 不匹配" }
            }

            val version = bytes[4].toInt() and 0xFF
            require(version in VERSION_30..VERSION_53) { "不支持的 MOC3 版本 $version" }

            val endian = bytes[5].toInt() and 0xFF
            require(endian == 0 || endian == 1) { "无效的 MOC3 字节序标记 $endian" }

            val moc = CubismMoc3(bytes, version, endian == 1)
            require(bytes.size >= HEADER_SIZE + moc.sectionCount * 4) { "MOC3 section 表被截断" }
            return moc
        }
    }
}