/*
 * LiquidBounce nextgen module
 *
 * Plays custom sound `bingbingbing.ogg` when any tracked entity takes damage.
 *
 * Sound path (must match clientIdentifier):
 *   src/main/resources/resources/liquidbounce/sounds/bingbingbing.ogg
 *
 * Also register the sound id in HitFXRegistry (one-liner) so SoundManager loads it —
 * see README in the zip.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.EntityHealthUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.boxedDistanceTo
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * DamageSound
 *
 * Detect entity health drop and play bingbingbing.ogg.
 */
object ModuleDamageSound : ClientModule("DamageSound", ModuleCategories.RENDER) {

    private val volume by float("Volume", 1.0f, 0.0f..2.0f)
    private val pitch by float("Pitch", 1.0f, 0.5f..2.0f)
    private val range by float("Range", 64f, 8f..128f)

    private val targets by multiEnumChoice("Targets", TargetFilter.PLAYERS, TargetFilter.MOBS)

    private enum class TargetFilter(override val tag: String) : Tagged {
        SELF("Self"),
        PLAYERS("Players"),
        MOBS("Mobs"),
        ONLY_ATTACKED("OnlyAttacked") // entities that pass combat filter (enemies)
    }

    /** Minimum HP drop to trigger (filters tiny regen jitter / rounding). */
    private val minDamage by float("MinDamage", 0.1f, 0.0f..20f)

    /** Cooldown per entity id (ms) to avoid double-fire same tick. */
    private val cooldownMs by int("Cooldown", 50, 0..1000, "ms")

    private val lastPlay = HashMap<Int, Long>()

    /**
     * Sound id must match file:
     * resources/liquidbounce/sounds/bingbingbing.ogg
     * → Identifier liquidbounce:bingbingbing
     */
    private val soundEvent: SoundEvent =
        SoundEvent.createVariableRangeEvent(clientIdentifier("bingbingbing"))

    override fun onDisabled() {
        lastPlay.clear()
    }

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> {
        lastPlay.clear()
    }

    @Suppress("unused")
    private val healthHandler = handler<EntityHealthUpdateEvent> { event ->
        if (!running) return@handler

        val entity = event.entity
        val dealt = event.old - event.new
        if (dealt < minDamage) return@handler

        if (!matchesTarget(entity)) return@handler

        val p = player ?: return@handler
        if (entity.boxedDistanceTo(p) > range) return@handler

        val now = System.currentTimeMillis()
        val prev = lastPlay[entity.id] ?: 0L
        if (now - prev < cooldownMs) return@handler
        lastPlay[entity.id] = now

        playBing(entity)
    }

    private fun matchesTarget(entity: LivingEntity): Boolean {
        val p = player
        val filters = targets
        if (filters.isEmpty()) return true

        if (TargetFilter.SELF in filters && p != null && entity.id == p.id) return true
        if (TargetFilter.PLAYERS in filters && entity is Player && entity.id != p?.id) return true
        if (TargetFilter.MOBS in filters && entity !is Player) return true
        if (TargetFilter.ONLY_ATTACKED in filters && entity.shouldBeAttacked()) return true

        return false
    }

    private fun playBing(entity: LivingEntity) {
        val w = world ?: return
        // Client-side local sound at entity position
        w.playLocalSound(
            entity.x,
            entity.y + entity.bbHeight * 0.5,
            entity.z,
            soundEvent,
            SoundSource.PLAYERS,
            volume,
            pitch,
            false
        )
    }
}
