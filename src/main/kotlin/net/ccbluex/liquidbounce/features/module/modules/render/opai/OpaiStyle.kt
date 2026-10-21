/*
 * OpaiStyle —— Samsara OpaiStyle 的 1:1 移植（调色板数值逐字保留）。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/opai/OpaiStyle.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.opai

object OpaiStyle {

    data class Palette(
        val header: Int,
        val body: Int,
        val enabled: Int,
        val enabledHover: Int,
        val accent: Int,
        val enabledText: Int,
        val text: Int,
        val hover: Int,
        val field: Int,
        val fieldLine: Int,
        val track: Int,
        val scrollbar: Int,
        val selected: Int,
        val toggleOutline: Int,
        val tick: Int,
        val hudProgress: Int,
        val hudKnob: Int,
    )

    val LAVENDER = Palette(
        0xFF1C1F29.toInt(), 0xCC151315.toInt(), 0xFFA8B0E7.toInt(), 0xFFB0B8EF.toInt(), 0xFFBBC3FF.toInt(),
        0xFF172778.toInt(), 0xFFE4E1E6.toInt(), 0x0CE4E1E6, 0xFF2D2D38.toInt(), 0xFFC4C0CC.toInt(),
        0xFF484858.toInt(), 0xCC636472.toInt(), 0xFF414253.toInt(), 0xFF938F99.toInt(), 0xFF626786.toInt(),
        0xFF9CA5DA.toInt(), 0xFF5B5F7C.toInt(),
    )

    val LIGHT_PINK = Palette(
        0xFF161110.toInt(), 0xCC1A1413.toInt(), 0xFFDF9D95.toInt(), 0xFFE5A59D.toInt(), 0xFFFFB4AA.toInt(),
        0xFF5F150F.toInt(), 0xFFEDE0DE.toInt(), 0x0CEDE0DE, 0xFF2E2625.toInt(), 0xFFCDB7B4.toInt(),
        0xFF56413E.toInt(), 0xCC69534D.toInt(), 0xFF483A37.toInt(), 0xFF9E8D8B.toInt(), 0xFF85645D.toInt(),
        0xFFD19997.toInt(), 0xFF7C5752.toInt(),
    )

    const val HEADER_TEXT_SIZE = 9.2f
    const val ROW_TEXT_SIZE = 8.3f
    const val TEXT_WEIGHT_OFFSET = 0.10f

    fun palette(name: String): Palette = if ("Light Pink" == name) LIGHT_PINK else LAVENDER
}