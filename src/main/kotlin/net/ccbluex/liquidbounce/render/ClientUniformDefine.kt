package net.ccbluex.liquidbounce.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderPass
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.render.ClientRenderPipelines.withBindGroupLayout
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.render.std140Size
import net.minecraft.client.renderer.MappableRingBuffer
import java.util.function.Supplier

enum class ClientUniformDefine(val uboName: String, val size: Int) {
    DISTANCE_FADE("u_DistanceFade", std140Size { vec4 }),
    MESH_BASE_BLOCK_POS("u_MeshBaseBlockPos", std140Size { ivec3 }),
    ROUNDED_RECT("u_RoundedRect", std140Size { vec2 + float }),
    HAND_ITEM_LIGHTMAP("ItemChamsData", std140Size { int + float + vec4 + float + vec4 + float + int }),
    CHAMS("ChamsData", std140Size { vec2 + vec2 }),
    GUI_BLUR("BlurData", std140Size { float + float }),
    GUI_BLUR_KERNEL("BlurKernelData", std140Size { repeat(23) { vec4 } + int }),
    BLEND("BlendData", std140Size { vec4 }),
    THEME_BACKGROUND("ThemeBackgroundData", std140Size { float + vec2 + vec2 }),
    FULLSCREEN_SHADER("FullscreenShaderData", 192),
    ;

    val bindGroupLayout: BindGroupLayout = BindGroupLayout.builder().apply(this::appendTo).build()

    fun appendTo(builder: BindGroupLayout.Builder) = builder.withUniform(this.uboName, UniformType.UNIFORM_BUFFER)
    fun label(): String = "${LiquidBounce.CLIENT_NAME} Uniform ${this.uboName} (${this.size}b)"

    @JvmOverloads
    fun createSingleBuffer(labelGetter: Supplier<String> = Supplier(this::label)): GpuBufferSlice =
        gpuDevice.createBuffer(labelGetter, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, this.size.toLong()).slice()

    @JvmOverloads
    fun createRingBuffer(labelGetter: Supplier<String> = Supplier(this::label)): MappableRingBuffer =
        MappableRingBuffer(labelGetter, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, this.size)

    fun setTo(renderPass: RenderPass, slice: GpuBufferSlice) = renderPass.setUniform(this.uboName, slice)
}
