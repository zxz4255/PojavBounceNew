/*
 * Live2DConfig —— 独立于模块设置的状态文件（位置、大小、模型、表情、事件映射）。
 *
 * 存到 <gamedir>/LiquidBounce/live2d.json，与模块自身的 Value 设置解耦：
 * 位置/大小允许通过鼠标拖拽、滚轮实时改动，不必每次触发一次配置写盘。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DConfig.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class Live2DConfig {

    var enabled: Boolean = true
    var model: String = ""
    var size: Int = 260
    var posX: Int = 16
    var posY: Int = 110

    var swayStrength: Float = 3.0f
    var smoothing: Float = 0.1f
    var followPitch: Boolean = true
    var blinkEnabled: Boolean = true
    var expressionsEnabled: Boolean = true
    var masksEnabled: Boolean = true

    var chatDragEnabled: Boolean = true
    var wheelResizeStep: Int = 8

    var soundBlinkEnabled: Boolean = false
    var soundBlinkThreshold: Float = 0.5f

    var idleMotionGroup: String = "Idle"
    var autoIdleMotion: Boolean = true

    val hiddenDrawables: MutableList<String> = mutableListOf()

    /** 事件名 → 动作。 */
    val events: MutableMap<String, EventAction> = LinkedHashMap()

    class EventAction {
        var type: String = "expression"
        var target: String = ""
        var value: Float = 0f
        var duration: Float = 0f
        var fade: Float = 0.3f
    }

    fun save(path: Path) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            val root = JsonObject()
            root.addProperty("enabled", enabled)
            root.addProperty("model", model)
            root.addProperty("size", size)
            root.addProperty("posX", posX)
            root.addProperty("posY", posY)
            root.addProperty("swayStrength", swayStrength)
            root.addProperty("smoothing", smoothing)
            root.addProperty("followPitch", followPitch)
            root.addProperty("blinkEnabled", blinkEnabled)
            root.addProperty("expressionsEnabled", expressionsEnabled)
            root.addProperty("masksEnabled", masksEnabled)
            root.addProperty("chatDragEnabled", chatDragEnabled)
            root.addProperty("wheelResizeStep", wheelResizeStep)
            root.addProperty("soundBlinkEnabled", soundBlinkEnabled)
            root.addProperty("soundBlinkThreshold", soundBlinkThreshold)
            root.addProperty("idleMotionGroup", idleMotionGroup)
            root.addProperty("autoIdleMotion", autoIdleMotion)
            root.add("hiddenDrawables", GSON.toJsonTree(hiddenDrawables))

            val eventsObject = JsonObject()
            for ((key, action) in events) {
                eventsObject.add(key, JsonObject().apply {
                    addProperty("type", action.type)
                    addProperty("target", action.target)
                    addProperty("value", action.value)
                    addProperty("duration", action.duration)
                    addProperty("fade", action.fade)
                })
            }
            root.add("events", eventsObject)

            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8)
        }.onFailure { logger.warn("保存 Live2D 配置失败", it) }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        val defaultPath: Path
            get() = ConfigSystem.rootFolder.toPath().resolve("live2d.json")

        fun load(path: Path = defaultPath): Live2DConfig {
            val config = Live2DConfig()
            if (!Files.isRegularFile(path)) return config
            runCatching {
                Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    root.get("enabled")?.let { config.enabled = it.asBoolean }
                    root.get("model")?.let { config.model = it.asString }
                    root.get("size")?.let { config.size = it.asInt }
                    root.get("posX")?.let { config.posX = it.asInt }
                    root.get("posY")?.let { config.posY = it.asInt }
                    root.get("swayStrength")?.let { config.swayStrength = it.asFloat }
                    root.get("smoothing")?.let { config.smoothing = it.asFloat }
                    root.get("followPitch")?.let { config.followPitch = it.asBoolean }
                    root.get("blinkEnabled")?.let { config.blinkEnabled = it.asBoolean }
                    root.get("expressionsEnabled")?.let { config.expressionsEnabled = it.asBoolean }
                    root.get("masksEnabled")?.let { config.masksEnabled = it.asBoolean }
                    root.get("chatDragEnabled")?.let { config.chatDragEnabled = it.asBoolean }
                    root.get("wheelResizeStep")?.let { config.wheelResizeStep = it.asInt }
                    root.get("soundBlinkEnabled")?.let { config.soundBlinkEnabled = it.asBoolean }
                    root.get("soundBlinkThreshold")?.let { config.soundBlinkThreshold = it.asFloat }
                    root.get("idleMotionGroup")?.let { config.idleMotionGroup = it.asString }
                    root.get("autoIdleMotion")?.let { config.autoIdleMotion = it.asBoolean }
                    root.getAsJsonArray("hiddenDrawables")?.forEach {
                        if (it.isJsonPrimitive) config.hiddenDrawables.add(it.asString)
                    }
                    root.getAsJsonObject("events")?.entrySet()?.forEach { (key, element) ->
                        if (!element.isJsonObject) return@forEach
                        val obj = element.asJsonObject
                        config.events[key] = EventAction().apply {
                            obj.get("type")?.let { type = it.asString }
                            obj.get("target")?.let { target = it.asString }
                            obj.get("value")?.let { value = it.asFloat }
                            obj.get("duration")?.let { duration = it.asFloat }
                            obj.get("fade")?.let { fade = it.asFloat }
                        }
                    }
                }
            }.onFailure { logger.warn("读取 Live2D 配置失败", it) }
            return config
        }
    }
}