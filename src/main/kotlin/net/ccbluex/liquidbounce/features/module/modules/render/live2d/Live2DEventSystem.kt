/*
 * Live2DEventSystem —— 游戏状态 → 模型动作/表情的事件映射。
 *
 * 事件名与 Samsara/原版 MinecraftLive2d 一致，用户在配置里写
 *   events: { "hurt": { "type": "expression", "target": "F01" } }
 * 即可触发。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DEventSystem.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import net.ccbluex.liquidbounce.utils.client.mc

class Live2DEventSystem(
    private val hud: Live2DHudRenderer,
    private val config: Live2DConfig,
) {

    private var joined = false
    private var wasChatOpen = false
    private var wasScreenOpen = false
    private var wasHurt = false
    private var wasDead = false
    private var wasSwinging = false
    private var wasOnGround = false
    private var wasSwimming = false
    private var wasUnderwater = false
    private var wasSneaking = false
    private var wasSprinting = false
    private var wasRaining = false
    private var wasThundering = false
    private var wasNight = false
    private var wasLowHealth = false
    private var lastIdleFire = System.currentTimeMillis()

    fun tick() {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            joined = false
            return
        }

        if (!joined) {
            joined = true
            snapshot(player)
            fire("join_world")
            return
        }

        val chatOpen = mc.gui.screen() is net.minecraft.client.gui.screens.ChatScreen
        val screenOpen = mc.gui.screen() != null
        val hurt = player.hurtTime > 0
        val dead = !player.isAlive
        val swinging = player.isSwinging
        val onGround = player.onGround()
        val swimming = player.isSwimming
        val underwater = player.isUnderWater
        val sneaking = player.isShiftKeyDown
        val sprinting = player.isSprinting
        val raining = level.isRaining
        val thundering = level.isThundering
        val night = isNight(level)
        val lowHealth = player.health / player.maxHealth.coerceAtLeast(0.001f) < 0.3f

        if (chatOpen && !wasChatOpen) fire("chat_opened")
        if (!chatOpen && wasChatOpen) fire("chat_closed")
        if (screenOpen && !wasScreenOpen) fire("screen_open")
        if (!screenOpen && wasScreenOpen) fire("screen_close")
        if (hurt && !wasHurt) fire("hurt")
        if (dead && !wasDead) fire("death")
        if (!dead && wasDead) fire("respawn")
        if (swinging && !wasSwinging) fire("attack")
        if (!onGround && wasOnGround && player.deltaMovement.y > 0.05) fire("jump")
        if (onGround && !wasOnGround) fire("land")
        if (swimming && !wasSwimming) fire("swim_start")
        if (!swimming && wasSwimming) fire("swim_end")
        if (underwater && !wasUnderwater) fire("underwater")
        if (!underwater && wasUnderwater) fire("surface")
        if (sneaking && !wasSneaking) fire("sneak")
        if (!sneaking && wasSneaking) fire("stand")
        if (sprinting && !wasSprinting) fire("sprint_start")
        if (!sprinting && wasSprinting) fire("sprint_end")
        if (raining && !wasRaining) fire("rain")
        if (!raining && wasRaining) fire("clear")
        if (thundering && !wasThundering) fire("thunder")
        if (night && !wasNight) fire("night")
        if (!night && wasNight) fire("day")
        if (lowHealth && !wasLowHealth) fire("low_health")
        if (!lowHealth && wasLowHealth) fire("high_health")

        val now = System.currentTimeMillis()
        if (now - lastIdleFire > 30_000L) {
            lastIdleFire = now
            fire("idle")
        }

        wasChatOpen = chatOpen
        wasScreenOpen = screenOpen
        wasHurt = hurt
        wasDead = dead
        wasSwinging = swinging
        wasOnGround = onGround
        wasSwimming = swimming
        wasUnderwater = underwater
        wasSneaking = sneaking
        wasSprinting = sprinting
        wasRaining = raining
        wasThundering = thundering
        wasNight = night
        wasLowHealth = lowHealth
    }

    private fun snapshot(player: net.minecraft.client.player.LocalPlayer) {
        val level = mc.level ?: return
        wasChatOpen = mc.gui.screen() is net.minecraft.client.gui.screens.ChatScreen
        wasScreenOpen = mc.gui.screen() != null
        wasHurt = player.hurtTime > 0
        wasDead = !player.isAlive
        wasSwinging = player.isSwinging
        wasOnGround = player.onGround()
        wasSwimming = player.isSwimming
        wasUnderwater = player.isUnderWater
        wasSneaking = player.isShiftKeyDown
        wasSprinting = player.isSprinting
        wasRaining = level.isRaining
        wasThundering = level.isThundering
        wasNight = isNight(level)
        wasLowHealth = player.health / player.maxHealth.coerceAtLeast(0.001f) < 0.3f
        lastIdleFire = System.currentTimeMillis()
    }

    /** 26.3 的昼夜由 WorldClock 表达，getDefaultClockTime() 即原 dayTime。 */
    private fun isNight(level: net.minecraft.world.level.Level): Boolean =
        level.defaultClockTime % 24000L !in 0..12000L

    fun fire(event: String) {
        val action = config.events[event] ?: return
        hud.triggerAction(action)
    }
}