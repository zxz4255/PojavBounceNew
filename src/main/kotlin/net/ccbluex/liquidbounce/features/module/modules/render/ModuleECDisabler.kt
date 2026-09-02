package net.ccbluex.liquidbounce.features.module.modules.exploit

import net.ccbluex.liquidbounce.config.Choice
import net.ccbluex.liquidbounce.config.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.entity.strafe
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.random.Random

/**
 * EC Disabler - 复刻 Zen Client 的 FakeECDisabler 功能
 * 适用于 LiquidBounce Nextgen (1.20+)
 */
object ModuleECDisabler : ClientModule("ECDisabler", Category.EXPLOIT) {

    private val ecRandom = Random.Default
    private var ecTick = 0
    private var ecX = 0.0
    private var ecY = 0.0
    private var ecZ = 0.0
    private var ecFlag1 = false
    private var ecFlag2 = true

    // 模式选择 - 复刻原版的10种模式
    private val modes = choices("Mode", Fuckec1, arrayOf(
        Fuckec1, Fuckec2, Fuckec3, Fuckec4, Fuckec5,
        Fuckec6, Fuckec7, Fuckec8, Fuckec9, Fuckec10
    ))

    override fun enable() {
        player?.let {
            ecX = it.x
            ecY = it.y
            ecZ = it.z
        }
        ecTick = 0
        ecFlag1 = false
        ecFlag2 = true
    }

    // ========== Tick 事件处理 ==========
    @EventHandler
    private val tickHandler = handler<TickEvent> {
        if (player == null) return@handler
        ecTick++

        when (modes.activeChoice) {
            is Fuckec1 -> handleTickFuckec1()
            is Fuckec2 -> handleTickFuckec2()
            is Fuckec3 -> handleTickFuckec3()
            is Fuckec4 -> handleTickFuckec4()
            is Fuckec5 -> handleTickFuckec5()
            is Fuckec6 -> handleTickFuckec6()
            is Fuckec7 -> handleTickFuckec7()
            is Fuckec8 -> handleTickFuckec8()
            is Fuckec9 -> handleTickFuckec9()
            is Fuckec10 -> handleTickFuckec10()
        }
    }

    // ========== PreMotion 事件处理 ==========
    @EventHandler
    private val preMotionHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        if (player == null) return@handler

        when (modes.activeChoice) {
            is Fuckec1 -> handlePreMotionFuckec1(event)
            is Fuckec2 -> handlePreMotionFuckec2(event)
            is Fuckec3 -> handlePreMotionFuckec3(event)
            is Fuckec4 -> handlePreMotionFuckec4(event)
            is Fuckec5 -> handlePreMotionFuckec5(event)
            is Fuckec6 -> handlePreMotionFuckec6(event)
            is Fuckec7 -> handlePreMotionFuckec7(event)
            is Fuckec8 -> handlePreMotionFuckec8(event)
            is Fuckec9 -> handlePreMotionFuckec9(event)
            is Fuckec10 -> handlePreMotionFuckec10(event)
        }
    }

    // ========== Packet 事件处理 (发送) ==========
    @EventHandler
    private val packetHandler = handler<PacketEvent> { event ->
        if (player == null || event.origin != TransferOrigin.SEND) return@handler

        val packet = event.packet

        when (modes.activeChoice) {
            is Fuckec1 -> {
                if (packet is PlayerMoveC2SPacket.LookAndOnGround && ecRandom.nextBoolean()) {
                    event.cancelEvent()
                }
            }
            is Fuckec2 -> {
                if (packet is PlayerMoveC2SPacket.PositionAndOnGround && ecTick % 2 == 0) {
                    event.cancelEvent()
                }
            }
            is Fuckec3 -> {
                if (packet is PlayerMoveC2SPacket && player!!.isOnGround && ecRandom.nextFloat() < 0.3f) {
                    event.cancelEvent()
                }
            }
            is Fuckec4 -> {
                if (packet is PlayerInteractBlockC2SPacket && ecRandom.nextBoolean()) {
                    event.cancelEvent()
                }
            }
            is Fuckec5 -> {
                if (packet is PlayerMoveC2SPacket.OnGroundOnly && ecTick % 3 == 0) {
                    event.cancelEvent()
                }
            }
            is Fuckec6 -> {
                if (packet is PlayerMoveC2SPacket && ecRandom.nextDouble() > 0.7) {
                    event.cancelEvent()
                }
            }
            is Fuckec7 -> {
                if (packet is PlayerMoveC2SPacket.PositionAndOnGround && player!!.fallDistance > 0.1f) {
                    event.cancelEvent()
                }
            }
            is Fuckec8 -> {
                if (packet is PlayerMoveC2SPacket.LookAndOnGround && ecTick % 4 == 0) {
                    event.cancelEvent()
                }
            }
            is Fuckec9 -> {
                if (packet is PlayerMoveC2SPacket && ecRandom.nextBoolean()) {
                    event.cancelEvent()
                }
            }
            is Fuckec10 -> {
                // FUCKEC10 - Bypass, 不取消任何包
            }
        }
    }

    // ========== Strafe 事件处理 ==========
    @EventHandler
    private val strafeHandler = handler<PlayerStrafeEvent> { event ->
        if (player == null) return@handler

        when (modes.activeChoice) {
            is Fuckec1 -> {
                val ecFactor = 0.8 + ecRandom.nextDouble() * 0.4
                player!!.strafe(speed = player!!.speed * ecFactor.toFloat())
            }
            is Fuckec2 -> {
                if (ecRandom.nextBoolean()) {
                    player!!.strafe(speed = player!!.speed * 1.1f)
                }
            }
            is Fuckec3 -> {
                if (player!!.isOnGround && ecRandom.nextFloat() < 0.2f) {
                    player!!.strafe(speed = 0.0f)
                }
            }
            is Fuckec4 -> {
                // FUCK EC4 - 不处理
            }
            is Fuckec5 -> {
                if (ecTick % 2 == 0) {
                    player!!.strafe(speed = player!!.speed * 1.05f)
                }
            }
            is Fuckec6 -> {
                if (ecRandom.nextBoolean()) {
                    player!!.strafe(speed = -player!!.speed)
                }
            }
            is Fuckec7 -> {
                if (player!!.isTouchingWater) {
                    player!!.strafe(speed = player!!.speed * 0.5f)
                }
            }
            is Fuckec8 -> {
                if (ecRandom.nextFloat() < 0.1f) {
                    player!!.strafe(speed = player!!.speed * 0.8f)
                }
            }
            is Fuckec9 -> {
                if (ecRandom.nextBoolean()) {
                    player!!.strafe(speed = player!!.speed * 0.9f)
                }
            }
            is Fuckec10 -> {
                if (ecRandom.nextBoolean()) {
                    player!!.strafe(speed = player!!.speed * 1.2f)
                }
            }
        }
    }

    // ========== 接收数据包处理 ==========
    @EventHandler
    private val receivePacketHandler = handler<PacketEvent> { event ->
        if (player == null || event.origin != TransferOrigin.RECEIVE) return@handler

        if (event.packet is PlayerPositionLookS2CPacket) {
            // 原版空处理，可扩展
        }
    }

    // ========== 10种模式定义 ==========

    private object Fuckec1 : Choice("FUCKEC1") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec2 : Choice("FUCKEC2") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec3 : Choice("FUCKEC3") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec4 : Choice("FUCKEC4") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec5 : Choice("FUCKEC5") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec6 : Choice("FUCKEC6") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec7 : Choice("FUCKEC7") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec8 : Choice("FUCKEC8") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec9 : Choice("FUCKEC9") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    private object Fuckec10 : Choice("FUCKEC10") {
        override val parent: ChoiceConfigurable<*>
            get() = modes
    }

    // ========== Tick 模式处理函数 ==========

    private fun handleTickFuckec1() {
        if (ecRandom.nextBoolean()) {
            ecX += (ecRandom.nextDouble() - 0.5) * 0.001
        }
        if (ecTick % 3 == 0) {
            network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    180 + ecRandom.nextFloat() * 10,
                    45 + ecRandom.nextFloat() * 10,
                    player!!.isOnGround,
                    player!!.horizontalCollision
                )
            )
        }
    }

    private fun handleTickFuckec2() {
        if (ecTick % 2 == 0) {
            network.sendPacket(
                PlayerMoveC2SPacket.PositionAndOnGround(
                    ecX, ecY, ecZ, false, player!!.horizontalCollision
                )
            )
            network.sendPacket(
                PlayerMoveC2SPacket.PositionAndOnGround(
                    ecX, ecY, ecZ, true, player!!.horizontalCollision
                )
            )
        }
        ecY = player!!.y
    }

    private fun handleTickFuckec3() {
        if (ecRandom.nextFloat() < 0.1f) {
            val ecDx = (ecRandom.nextDouble() - 0.5) * 0.02
            val ecDz = (ecRandom.nextDouble() - 0.5) * 0.02
            network.sendPacket(
                PlayerMoveC2SPacket.PositionAndOnGround(
                    player!!.x + ecDx, player!!.y, player!!.z + ecDz,
                    false, player!!.horizontalCollision
                )
            )
        }
    }

    private fun handleTickFuckec4() {
        if (ecTick % 5 == 0) {
            val ecFar = BlockPos(2000000 + ecRandom.nextInt(1000000), 64, 2000000 + ecRandom.nextInt(1000000))
            val ecHit = BlockHitResult(
                Vec3d(ecFar.x.toDouble(), ecFar.y.toDouble(), ecFar.z.toDouble()),
                Direction.UP, ecFar, false
            )
            network.sendPacket(PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, ecHit, 0))
        }
    }

    private fun handleTickFuckec5() {
        if (ecRandom.nextBoolean()) {
            network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    0f, 90f, player!!.isOnGround, player!!.horizontalCollision
                )
            )
            network.sendPacket(
                PlayerMoveC2SPacket.PositionAndOnGround(
                    player!!.x + 0.01, player!!.y - 0.01, player!!.z + 0.01,
                    true, player!!.horizontalCollision
                )
            )
        }
    }

    private fun handleTickFuckec6() {
        if (ecTick % 4 == 0) {
            network.sendPacket(
                PlayerMoveC2SPacket.OnGroundOnly(true, player!!.horizontalCollision)
            )
            network.sendPacket(
                PlayerMoveC2SPacket.OnGroundOnly(false, player!!.horizontalCollision)
            )
        }
    }

    private fun handleTickFuckec7() {
        for (i in 0 until 3) {
            network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    ecRandom.nextFloat() * 360,
                    ecRandom.nextFloat() * 90,
                    player!!.isOnGround,
                    player!!.horizontalCollision
                )
            )
        }
    }

    private fun handleTickFuckec8() {
        if (ecTick % 3 == 0) {
            network.sendPacket(
                PlayerMoveC2SPacket.Full(
                    player!!.x, player!!.y + 0.05, player!!.z,
                    45f, 45f, false, player!!.horizontalCollision
                )
            )
            val ecFar2 = BlockPos(3000000, 64, 3000000)
            val ecHit2 = BlockHitResult(
                Vec3d(3000000.0, 64.0, 3000000.0),
                Direction.UP, ecFar2, false
            )
            network.sendPacket(PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, ecHit2, 0))
        }
    }

    private fun handleTickFuckec9() {
        var ecTmp = 0.0
        for (i in 0 until 10) ecTmp += ecRandom.nextDouble()
        if (ecTmp > 5) {
            network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    180f, 0f, player!!.isOnGround, player!!.horizontalCollision
                )
            )
        }
    }

    private fun handleTickFuckec10() {
        val ecChoice = ecRandom.nextInt(3)
        when (ecChoice) {
            0 -> network.sendPacket(
                PlayerMoveC2SPacket.PositionAndOnGround(
                    player!!.x + 0.001, player!!.y, player!!.z - 0.001,
                    true, player!!.horizontalCollision
                )
            )
            1 -> network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    90 + ecRandom.nextFloat() * 20,
                    30 + ecRandom.nextFloat() * 20,
                    player!!.isOnGround,
                    player!!.horizontalCollision
                )
            )
            else -> {
                val ecFar3 = BlockPos(4000000, 128, 4000000)
                val ecHit3 = BlockHitResult(
                    Vec3d(4000000.0, 128.0, 4000000.0),
                    Direction.UP, ecFar3, false
                )
                network.sendPacket(PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, ecHit3, 0))
            }
        }
    }

    // ========== PreMotion 模式处理函数 ==========

    private fun handlePreMotionFuckec1(event: PlayerNetworkMovementTickEvent) {
        network.sendPacket(
            PlayerMoveC2SPacket.LookAndOnGround(
                Float.MAX_VALUE, Float.MAX_VALUE,
                player!!.isOnGround, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.LookAndOnGround(
                player!!.yaw, player!!.pitch,
                player!!.isOnGround, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec2(event: PlayerNetworkMovementTickEvent) {
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y, player!!.z,
                false, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x + 0.0001, player!!.y, player!!.z - 0.0001,
                true, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec3(event: PlayerNetworkMovementTickEvent) {
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y + 100, player!!.z,
                false, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y, player!!.z,
                true, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec4(event: PlayerNetworkMovementTickEvent) {
        val ecFar = BlockPos(5000000, 64, 5000000)
        val ecHit = BlockHitResult(
            Vec3d(5000000.0, 64.0, 5000000.0),
            Direction.UP, ecFar, false
        )
        network.sendPacket(PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, ecHit, 0))
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y, player!!.z,
                false, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec5(event: PlayerNetworkMovementTickEvent) {
        network.sendPacket(
            PlayerMoveC2SPacket.OnGroundOnly(true, player!!.horizontalCollision)
        )
        network.sendPacket(
            PlayerMoveC2SPacket.OnGroundOnly(false, player!!.horizontalCollision)
        )
        network.sendPacket(
            PlayerMoveC2SPacket.LookAndOnGround(
                90f, 45f, player!!.isOnGround, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec6(event: PlayerNetworkMovementTickEvent) {
        val ecDx = ecRandom.nextDouble() * 0.01
        val ecDz = ecRandom.nextDouble() * 0.01
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x + ecDx, player!!.y, player!!.z + ecDz,
                false, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.LookAndOnGround(
                180f, 0f, player!!.isOnGround, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y, player!!.z,
                true, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec7(event: PlayerNetworkMovementTickEvent) {
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y + 0.42, player!!.z,
                false, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x, player!!.y, player!!.z,
                true, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec8(event: PlayerNetworkMovementTickEvent) {
        for (i in 0 until 5) {
            network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    ecRandom.nextFloat() * 360,
                    ecRandom.nextFloat() * 90,
                    player!!.isOnGround,
                    player!!.horizontalCollision
                )
            )
        }
        val ecFar2 = BlockPos(6000000, 64, 6000000)
        val ecHit2 = BlockHitResult(
            Vec3d(6000000.0, 64.0, 6000000.0),
            Direction.UP, ecFar2, false
        )
        network.sendPacket(PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, ecHit2, 0))
    }

    private fun handlePreMotionFuckec9(event: PlayerNetworkMovementTickEvent) {
        network.sendPacket(
            PlayerMoveC2SPacket.LookAndOnGround(
                45f, 45f, player!!.isOnGround, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.PositionAndOnGround(
                player!!.x + 0.02, player!!.y - 0.02, player!!.z + 0.02,
                true, player!!.horizontalCollision
            )
        )
        network.sendPacket(
            PlayerMoveC2SPacket.LookAndOnGround(
                180f, 0f, player!!.isOnGround, player!!.horizontalCollision
            )
        )
    }

    private fun handlePreMotionFuckec10(event: PlayerNetworkMovementTickEvent) {
        val ecRnd = ecRandom.nextInt(3)
        when (ecRnd) {
            0 -> network.sendPacket(
                PlayerMoveC2SPacket.Full(
                    player!!.x, player!!.y + 0.1, player!!.z,
                    0f, 90f, false, player!!.horizontalCollision
                )
            )
            1 -> {
                val ecFar3 = BlockPos(7000000, 64, 7000000)
                val ecHit3 = BlockHitResult(
                    Vec3d(7000000.0, 64.0, 7000000.0),
                    Direction.UP, ecFar3, false
                )
                network.sendPacket(PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, ecHit3, 0))
            }
            else -> network.sendPacket(
                PlayerMoveC2SPacket.LookAndOnGround(
                    90f, 90f, player!!.isOnGround, player!!.horizontalCollision
                )
            )
        }
    }
}
