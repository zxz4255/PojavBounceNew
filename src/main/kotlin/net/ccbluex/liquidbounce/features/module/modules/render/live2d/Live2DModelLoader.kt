/*
 * Live2DModelLoader —— 模型发现、moc3 / 纹理 / 表情 / 动作 / 物理 / 姿态加载。
 *
 * 模型目录不写死：扫描 <gamedir>/config/live2d/ 下所有含 *.model3.json 的目录，
 * 目录名即模型名。纹理以 DynamicTexture 注册进 TextureManager。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/live2d/Live2DModelLoader.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.live2d

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.resources.Identifier
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier

class Live2DModelLoader {

    /** <gamedir>/config/live2d */
    val modelsRoot: Path = mc.gameDirectory.toPath().resolve("config").resolve("live2d")

    /** 本加载器注册过的纹理，close 时统一释放。 */
    private val registered = HashMap<String, AbstractTexture>()

    /* --------------------------- 模型发现 --------------------------- */

    /** 目录名列表（只含真正带 model3.json 的目录），不写死任何名字。 */
    fun availableModels(): List<String> {
        if (!Files.isDirectory(modelsRoot)) return emptyList()
        return runCatching {
            Files.list(modelsRoot).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { findModel3(it) != null }
                    .map { it.fileName.toString() }
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList()
            }
        }.getOrElse {
            logger.warn("无法扫描 Live2D 模型目录 $modelsRoot", it)
            emptyList()
        }
    }

    fun modelDirectory(name: String): Path = modelsRoot.resolve(name)

    private fun findModel3(dir: Path): String? = runCatching {
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().lowercase().endsWith(".model3.json") }
                .map { it.fileName.toString() }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    fun readModel3(name: String): JsonObject? {
        val dir = modelDirectory(name)
        val file = findModel3(dir) ?: return null
        return readJson(dir.resolve(file))
    }

    /** 读取模型目录内的相对路径，拒绝越界。 */
    fun readBytes(name: String, relative: String): ByteArray? {
        val base = modelDirectory(name).toAbsolutePath().normalize()
        val target = base.resolve(relative.replace('\\', '/')).normalize()
        if (!target.startsWith(base)) return null
        if (!Files.isRegularFile(target)) return null
        return runCatching { Files.readAllBytes(target) }
            .onFailure { logger.warn("读取模型资源失败：$relative", it) }
            .getOrNull()
    }

    private fun readJson(path: Path): JsonObject? = runCatching {
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use {
            JsonParser.parseReader(it).asJsonObject
        }
    }.onFailure { logger.warn("解析 $path 失败", it) }.getOrNull()

    private fun readJson(name: String, relative: String): JsonObject? {
        val bytes = readBytes(name, relative) ?: return null
        return runCatching {
            JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
        }.onFailure { logger.warn("解析 $relative 失败", it) }.getOrNull()
    }

    /* ---------------------------- 纹理注册 ---------------------------- */

    fun resolveTexture(name: String, relative: String, index: Int): Identifier? {
        val bytes = readBytes(name, relative) ?: return null
        val identifier = Identifier.fromNamespaceAndPath(
            "live2d", "model/${sanitize(name)}/${sanitize(relative)}_$index",
        )
        if (registered.containsKey(identifier.toString())) return identifier

        val image = runCatching { NativeImage.read(ByteArrayInputStream(bytes)) }.getOrElse {
            logger.warn("解析纹理 $relative 失败", it)
            return null
        }
        val texture = DynamicTexture(Supplier { "Live2D $name #$index" }, image)
        mc.textureManager.register(identifier, texture)
        registered[identifier.toString()] = texture
        return identifier
    }

    private fun sanitize(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")

    /** 释放本加载器注册的全部纹理（把槽位换回 missing 再关闭）。 */
    fun releaseTextures() {
        if (registered.isEmpty()) return
        val fallback = runCatching {
            mc.textureManager.getTexture(MissingTextureAtlasSprite.getLocation())
        }.getOrNull()

        for ((key, texture) in registered) {
            runCatching {
                val identifier = Identifier.parse(key)
                if (fallback != null) mc.textureManager.register(identifier, fallback)
                texture.close()
            }
        }
        registered.clear()
    }

    /* ------------------------------ 加载 ------------------------------ */

    class LoadedModel(
        val name: String,
        val model: Live2DModel,
        val textures: List<Identifier>,
    ) : AutoCloseable {
        override fun close() = model.close()
    }

    fun load(name: String): LoadedModel {
        val model3 = readModel3(name)
            ?: throw IllegalStateException("模型 $name 缺少 model3.json")
        val refs = model3.getAsJsonObject("FileReferences")
            ?: throw IllegalStateException("模型 $name 的 model3.json 缺少 FileReferences")

        val mocFile = refs.get("Moc")?.asString
            ?: throw IllegalStateException("模型 $name 缺少 FileReferences.Moc")
        val mocBytes = readBytes(name, mocFile)
            ?: throw IllegalStateException("模型 $name 缺少 moc3：$mocFile")

        val model = Live2DModel.loadBytes(mocBytes)
        logger.info(
            "Live2D 模型 $name 载入：参数 ${model.core.parameterCount}，" +
                "drawable ${model.drawableCount}，画布 ${model.canvasWidth}x${model.canvasHeight}"
        )

        // 纹理
        val textures = ArrayList<Identifier>()
        refs.getAsJsonArray("Textures")?.forEachIndexed { index, element ->
            resolveTexture(name, element.asString, index)?.let { textures.add(it) }
        }
        if (textures.isEmpty()) {
            model.close()
            throw IllegalStateException("模型 $name 没有任何可用纹理")
        }

        // 表情
        refs.getAsJsonArray("Expressions")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val obj = element.asJsonObject
            val expressionName = obj.get("Name")?.asString ?: return@forEach
            val file = obj.get("File")?.asString ?: return@forEach
            loadExpression(model, name, expressionName, file)
        }
        scanFolder(name, ".exp3.json").forEach { file ->
            val base = file.substringAfterLast('/').removeSuffix(".exp3.json")
            if (!model.hasExpression(base)) loadExpression(model, name, base, file)
        }

        // 动作（key 用 model3.json 中的相对路径，便于事件按文件名匹配）
        refs.getAsJsonObject("Motions")?.entrySet()?.forEach { (_, value) ->
            if (!value.isJsonArray) return@forEach
            value.asJsonArray.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val file = element.asJsonObject.get("File")?.asString ?: return@forEach
                loadMotion(model, name, file)
            }
        }
        scanFolder(name, ".motion3.json").forEach { file ->
            if (!model.hasMotion(file)) loadMotion(model, name, file)
        }

        // 物理 / 姿态
        refs.get("Physics")?.asString?.let { file ->
            readJson(name, file)?.let { model.physics = CubismPhysics.parse(it) }
        }
        refs.get("Pose")?.asString?.let { file ->
            readJson(name, file)?.let { model.pose = CubismPose.parse(it) }
        }

        model.update()
        return LoadedModel(name, model, textures)
    }

    private fun loadExpression(model: Live2DModel, name: String, expressionName: String, file: String) {
        val json = readJson(name, file) ?: return
        runCatching { model.addExpression(expressionName, CubismExpression.parse(expressionName, json)) }
            .onFailure { logger.warn("加载表情 $expressionName 失败", it) }
    }

    private fun loadMotion(model: Live2DModel, name: String, file: String) {
        val json = readJson(name, file) ?: return
        runCatching { model.addMotion(file, Live2DMotion.parse(file, json)) }
            .onFailure { logger.warn("加载动作 $file 失败", it) }
    }

    /** 递归收集模型目录内指定后缀的文件（相对路径）。 */
    private fun scanFolder(name: String, suffix: String): List<String> {
        val base = modelDirectory(name)
        if (!Files.isDirectory(base)) return emptyList()
        return runCatching {
            Files.walk(base).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().lowercase().endsWith(suffix) }
                    .map { base.relativize(it).toString().replace('\\', '/') }
                    .sorted()
                    .toList()
            }
        }.getOrElse { emptyList() }
    }
}