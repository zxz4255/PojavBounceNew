/*
 * OpaiConfigPanel / OpaiTargetsPanel / OpaiConfigRepository
 * —— Samsara 配置面板、Targets 面板、配置仓储的 1:1 移植。
 *
 * 后端换成 LiquidBounce 的 ConfigSystem（modules 根配置）与 userConfigsFolder。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/opai/OpaiConfigPanel.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.opai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.fileGson
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.FIELD_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.FIELD_Y
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.MODE_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Companion.OPTION_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiContentLayout.Rect
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.BACK
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.DELETE
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.FOLDER
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.GEAR
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.LOAD
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.PLUS
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.REFRESH
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.SAVE
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiIcons.Icon.UPLOAD
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.HEADER_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.ROW_H
import net.ccbluex.liquidbounce.features.module.modules.render.opai.OpaiLayout.TEXT_PAD
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* ======================== 后端：LiquidBounce ConfigSystem ======================== */

/**
 * 与 Samsara OpaiConfigRepository 等价的实现：
 * 名称校验、原子写、拒绝符号链接、错误文案保持一致；读写对象换成 LiquidBounce 的 modules 根配置。
 */
class OpaiConfigRepository : OpaiConfigPanel.Backend {

    private val directory: Path
        get() = ConfigSystem.userConfigsFolder.toPath().toAbsolutePath().normalize()

    private fun ensureDirectory(): Path {
        Files.createDirectories(directory)
        return directory
    }

    private fun file(name: String): Path {
        val dir = ensureDirectory()
        val target = dir.resolve(OpaiConfigPanel.validName(name) + ".json").normalize()
        if (target.parent != dir) throw IOException("Invalid configuration path")
        if (Files.isSymbolicLink(target)) throw IOException("Linked configuration files are not supported")
        return target
    }

    override fun names(): List<String> {
        val dir = ensureDirectory()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                .map { it.fileName.toString() }
                .filter { it.endsWith(".json") }
                .map { it.substring(0, it.length - 5) }
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList()
        }
    }

    private fun snapshot(): JsonObject =
        ConfigSystem.serializeValueGroup(ModuleManager.modulesConfig, fileGson)

    override fun create(name: String, blank: Boolean) {
        val json = if (blank) {
            // 空白配置：仅写名称与空 value 数组，加载时全部保持当前值。
            JsonObject().apply {
                addProperty("name", ModuleManager.modulesConfig.name)
                add("value", JsonArray())
            }
        } else {
            snapshot()
        }
        Files.writeString(
            file(name), fileGson.toJson(json), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
        )
    }

    override fun update(name: String) {
        val target = file(name)
        if (!Files.isRegularFile(target)) throw IOException("Configuration no longer exists")
        atomicWrite(target, fileGson.toJson(snapshot()))
    }

    override fun load(name: String) {
        val json = try {
            JsonParser.parseString(Files.readString(file(name), StandardCharsets.UTF_8)).asJsonObject
        } catch (error: RuntimeException) {
            throw IOException("Invalid configuration JSON", error)
        }
        try {
            ConfigSystem.deserializeValueGroup(ModuleManager.modulesConfig, json)
        } catch (error: RuntimeException) {
            throw IOException("Invalid configuration values", error)
        }
        ConfigSystem.store(ModuleManager.modulesConfig)
    }

    override fun delete(name: String) {
        Files.delete(file(name))
    }

    override fun openFolder() {
        val dir = ensureDirectory()
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> "explorer.exe"
            os.contains("mac") -> "open"
            else -> "xdg-open"
        }
        runCatching { ProcessBuilder(command, dir.toString()).start() }
            .onFailure { logger.warn("Unable to open configuration folder", it) }
    }

    private fun atomicWrite(target: Path, content: String) {
        Files.createDirectories(target.parent)
        if (Files.isSymbolicLink(target)) throw IOException("Linked configuration files are not supported")
        val temp = Files.createTempFile(target.parent, ".opai-", ".tmp")
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

/* =============================== Targets 面板 =============================== */

/**
 * Samsara OpaiTargetsPanel 的 1:1 移植。
 * 选项来源换成 LiquidBounce 的 MultiChoiceListValue（Combat / Visual 两组，标签与顺序一致）。
 */
class OpaiTargetsPanel(
    private val combat: OpaiTargetGroup,
    private val visual: OpaiTargetGroup,
) {
    private val reveal = OpaiMotion(0f, 40.0)
    private var open = -1
    private var expanded = false
    private var scroll = 0f
    private var listHeight = 0f

    private fun group(group: Int): OpaiTargetGroup = if (group == 0) combat else visual
    private fun options(group: Int): List<String> = group(group).options

    fun close() {
        expanded = false
        reveal.approach(0f, OpaiMotion.now())
    }

    fun update(available: Float, now: Double) {
        val count = if (open < 0) 0 else options(open).size
        listHeight = if (open < 0) 0f
        else min(count * OPTION_H, max(OPTION_H, available - MODE_H * 2 - 6))
        scroll = if (open < 0) 0f else scroll.coerceIn(0f, max(0f, count * OPTION_H - listHeight))
        reveal.approach(if (expanded) 1f else 0f, now)
    }

    fun height(): Float = MODE_H * 2 + 6 + listHeight * reveal.value()

    private fun groupTop(group: Int): Float =
        3 + group * MODE_H + (if (group == 1 && open == 0) listHeight * reveal.value() else 0f)

    fun wheel(amount: Double): Boolean {
        if (open >= 0 && expanded) {
            val count = options(open).size
            scroll = (scroll - amount.toFloat() * OPTION_H * 2).coerceIn(0f, max(0f, count * OPTION_H - listHeight))
        }
        return true
    }

    fun click(x: Float, y: Float, width: Float) {
        if (x < TEXT_PAD - 2 || x >= width - TEXT_PAD + 2) return
        if (open >= 0) {
            val top = groupTop(open) + FIELD_Y + FIELD_H
            if (y >= top && y < top + listHeight * reveal.value()) {
                if (expanded) {
                    val option = ((y - top + scroll) / OPTION_H).toInt()
                    if (option in options(open).indices) group(open).toggle(option)
                }
                return
            }
        }
        for (index in 0..1) {
            val top = groupTop(index) + FIELD_Y
            if (y < top || y >= top + FIELD_H) continue
            if (open == index) {
                expanded = !expanded
            } else {
                open = index
                expanded = true
                scroll = 0f
                reveal.snap(0f, OpaiMotion.now())
            }
            return
        }
    }

    fun paint(s: OpaiSurface, p: OpaiStyle.Palette, x: Float, y: Float, width: Float) {
        for (index in 0..1) {
            val group = group(index)
            val options = options(index)
            val top = y + groupTop(index)
            val left = x + TEXT_PAD - 2
            val fieldWidth = width - TEXT_PAD * 2 + 4
            s.text(group.title, x + TEXT_PAD, top + 8, 8.5f, fieldWidth, p.text)
            s.rounded(left, top + FIELD_Y, fieldWidth, FIELD_H, 2f, 0xFF2A2A2D.toInt())
            s.text(
                "${group.selectedCount} Selected", left + 6, top + FIELD_Y + FIELD_H / 2, 8.5f,
                fieldWidth - 12, p.text,
            )
            s.rect(
                left + 1, top + FIELD_Y + FIELD_H - 1, fieldWidth - 2, 1f,
                if (open == index && expanded) p.accent else p.fieldLine,
            )
            if (open != index || reveal.value() <= 0f) continue
            val listTop = top + FIELD_Y + FIELD_H
            val shown = listHeight * reveal.value()
            s.rounded(left, listTop, fieldWidth, shown, 2f, 0xFF2A2A2D.toInt())
            s.clip(left, listTop, fieldWidth, shown) {
                for (i in options.indices) {
                    val rowY = listTop + i * OPTION_H - scroll
                    if (rowY + OPTION_H <= listTop || rowY >= listTop + shown) continue
                    if (group.selected(i)) {
                        s.rect(
                            left + 1, rowY, fieldWidth - 2, OPTION_H,
                            OpaiSurface.mix(0xFF2A2A2D.toInt(), p.accent, .10f),
                        )
                        s.icon(Icon.CHECK, left + 5.5f, rowY + 6, 8f, p.text)
                    }
                    s.text(options[i], left + 17, rowY + OPTION_H / 2, 8.5f, fieldWidth - 23, p.text)
                }
                val total = options.size * OPTION_H
                if (total > listHeight) {
                    val thumb = max(8f, listHeight * listHeight / total)
                    s.rounded(
                        left + fieldWidth - 3,
                        listTop + (listHeight - thumb) * scroll / (total - listHeight),
                        2f, thumb, 1f, p.scrollbar,
                    )
                }
            }
        }
    }
}

/* ================================ 配置面板 ================================ */

class OpaiConfigPanel(private val backend: Backend) {

    interface Backend {
        fun names(): List<String>
        fun create(name: String, blank: Boolean)
        fun update(name: String)
        fun load(name: String)
        fun delete(name: String)
        fun openFolder()
    }

    enum class Page { CONFIGS, ADD, SETTINGS, TARGETS }

    companion object {
        private const val INSET = 8f
        private const val LIST_ROW = 24f
        private const val BUTTON_H = 18f
        private const val CHIP_H = 11.75f
        private const val CHIP_TEXT_SIZE = 7.25f
        private const val CHIP_RADIUS = 3.875f
        private const val CHIP_RIGHT_OVERHANG = 1.125f
        private const val CHIP_PADDING = 3.75f
        private const val CHIP_BACKGROUND = 0xFF131316.toInt()

        /** 与 Samsara ConfigManager.validName 相同的校验规则。 */
        fun validName(raw: String): String {
            var name = raw.trim()
            if (name.lowercase().endsWith(".json")) name = name.substring(0, name.length - 5)
            val forbidden = "<>:\"/\\|?*"
            require(
                name.isNotBlank() && name.length <= 64 && !name.endsWith(".") && !name.endsWith(" ") &&
                    name.none { it.code < 32 || forbidden.indexOf(it) >= 0 } &&
                    name != "." && name != ".." &&
                    !name.uppercase().matches(Regex("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
            ) { "Enter a valid configuration name" }
            return name
        }
    }

    private val height = OpaiMotion(0f, 40.0)
    private val pageFade = OpaiMotion(1f, 25.0)
    private val scrollMotion = OpaiMotion(0f, 20.0)
    private val popup = OpaiMotion(0f, 18.0)
    private val feedbacks = HashMap<String, OpaiFeedback>()
    private val selectionAnims = HashMap<String, OpaiMotion>()

    private val names = ArrayList<String>()
    private var page = Page.CONFIGS
    private var outgoing: Page? = null
    private var selected: String? = null
    private var input = ""
    private var message = ""
    private var cursor = 0
    private var selection = 0
    private var messageUntil = 0.0

    private var x = 0f
    private var y = 0f
    private var width = OpaiLayout.COL_W
    private var scrollTarget = 0f
    private var viewportHeight = Float.MAX_VALUE
    private var dockRight = 0f
    private var dockY = 0f
    private var chipWidth = 76f
    private var expanded = false
    private var focused = false
    private var dragX = 0f
    private var dragY = 0f
    private var scrollbarGrab = 0f
    private var dragging = false
    private var draggingScrollbar = false
    private var movedHeader = false

    var targets: OpaiTargetsPanel? = null

    fun position(x: Float, y: Float, width: Float) {
        this.x = x
        this.y = y
        this.width = width
        this.dockRight = x + width
        this.dockY = y
    }

    fun dock(viewportWidth: Float, top: Float) {
        this.dockRight = viewportWidth
        this.dockY = max(4f, top)
    }

    fun collapse() {
        expanded = false
        focused = false
        dragging = false
        draggingScrollbar = false
        targets?.close()
        popup.approach(0f, OpaiMotion.now())
    }

    fun open() {
        this.x = max(4f, dockRight - width - 4)
        this.y = dockY.coerceIn(4f, max(4f, viewportHeight - HEADER_H - 76))
        expanded = true
        popup.approach(1f, OpaiMotion.now())
        height.approach(pageHeight(page), OpaiMotion.now())
    }

    fun fitViewport(viewportWidth: Int, viewportHeight: Int) {
        this.viewportHeight = viewportHeight.toFloat()
        this.width = min(OpaiLayout.COL_W, max(80f, viewportWidth - 8f))
        this.x = this.x.coerceIn(4f, max(4f, viewportWidth - this.width - 4))
        this.y = this.y.coerceIn(4f, max(4f, viewportHeight - HEADER_H - 76))
    }

    fun refresh() {
        try {
            val refreshed = backend.names().sortedWith(String.CASE_INSENSITIVE_ORDER)
            names.clear()
            names.addAll(refreshed)
            if (selected !in names) selected = names.firstOrNull()
            selectionAnims.keys.retainAll(names.toSet())
            for (name in names) {
                selectionAnims.getOrPut(name) { OpaiMotion(if (name == selected) 1f else 0f, 25.0) }
            }
            scrollTarget = scrollTarget.coerceIn(0f, maximumScroll())
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun editor(input: String, cursor: Int, selection: Int) {
        this.input = input
        this.cursor = cursor.coerceIn(0, input.length)
        this.selection = selection.coerceIn(0, input.length)
    }

    fun focused(): Boolean = focused && page == Page.ADD && expanded
    fun blur() { focused = false }
    fun adding(): Boolean = page == Page.ADD
    fun input(): String = input
    fun selected(): String? = selected
    fun names(): List<String> = names.toList()
    fun expanded(): Boolean = expanded
    fun captured(): Boolean = dragging || draggingScrollbar
    fun bodyHeight(): Float = height.value()

    fun cursorAt(surface: OpaiSurface, mouseX: Double): Int {
        val mx = inputX(mouseX)
        val inner = width - INSET * 2
        val offset = max(0f, surface.textWidth(input.substring(0, cursor), 8.3f) - inner + 14)
        val desired = (mx - x - INSET - 6 + offset).toFloat()
        var best = 0
        var distance = Float.MAX_VALUE
        for (index in 0..input.length) {
            if (index > 0 && index < input.length && Character.isLowSurrogate(input[index])) continue
            val delta = abs(surface.textWidth(input.substring(0, index), 8.3f) - desired)
            if (delta < distance) {
                distance = delta
                best = index
            }
        }
        return best
    }

    fun update(now: Double) {
        targets?.update(max(86f, viewportHeight - y - HEADER_H - 8), now)
        popup.approach(if (expanded) 1f else 0f, now)
        height.approach(if (expanded) pageHeight(page) else 0f, now)
        pageFade.approach(1f, now)
        scrollTarget = scrollTarget.coerceIn(0f, maximumScroll())
        scrollMotion.approach(scrollTarget, now)
        if (pageFade.value() == 1f) outgoing = null
        for ((name, anim) in selectionAnims) anim.approach(if (name == selected) 1f else 0f, now)
    }

    private fun listHeight(): Float {
        val available = max(24f, viewportHeight - y - HEADER_H - 62)
        return min(min(144f, available), max(24f, names.size * LIST_ROW))
    }

    private fun maximumScroll(): Float = max(0f, names.size * LIST_ROW - listHeight())

    private fun pageHeight(page: Page): Float = when (page) {
        Page.CONFIGS -> 3 + listHeight() + 5 + BUTTON_H * 2 + 2 + 8
        Page.ADD -> 3 + 21 + 7 + BUTTON_H * 2 + 2 + 8
        Page.SETTINGS -> 5 * ROW_H + 4
        Page.TARGETS -> targets?.height() ?: 0f
    }

    private fun switchPage(page: Page) {
        if (this.page == page) return
        outgoing = this.page
        this.page = page
        if (page != Page.TARGETS) targets?.close()
        pageFade.snap(0f, OpaiMotion.now())
        focused = false
        message = ""
    }

    fun contains(mouseX: Double, mouseY: Double): Boolean {
        if (!expanded && chip().contains(mouseX, mouseY)) return true
        val scale = popupScale()
        return popup.value() > .005f &&
            Rect(x + width * (1 - scale), y, width * scale, (HEADER_H + height.value()) * scale)
                .contains(mouseX, mouseY)
    }

    private fun chip(): Rect = Rect(dockRight + CHIP_RIGHT_OVERHANG - chipWidth, dockY + .125f, chipWidth, CHIP_H)

    private fun popupScale(): Float {
        val t = popup.value()
        return .86f + .14f * t + .07f * sin(Math.PI * t).toFloat()
    }

    private fun inputX(mouseX: Double): Double = x + width + (mouseX - x - width) / popupScale()
    private fun inputY(mouseY: Double): Double = y + (mouseY - y) / popupScale()

    fun release() {
        val close = dragging && !movedHeader
        dragging = false
        draggingScrollbar = false
        if (close) collapse()
    }

    fun drag(mouseX: Double, mouseY: Double, viewportWidth: Int, viewportHeight: Int) {
        if (dragging) {
            if (abs(mouseX - x - dragX) + abs(mouseY - y - dragY) <= 3 && !movedHeader) return
            movedHeader = true
            x = (mouseX - dragX).toFloat().coerceIn(4f, max(4f, viewportWidth - width - 4f))
            y = (mouseY - dragY).toFloat().coerceIn(
                4f, max(4f, viewportHeight - HEADER_H - (if (expanded) 76f else 8f)),
            )
        } else if (draggingScrollbar) {
            val my = inputY(mouseY)
            val visible = listHeight()
            val track = visible - 4
            val thumb = min(track, max(12f, track * visible / max(1f, names.size * LIST_ROW)))
            val travel = track - thumb
            val top = (my - y - HEADER_H - 3 - scrollbarGrab - 2).toFloat()
            scrollTarget = if (travel <= 0f) 0f else (top / travel).coerceIn(0f, 1f) * maximumScroll()
            scrollMotion.snap(scrollTarget, OpaiMotion.now())
        }
    }

    fun wheel(amount: Double): Boolean {
        if (expanded && page == Page.TARGETS) return targets?.wheel(amount) ?: true
        if (!expanded || page != Page.CONFIGS) return true
        scrollTarget = (scrollTarget - amount.toFloat() * LIST_ROW * 2).coerceIn(0f, maximumScroll())
        return true
    }

    fun escape(): Boolean {
        if (page == Page.CONFIGS) {
            if (!expanded) return false
            collapse()
            return true
        }
        switchPage(if (page == Page.TARGETS) Page.SETTINGS else Page.CONFIGS)
        return true
    }

    fun click(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!contains(mouseX, mouseY)) {
            focused = false
            return false
        }
        if (!expanded) {
            if (chip().contains(mouseX, mouseY) && (button == 0 || button == 1)) {
                val chip = chip()
                feedback("chip").click(
                    (mouseX - chip.x).toFloat(), (mouseY - chip.y).toFloat(), chip.width, CHIP_H, OpaiMotion.now(),
                )
                open()
            }
            return true
        }
        val rawX = mouseX
        val rawY = mouseY
        val mx = inputX(mouseX)
        val my = inputY(mouseY)
        val localX = (mx - x).toFloat()
        val localY = (my - y).toFloat()
        if (localY < HEADER_H) {
            if (button == 1) {
                collapse()
            } else if (button == 0) {
                if (page != Page.CONFIGS && localX < 20) {
                    headerClick(BACK)
                    switchPage(if (page == Page.TARGETS) Page.SETTINGS else Page.CONFIGS)
                } else if (page == Page.CONFIGS && localX >= width - 39 && localX < width - 23) {
                    headerClick(PLUS)
                    switchPage(Page.ADD)
                } else if (page == Page.CONFIGS && localX >= width - 22) {
                    headerClick(GEAR)
                    switchPage(Page.SETTINGS)
                } else {
                    dragging = true
                    movedHeader = false
                    dragX = (rawX - x).toFloat()
                    dragY = (rawY - y).toFloat()
                }
            }
            return true
        }
        if (!expanded || button != 0) return true
        val by = localY - HEADER_H
        when (page) {
            Page.CONFIGS -> {
                if (by >= 3 && by < 3 + listHeight() && localX >= INSET && localX < width - INSET) {
                    if (maximumScroll() > 0 && localX > width - INSET - 6) {
                        val track = listHeight() - 4
                        val thumb = min(track, max(12f, track * listHeight() / (names.size * LIST_ROW)))
                        val top = 2 + (track - thumb) * scrollMotion.value() / maximumScroll()
                        val clicked = by - 3
                        scrollbarGrab = if (clicked >= top && clicked < top + thumb) clicked - top else thumb / 2
                        draggingScrollbar = true
                    } else {
                        val index = ((by - 3 + scrollMotion.value()) / LIST_ROW).toInt()
                        if (index in names.indices) {
                            selected = names[index]
                            feedback("row:$selected").click(
                                localX - INSET, (by - 3 + scrollMotion.value()) % LIST_ROW,
                                width - INSET * 2, LIST_ROW, OpaiMotion.now(),
                            )
                            update(OpaiMotion.now())
                        }
                    }
                } else {
                    val controlsY = 3 + listHeight() + 5
                    if (by >= controlsY && by < controlsY + BUTTON_H) {
                        val gap = 6f
                        val cell = (width - INSET * 2 - gap * 3) / 4
                        for (i in 0..3) {
                            if (localX >= INSET + i * (cell + gap) && localX < INSET + i * (cell + gap) + cell) {
                                clickButton(
                                    arrayOf("REFRESH", "SAVE", "LOAD", "DELETE")[i],
                                    localX - INSET - i * (cell + gap), by - controlsY, cell,
                                )
                                action(i)
                                break
                            }
                        }
                    } else if (by >= controlsY + BUTTON_H + 2 && by < controlsY + BUTTON_H * 2 + 2 &&
                        localX >= INSET && localX < width / 2 - 3
                    ) {
                        clickButton("Folder", localX - INSET, by - controlsY - BUTTON_H - 2, (width - INSET * 2 - 6) / 2)
                        runCatching { backend.openFolder() }.onFailure { fail(it) }
                    } else if (by >= controlsY + BUTTON_H + 2 && by < controlsY + BUTTON_H * 2 + 2 &&
                        localX >= width / 2 + 3 && localX < width - INSET
                    ) {
                        clickButton(
                            "Upload", localX - width / 2 - 3, by - controlsY - BUTTON_H - 2,
                            (width - INSET * 2 - 6) / 2,
                        )
                    }
                    // Upload 与原实现一致：视觉占位，不发网络请求。
                }
            }

            Page.ADD -> {
                focused = by >= 3 && by < 24 && localX >= INSET && localX < width - INSET
                if (localX >= INSET && localX < width - INSET) {
                    if (by >= 31 && by < 31 + BUTTON_H) {
                        clickButton("Create", localX - INSET, by - 31, width - INSET * 2)
                        create(false)
                    } else if (by >= 51 && by < 51 + BUTTON_H) {
                        clickButton("Create Blank", localX - INSET, by - 51, width - INSET * 2)
                        create(true)
                    }
                }
            }

            Page.TARGETS -> targets?.click(localX, by, width)

            Page.SETTINGS -> {
                if (by >= 0 && by < ROW_H) switchPage(Page.CONFIGS)
                else if (by >= ROW_H * 3 && by < ROW_H * 4) switchPage(Page.TARGETS)
                // 其余 Settings 行为保留占位（与原实现一致）。
            }
        }
        return true
    }

    private fun feedback(key: String): OpaiFeedback = feedbacks.getOrPut(key) { OpaiFeedback() }

    private fun clickButton(key: String, x: Float, y: Float, width: Float) {
        feedback(key).click(x, y, width, BUTTON_H, OpaiMotion.now())
    }

    private fun headerClick(icon: Icon) {
        feedback("header:$icon").click(5f, 5f, 10f, 10f, OpaiMotion.now())
    }

    private fun action(index: Int) {
        try {
            if (index == 0) {
                refresh()
                return
            }
            val target = selected ?: return
            when (index) {
                1 -> backend.update(target)
                2 -> backend.load(target)
                3 -> backend.delete(target)
            }
            refresh()
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun create(blank: Boolean) {
        try {
            val name = validName(input)
            if (names.any { it.equals(name, true) }) throw IllegalArgumentException("Name already exists")
            backend.create(name, blank)
            refresh()
            selected = name
            switchPage(Page.CONFIGS)
            val top = names.indexOf(name) * LIST_ROW
            scrollTarget = (top + LIST_ROW - listHeight()).coerceIn(0f, maximumScroll())
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        message = error.message ?: "Configuration failed"
        messageUntil = OpaiMotion.now() + 3500
    }

    fun paint(s: OpaiSurface, palette: OpaiStyle.Palette, mouseX: Double, mouseY: Double, now: Double) {
        chipWidth = min(dockRight, s.textWidth("Configurations", CHIP_TEXT_SIZE) + CHIP_PADDING)
        val opacity = popup.value()
        if (opacity < 1f) {
            val factor = (1 - opacity) * (1 - opacity) * (1 - opacity) * (1 - opacity)
            s.opacity(factor) {
                val chip = chip()
                s.rounded(chip.x, chip.y, chip.width, CHIP_H, CHIP_RADIUS, CHIP_BACKGROUND)
                feedback("chip").paint(s, chip.x, chip.y, chip.width, CHIP_H, CHIP_RADIUS, palette.accent, now)
                s.text(
                    "Configurations", chip.x + 2, chip.y + CHIP_H / 2, CHIP_TEXT_SIZE,
                    chip.width - CHIP_PADDING, palette.text,
                )
            }
        }
        if (opacity <= .005f) return
        val mx = inputX(mouseX)
        val my = inputY(mouseY)
        s.opacity(opacity) {
            s.scale(popupScale(), x + width, y) {
                paintPopup(s, palette, mx, my, now)
            }
        }
    }

    private fun paintPopup(s: OpaiSurface, palette: OpaiStyle.Palette, mouseX: Double, mouseY: Double, now: Double) {
        val body = height.value()
        if (body > .01f) s.panel(x, y + HEADER_H, width, body, false, palette.body)
        if (body > .5f) s.panel(x, y, width, HEADER_H, true, palette.header)
        else s.rounded(x, y, width, HEADER_H, OpaiLayout.RADIUS, palette.header)
        val title = when (page) {
            Page.ADD -> "Add"
            Page.SETTINGS -> "Settings"
            Page.TARGETS -> "Targets"
            else -> "Configurations"
        }
        var titleX = x + TEXT_PAD
        if (page != Page.CONFIGS) {
            headerIcon(s, palette, BACK, x + 8, 8f, mouseX, mouseY, now)
            titleX += 10
        }
        s.text(
            title, titleX, y + HEADER_H / 2, OpaiStyle.HEADER_TEXT_SIZE,
            width - (if (page == Page.CONFIGS) 48f else 30f), palette.text,
        )
        if (page == Page.CONFIGS) {
            headerIcon(s, palette, PLUS, x + width - 37, 10f, mouseX, mouseY, now)
            headerIcon(s, palette, GEAR, x + width - 21, 10f, mouseX, mouseY, now)
        }
        if (body <= .01f) return
        s.clip(x, y + HEADER_H, width, body) {
            outgoing?.let { previous ->
                s.opacity(1 - pageFade.value()) { paintPage(s, palette, previous, mouseX, mouseY) }
            }
            s.opacity(pageFade.value()) { paintPage(s, palette, page, mouseX, mouseY) }
        }
        if (message.isNotEmpty() && now < messageUntil) {
            s.rounded(x + 3, y + HEADER_H + body + 3, width - 6, 15f, 3f, palette.field)
            s.text(message, x + 7, y + HEADER_H + body + 10.5f, 7f, width - 14, 0xFFFF9999.toInt())
        }
    }

    private fun headerIcon(
        s: OpaiSurface, palette: OpaiStyle.Palette, icon: Icon, x: Float, size: Float,
        mx: Double, my: Double, now: Double,
    ) {
        val ink = feedback("header:$icon")
        val hover = ink.hover(Rect(x - 3, y + 3, size + 6, 16f).contains(mx, my), now)
        s.scale(1 - .12f * ink.press(now), x + size / 2, y + 6 + size / 2) {
            s.icon(icon, x, y + 6, size, OpaiSurface.mix(palette.fieldLine, palette.accent, hover))
        }
    }

    private fun paintPage(s: OpaiSurface, p: OpaiStyle.Palette, page: Page, mx: Double, my: Double) {
        val left = x + INSET
        val top = y + HEADER_H + 3
        val inner = width - INSET * 2
        when (page) {
            Page.CONFIGS -> {
                s.rounded(left, top, inner, listHeight(), 8f, OpaiSurface.mix(p.field, 0xFF202020.toInt(), .45f))
                s.clip(left, top, inner, listHeight()) {
                    if (names.isEmpty()) {
                        s.text("No configurations", left + 6, top + 12, 8.3f, inner - 12, p.fieldLine)
                    } else {
                        for (i in names.indices) {
                            val name = names[i]
                            val rowY = top + LIST_ROW * i - scrollMotion.value()
                            if (rowY + LIST_ROW <= top || rowY >= top + listHeight()) continue
                            val selectedAmount = selectionAnims[name]?.value() ?: 0f
                            val highlight = (p.accent and 0xFFFFFF) or ((255 * selectedAmount).toInt() shl 24)
                            if (selectedAmount > 0) {
                                s.rounded(
                                    left, rowY, inner, LIST_ROW,
                                    if (i == 0 || i == names.size - 1) 8f else 0f, highlight,
                                )
                                if (i == 0 && names.size > 1) {
                                    s.rect(left, rowY + 8, inner, LIST_ROW - 8, highlight)
                                } else if (i == names.size - 1 && i > 0) {
                                    s.rect(left, rowY, inner, LIST_ROW - 8, highlight)
                                }
                            } else if (Rect(left, rowY, inner, LIST_ROW).contains(mx, my)) {
                                s.rect(left, rowY, inner, LIST_ROW, p.hover)
                            }
                            feedback("row:$name").paint(s, left, rowY, inner, LIST_ROW, 0f, p.accent, OpaiMotion.now())
                            s.text(
                                "$name.json", left + 6, rowY + LIST_ROW / 2, 8.3f, inner - 12,
                                OpaiSurface.mix(p.text, p.enabledText, selectedAmount),
                            )
                        }
                    }
                    if (maximumScroll() > 0) {
                        val track = listHeight() - 4
                        val thumb = min(track, max(12f, track * listHeight() / (names.size * LIST_ROW)))
                        s.rounded(
                            left + inner - 3,
                            top + 2 + (track - thumb) * scrollMotion.value() / maximumScroll(),
                            2f, thumb, 1f, p.scrollbar,
                        )
                    }
                }
                val y = top + listHeight() + 5
                val gap = 6f
                val cell = (inner - gap * 3) / 4
                val icons = arrayOf(REFRESH, SAVE, LOAD, DELETE)
                for (i in 0..3) {
                    button(
                        s, p, left + i * (cell + gap), y, cell, "", icons[i],
                        i == 0 || selected != null, mx, my,
                    )
                }
                val half = (inner - gap) / 2
                button(s, p, left, y + BUTTON_H + 2, half, "Folder", FOLDER, true, mx, my)
                button(s, p, left + half + gap, y + BUTTON_H + 2, half, "Upload", UPLOAD, true, mx, my)
            }

            Page.ADD -> {
                s.rounded(left, top, inner, 21f, 1.5f, if (focused) p.accent else p.toggleOutline)
                s.rounded(left + .8f, top + .8f, inner - 1.6f, 19.4f, 1f, 0xFF111014.toInt())
                if (focused) {
                    s.rect(left + 3, top - 2, 19f, 5f, p.header)
                    s.text("Name", left + 4, top + .5f, 5.5f, 19f, p.accent)
                }
                val cursorWidth = s.textWidth(input.substring(0, cursor), 8.3f)
                val offset = max(0f, cursorWidth - inner + 14)
                s.clip(left + 4, top + 2, inner - 8, 17f) {
                    val tx = left + 6 - offset
                    if (focused && cursor != selection) {
                        val a = s.textWidth(input.substring(0, min(cursor, selection)), 8.3f)
                        val b = s.textWidth(input.substring(0, max(cursor, selection)), 8.3f)
                        s.rect(tx + a, top + 3, b - a, 15f, 0x665C6399)
                    }
                    s.text(
                        if (input.isEmpty() && !focused) "Name" else input, tx, top + 10.5f, 8.3f,
                        max(inner, s.textWidth(input, 8.3f) + 1), p.text,
                    )
                    if (focused && (OpaiMotion.now().toLong() / 500) % 2 == 0L) {
                        s.rect(tx + cursorWidth, top + 4, .7f, 13f, p.text)
                    }
                }
                button(s, p, left, top + 28, inner, "Create", PLUS, true, mx, my)
                button(s, p, left, top + 48, inner, "Create Blank", PLUS, true, mx, my)
            }

            Page.TARGETS -> targets?.paint(s, p, x, y + HEADER_H, width)

            Page.SETTINGS -> {
                val items = arrayOf("Configurations", "Chat Translate", "Module Tweaks", "Targets", "Misc")
                for (i in items.indices) {
                    s.text(
                        items[i], x + TEXT_PAD, y + HEADER_H + ROW_H * (i + .5f), 8.3f,
                        width - TEXT_PAD * 2, p.text,
                    )
                }
            }
        }
    }

    private fun button(
        s: OpaiSurface, p: OpaiStyle.Palette, x: Float, y: Float, w: Float, label: String,
        icon: Icon, active: Boolean, mx: Double, my: Double,
    ) {
        val now = OpaiMotion.now()
        val ink = feedback(if (label.isEmpty()) icon.name else label)
        val hover = ink.hover(Rect(x, y, w, BUTTON_H).contains(mx, my) && active, now)
        s.scale(1 - .06f * ink.press(now), x + w / 2, y + BUTTON_H / 2) {
            paintButton(s, p, x, y, w, label, icon, active, ink, hover, now)
        }
    }

    private fun paintButton(
        s: OpaiSurface, p: OpaiStyle.Palette, x: Float, y: Float, w: Float, label: String,
        icon: Icon, active: Boolean, ink: OpaiFeedback, hover: Float, now: Double,
    ) {
        val fill = OpaiSurface.mix(p.field, p.accent, .15f + .10f * hover)
        s.rounded(x, y, w, BUTTON_H, BUTTON_H / 2, fill)
        ink.paint(s, x, y, w, BUTTON_H, BUTTON_H / 2, p.accent, now)
        val textW = if (label.isEmpty()) 0f else s.textWidth(label, 8.3f)
        val glyph = 9f
        val left = x + (w - textW - glyph - (if (label.isEmpty()) 0f else 5f)) / 2
        s.opacity(if (active) 1f else .4f) {
            s.icon(icon, left, y + (BUTTON_H - glyph) / 2, glyph, p.text)
            if (label.isNotEmpty()) s.text(label, left + glyph + 5, y + BUTTON_H / 2, 8.3f, textW + 1, p.text)
        }
    }
}