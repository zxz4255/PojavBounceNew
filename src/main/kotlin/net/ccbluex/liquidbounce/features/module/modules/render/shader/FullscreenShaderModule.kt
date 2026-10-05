package net.ccbluex.liquidbounce.features.module.modules.render.shader

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.events.ResourceReloadEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientShaders
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.kotlin.optional
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.renderer.BindGroupLayouts
import org.joml.Matrix4f
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.isRegularFile
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.renderer.MappableRingBuffer

/**
 * Minecraft 26.2 fullscreen shader base.
 *
 * The effect is rendered into a private intermediate RenderTarget, sampling the
 * current scene color/depth, then copied back with a local vanilla core/blit_screen pipeline.
 */
abstract class FullscreenShaderModule(
    name: String,
    description: String,
    defaultVertexPath: String,
    defaultFragmentPath: String,
) : ClientModule(name, ModuleCategories.RENDER) {

    private val vertexShaderPath by text("VertexShaderPath", defaultVertexPath)
    private val fragmentShaderPath by text("FragmentShaderPath", defaultFragmentPath)

    protected data class ShaderSettings(
        val gridSpeed: Float = 2f,
        val sunSpeed: Float = 1f,
        val scanSpeed: Float = 1f,
        val loopEnabled: Float = 0f,
        val scanDuration: Float = 3f,
        val skyEnabled: Float = 1f,
        val groundEnabled: Float = 1f,
    )

    protected open fun shaderSettings() = ShaderSettings()

    private var fragmentIdentifier: Identifier? = null
    private var vertexIdentifier: Identifier? = null
    private var shaderPipeline: RenderPipeline? = null
    private var blitPipeline: RenderPipeline? = null
    private var uniformBuffer: MappableRingBuffer? = null
    private var initialized = false
    private var cleanupPending = false
    private var activationNanos = 0L

    private val intermediateTarget = LazyRenderTargetHolder(
        "$name Fullscreen Shader Intermediate",
        useDepth = false,
    )

    private val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)

    private val reloadHandler = handler<ResourceReloadEvent> {
        cleanupPending = true
        initialized = false
        shaderPipeline = null
        blitPipeline = null
        fragmentIdentifier = null
        vertexIdentifier = null
    }

    private val renderHandler = handler<WorldRenderEvent>(priority = -200) {
        if (!enabled) {
            if (cleanupPending) cleanupGpuObjects()
            return@handler
        }

        if (cleanupPending) {
            cleanupGpuObjects()
            cleanupPending = false
        }

        if (!initialized) {
            runCatching {
                initializePipelines()
                uniformBuffer = ClientUniformDefine.FULLSCREEN_SHADER.createRingBuffer()
                initialized = true
            }.onFailure { throwable ->
                logger.error("Failed to initialize fullscreen shader '$name'", throwable)
                initialized = false
                cleanupPending = true
                enabled = false
            }

            if (!initialized) return@handler
        }

        runCatching {
            renderFullscreen(it.renderTarget)
        }.onFailure { throwable ->
            logger.error("Failed to render fullscreen shader '$name'", throwable)
            cleanupPending = true
            enabled = false
        }
    }

    override fun onEnabled() {
        activationNanos = System.nanoTime()
        cleanupPending = false
        initialized = false
    }

    override fun onDisabled() {
        cleanupPending = true
    }

    private fun initializePipelines() {
        RenderSystem.assertOnRenderThread()

        val vertexSource = loadShaderSource(vertexShaderPath)
        val fragmentSource = loadShaderSource(fragmentShaderPath)

        val shaderKey = buildString {
            append(name.lowercase().replace(Regex("[^a-z0-9_-]"), "_"))
            append('_')
            append(Integer.toUnsignedString(vertexShaderPath.hashCode()))
            append('_')
            append(Integer.toUnsignedString(fragmentShaderPath.hashCode()))
        }

        vertexIdentifier = ClientShaders.Vertex.registerSource(shaderKey, vertexSource)
        fragmentIdentifier = ClientShaders.Fragment.registerSource(shaderKey, fragmentSource)

        val shaderLayout = BindGroupLayout.builder()
            .withSampler("MainColorSampler")
            .withSampler("MainDepthSampler")
            .withUniform(
                ClientUniformDefine.FULLSCREEN_SHADER.uboName,
                com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER,
            )
            .build()

        shaderPipeline = RenderPipeline.Builder()
            .withLocation(LiquidBounce.identifier("pipeline/fullscreen/$shaderKey"))
            .withVertexShader(vertexIdentifier!!)
            .withFragmentShader(fragmentIdentifier!!)
            .withBindGroupLayout(shaderLayout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(optional())
            .build()
            .also { pipeline ->
                gpuDevice.precompilePipeline(pipeline, ClientShaders)
            }

        val blitLayout = BindGroupLayouts.IN_SAMPLER
        blitPipeline = RenderPipeline.Builder()
            .withLocation(LiquidBounce.identifier("pipeline/fullscreen_blit/$shaderKey"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(blitLayout)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(optional())
            .build()
            .also { pipeline ->
                // core/screenquad and core/blit_screen belong to Minecraft's default shader source.
            // Do not precompile this pipeline through ClientShaders, which only contains
            // LiquidBounce-registered shader sources.
            gpuDevice.precompilePipeline(pipeline, null)
            }
    }

    private fun renderFullscreen(target: RenderTarget) {
        RenderSystem.assertOnRenderThread()

        val mainColor = target.colorTextureView ?: return
        val mainDepth = target.depthTextureView ?: return
        val intermediate = intermediateTarget.initAndGet(target.width, target.height)
        val intermediateColor = intermediate.colorTextureView ?: return
        val pipeline = shaderPipeline ?: return
        val blit = blitPipeline ?: return
        val uniform = uploadUniforms(target.width, target.height)

        intermediate.createRenderPass(
            labelGetter = { "$name Shader Pass" },
            clearColor = Optional.empty(),
            clearDepth = java.util.OptionalDouble.empty(),
            useDepthAttachment = false,
        ).use { pass ->
            pass.setPipeline(pipeline)
            pass.bindTexture("MainColorSampler", mainColor, sampler)
            pass.bindTexture("MainDepthSampler", mainDepth, sampler)
            pass.setUniform(ClientUniformDefine.FULLSCREEN_SHADER.uboName, uniform)
            pass.draw(3, 1, 0, 0)
        }

        target.createRenderPass(
            labelGetter = { "$name Composite Pass" },
            clearColor = Optional.empty(),
            clearDepth = java.util.OptionalDouble.empty(),
            useDepthAttachment = false,
        ).use { pass ->
            pass.setPipeline(blit)
            pass.bindTexture("InSampler", intermediateColor, sampler)
            pass.draw(3, 1, 0, 0)
        }
    }

    private fun uploadUniforms(width: Int, height: Int): com.mojang.blaze3d.buffers.GpuBufferSlice {
        val buffer = uniformBuffer ?: error("Fullscreen shader uniform buffer is not initialized")
        buffer.rotate()
        val slice = buffer.currentBuffer().slice()

        val elapsedSeconds = (System.nanoTime() - activationNanos).toFloat() / 1_000_000_000f
        val settings = shaderSettings()
        val camera = mc.gameRenderer.mainCamera()
        val cameraPos = camera.position()

        val viewProjection = camera.getViewRotationProjectionMatrix(Matrix4f())
        val view = camera.getViewRotationMatrix(Matrix4f())
        val projection = viewProjection.mul(view.invert(Matrix4f()))
        val inverseProjection = projection.invert(Matrix4f())
        val inverseView = view.invert(Matrix4f())

        slice.writeStd140 {
            putVec4(
                elapsedSeconds,
                width.toFloat(),
                height.toFloat(),
                0f,
            )
            putVec4(
                settings.gridSpeed,
                settings.sunSpeed,
                settings.scanSpeed,
                settings.loopEnabled,
            )
            putVec4(
                settings.scanDuration,
                settings.skyEnabled,
                settings.groundEnabled,
                if (gpuDevice.deviceInfo.isZZeroToOne) 1f else 0f,
            )
            putVec4(
                cameraPos.x.toFloat(),
                cameraPos.y.toFloat(),
                cameraPos.z.toFloat(),
                0f,
            )
            putMat4f(inverseProjection)
            putMat4f(inverseView)
        }

        return slice
    }

    private fun cleanupGpuObjects() {
        if (!RenderSystem.isOnRenderThread()) return

        uniformBuffer?.close()
        uniformBuffer = null
        intermediateTarget.close()
    }

    private fun loadShaderSource(path: String): String {
        val normalized = path.trim()
        if (normalized.isEmpty()) error("Shader path is empty for $name")

        val filePath = Path.of(normalized.removePrefix("file:"))
        if (filePath.isRegularFile()) {
            return Files.readString(filePath)
        }

        val resourcePath = normalized
            .removePrefix("classpath:")
            .removePrefix("/")
            .removePrefix("resources/liquidbounce/")

        return runCatching {
            LiquidBounce.resourceToString(resourcePath)
        }.getOrElse {
            val devPath = Path.of(mc.gameDirectory.toString(), normalized)
            if (devPath.isRegularFile()) {
                Files.readString(devPath)
            } else {
                throw IllegalArgumentException(
                    "Shader file not found: $normalized. Use an absolute file path or " +
                        "a LiquidBounce resource path under resources/liquidbounce/.",
                    it,
                )
            }
        }
    }
}
