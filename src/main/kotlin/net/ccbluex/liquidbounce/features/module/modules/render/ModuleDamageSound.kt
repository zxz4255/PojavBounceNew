/*
 * LiquidBounce nextgen / Minecraft 26.3
 *
 * ModuleDamageSound — play bingbingbing.ogg when any living entity takes damage.
 *
 * Resource:
 *   src/main/resources/resources/liquidbounce/sounds/bingbingbing.ogg
 *
 * Must register sound in HitFXRegistry (BINGBINGBING) or SoundManager stays silent.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.EntityHealthUpdateEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * DamageSound (MC 26.3)
 *
 * Triggers:
 *  - GameTick HP scan (primary, same approach as ModuleDamageParticles)
 *  - EntityHealthUpdateEvent (mixin setHealth)
 *  - AttackEntityEvent (local player attack, optional)
 */
object ModuleDamageSound : ClientModule("DamageSound", ModuleCategories.RENDER) {

    private val volume by float("Volume", 1.0f, 0.0f..2.0f)
    private val pitch by float("Pitch", 1.0f, 0.5f..2.0f)
    private val range by float("Range", 64f, 8f..128f)

    private val players by boolean("Players", true)
    private val mobs by boolean("Mobs", true)
    private val self by boolean("Self", false)

    private val minDamage by float("MinDamage", 0.05f, 0.0f..20f)
    private val cooldownMs by int("Cooldown", 40, 0..500, "ms")
    private val onAttack by boolean("OnAttack", true)

    private val soundEvent: SoundEvent =
        SoundEvent.createVariableRangeEvent(clientIdentifier("bingbingbing"))

    private val lastPlay = HashMap<Int, Long>()
    private val healthMap = Reference2FloatOpenHashMap<LivingEntity>()

    override fun onDisabled() {
        lastPlay.clear()
        healthMap.clear()
    }

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> {
        lastPlay.clear()
        healthMap.clear()
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (!onAttack) return@handler
        val target = event.entity
        if (target !is LivingEntity || !target.isAlive) return@handler
        if (!matchesTarget(target)) return@handler
        tryPlay(target, force = true)
    }

    @Suppress("unused")
    private val healthUpdateHandler = handler<EntityHealthUpdateEvent> { event ->
        if (event.old - event.new < minDamage) return@handler
        if (!matchesTarget(event.entity)) return@handler
        tryPlay(event.entity)
    }

    /**
     * Primary path — mirrors ModuleDamageParticles ON_TICK on 26.3:
     * Reference2FloatOpenHashMap.put returns previous (0f if absent).
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent>(priority = FIRST_PRIORITY) {
        val w = world ?: return@handler
        val p = player ?: return@handler
        val entities = w.entitiesForRendering()

        for (entity in entities) {
            if (entity !is LivingEntity) continue
            if (entity.tickCount == 0) continue
            if (!matchesTarget(entity)) continue
            if (p.distanceTo(entity) > range) continue

            val newHealth = entity.health
            val oldHealth = healthMap.put(entity, newHealth)
            // 0f means first sample (or truly 0 HP) — skip first observation
            if (oldHealth != 0f && oldHealth - newHealth >= minDamage) {
                tryPlay(entity)
            }
        }

        healthMap.keys.removeIf { it !in entities || it.isDeadOrDying }
    }

    private fun matchesTarget(entity: LivingEntity): Boolean {
        val p = player
        if (entity === p || (p != null && entity.id == p.id)) {
            return self
        }
        if (entity is Player) {
            return players
        }
        return mobs
    }

    private fun tryPlay(entity: LivingEntity, force: Boolean = false) {
        val p = player ?: return
        if (p.distanceTo(entity) > range) return

        val now = System.currentTimeMillis()
        val prev = lastPlay[entity.id] ?: 0L
        if (!force && now - prev < cooldownMs) return
        if (force && now - prev < 20L) return
        lastPlay[entity.id] = now
        playBing(entity)
    }

    private fun playBing(entity: LivingEntity) {
        val w = world ?: return
        val x = entity.x
        val y = entity.y + entity.bbHeight * 0.5
        val z = entity.z
        val vol = volume
        val pit = pitch
        // Client main thread (same as ModuleHitFX on 26.3)
        mc.execute {
            w.playLocalSound(x, y, z, soundEvent, SoundSource.PLAYERS, vol, pit, false)
        }
    }
}
