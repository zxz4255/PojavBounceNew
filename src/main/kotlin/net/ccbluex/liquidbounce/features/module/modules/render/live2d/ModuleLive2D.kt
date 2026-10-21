/*
 * ModuleLive2D —— LiquidBounce 模块入口。
 *
 * 设置项与原版 MinecraftLive2d / Samsara 对齐；位置、大小、模型、表情、事件映射
 * 存在 <gamedir>/LiquidBounce/live2d.json（Live2DConfig）。
 *
 * 模型目录：<gamedir>/config/live2d/<模型名>/，模型名通过扫描得到，不写死。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/ModuleLive2D.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.ChatScreen

object ModuleLive2D : ClientModule(
    "Live2D",
    ModuleCategories.RENDER,
    state = false,
) {

    /* ------------------------------ 设置 ------------------------------ */

    private val modelName by text("Model", "")

    private val size by int("Size", 260, 40..800, "px")
    private val posX by int("PosX", 16, -2000..4000, "px")
    private val posY by int("PosY", 110, -2000..4000, "px")

    private val swayStrength by float("SwayStrength", 3.0f, 0.0f..10.0f)
    private val smoothing by float("Smoothing", 0.1f, 0.02f..0.6f)
    private val followPitch by boolean("FollowPitch", true)
    private val blink by boolean("Blink", true)
    private val expressionsEnabled by boolean("Expressions", true)
    private val masks by boolean("Masks", true)
    private val idleMotion by boolean("IdleMotion", true)

    private var hud: Live2DHudRenderer? = null

    /** 拖拽/滚轮只在聊天框内生效，避免和正常操作抢输入。 */
    private var chatOpen: Boolean = false
    private var draggingModel: Boolean = false
    private var lastTickMouseX: Double = 0.0
    private var lastTickMouseY: Double = 0.0

    /* ---------------------------- 生命周期 ---------------------------- */

    override fun onEnabled() {
        if (hud == null) {
            val config = Live2DConfig.load()

            // 模块设置覆盖持久配置。
            config.size = size
            config.posX = posX
            config.posY = posY
            config.swayStrength = swayStrength
            config.smoothing = smoothing
            config.followPitch = followPitch
            config.blinkEnabled = blink
            config.expressionsEnabled = expressionsEnabled
            config.masksEnabled = masks
            config.autoIdleMotion = idleMotion
            if (modelName.isNotBlank()) config.model = modelName

            hud = Live2DHudRenderer(config, Live2DConfig.defaultPath)
        }

        val available = hud?.getAvailableModels() ?: emptyList()
        if (available.isEmpty()) {
            logger.warn("Live2D：{} 下未找到 *.model3.json", Live2DConfig.defaultPath.parent)
        } else {
            logger.info("Live2D：发现 {} 个模型：{}", available.size, available.joinToString(", "))
        }
        super.onEnabled()
    }

    override fun onDisabled() {
        hud?.close()
        hud = null
        draggingModel = false
        super.onDisabled()
    }

    /* ------------------------------ 事件 ------------------------------ */

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        hud?.render(event.context, event.tickDelta)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        hud?.handleTick()
        // 拖拽：用 tick 采样鼠标位移，避免依赖 UI 事件里不保证存在的坐标。
        if (chatOpen && draggingModel) {
            val renderer = hud
            if (renderer != null) {
                val mouseX = scaledMouseX()
                val mouseY = scaledMouseY()
                val dx = (mouseX - lastTickMouseX).toInt()
                val dy = (mouseY - lastTickMouseY).toInt()
                if (dx != 0 || dy != 0) renderer.movePosition(dx, dy)
                lastTickMouseX = mouseX
                lastTickMouseY = mouseY
            }
        }
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        chatOpen = event.screen is ChatScreen
        if (!chatOpen) draggingModel = false
    }

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        if (!chatOpen || !event.isLeftButton) {
            draggingModel = false
            return@handler
        }
        if (event.isPressed) {
            val renderer = hud ?: return@handler
            val bounds = renderer.elementBounds()
            val mouseX = scaledMouseX().toInt()
            val mouseY = scaledMouseY().toInt()
            draggingModel = bounds.containsPoint(mouseX, mouseY)
            if (draggingModel) {
                lastTickMouseX = scaledMouseX()
                lastTickMouseY = scaledMouseY()
            }
        } else {
            draggingModel = false
        }
    }

    @Suppress("unused")
    private val mouseScrollHandler = handler<MouseScrollEvent> { event ->
        if (!chatOpen) return@handler
        val renderer = hud ?: return@handler
        val bounds = renderer.elementBounds()
        if (!bounds.containsPoint(scaledMouseX().toInt(), scaledMouseY().toInt())) return@handler
        val config = renderer.getConfig()
        renderer.setSize(config.size + (event.vertical * config.wheelResizeStep).toInt())
    }

    /** MouseHandler 里存的是窗口坐标，换算成 GUI 坐标。 */
    private val mouseHandler: MouseHandler
        get() = mc.mouseHandler

    private fun scaledMouseX(): Double =
        MouseHandler.getScaledXPos(mc.window, mouseHandler.xpos())

    private fun scaledMouseY(): Double =
        MouseHandler.getScaledYPos(mc.window, mouseHandler.ypos())

    /* ---------------------------- 对外接口 ---------------------------- */

    fun cycleModel() = hud?.cycleModel()

    fun setModel(name: String) = hud?.setModel(name)

    fun cycleExpression() = hud?.cycleExpression()

    fun toggleHud() = hud?.toggleEnabled()

    fun status(): String = hud?.getStatus() ?: "Live2D 未启用"

    fun renderer(): Live2DHudRenderer? = hud

    fun availableModels(): List<String> = hud?.getAvailableModels() ?: emptyList()

    fun availableExpressions(): List<String> = hud?.getAvailableExpressions() ?: emptyList()

    fun availableMotions(): List<String> = hud?.getAvailableMotions() ?: emptyList()

    fun currentModelName(): String = hud?.getLoadedModelName() ?: ""

    fun triggerEvent(event: String) = hud?.getEventSystem()?.fire(event)
}