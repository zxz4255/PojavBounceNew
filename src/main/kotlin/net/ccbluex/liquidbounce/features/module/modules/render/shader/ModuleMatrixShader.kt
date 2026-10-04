package net.ccbluex.liquidbounce.features.module.modules.render.shader

import net.ccbluex.liquidbounce.config.types.group.ValueGroup

object ModuleMatrixShader : FullscreenShaderModule(
    name = "MatrixShader",
    description = "Matrix-style fullscreen world shader ported from Invincible Machine Gun.",
    defaultVertexPath = "shaders/matrix.vsh",
    defaultFragmentPath = "shaders/matrix.fsh",
) {

    private val general = ValueGroup("General").also(::tree)

    private val gridSpeed by general.float("GridSpeed", 2.0f, 0.0f..10.0f)
    private val sunSpeed by general.float("SunSpeed", 1.0f, 0.0f..5.0f)
    private val scanSpeed by general.float("ScanSpeed", 1.0f, 0.1f..5.0f)
    private val loopScan by general.boolean("LoopScan", false)
    private val scanDuration by general.float("ScanDuration", 3.0f, 1.0f..10.0f)

    override fun shaderSettings() = ShaderSettings(
        gridSpeed = gridSpeed,
        sunSpeed = sunSpeed,
        scanSpeed = scanSpeed,
        loopEnabled = if (loopScan) 1f else 0f,
        scanDuration = scanDuration,
        skyEnabled = 1f,
        groundEnabled = 1f,
    )
}
