/*
 * ModuleChestESP — 还原箱子 ESP（未开绿 / 已开红，Nitro 风格可选）
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import java.util.concurrent.CopyOnWriteArrayList

object ModuleChestESP : ClientModule(
    "ChestESP",
    ModuleCategories.RENDER,
    aliases = listOf("StorageESP", "ChestBox"),
) {

    private enum class Style(override val tag: String) : Tagged {
        DEFAULT("Default"),
        NITRO("Nitro"),
    }

    private val style by enumChoice("Style", Style.DEFAULT)
    private val throughWalls by boolean("Through Walls", true)
    private val fullBlock by boolean("Full Block Box", true)
    private val range by int("Range", 64, 16..256)
    private val showTrapped by boolean("Trapped Chests", true)
    private val showEnder by boolean("Ender Chests", true)
    private val outline by boolean("Outline", true)

    private val unopenedColor by color("Unopened", Color4b(0, 255, 0, 64))
    private val openedColor by color("Opened", Color4b(255, 0, 0, 64))
    private val nitroUnopened by color("Nitro Unopened", Color4b(55, 162, 87, 115))
    private val nitroOpened by color("Nitro Opened", Color4b(153, 30, 76, 115))
    private val enderColor by color("Ender Color", Color4b(120, 40, 200, 80))

    private val opened = HashSet<Long>()
    private val boxes = CopyOnWriteArrayList<Pair<AABB, Boolean>>() // true = opened
    private val enderBoxes = CopyOnWriteArrayList<AABB>()

    private fun pack(pos: BlockPos): Long =
        (pos.x.toLong() and 0x3FFFFFF) or
            ((pos.z.toLong() and 0x3FFFFFF) shl 26) or
            ((pos.y.toLong() and 0xFFF) shl 52)

    private fun colorUnopened() = if (style == Style.NITRO) nitroUnopened else unopenedColor
    private fun colorOpened() = if (style == Style.NITRO) nitroOpened else openedColor

    private fun chestAabb(be: ChestBlockEntity): AABB? {
        val state = be.blockState
        val type = try {
            state.getValue(ChestBlock.TYPE)
        } catch (_: Throwable) {
            return fullBox(be.blockPos)
        }
        if (type == ChestType.LEFT) return null
        var box = fullBox(be.blockPos)
        if (type != ChestType.SINGLE) {
            runCatching {
                val dir = ChestBlock.getConnectedDirection(state)
                val other = be.blockPos.relative(dir)
                box = box.minmax(fullBox(other))
            }
        }
        return box
    }

    private fun fullBox(pos: BlockPos): AABB =
        if (fullBlock) AABB(pos)
        else AABB(
            pos.x + 0.06, pos.y.toDouble(), pos.z + 0.06,
            pos.x + 0.94, pos.y + 0.875, pos.z + 0.94,
        )

    private fun scan() {
        val world = mc.level ?: return
        val self = mc.player ?: return
        boxes.clear()
        enderBoxes.clear()
        val r = range
        val origin = self.blockPosition()
        runCatching {
            for (be in world.blockEntities) {
                val pos = be.blockPos
                if (hypot3(origin, pos) > r) continue
                when (be) {
                    is ChestBlockEntity -> {
                        if (!showTrapped && be is TrappedChestBlockEntity) continue
                        val aabb = chestAabb(be) ?: continue
                        val isOpen = opened.contains(pack(pos)) ||
                            opened.contains(pack(BlockPos.containing(aabb.minX, aabb.minY, aabb.minZ)))
                        boxes += aabb to isOpen
                    }
                    else -> {
                        if (showEnder) {
                            val name = be.type.toString().lowercase()
                            if (name.contains("ender")) {
                                enderBoxes += fullBox(pos)
                            }
                        }
                    }
                }
            }
        }
        // 兜底：按区块扫描方块类型
        if (boxes.isEmpty()) {
            runCatching {
                for (dx in -r..r) for (dy in -r / 2..r / 2) for (dz in -r..r) {
                    val pos = origin.offset(dx, dy, dz)
                    val st = world.getBlockState(pos)
                    val b = st.block
                    if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                        if (b == Blocks.TRAPPED_CHEST && !showTrapped) continue
                        val type = try { st.getValue(ChestBlock.TYPE) } catch (_: Throwable) { ChestType.SINGLE }
                        if (type == ChestType.LEFT) continue
                        var box = fullBox(pos)
                        if (type != ChestType.SINGLE) {
                            runCatching {
                                val dir = ChestBlock.getConnectedDirection(st)
                                box = box.minmax(fullBox(pos.relative(dir)))
                            }
                        }
                        boxes += box to opened.contains(pack(pos))
                    } else if (showEnder && b == Blocks.ENDER_CHEST) {
                        enderBoxes += fullBox(pos)
                    }
                }
            }
        }
    }

    private fun hypot3(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet is ClientboundBlockEventPacket) {
            runCatching {
                // b0==1 && b1==1 → 箱子打开
                val b0 = packet.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && (it.name == "getB0" || it.name.contains("Param", true) || it.name == "getType")
                }
                // 标准: getValue1/getValue2 或类似
                val pos = packet.pos
                val block = try { packet.block } catch (_: Throwable) { null }
                val isChest = block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST ||
                    worldBlockIsChest(pos)
                if (isChest) {
                    // 多数实现：打开事件
                    opened.add(pack(pos))
                }
            }
        }
    }

    private fun worldBlockIsChest(pos: BlockPos): Boolean {
        val w = mc.level ?: return false
        val b = w.getBlockState(pos).block
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        opened.clear()
        boxes.clear()
        enderBoxes.clear()
    }

    private var lastScanNs = 0L

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val ns = System.nanoTime()
        if (lastScanNs == 0L || ns - lastScanNs > 200_000_000L) {
            scan()
            lastScanNs = ns
        }
        if (boxes.isEmpty() && enderBoxes.isEmpty()) return@handler

        event.renderEnvironment {
            for ((aabb, isOpen) in boxes) {
                val col = if (isOpen) colorOpened() else colorUnopened()
                val out = if (outline) Color4b(col.r, col.g, col.b, 220) else null
                val cx = (aabb.minX + aabb.maxX) * 0.5
                val cy = (aabb.minY + aabb.maxY) * 0.5
                val cz = (aabb.minZ + aabb.maxZ) * 0.5
                val hx = (aabb.maxX - aabb.minX) * 0.5
                val hy = (aabb.maxY - aabb.minY) * 0.5
                val hz = (aabb.maxZ - aabb.minZ) * 0.5
                withPositionRelativeToCamera(cx, cy, cz) {
                    drawBox(
                        AABB(-hx, -hy, -hz, hx, hy, hz),
                        faceColor = col,
                        outlineColor = out,
                        noDepthTest = throughWalls,
                    )
                }
            }
            for (aabb in enderBoxes) {
                val col = enderColor
                val out = if (outline) Color4b(col.r, col.g, col.b, 220) else null
                val cx = (aabb.minX + aabb.maxX) * 0.5
                val cy = (aabb.minY + aabb.maxY) * 0.5
                val cz = (aabb.minZ + aabb.maxZ) * 0.5
                val hx = (aabb.maxX - aabb.minX) * 0.5
                val hy = (aabb.maxY - aabb.minY) * 0.5
                val hz = (aabb.maxZ - aabb.minZ) * 0.5
                withPositionRelativeToCamera(cx, cy, cz) {
                    drawBox(
                        AABB(-hx, -hy, -hz, hx, hy, hz),
                        faceColor = col,
                        outlineColor = out,
                        noDepthTest = throughWalls,
                    )
                }
            }
        }
    }

    override fun onDisabled() {
        boxes.clear()
        enderBoxes.clear()
    }
}
