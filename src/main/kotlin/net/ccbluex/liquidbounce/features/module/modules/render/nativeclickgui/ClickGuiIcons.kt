/*
 * Category / UI icons for native ClickGUI.
 * Uses the exact SVG assets from src-theme/public/img/clickgui/.
 *
 * Place the SVG (or rasterized PNG) files under:
 *   assets/liquidbounce/textures/clickgui/
 * Identifier path: liquidbounce:clickgui/<name>
 *
 * Existing files (copied from theme):
 *   icon-combat.svg, icon-movement.svg, icon-render.svg,
 *   icon-player.svg, icon-world.svg, icon-misc.svg,
 *   icon-exploit.svg, icon-fun.svg, icon-client.svg,
 *   icon-settings-expand.svg, icon-tick.svg, icon-tick-checked.svg, ...
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.minecraft.resources.Identifier

object ClickGuiIcons {

    private const val NS = "liquidbounce"
    private const val PREFIX = "clickgui/"

    val CLIENT = id("icon-client")
    val COMBAT = id("icon-combat")
    val MOVEMENT = id("icon-movement")
    val RENDER = id("icon-render")
    val PLAYER = id("icon-player")
    val WORLD = id("icon-world")
    val MISC = id("icon-misc")
    val EXPLOIT = id("icon-exploit")
    val FUN = id("icon-fun")
    val SETTINGS_EXPAND = id("icon-settings-expand")
    val TICK = id("icon-tick")
    val TICK_CHECKED = id("icon-tick-checked")
    val CROSS = id("icon-cross")
    val DRAG = id("icon-drag")
    val RESET = id("icon-reset")
    val OPEN_FILE = id("icon-open-file")

    private fun id(name: String): Identifier =
        Identifier.fromNamespaceAndPath(NS, PREFIX + name)

    /**
     * Map official ModuleCategory → theme icon.
     * Falls back to CLIENT icon.
     */
    fun forCategory(category: ModuleCategory): Identifier = when (category) {
        ModuleCategories.COMBAT -> COMBAT
        ModuleCategories.MOVEMENT -> MOVEMENT
        ModuleCategories.RENDER -> RENDER
        ModuleCategories.PLAYER -> PLAYER
        ModuleCategories.WORLD -> WORLD
        ModuleCategories.MISC -> MISC
        ModuleCategories.EXPLOIT -> EXPLOIT
        ModuleCategories.FUN -> FUN
        else -> CLIENT
    }

    fun forCategoryName(name: String): Identifier = when (name.lowercase()) {
        "combat" -> COMBAT
        "movement" -> MOVEMENT
        "render" -> RENDER
        "player" -> PLAYER
        "world" -> WORLD
        "misc" -> MISC
        "exploit" -> EXPLOIT
        "fun" -> FUN
        else -> CLIENT
    }
}
