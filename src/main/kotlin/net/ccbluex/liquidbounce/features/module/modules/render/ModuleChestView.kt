/*
 * ModuleChestView — 打开容器时在屏幕上显示库存预览（TenSine 风格移植）
 * LiquidBounce Nextgen 0.39 · 原生渲染 · 无 Web
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object ModuleChestView : ClientModule(
    "ChestView",
    ModuleCategories.RENDER,
    aliases = listOf("StorageView", "ContainerView"),
) {

    private val maxDistance by float("Max Distance", 10f, 3f..20f)
    private val scale by float("Scale", 1f, 0.5f..1.8f)
    private val slotSize by float("Slot Size", 16f, 12f..24f)
    private val slotPad by float("Slot Pad", 1f, 0f..4f)
    private val panelPad by float("Panel Pad", 6f, 2f..14f)
    private val radius by float("Radius", 8f, 0f..16f)
    private val animSpeed by float("Anim Speed", 12f, 3f..24f)
    private val yOffset by float("Y Offset", 0.55f, -1f..2.5f)

    private val screenAnchor by enumChoice("Screen Anchor", Anchor.PROJECTED)

    private enum class Anchor(override val tag: String) : Tagged {
        PROJECTED("Projected"),
        CENTER("Center"),
        RIGHT("Right"),
        LEFT("Left"),
    }

    private val showTitle by boolean("Show Title", true)
    private val titleHeight by float("Title Height", 14f, 10f..22f)
    private val showDivider by boolean("Show Divider", true)
    private val alwaysTryFind by boolean("Auto Find Chest", true)

    private val bgAlpha by int("Background Alpha", 170, 40..240)
    private val bgColor by color("Background", Color4b(20, 24, 32, 200))
    private val slotBg by color("Slot BG", Color4b(0, 0, 0, 100))
    private val titleBarColor by color("Title Bar", Color4b(0, 0, 0, 130))
    private val titleColor by color("Title Text", Color4b(255, 255, 255, 255))
    private val dividerColor by color("Divider", Color4b(255, 255, 255, 45))
    private val accent by color("Accent", Color4b(100, 180, 255, 255))

    private enum class ColorMode(override val tag: String) : Tagged {
        CUSTOM("Custom"), ACCENT("Accent"), RAINBOW("Rainbow"),
    }

    private val borderMode by enumChoice("Border Mode", ColorMode.ACCENT)
    private val borderColor by color("Border Color", Color4b(100, 180, 255, 180))
    private val rainbowSpeed by float("Rainbow Speed", 0.5f, 0.1f..3f)

    private val enableGlow by boolean("Glow", true)
    private val glowRadius by float("Glow Radius", 10f, 2f..28f)
    private val glowStrength by float("Glow Strength", 0.75f, 0.1f..2f)
    private val glowLayers by int("Glow Layers", 8, 2..16)

    private val renderItems by boolean("Render Items", true)
    private val itemCountText by boolean("Item Count", true)
    private val showEmptySlots by boolean("Show EmptySlots", true)

    private var containerPos: BlockPos? = null
    private var viewScale = 0f
    private var targetScale = 0f
    private var wasOpen = false
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
        ColorMode.ACCENT -> accent
        ColorMode.RAINBOW -> rainbow(0f)
    }

    // —— Screen 访问（兼容 LB 封装）——
    private fun clientScreen(): Any? {
        runCatching {
            val gui = mc.gui
            gui.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name.equals("screen", true) || it.name.equals("getScreen", true))
            }?.invoke(gui)?.let { return it }
        }
        runCatching {
            mc.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "screen" || it.name == "getScreen")
            }?.invoke(mc)?.let { return it }
        }
        return null
    }

    private fun isContainerOpen(): Boolean {
        val s = clientScreen() ?: return false
        // 任意 AbstractContainerScreen，且不是创造栏/玩家背包纯界面时尽量显示
        if (s is AbstractContainerScreen<*>) {
            val name = s.javaClass.simpleName.lowercase()
            if (name.contains("inventory") && !name.contains("chest") && !name.contains("container") &&
                !name.contains("shulker") && !name.contains("hopper") && !name.contains("barrel")
            ) {
                // 普通背包也可能误判；若菜单槽很多仍可能是箱子
                val slots = runCatching { s.menu.slots.size }.getOrDefault(0)
                return slots > 45 // 大箱子 90，小箱子 63
            }
            return true
        }
        // 反射兜底
        val n = s.javaClass.name.lowercase()
        return n.contains("chest") || n.contains("container") || n.contains("shulker") ||
            n.contains("hopper") || n.contains("barrel") || n.contains("dispenser")
    }

    private fun menuOrNull(): AbstractContainerMenu? {
        val s = clientScreen() as? AbstractContainerScreen<*> ?: return null
        return s.menu
    }

    private fun isStorageBlock(pos: BlockPos): Boolean {
        val world = mc.level ?: return false
        return try {
            val state = world.getBlockState(pos)
            val b = state.block
            b is ChestBlock || b is EnderChestBlock || b is BarrelBlock || b is ShulkerBoxBlock ||
                world.getBlockEntity(pos) is BaseContainerBlockEntity
        } catch (_: Exception) {
            false
        }
    }

    private fun findNearbyChest(): BlockPos? {
        val player = mc.player ?: return null
        val world = mc.level ?: return null
        val eye = player.eyePosition
        val range = maxDistance.toInt().coerceIn(3, 16)
        var best: BlockPos? = null
        var bestDist = Double.MAX_VALUE
        val origin = player.blockPosition()
        for (dx in -range..range) {
            for (dy in -2..3) {
                for (dz in -range..range) {
                    val pos = origin.offset(dx, dy, dz)
                    if (!isStorageBlock(pos)) continue
                    val d = eye.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    if (d < bestDist && d <= maxDistance * maxDistance) {
                        bestDist = d
                        best = pos
                    }
                }
            }
        }
        return best
    }

    private fun findLookingChest(): BlockPos? {
        val hit = mc.hitResult
        if (hit is BlockHitResult && isStorageBlock(hit.blockPos)) return hit.blockPos
        // 射线再扫
        val player = mc.player ?: return null
        val world = mc.level ?: return null
        val eye = player.eyePosition
        val look = player.getViewVector(1f)
        for (i in 1..20) {
            val p = eye.add(look.scale(i * 0.5))
            val pos = BlockPos.containing(p)
            if (isStorageBlock(pos)) return pos
        }
        return null
    }

    private fun ensureContainerPos() {
        if (containerPos != null && isStorageBlock(containerPos!!)) return
        containerPos = findLookingChest() ?: if (alwaysTryFind) findNearbyChest() else null
    }

    private fun containerTitle(): String {
        val pos = containerPos ?: return "Container"
        val world = mc.level ?: return "Container"
        return try {
            when (val b = world.getBlockState(pos).block) {
                is EnderChestBlock -> "Ender Chest"
                is BarrelBlock -> "Barrel"
                is ShulkerBoxBlock -> "Shulker"
                is ChestBlock -> "Chest"
                else -> b.name.string.take(16).ifBlank { "Container" }
            }
        } catch (_: Exception) {
            "Container"
        }
    }

    /** 容器槽数量（去掉玩家背包 36 格） */
    private fun chestSlotCount(): Int {
        val menu = menuOrNull() ?: return 27
        val total = menu.slots.size
        return when {
            total >= 90 -> 54 // 大箱
            total >= 63 -> 27 // 小箱
            total > 36 -> total - 36
            else -> total.coerceAtMost(54)
        }
    }

    private fun slotStack(index: Int): ItemStack {
        val menu = menuOrNull() ?: return ItemStack.EMPTY
        return try {
            if (index in menu.slots.indices) menu.getSlot(index).item else ItemStack.EMPTY
        } catch (_: Exception) {
            ItemStack.EMPTY
        }
    }

    private fun worldToScreen(world: Vec3): FloatArray? {
        val player = mc.player ?: return null
        val eye = player.eyePosition
        val relX = (world.x - eye.x).toFloat()
        val relY = (world.y - eye.y).toFloat()
        val relZ = (world.z - eye.z).toFloat()

        val yaw = Math.toRadians(player.yRot.toDouble())
        val pitch = Math.toRadians(player.xRot.toDouble())
        val cosY = cos(yaw).toFloat()
        val sinY = sin(yaw).toFloat()
        val cosP = cos(pitch).toFloat()
        val sinP = sin(pitch).toFloat()

        val x1 = relX * cosY + relZ * sinY
        val z1 = -relX * sinY + relZ * cosY
        val y2 = relY * cosP - z1 * sinP
        val z2 = relY * sinP + z1 * cosP
        if (z2 >= -0.01f) return null

        val sw = mc.window.guiScaledWidth.toFloat()
        val sh = mc.window.guiScaledHeight.toFloat()
        val scaleF = (sh / 2f) / kotlin.math.tan(Math.toRadians(35.0)).toFloat()
        val sx = sw / 2f - (x1 / -z2) * scaleF
        val sy = sh / 2f - (y2 / -z2) * scaleF
        return floatArrayOf(sx, sy)
    }

    private fun panelAnchor(sw: Float, sh: Float, panelW: Float, panelH: Float): FloatArray {
        when (screenAnchor) {
            Anchor.CENTER -> return floatArrayOf((sw - panelW) / 2f, (sh - panelH) / 2f - 20f)
            Anchor.RIGHT -> return floatArrayOf(sw - panelW - 12f, (sh - panelH) / 2f)
            Anchor.LEFT -> return floatArrayOf(12f, (sh - panelH) / 2f)
            Anchor.PROJECTED -> {
                val pos = containerPos
                if (pos != null) {
                    val center = Vec3(pos.x + 0.5, pos.y + 0.5 + yOffset, pos.z + 0.5)
                    val proj = worldToScreen(center)
                    if (proj != null) {
                        return floatArrayOf(proj[0] - panelW / 2f, proj[1] - panelH / 2f)
                    }
                }
                return floatArrayOf((sw - panelW) / 2f, (sh - panelH) / 2f - 20f)
            }
        }
    }

    private fun extractBlockPos(packet: Packet<*>): BlockPos? {
        // ServerboundUseItemOnPacket / 各种交互包
        runCatching {
            for (m in packet.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val n = m.name.lowercase()
                if (n.contains("hit") || n.contains("blockpos") || n.contains("location") || n == "getpos") {
                    when (val r = m.invoke(packet)) {
                        is BlockHitResult -> return r.blockPos
                        is BlockPos -> return r
                    }
                }
            }
        }
        runCatching {
            for (f in packet.javaClass.declaredFields) {
                f.isAccessible = true
                when (val r = f.get(packet)) {
                    is BlockHitResult -> return r.blockPos
                    is BlockPos -> return r
                }
            }
        }
        return null
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (!enabled) return@handler
        // 仅处理发出的交互包
        val packet = event.packet
        val name = packet.javaClass.simpleName
        if (!name.contains("UseItemOn", true) &&
            !name.contains("PlayerInteract", true) &&
            !name.contains("BlockPlacement", true) &&
            !name.contains("UseBlock", true)
        ) {
            // 仍尝试解析
            if (!name.contains("Serverbound") && !name.contains("CPacket") && !name.contains("C0")) return@handler
        }
        val pos = extractBlockPos(packet) ?: return@handler
        if (isStorageBlock(pos)) containerPos = pos
    }

    private fun drawGlow(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        anim: Float,
    ) {
        if (!enableGlow || anim < 0.05f) return
        val base = if (borderMode == ColorMode.RAINBOW) rainbow(0.2f) else accent
        for (i in glowLayers downTo 1) {
            val u = i / glowLayers.toFloat()
            val expand = glowRadius * u
            val fall = kotlin.math.exp((-(u * u) * 2.5).toDouble()).toFloat()
            val aa = (fall * glowStrength * 65f * anim).toInt().coerceIn(0, 85)
            if (aa < 2) continue
            ctx.drawRoundedRect(
                x - expand, y - expand, x + w + expand, y + h + expand,
                radius + expand * 0.3f,
                Color4b(base.r, base.g, base.b, aa),
            )
        }
    }

    private fun tryRenderItem(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (stack.isEmpty) return
        runCatching {
            for (m in ctx.javaClass.methods) {
                if (!m.name.lowercase().contains("item")) continue
                if (m.parameterCount !in 3..5) continue
                try {
                    when (m.parameterCount) {
                        3 -> {
                            m.invoke(ctx, stack, x, y); return
                        }
                        4 -> {
                            m.invoke(ctx, stack, x, y, 0); return
                        }
                        5 -> {
                            m.invoke(ctx, stack, x, y, 0, 0); return
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        // 文字回退：显示物品名首词
        runCatching {
            val label = stack.hoverName.string.take(3)
            ctx.text(mc.font, label, x, y + 4, 0xFFFFFFFF.toInt(), false)
        }
    }

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (!enabled) return@handler

        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        val open = isContainerOpen()
        if (open) {
            ensureContainerPos()
            targetScale = 1f
            wasOpen = true
        } else {
            if (wasOpen) targetScale = 0f
            wasOpen = false
        }

        viewScale = lerp(viewScale, targetScale, (dt * animSpeed * 0.12f).coerceIn(0f, 1f))
        if (viewScale < 0.02f) {
            if (!open) containerPos = null
            return@handler
        }

        // 无容器菜单时不画（避免空面板）
        if (!open && viewScale < 0.5f) return@handler
        val slots = if (open) chestSlotCount() else 27
        if (open && menuOrNull() == null) return@handler

        val ctx = event.context
        val font = mc.font
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()

        val cols = 9
        val rows = max(1, (slots + cols - 1) / cols)
        val cell = slotSize + slotPad
        val gridW = cols * cell - slotPad
        val gridH = rows * cell - slotPad
        val titleH = if (showTitle) titleHeight else 0f
        val divH = if (showDivider && showTitle) 2f else 0f
        val panelW = (gridW + panelPad * 2) * scale
        val panelH = (gridH + panelPad * 2 + titleH + divH) * scale

        val anim = viewScale
        val anchor = panelAnchor(sw, sh, panelW, panelH)
        val px = anchor[0]
        val py = anchor[1]

        val bg = bgColor.alpha(bgAlpha)
        drawGlow(ctx, px, py, panelW, panelH, anim)

        // 边框底
        val bc = borderCol()
        ctx.drawRoundedRect(px - 1.5f, py - 1.5f, px + panelW + 1.5f, py + panelH + 1.5f, radius + 1f, a(bc.alpha(100), anim))
        ctx.drawRoundedRect(px, py, px + panelW, py + panelH, radius, a(bg, anim))

        fun lx(v: Float) = px + v * scale
        fun ly(v: Float) = py + v * scale

        if (showTitle) {
            ctx.drawRoundedRect(lx(0f), ly(0f), lx(panelW / scale), ly(titleH + 2f), radius, a(titleBarColor, anim))
            ctx.drawQuad(lx(0f), ly(titleH - 2f), lx(panelW / scale), ly(titleH + 2f), a(titleBarColor, anim))
            val title = containerTitle()
            val tw = font.width(title)
            ctx.text(
                font, title,
                (lx(panelW / scale * 0.5f) - tw / 2f).toInt(),
                ly(3f).toInt(),
                a(titleColor, anim).argb, false,
            )
            if (showDivider) {
                ctx.drawQuad(
                    lx(panelPad), ly(titleH),
                    lx(panelW / scale - panelPad), ly(titleH + divH),
                    a(dividerColor, anim),
                )
            }
        }

        val gridY0 = titleH + divH + panelPad
        for (i in 0 until slots) {
            val col = i % cols
            val row = i / cols
            val ix = panelPad + col * cell
            val iy = gridY0 + row * cell
            val stack = if (open) slotStack(i) else ItemStack.EMPTY
            if (!showEmptySlots && stack.isEmpty) continue

            ctx.drawRoundedRect(
                lx(ix), ly(iy),
                lx(ix + slotSize), ly(iy + slotSize),
                2f, a(slotBg, anim),
            )

            if (renderItems && open && !stack.isEmpty) {
                tryRenderItem(ctx, stack, lx(ix + 1f).toInt(), ly(iy + 1f).toInt())
                if (itemCountText && stack.count > 1) {
                    val cs = stack.count.toString()
                    ctx.text(
                        font, cs,
                        (lx(ix + slotSize) - font.width(cs) - 1).toInt(),
                        (ly(iy + slotSize) - 9).toInt(),
                        a(Color4b.WHITE, anim).argb, false,
                    )
                }
            }
        }
    }

    override fun onDisabled() {
        containerPos = null
        viewScale = 0f
        targetScale = 0f
        wasOpen = false
    }
}
