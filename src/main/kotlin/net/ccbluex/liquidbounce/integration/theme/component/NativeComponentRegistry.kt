package net.ccbluex.liquidbounce.integration.theme.component

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.utils.render.Alignment

typealias ComponentFactory = (String, Boolean, Alignment, Array<HudComponentTweak>, Array<JsonObject>) -> HudComponent

object NativeComponentRegistry {
    private val factories = mutableMapOf<String, ComponentFactory>()

    fun register(name: String, factory: ComponentFactory) {
        factories[name] = factory
    }

    fun create(name: String, enabled: Boolean, alignment: Alignment, tweaks: Array<HudComponentTweak>, values: Array<JsonObject>): HudComponent? {
        return factories[name]?.invoke(name, enabled, alignment, tweaks, values)
    }
}
