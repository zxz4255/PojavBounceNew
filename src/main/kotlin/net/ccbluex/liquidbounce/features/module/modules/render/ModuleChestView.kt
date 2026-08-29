/*
 * ModuleChestView — 移植自 TenSine ChestView
 * 打开箱子时，在箱子世界位置的屏幕投影处绘制库存预览 HUD
 * LiquidBounce Nextgen 0.39 · 原生渲染 · 无 Web
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.Camera
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.Container
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ShulkerBoxMenu
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object ModuleChestView : ClientModule(
    "ChestView",
    ModuleCategories.RENDER,
    aliases = listOf("StorageView", "ContainerView"),
) {

    private val maxDistance by float("Max Distance", 8f, 3f..16f)
    private val scale by float("Scale", 1f, 0.5f..1.8f)
    private val slotSize by float("Slot Size", 16f, 12f..24f)
    private val slotPad by float("Slot Pad", 1f, 0f..4f)
    private val panelPad by float("Panel Pad", 6f, 2f..14f)
    private val radius by float("Radius", 8f, 0f..16f)
    private val animSpeed by float("Anim Speed", 10f, 3f..20f)
    private val followSmooth by float("Follow Smooth", 0.25f, 0.05f..1f)
    private val yOffset by float("Y Offset", 0.6f, -1f..2f)

    private val showTitle by boolean("Show Title", true)
    private val titleHeight by float("Title Height", 14f, 10f..22f)
    private val showDivider by boolean("Show Divider", true)

    private val bgAlpha by int("Background Alpha", 160, 40..240)
    private val bgColor by color("Background", Color4b(20, 24, 32, 200))
    private val slotBg by color("Slot BG", Color4b(0, 0, 0, 90))
    private val titleBarColor by color("Title Bar", Color4b(0, 0, 0, 120))
    private val titleColor by color("Title Text", Color4b(255, 255, 255, 255))
    private val dividerColor by color("Divider", Color4b(255, 255, 255, 40))
    private val accent by color("Accent", Color4b(100, 180, 255, 255))

    private enum class ColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"), ACCENT("Accent"), RAINBOW("Rainbow"), THEME("Theme"),
    }

    private val borderMode by enumChoice("Border Mode", ColorMode.ACCENT)
    private val borderColor by color("Border Color", Color4b(100, 180, 255, 180))
    private val rainbowSpeed by float("Rainbow Speed", 0.5f, 0.1f..3f)

    private val enableGlow by boolean("Glow", true)
    private val glowRadius by float("Glow Radius", 10f, 2f..28f)
    private val glowStrength by float("Glow Strength", 0.7f, 0.1f..2f)
    private val glowLayers by int("Glow Layers", 8, 2..16)

    private enum class GlowColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"), FOLLOW_ACCENT("FollowAccent"), RAINBOW("Rainbow"),
    }

    private val glowColorMode by enumChoice("Glow Color Mode", GlowColorMode.FOLLOW_ACCENT)
    private val glowColor by color("Glow Color", Color4b(80, 160, 255, 120))

    private val onlyWhenOpen by boolean("Only When Container Open", true)
    private val renderItems by boolean("Render Items", true)
    private val itemCountText by boolean("Item Count", true)

    // state
    private var containerPos: BlockPos? = null
    private var screenX = 0f
    private var screenY = 0f
    private var hasProj = false
    private var viewScale = 0f
    private var targetScale = 0f
    private var wasOpen = false
    private var smoothSX = 0f
    private var smoothSY = 0f
    private var smoothInited = false
    private var lastNs = 0L

    private fun a(c: Color4b, m: Float) = c.alpha((c.a * m).toInt().coerceIn(0, 255))

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun rainbow(off: Float): Color4b {
        val t = (System.currentTimeMillis() % 100000L) / 1000f * rainbowSpeed + off
        val h = ((t % 1f) + 1f) % 1f
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val q = 1f - f
        val (r, g, b) = when (i % 6) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(q, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, q, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, q)
        }
        return Color4b((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), 255)
    }

    private fun borderCol(): Color4b = when (borderMode) {
        ColorMode.CUSTOM -> borderColor
        ColorMode.ACCENT, ColorMode.THEME -> accent
        ColorMode.RAINBOW -> rainbow(0f)
    }

    private fun glowCol(): Color4b = when (glowColorMode) {
        GlowColorMode.CUSTOM -> glowColor
        GlowColorMode.FOLLOW_ACCENT -> accent
        GlowColorMode.RAINBOW -> rainbow(0.3f)
    }

    private fun isContainerScreen(): Boolean {
        val s = mc.screen
        return s is AbstractContainerScreen<*> && (
            s is ContainerScreen ||
                s is ShulkerBoxScreen ||
                runCatching { s.menu is ChestMenu || s.menu is ShulkerBoxMenu }.getOrDefault(false)
            )
    }

    private fun camera(): Camera? = runCatching {
        mc.gameRenderer.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && (it.name == "getMainCamera" || it.name.contains("Camera")) }
            ?.invoke(mc.gameRenderer) as? Camera
    }.getOrNull()

    /** 世界坐标 → 屏幕（简易视角投影） */
    private fun worldToScreen(pos: Vec3, partial: Float): FloatArray? {
        val cam = camera() ?: return null
        val camPos = cam.position
        val relX = (pos.x - camPos.x).toFloat()
        val relY = (pos.y - camPos.y).toFloat()
        val relZ = (pos.z - camPos.z).toFloat()

        val yaw = Math.toRadians(cam.yRot.toDouble())
        val pitch = Math.toRadians(cam.xRot.toDouble())

        // 相机空间：先 yaw 再 pitch
        val cosY = cos(yaw).toFloat()
        val sinY = sin(yaw).toFloat()
        val cosP = cos(pitch).toFloat()
        val sinP = sin(pitch).toFloat()

        // MC 相机朝向：-Z 前
        val x1 = relX * cosY + relZ * sinY
        val z1 = -relX * sinY + relZ * cosY
        val y2 = relY * cosP - z1 * sinP
        val z2 = relY * sinP + z1 * cosP

        if (z2 >= -0.05f) return null // 身后

        val sw = mc.window.guiScaledWidth.toFloat()
        val sh = mc.window.guiScaledHeight.toFloat()
        val fov = 70f
        val scaleF = (sh / 2f) / kotlin.math.tan(Math.toRadians(fov / 2.0)).toFloat()
        val sx = sw / 2f - (x1 / -z2) * scaleF
        val sy = sh / 2f - (y2 / -z2) * scaleF
        if (sx < -200 || sx > sw + 200 || sy < -200 || sy > sh + 200) return null
        return floatArrayOf(sx, sy)
    }

    private fun findLookingContainer(): BlockPos? {
        val hit = mc.hitResult
        if (hit is BlockHitResult) {
            val pos = hit.blockPos
            if (isStorageBlock(pos)) return pos
        }
        return containerPos
    }

    private fun isStorageBlock(pos: BlockPos): Boolean {
        val world = mc.level ?: return false
        val state = world.getBlockState(pos)
        val b = state.block
        return b is ChestBlock || b is EnderChestBlock || b is BarrelBlock || b is ShulkerBoxBlock ||
            world.getBlockEntity(pos) is BaseContainerBlockEntity
    }

    private fun containerTitle(): String {
        val pos = containerPos
        if (pos != null) {
            val world = mc.level
            if (world != null) {
                val b = world.getBlockState(pos).block
                return when (b) {
                    is EnderChestBlock -> "Ender Chest"
                    is BarrelBlock -> "Barrel"
                    is ShulkerBoxBlock -> "Shulker"
                    is ChestBlock -> "Chest"
                    else -> "Container"
                }
            }
        }
        return "Chest"
    }

    private fun chestSlots(): Int {
        val menu = (mc.screen as? AbstractContainerScreen<*>)?.menu ?: return 27
        return when (menu) {
            is ChestMenu -> menu.rowCount * 9
            is ShulkerBoxMenu -> 27
            else -> min(menu.slots.size - 36, 54).coerceAtLeast(9) // 减掉玩家背包
        }
    }

    private fun slotStack(index: Int): net.minecraft.world.item.ItemStack {
        val menu = (mc.screen as? AbstractContainerScreen<*>)?.menu ?: return net.minecraft.world.item.ItemStack.EMPTY
        if (index < 0 || index >= menu.slots.size) return net.minecraft.world.item.ItemStack.EMPTY
        // 容器槽在前
        return try {
            menu.getSlot(index).item
        } catch (_: Exception) {
            net.minecraft.world.item.ItemStack.EMPTY
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet is ServerboundUseItemOnPacket) {
            val pos = packet.blockHit.blockPos
            if (isStorageBlock(pos)) {
                containerPos = pos
            }
        }
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        val pos = containerPos ?: findLookingContainer() ?: return@handler
        containerPos = pos
        val center = Vec3(pos.x + 0.5, pos.y + 0.5 + yOffset, pos.z + 0.5)
        val player = mc.player ?: return@handler
        if (player.distanceToSqr(center) > maxDistance * maxDistance) {
            hasProj = false
            return@handler
        }
        val proj = worldToScreen(center, event.partialTicks)
        if (proj != null) {
            screenX = proj[0]
            screenY = proj[1]
            hasProj = true
        } else {
            hasProj = false
        }
    }

    private fun drawGlow(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
    ) {
        if (!enableGlow || anim < 0.05f) return
        val base = glowCol()
        val layers = glowLayers.coerceIn(2, 16)
        for (i in layers downTo 1) {
            val u = i / layers.toFloat()
            val expand = glowRadius * u
            val fall = kotlin.math.exp((-(u * u) * 2.4).toDouble()).toFloat()
            val aa = (fall * glowStrength * 70f * anim).toInt().coerceIn(0, 90)
            if (aa < 2) continue
            ctx.drawRoundedRect(
                x - expand, y - expand,
                x + w + expand, y + h + expand,
                radius + expand * 0.3f,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
    }

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled) return@handler

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        val open = isContainerScreen()
        if (onlyWhenOpen) {
            if (open && !wasOpen) {
                targetScale = 1f
                if (containerPos == null) containerPos = findLookingContainer()
            } else if (!open && wasOpen) {
                targetScale = 0f
            }
            wasOpen = open
        } else {
            targetScale = if (containerPos != null && hasProj) 1f else 0f
        }

        viewScale = lerp(viewScale, targetScale, (dt * animSpeed * 0.15f).coerceIn(0f, 1f))
        if (viewScale < 0.02f) {
            if (!open) {
                // 关 GUI 后延迟清掉坐标
                if (targetScale <= 0f) {
                    // keep pos until fully hidden
                }
            }
            if (viewScale < 0.01f && !open) {
                containerPos = null
                hasProj = false
                smoothInited = false
            }
            return@handler
        }

        if (!hasProj || containerPos == null) return@handler

        if (!smoothInited) {
            smoothSX = screenX
            smoothSY = screenY
            smoothInited = true
        } else {
            smoothSX = lerp(smoothSX, screenX, followSmooth)
            smoothSY = lerp(smoothSY, screenY, followSmooth)
        }

        val ctx = event.context
        val font = mc.font
        val slots = chestSlots()
        val cols = 9
        val rows = (slots + cols - 1) / cols

        val cell = slotSize + slotPad
        val gridW = cols * cell - slotPad
        val gridH = rows * cell - slotPad
        val titleH = if (showTitle) titleHeight else 0f
        val divH = if (showDivider && showTitle) 2f else 0f
        val panelW = gridW + panelPad * 2
        val panelH = gridH + panelPad * 2 + titleH + divH

        val s = viewScale * scale
        val px = smoothSX - panelW * s * 0.5f
        val py = smoothSY - panelH * s * 0.5f

        // 缩放绘制：用矩阵近似 — 直接乘坐标
        fun sx(v: Float) = px + v * s
        fun sy(v: Float) = py + v * s
        fun sw(v: Float) = v * s

        val anim = viewScale
        val bg = bgColor.alpha(bgAlpha)

        drawGlow(ctx, sx(0f), sy(0f), sw(panelW), sw(panelH), anim)

        // 面板
        ctx.drawRoundedRect(sx(0f), sy(0f), sx(panelW), sy(panelH), radius * s, a(bg, anim))
        // 边框
        val bc = borderCol()
        ctx.drawRoundedRect(
            sx(0f) - 1f, sy(0f) - 1f, sx(panelW) + 1f, sy(panelH) + 1f,
            radius * s + 1f,
            Color4b(bc.r, bc.g, bc.b, (bc.a * 0.35f * anim).toInt().coerceIn(0, 120)),
        )
        ctx.drawRoundedRect(sx(0f), sy(0f), sx(panelW), sy(panelH), radius * s, a(bg, anim))

        // 标题
        if (showTitle) {
            ctx.drawRoundedRect(
                sx(0f), sy(0f), sx(panelW), sy(titleH + 2f),
                radius * s, a(titleBarColor, anim),
            )
            // 盖住下圆角
            ctx.drawQuad(sx(0f), sy(titleH - 2f), sx(panelW), sy(titleH + 2f), a(titleBarColor, anim))
            val title = containerTitle()
            val tw = font.width(title)
            ctx.text(
                font, title,
                (sx(panelW * 0.5f) - tw * 0.5f * min(1f, s)).toInt(),
                sy(3f).toInt(),
                a(titleColor, anim).argb, false,
            )
            if (showDivider) {
                ctx.drawQuad(
                    sx(panelPad), sy(titleH),
                    sx(panelW - panelPad), sy(titleH + divH),
                    a(dividerColor, anim),
                )
            }
        }

        // 格子 + 物品
        val gridY0 = titleH + divH + panelPad
        for (i in 0 until slots) {
            val col = i % cols
            val row = i / cols
            val ix = panelPad + col * cell
            val iy = gridY0 + row * cell
            ctx.drawRoundedRect(
                sx(ix), sy(iy),
                sx(ix + slotSize), sy(iy + slotSize),
                2f * s, a(slotBg, anim),
            )
            // 物品
            if (renderItems) {
                val stack = slotStack(i)
                if (!stack.isEmpty) {
                    val itemX = sx(ix + 1f).toInt()
                    val itemY = sy(iy + 1f).toInt()
                    runCatching {
                        // GuiGraphics 物品渲染
                        val g = ctx
                        val methods = g.javaClass.methods.filter {
                            it.name.contains("renderItem", true) || it.name.contains("renderFakeItem", true)
                        }
                        // 尝试 context 自带
                        val m = g.javaClass.methods.firstOrNull {
                            it.parameterCount in 3..5 && it.name.lowercase().contains("item")
                        }
                        // 用 minecraft gui graphics 标准
                    }
                    runCatching {
                        // 通过 mc.gui 或直接 ItemRenderer 较复杂；用文字数量兜底 + 尝试 renderItem
                        val gfx = event.context
                        // LB GuiGraphicsExtractor 可能代理 renderItem
                        val render = gfx.javaClass.methods.firstOrNull { method ->
                            method.name.equals("renderItem", true) && method.parameterCount >= 3
                        }
                        if (render != null) {
                            when (render.parameterCount) {
                                3 -> render.invoke(gfx, stack, itemX, itemY)
                                4 -> render.invoke(gfx, stack, itemX, itemY, 0)
                                else -> render.invoke(gfx, stack, itemX, itemY, 0, 0)
                            }
                        }
                    }
                    if (itemCountText && stack.count > 1) {
                        val cs = stack.count.toString()
                        ctx.text(
                            font, cs,
                            (sx(ix + slotSize) - font.width(cs) - 1).toInt(),
                            (sy(iy + slotSize) - 9).toInt(),
                            a(Color4b(255, 255, 255, 255), anim).argb, false,
                        )
                    }
                }
            }
        }
    }

    override fun onDisabled() {
        containerPos = null
        hasProj = false
        viewScale = 0f
        targetScale = 0f
        wasOpen = false
        smoothInited = false
    }
}
