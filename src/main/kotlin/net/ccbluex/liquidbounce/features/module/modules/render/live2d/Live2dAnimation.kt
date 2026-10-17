/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.ciallo.live2d.cubism.Live2dModelRuntime
import net.minecraft.util.Mth

/**
 * Drives the per-frame parameter animation of a Live2D model:
 * view tracking with spring physics, automatic blinking, breathing,
 * eye-ball follow and the hair/ear parameter overlay.
 *
 * Ported from the original MinecraftLive2d Live2dHudRenderer (MIT).
 */
class Live2dAnimation {

    // view tracking
    var firstFrame = true
    private var lastYaw = 0f
    private var lastPitch = 0f

    var angleX = 0f
        private set
    var angleY = 0f
        private set
    var angleZ = 0f
        private set
    private var bodyAngleX = 0f

    var springX = 0f
        private set
    var springY = 0f
        private set
    private var springVX = 0f
    private var springVY = 0f

    private var earSpring = 0f
    private var earVelocity = 0f

    // blink
    private var nextBlinkAt = System.currentTimeMillis() + 1800L
    private var blinkStartedAt = -1L

    var swayStrength = 3.0f
    var smoothing = 0.1f
    var followPitch = true
    var blinkEnabled = true

    fun reset() {
        firstFrame = true
        angleX = 0f
        angleY = 0f
        angleZ = 0f
        bodyAngleX = 0f
        springX = 0f
        springY = 0f
        springVX = 0f
        springVY = 0f
        earSpring = 0f
        earVelocity = 0f
        blinkStartedAt = -1L
        nextBlinkAt = System.currentTimeMillis() + 1800L
    }

    /**
     * Advances the animation by [dt] seconds using the player's current view.
     */
    fun update(model: Live2dModelRuntime, yaw: Float, pitch: Float, dtSeconds: Float) {
        if (firstFrame) {
            lastYaw = yaw
            lastPitch = pitch
            firstFrame = false
            apply(model)
            return
        }
        val dt = dtSeconds.coerceAtLeast(0.001f)
        val deltaYaw = Mth.wrapDegrees(yaw - lastYaw)
        val deltaPitch = pitch - lastPitch
        lastYaw = yaw
        lastPitch = pitch

        val strength = swayStrength
        val targetX = Mth.clamp(-deltaYaw / dt * 0.018f * strength, -30f, 30f)
        val targetY = if (followPitch) Mth.clamp(-deltaPitch / dt * 0.014f * strength, -20f, 20f) else 0f
        val targetZ = Mth.clamp(deltaYaw / dt * 0.01f * strength, -18f, 18f)
        val smooth = Mth.clamp(smoothing, 0.02f, 0.6f)
        angleX = lerp(angleX, targetX, smooth)
        angleY = lerp(angleY, targetY, smooth)
        angleZ = lerp(angleZ, targetZ, smooth * 0.75f)
        bodyAngleX = lerp(bodyAngleX, angleX * 0.35f, smooth * 0.55f)

        springX = updateSpring(springX, angleX + bodyAngleX, 0.12f, 0.78f, true)
        springY = updateSpring(springY, angleY, 0.08f, 0.82f, false)

        apply(model)
    }

    private fun apply(model: Live2dModelRuntime) {
        val now = System.currentTimeMillis()
        val t = now / 1000.0

        var eyeOpen = 1f
        if (blinkEnabled) {
            if (blinkStartedAt < 0L && now >= nextBlinkAt) {
                blinkStartedAt = now
            }
            if (blinkStartedAt >= 0L) {
                val p = ((now - blinkStartedAt) / 180f).coerceIn(0f, 1f)
                eyeOpen = if (p < 0.5f) 1f - p * 2f else (p - 0.5f) * 2f
                if (p >= 1f) {
                    blinkStartedAt = -1L
                    nextBlinkAt = now + 2200L + ((Math.sin(t * 1.7) * 500.0 + 650.0).toLong())
                }
            }
        }
        model.setParameter("ParamEyeLOpen", eyeOpen)
        model.setParameter("ParamEyeROpen", eyeOpen)

        val breath = ((Math.sin(t * 2.1) + 1.0) * 0.5).toFloat()
        model.setParameter("ParamBreath", breath)

        model.setParameter("ParamAngleX", angleX)
        model.setParameter("ParamAngleY", angleY)
        model.setParameter("ParamAngleZ", angleZ)
        model.setParameter("ParamBodyAngleX", bodyAngleX)
        model.setParameter("ParamBodyAngleY", angleY * 0.2f)
        model.setParameter("ParamBodyAngleZ", angleZ * 0.3f)

        model.setParameter("ParamEyeBallX", Mth.clamp(angleX / 30f, -1f, 1f))
        model.setParameter("ParamEyeBallY", Mth.clamp(angleY / 20f, -1f, 1f))

        val hairX = angleX * 0.09f + (Math.sin(t * 2.8) * 1.2).toFloat()
        model.setParameter("ParamHairFront", hairX)
        model.setParameter("ParamHairSide", hairX)
        model.setParameter("ParamHairBack", hairX)

        earVelocity += (angleZ * 0.55f + angleX * 0.18f - earSpring) * 0.16f
        earVelocity *= 0.74f
        earSpring += earVelocity
        val ear = Mth.clamp(earSpring, -12f, 12f)
        model.setParameter("Param10", ear)
        model.setParameter("Param14", -ear)
    }

    private fun updateSpring(value: Float, target: Float, stiffness: Float, damping: Float, horizontal: Boolean): Float {
        var v = (if (horizontal) springVX else springVY) + (target - value) * stiffness
        v *= damping
        if (horizontal) {
            springVX = v
        } else {
            springVY = v
        }
        return value + v
    }

    private fun lerp(from: Float, to: Float, factor: Float): Float {
        return from + (to - from) * Mth.clamp(factor, 0f, 1f)
    }
}