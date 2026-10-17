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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Loads a Live2D Cubism model folder (model3.json + moc3 + textures) and turns
 * it into a [Live2dModelRuntime] plus the texture pixel data used by the
 * software rasterizer.
 *
 * Layout expected in the model directory:
 *   <dir>/<name>.model3.json
 *   <dir>/<name>.moc3
 *   <dir>/<any>.png                (textures referenced from model3.json)
 *   <dir>/<any>.motion3.json       (optional, auto-registered)
 *   <dir>/<any>.exp3.json          (optional, auto-registered)
 */
class Live2dModelLoader(private val modelsRoot: File) {

    /** A model ready for rendering. */
    class LoadedModel(
        val name: String,
        val runtime: Live2dModelRuntime,
        /** One ARGB pixel array per texture slot referenced by the moc3. */
        val textures: List<IntArray>,
        val textureWidths: IntArray,
        val textureHeights: IntArray,
    ) {
        fun textureCount(): Int = textures.size
    }

    private val cache = ConcurrentHashMap<String, LoadedModel>()

    /** Lists available model names (folders that contain a .model3.json). */
    fun listModels(): List<String> {
        val dirs = modelsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.filter { dir ->
            dir.listFiles { f -> f.name.endsWith(".model3.json") }?.isNotEmpty() == true
        }.map { it.name }.sorted()
    }

    /**
     * Loads (or returns a cached) model by name.
     * Throws [IllegalStateException] when the model cannot be parsed.
     */
    fun load(name: String): LoadedModel {
        cache[name]?.let { return it }
        val dir = File(modelsRoot, name)
        require(dir.isDirectory) { "Model folder not found: $dir" }
        val model3 = dir.listFiles { f -> f.name.endsWith(".model3.json") }?.firstOrNull()
            ?: throw IllegalStateException("No .model3.json in $dir")
        val json = model3.readText()
        val mocFile = extractJsonString(json, "\"Moc\"")
            ?: throw IllegalStateException("model3.json has no FileReferences.Moc")
        val mocPath = File(dir, mocFile)
        require(mocPath.isFile) { "Missing moc3: $mocPath" }

        val runtime = Live2dModelRuntime(mocPath.readBytes())

        // Load textures: the moc3 references texture slots by index; model3.json
        // lists the files in the same order.
        val texFiles = extractJsonStringArray(json, "\"Textures\"")
        val textures = ArrayList<IntArray>(texFiles.size)
        val widths = IntArray(texFiles.size)
        val heights = IntArray(texFiles.size)
        for ((i, rel) in texFiles.withIndex()) {
            val img = ImageIO.read(File(dir, rel)) ?: throw IllegalStateException("Cannot read texture $rel")
            val w = img.width
            val h = img.height
            val px = IntArray(w * h)
            img.getRGB(0, 0, w, h, px, 0, w)
            textures.add(px)
            widths[i] = w
            heights[i] = h
        }

        // Register motions and expressions (optional, keyed by file name).
        dir.listFiles { f -> f.name.endsWith(".motion3.json") }?.forEach { f ->
            runCatching {
                val obj = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
                val motion = com.ciallo.live2d.cubism.Live2dMotion.parse(f.name, obj)
                runtime.addMotion(f.name, motion)
            }
        }
        dir.listFiles { f -> f.name.endsWith(".exp3.json") }?.forEach { f ->
            runCatching {
                val obj = com.google.gson.JsonParser.parseString(f.readText()).asJsonObject
                val expr = com.ciallo.live2d.cubism.CubismExpression.parse(f.name.removeSuffix(".exp3.json"), obj)
                runtime.addExpression(expr.name, expr)
            }
        }

        val loaded = LoadedModel(name, runtime, textures, widths, heights)
        cache[name] = loaded
        return loaded
    }

    fun invalidate(name: String) {
        cache.remove(name)?.runtime?.close()
    }

    fun invalidateAll() {
        cache.values.forEach { it.runtime.close() }
        cache.clear()
    }

    // ------------------------------------------------------------- tiny JSON

    private fun extractJsonString(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx)
        if (colon < 0) return null
        val start = json.indexOf('"', colon + 1)
        if (start < 0) return null
        val end = json.indexOf('"', start + 1)
        if (end < 0) return null
        return json.substring(start + 1, end)
    }

    private fun extractJsonStringArray(json: String, key: String): List<String> {
        val idx = json.indexOf(key)
        if (idx < 0) return emptyList()
        val open = json.indexOf('[', idx)
        if (open < 0) return emptyList()
        val close = json.indexOf(']', open)
        if (close < 0) return emptyList()
        val body = json.substring(open + 1, close)
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }
}