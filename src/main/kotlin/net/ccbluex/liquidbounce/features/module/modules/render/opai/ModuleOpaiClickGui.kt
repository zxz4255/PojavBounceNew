/*
 * ModuleOpaiClickGui —— Samsara ClickGui 模块的移植（仅 opai 样式）。
 *
 * Samsara 的 ClickGui 模块提供 Style/Interface/OpaiColor 三项；本移植只保留 opai，
 * 因此只暴露 OpaiColor（Lavender / Light Pink），绑定与开屏行为保持一致：
 * 右键 Shift 打开，onEnabled 内 setScreen，随后模块自身不进入启用态。
 *
 * 目标路径：
 *   src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/opai/ModuleOpaiClickGui.kt
 */
package net.ccbluex.liquidbounce.features.module.modules.render.opai

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc

/* ============================ Targets 选项（与 Samsara 逐字一致） ============================ */

/** Samsara TargetSettings.COMBAT 顺序。 */
enum class CombatTargetOption(override val tag: String) : Tagged {
    PLAYERS("Players"),
    HOSTILE("Hostile"),
    ANGERABLE("Angerable"),
    WATER_CREATURE("Water Creature"),
    PASSIVE("Passive"),
    INVISIBLE("Invisible"),
    DEAD("Dead"),
    SLEEPING("Sleeping"),
    FRIENDS("Friends"),
}

/** Samsara TargetSettings.VISUAL 顺序（Self 在首位）。 */
enum class VisualTargetOption(override val tag: String) : Tagged {
    SELF("Self"),
    PLAYERS("Players"),
    HOSTILE("Hostile"),
    ANGERABLE("Angerable"),
    WATER_CREATURE("Water Creature"),
    PASSIVE("Passive"),
    INVISIBLE("Invisible"),
    DEAD("Dead"),
    SLEEPING("Sleeping"),
    FRIENDS("Friends"),
}

/** Targets 面板只依赖这层接口，两种枚举各一个适配器。 */
interface OpaiTargetGroup {
    val title: String
    val options: List<String>
    val selectedCount: Int
    fun selected(index: Int): Boolean
    fun toggle(index: Int)
}

class OpaiTargetGroupAdapter<T>(
    private val value: MultiChoiceListValue<T>,
) : OpaiTargetGroup where T : Enum<T>, T : Tagged {

    override val title: String get() = value.name
    override val options: List<String> get() = value.choices.map { it.tag }
    override val selectedCount: Int get() = value.get().size

    override fun selected(index: Int): Boolean {
        val option = value.choices.toList().getOrNull(index) ?: return false
        return option in value
    }

    override fun toggle(index: Int) {
        val option = value.choices.toList().getOrNull(index) ?: return
        value.toggle(option)
    }
}

/** 与 Samsara 默认值一致：Players / Hostile / Angerable / Water Creature / Invisible。 */
object CombatTargets : ValueGroup("Targets") {

    private val defaults = listOf(
        CombatTargetOption.PLAYERS,
        CombatTargetOption.HOSTILE,
        CombatTargetOption.ANGERABLE,
        CombatTargetOption.WATER_CREATURE,
        CombatTargetOption.INVISIBLE,
    )

    val combat = multiEnumChoice<CombatTargetOption>("Combat", defaults)

    val visual = multiEnumChoice<VisualTargetOption>(
        "Visual",
        listOf(
            VisualTargetOption.PLAYERS,
            VisualTargetOption.HOSTILE,
            VisualTargetOption.ANGERABLE,
            VisualTargetOption.WATER_CREATURE,
            VisualTargetOption.INVISIBLE,
        ),
    )

    val combatGroup: OpaiTargetGroup get() = OpaiTargetGroupAdapter(combat)
    val visualGroup: OpaiTargetGroup get() = OpaiTargetGroupAdapter(visual)
}

/* ==================================== 模块 ==================================== */

object ModuleOpaiClickGui : ClientModule(
    "OpaiClickGui",
    ModuleCategories.RENDER,
    bind = InputConstants.KEY_RSHIFT,
    disableActivation = true,
    aliases = listOf("OpaiClickGUI", "SamsaraClickGui"),
) {

    /** 与 ModuleClickGui 相同：模块常驻“运行”，只负责开屏。 */
    override val running get() = true

    enum class OpaiColor(override val tag: String) : Tagged {
        LAVENDER("Lavender"),
        LIGHT_PINK("Light Pink"),
    }

    private val opaiColor by enumChoice("OpaiColor", OpaiColor.LAVENDER)

    init {
        tree(CombatTargets)
    }

    private var screen: OpaiClickGuiScreen? = null

    private fun palette(): OpaiStyle.Palette =
        if (opaiColor == OpaiColor.LIGHT_PINK) OpaiStyle.LIGHT_PINK else OpaiStyle.LAVENDER

    override fun onEnabled() {
        if (!inGame) return
        val instance = screen ?: OpaiClickGuiScreen(::palette).also { screen = it }
        mc.execute { mc.gui.setScreen(instance) }
        super.onEnabled()
    }
}