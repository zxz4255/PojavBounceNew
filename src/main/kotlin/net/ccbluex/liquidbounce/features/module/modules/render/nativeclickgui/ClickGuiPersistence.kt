/*
 * Panel position / expand state persistence (mirrors web localStorage clickgui.panel.*)
 * Uses simple JSON via ConfigSystem-friendly map; call save/load from Screen.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.nativeclickgui

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.ccbluex.liquidbounce.utils.client.mc
import java.io.File

object ClickGuiPersistence {

    private val gson = Gson()
    private val type = object : TypeToken<Map<String, ClickGuiPanel.PanelState>>() {}.type

    private fun file(): File {
        val dir = File(mc.gameDirectory, "liquidbounce")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "native-clickgui-panels.json")
    }

    fun load(): Map<String, ClickGuiPanel.PanelState> {
        val f = file()
        if (!f.exists()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, ClickGuiPanel.PanelState>>(f.readText(), type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    fun save(panels: List<ClickGuiPanel>) {
        val map = panels.associate { it.category.tag to it.saveState() }
        runCatching {
            file().writeText(gson.toJson(map))
        }
    }
}
