package net.ccbluex.liquidbounce.features.module.modules.exploit

import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * EC Disabler - 复刻 Zen Client 的 FakeECDisabler 功能
 */
object ModuleECDisabler : Module("ECDisabler", Category.EXPLOIT) {

    private val ecRandom = Random
    private var ecTick = 0
    private var ecX = 0.0
    private var ecY = 0.0
    private var ecZ = 0.0
    private var ecFlag1 = false
    private var ecFlag2 = true

    private val ecMode = choices("Mode", Fuckec1, arrayOf(
        Fuckec1, Fuckec2, Fuckec3, Fuckec4, Fuckec5,
        Fuckec6, Fuckec7, Fuckec8, Fuckec9, Fuckec10
    ))

    init {
        // Tick 事件
        handler<TickEvent> { event ->
            val player = Minecraft.getInstance().player ?: return@handler
            ecTick++

            when (ecMode.getActiveMode()) {
                Fuckec1 -> tickFuckec1(player)
                Fuckec2 -> tickFuckec2(player)
                Fuckec3 -> tickFuckec3(player)
                Fuckec4 -> tickFuckec4(player)
                Fuckec5 -> tickFuckec5(player)
                Fuckec6 -> tickFuckec6(player)
                Fuckec7 -> tickFuckec7(player)
                Fuckec8 -> tickFuckec8(player)
                Fuckec9 -> tickFuckec9(player)
                Fuckec10 -> tickFuckec10(player)
            }
        }

        // PreMotion 事件
        handler<PlayerNetworkMovementTickEvent> { event ->
            if (event.state != EventState.PRE) return@handler
            val player = Minecraft.getInstance().player ?: return@handler

            when (ecMode.getActiveMode()) {
                Fuckec1 -> preMotionFuckec1(player)
                Fuckec2 -> preMotionFuckec2(player)
                Fuckec3 -> preMotionFuckec3(player)
                Fuckec4 -> preMotionFuckec4(player)
                Fuckec5 -> preMotionFuckec5(player)
                Fuckec6 -> preMotionFuckec6(player)
                Fuckec7 -> preMotionFuckec7(player)
                Fuckec8 -> preMotionFuckec8(player)
                Fuckec9 -> preMotionFuckec9(player)
                Fuckec10 -> preMotionFuckec10(player)
            }
        }

        // Packet 事件（发送）
        handler<PacketEvent> { event ->
            if (event.origin != TransferOrigin.OUTGOING) return@handler
            val player = Minecraft.getInstance().player ?: return@handler
            val packet = event.packet

            when (ecMode.getActiveMode()) {
                Fuckec1 -> {
                    if (packet is ServerboundMovePlayerPacket.Rot && ecRandom.nextBoolean()) {
                        event.cancel()
                    }
                }
                Fuckec2 -> {
                    if (packet is ServerboundMovePlayerPacket.Pos && ecTick % 2 == 0) {
                        event.cancel()
                    }
                }
                Fuckec3 -> {
                    if (packet is ServerboundMovePlayerPacket && player.onGround() && ecRandom.nextFloat() < 0.3f) {
                        event.cancel()
                    }
                }
                Fuckec4 -> {
                    if (packet is ServerboundUseItemOnPacket && ecRandom.nextBoolean()) {
                        event.cancel()
                    }
                }
                Fuckec5 -> {
                    if (packet is ServerboundMovePlayerPacket.StatusOnly && ecTick % 3 == 0) {
                        event.cancel()
                    }
                }
                Fuckec6 -> {
                    if (packet is ServerboundMovePlayerPacket && ecRandom.nextDouble() > 0.7) {
                        event.cancel()
                    }
                }
                Fuckec7 -> {
                    if (packet is ServerboundMovePlayerPacket.Pos && player.fallDistance > 0.1f) {
                        event.cancel()
                    }
                }
                Fuckec8 -> {
                    if (packet is ServerboundMovePlayerPacket.Rot && ecTick % 4 == 0) {
                        event.cancel()
                    }
                }
                Fuckec9 -> {
                    if (packet is ServerboundMovePlayerPacket && ecRandom.nextBoolean()) {
                        event.cancel()
                    }
                }
                Fuckec10 -> {
                    // Bypass
                }
            }
        }

        // Strafe 事件
        handler<PlayerStrafeEvent> { event ->
            val player = Minecraft.getInstance().player ?: return@handler

            when (ecMode.getActiveMode()) {
                Fuckec1 -> {
                    val ecFactor = 0.8 + ecRandom.nextDouble() * 0.4
                    event.velocity = event.velocity.scale(ecFactor.toFloat())
                }
                Fuckec2 -> {
                    if (ecRandom.nextBoolean()) {
                        event.velocity = event.velocity.scale(1.1f)
                    }
                }
                Fuckec3 -> {
                    if (player.onGround() && ecRandom.nextFloat() < 0.2f) {
                        event.velocity = Vec3.ZERO
                    }
                }
                Fuckec4 -> {
                    // FUCK EC4
                }
                Fuckec5 -> {
                    if (ecTick % 2 == 0) {
                        event.velocity = event.velocity.scale(1.05f)
                    }
                }
                Fuckec6 -> {
                    if (ecRandom.nextBoolean()) {
                        event.velocity = event.velocity.scale(-1.0f)
                    }
                }
                Fuckec7 -> {
                    if (player.isInWater) {
                        event.velocity = event.velocity.scale(0.5f)
                    }
                }
                Fuckec8 -> {
                    if (ecRandom.nextFloat() < 0.1f) {
                        event.velocity = event.velocity.scale(0.8f)
                    }
                }
                Fuckec9 -> {
                    if (ecRandom.nextBoolean()) {
                        event.velocity = event.velocity.scale(0.9f)
                    }
                }
                Fuckec10 -> {
                    if (ecRandom.nextBoolean()) {
                        event.velocity = event.velocity.scale(1.2f)
                    }
                }
            }
        }

        // 接收数据包
        handler<PacketEvent> { event ->
            if (event.origin != TransferOrigin.INCOMING) return@handler
            if (event.packet is ClientboundPlayerPositionPacket) {
                // 空处理
            }
        }
    }

    override fun enable() {
        val player = Minecraft.getInstance().player
        if (player != null) {
            ecX = player.getX()
            ecY = player.getY()
            ecZ = player.getZ()
        }
        ecTick = 0
        ecFlag1 = false
        ecFlag2 = true
    }

    // ========== 10种模式定义 ==========

    private object Fuckec1 : Mode("FUCKEC1")
    private object Fuckec2 : Mode("FUCKEC2")
    private object Fuckec3 : Mode("FUCKEC3")
    private object Fuckec4 : Mode("FUCKEC4")
    private object Fuckec5 : Mode("FUCKEC5")
    private object Fuckec6 : Mode("FUCKEC6")
    private object Fuckec7 : Mode("FUCKEC7")
    private object Fuckec8 : Mode("FUCKEC8")
    private object Fuckec9 : Mode("FUCKEC9")
    private object Fuckec10 : Mode("FUCKEC10")

    // ========== Tick 模式处理函数 ==========

    private fun tickFuckec1(player: LocalPlayer) {
        if (ecRandom.nextBoolean()) {
            ecX += (ecRandom.nextDouble() - 0.5) * 0.001
        }
        if (ecTick % 3 == 0) {
            player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    180 + ecRandom.nextFloat() * 10,
                    45 + ecRandom.nextFloat() * 10,
                    player.onGround(),
                    player.horizontalCollision
                )
            )
        }
    }

    private fun tickFuckec2(player: LocalPlayer) {
        if (ecTick % 2 == 0) {
            player.connection.send(
                ServerboundMovePlayerPacket.Pos(
                    ecX, ecY, ecZ, false, player.horizontalCollision
                )
            )
            player.connection.send(
                ServerboundMovePlayerPacket.Pos(
                    ecX, ecY, ecZ, true, player.horizontalCollision
                )
            )
        }
        ecY = player.getY()
    }

    private fun tickFuckec3(player: LocalPlayer) {
        if (ecRandom.nextFloat() < 0.1f) {
            val ecDx = (ecRandom.nextDouble() - 0.5) * 0.02
            val ecDz = (ecRandom.nextDouble() - 0.5) * 0.02
            player.connection.send(
                ServerboundMovePlayerPacket.Pos(
                    player.getX() + ecDx, player.getY(), player.getZ() + ecDz,
                    false, player.horizontalCollision
                )
            )
        }
    }

    private fun tickFuckec4(player: LocalPlayer) {
        if (ecTick % 5 == 0) {
            val ecFar = BlockPos(2000000 + ecRandom.nextInt(1000000), 64, 2000000 + ecRandom.nextInt(1000000))
            val ecHit = BlockHitResult(
                Vec3(ecFar.getX().toDouble(), ecFar.getY().toDouble(), ecFar.getZ().toDouble()),
                Direction.UP, ecFar, false
            )
            player.connection.send(ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, ecHit, 0))
        }
    }

    private fun tickFuckec5(player: LocalPlayer) {
        if (ecRandom.nextBoolean()) {
            player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    0f, 90f, player.onGround(), player.horizontalCollision
                )
            )
            player.connection.send(
                ServerboundMovePlayerPacket.Pos(
                    player.getX() + 0.01, player.getY() - 0.01, player.getZ() + 0.01,
                    true, player.horizontalCollision
                )
            )
        }
    }

    private fun tickFuckec6(player: LocalPlayer) {
        if (ecTick % 4 == 0) {
            player.connection.send(
                ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision)
            )
            player.connection.send(
                ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision)
            )
        }
    }

    private fun tickFuckec7(player: LocalPlayer) {
        for (i in 0 until 3) {
            player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    ecRandom.nextFloat() * 360,
                    ecRandom.nextFloat() * 90,
                    player.onGround(),
                    player.horizontalCollision
                )
            )
        }
    }

    private fun tickFuckec8(player: LocalPlayer) {
        if (ecTick % 3 == 0) {
            player.connection.send(
                ServerboundMovePlayerPacket.PosRot(
                    player.getX(), player.getY() + 0.05, player.getZ(),
                    45f, 45f, false, player.horizontalCollision
                )
            )
            val ecFar2 = BlockPos(3000000, 64, 3000000)
            val ecHit2 = BlockHitResult(
                Vec3(3000000.0, 64.0, 3000000.0),
                Direction.UP, ecFar2, false
            )
            player.connection.send(ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, ecHit2, 0))
        }
    }

    private fun tickFuckec9(player: LocalPlayer) {
        var ecTmp = 0.0
        for (i in 0 until 10) ecTmp += ecRandom.nextDouble()
        if (ecTmp > 5) {
            player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    180f, 0f, player.onGround(), player.horizontalCollision
                )
            )
        }
    }

    private fun tickFuckec10(player: LocalPlayer) {
        val ecChoice = ecRandom.nextInt(3)
        when (ecChoice) {
            0 -> player.connection.send(
                ServerboundMovePlayerPacket.Pos(
                    player.getX() + 0.001, player.getY(), player.getZ() - 0.001,
                    true, player.horizontalCollision
                )
            )
            1 -> player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    90 + ecRandom.nextFloat() * 20,
                    30 + ecRandom.nextFloat() * 20,
                    player.onGround(),
                    player.horizontalCollision
                )
            )
            else -> {
                val ecFar3 = BlockPos(4000000, 128, 4000000)
                val ecHit3 = BlockHitResult(
                    Vec3(4000000.0, 128.0, 4000000.0),
                    Direction.UP, ecFar3, false
                )
                player.connection.send(ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, ecHit3, 0))
            }
        }
    }

    // ========== PreMotion 模式处理函数 ==========

    private fun preMotionFuckec1(player: LocalPlayer) {
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(
                Float.MAX_VALUE, Float.MAX_VALUE,
                player.onGround(), player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(
                player.yRot, player.xRot,
                player.onGround(), player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec2(player: LocalPlayer) {
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY(), player.getZ(),
                false, player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX() + 0.0001, player.getY(), player.getZ() - 0.0001,
                true, player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec3(player: LocalPlayer) {
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY() + 100, player.getZ(),
                false, player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY(), player.getZ(),
                true, player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec4(player: LocalPlayer) {
        val ecFar = BlockPos(5000000, 64, 5000000)
        val ecHit = BlockHitResult(
            Vec3(5000000.0, 64.0, 5000000.0),
            Direction.UP, ecFar, false
        )
        player.connection.send(ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, ecHit, 0))
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY(), player.getZ(),
                false, player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec5(player: LocalPlayer) {
        player.connection.send(
            ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision)
        )
        player.connection.send(
            ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision)
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(
                90f, 45f, player.onGround(), player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec6(player: LocalPlayer) {
        val ecDx = ecRandom.nextDouble() * 0.01
        val ecDz = ecRandom.nextDouble() * 0.01
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX() + ecDx, player.getY(), player.getZ() + ecDz,
                false, player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(
                180f, 0f, player.onGround(), player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY(), player.getZ(),
                true, player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec7(player: LocalPlayer) {
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY() + 0.42, player.getZ(),
                false, player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX(), player.getY(), player.getZ(),
                true, player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec8(player: LocalPlayer) {
        for (i in 0 until 5) {
            player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    ecRandom.nextFloat() * 360,
                    ecRandom.nextFloat() * 90,
                    player.onGround(),
                    player.horizontalCollision
                )
            )
        }
        val ecFar2 = BlockPos(6000000, 64, 6000000)
        val ecHit2 = BlockHitResult(
            Vec3(6000000.0, 64.0, 6000000.0),
            Direction.UP, ecFar2, false
        )
        player.connection.send(ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, ecHit2, 0))
    }

    private fun preMotionFuckec9(player: LocalPlayer) {
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(
                45f, 45f, player.onGround(), player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Pos(
                player.getX() + 0.02, player.getY() - 0.02, player.getZ() + 0.02,
                true, player.horizontalCollision
            )
        )
        player.connection.send(
            ServerboundMovePlayerPacket.Rot(
                180f, 0f, player.onGround(), player.horizontalCollision
            )
        )
    }

    private fun preMotionFuckec10(player: LocalPlayer) {
        val ecRnd = ecRandom.nextInt(3)
        when (ecRnd) {
            0 -> player.connection.send(
                ServerboundMovePlayerPacket.PosRot(
                    player.getX(), player.getY() + 0.1, player.getZ(),
                    0f, 90f, false, player.horizontalCollision
                )
            )
            1 -> {
                val ecFar3 = BlockPos(7000000, 64, 7000000)
                val ecHit3 = BlockHitResult(
                    Vec3(7000000.0, 64.0, 7000000.0),
                    Direction.UP, ecFar3, false
                )
                player.connection.send(ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, ecHit3, 0))
            }
            else -> player.connection.send(
                ServerboundMovePlayerPacket.Rot(
                    90f, 90f, player.onGround(), player.horizontalCollision
                )
            )
        }
    }
}
