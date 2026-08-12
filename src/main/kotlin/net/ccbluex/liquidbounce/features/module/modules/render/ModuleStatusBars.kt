/*
 * ============================================================================
 *  ModuleStatusBars —— 还原原版 LiquidBounce Nextgen HotBar 的生命值条 / 饥饿值条
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  样式来源: 原版 LB Nextgen Web 主题 src-theme/src/routes/hud/elements/hotbar/
 *        - Status.svelte : 圆角进度条 (高 20px, 圆角 5px, 半透明背景)
 *        - HotBar.svelte : 生命值(左) + 饥饿值(右) 成对布局, 列间距 25px
 *        - colors.scss   : --hotbar-health-color: #fc4130
 *                          --hotbar-hunger-color: #b88458
 *                          --hotbar-armor-color: #49ead6
 *                          --hotbar-air-color: #aac1e3
 *                          --hotbar-absorption-color: #d4af37
 *                          --hotbar-experience-color: #88c657
 *        - icon-heart.svg / icon-food.svg : 白色扁平图标 (原生几何绘制还原)
 *
 *  布局 (自下而上, 与原版 .status 一致):
 *        [经验条 (可选)]        ← 原版最上方
 *        [生命值条 | 饥饿值条]   ← 核心
 *        [吸收条 (可选)]
 *        [护甲条 | 空气条 (可选)]
 *
 *  可调节项 (25+): 对齐方式、X/Y 偏移、条宽、条高、列间距、行间距、圆角、
 *        背景开关与透明度、生命/饥饿/护甲/空气/吸收/经验 六种颜色、
 *        图标开关与大小、数值显示、文字颜色与阴影、护甲/空气/吸收/经验 显示开关、
 *        进度条动画速度等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor
 *        (drawRoundedRect / drawQuad / drawCircle / drawTriangle / mc.font),
 *        不依赖任何 Web / 浏览器组件。
 *
 *  安装:
 *    1. 本文件放入
 *       src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleStatusBars.kt
 *    2. ModuleManager.kt 中:
 *       - import 区 (render 模块 import 附近) 添加:
 *           import net.ccbluex.liquidbounce.features.module.modules.render.ModuleStatusBars
 *       - builtin 模块列表中添加一行:  ModuleStatusBars,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.exp
import kotlin.math.roundToInt

object ModuleStatusBars : ClientModule(
    "StatusBars",
    ModuleCategories.RENDER,
    aliases = listOf("HealthBar", "HungerBar", "HotbarStatus"),
) {

    private enum class Align(override val tag: String) : Tagged {
        CENTER("Center"), LEFT("Left"), RIGHT("Right")
    }

    /* ============================= 可调节项 ============================= */

    // —— 布局 ——
    private val align by enumChoice("Align", Align.CENTER)
    private val offsetX by int("Offset X", 0, -500..500)
    private val offsetY by int("Offset Y", 60, 0..1200)         // 距屏幕底部 (默认模拟物品栏上方)
    private val barWidth by int("Bar Width", 150, 80..400)
    private val barHeight by int("Bar Height", 20, 10..32)
    private val pairGap by int("Pair Gap", 25, 5..80)           // 左右两条间距 (原版 25px)
    private val rowGap by int("Row Gap", 5, 0..20)              // 行间距 (原版 5px)
    private val radius by int("Radius", 5, 0..12)               // 圆角 (原版 5px)

    // —— 外观 ——
    private val background by boolean("Background", true)
    private val backgroundAlpha by int("Background Alpha", 173, 0..255)  // 原版 base 色 68%
    private val healthColor by color("Health Color", Color4b(0xfc, 0x41, 0x30))
    private val hungerColor by color("Hunger Color", Color4b(0xb8, 0x84, 0x58))
    private val armorColor by color("Armor Color", Color4b(0x49, 0xea, 0xd6))
    private val airColor by color("Air Color", Color4b(0xaa, 0xc1, 0xe3))
    private val absorptionColor by color("Absorption Color", Color4b(0xd4, 0xaf, 0x37))
    private val experienceColor by color("Experience Color", Color4b(0x88, 0xc6, 0x57))
    private val showIcons by boolean("Show Icons", true)
    private val iconSize by int("Icon Size", 12, 8..20)
    private val showValues by boolean("Show Values", false)     // 条内显示数值
    private val textColor by color("Text Color", Color4b.WHITE)
    private val textShadow by boolean("Text Shadow", true)

    // —— 内容 (原版 HotBar 可选行) ——
    private val showArmor by boolean("Show Armor", true)
    private val showAir by boolean("Show Air", false)
    private val showAbsorption by boolean("Show Absorption", false)
    private val showExperience by boolean("Show Experience", false)

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 10f, 0.5f..30f)

    /* ============================= 内部状态 ============================= */

    // 平滑显示值 (原版 CSS transition width 0.2s 的近似)
    private var healthDisplay = 0f
    private var hungerDisplay = 0f
    private var armorDisplay = 0f
    private var airDisplay = 0f
    private var absorptionDisplay = 0f
    private var experienceDisplay = 0f
    private var lastFrameNs = 0L

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font
        val player = mc.player ?: return@handler

        // 帧间隔(秒), 保证动画与帧率无关
        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now
        val smooth = (1f - exp(-animationSpeed * frameTime)).coerceIn(0f, 1f)

        // 目标值
        val maxHealth = player.maxHealth
        val health = player.health
        val hunger = player.foodData.foodLevel
        val armor = player.armorValue
        val air = player.airSupply
        val maxAir = player.maxAirSupply
        val absorption = player.absorptionAmount
        val expLevel = player.experienceLevel
        val expProgress = player.experienceProgress

        // 平滑插值
        healthDisplay += ((health / maxHealth) - healthDisplay) * smooth
        hungerDisplay += ((hunger / 20f) - hungerDisplay) * smooth
        armorDisplay += ((armor / 20f) - armorDisplay) * smooth
        airDisplay += ((air / maxAir.toFloat()) - airDisplay) * smooth
        absorptionDisplay += (((absorption / 20f).coerceAtMost(1f)) - absorptionDisplay) * smooth
        experienceDisplay += (expProgress - experienceDisplay) * smooth

        val w = barWidth.toFloat()
        val h = barHeight.toFloat()

        // 行数 (自下而上): 核心行 + 可选行
        var rowCount = 1
        if (showArmor || showAir) rowCount++
        if (showAbsorption) rowCount++
        if (showExperience) rowCount++
        val totalHeight = rowCount * h + (rowCount - 1) * rowGap

        // X: 整组宽度 = 左条 + 间距 + 右条
        val totalWidth = w * 2 + pairGap
        val leftX = when (align) {
            Align.LEFT -> offsetX.toFloat()
            Align.RIGHT -> context.guiWidth() - totalWidth - offsetX
            Align.CENTER -> (context.guiWidth() - totalWidth) / 2f + offsetX
        }
        var rowY = context.guiHeight() - offsetY - totalHeight

        // 1. 经验行 (可选)
        if (showExperience && expLevel > 0) {
            context.drawStatusBar(
                font, leftX, rowY,
                experienceDisplay, experienceColor,
                null,
                if (showValues) expLevel.toString() else null,
            )
            rowY += h + rowGap
        }

        // 2. 核心行: 生命值(左) + 饥饿值(右)
        context.drawStatusBar(
            font, leftX, rowY,
            healthDisplay, healthColor,
            { ix, iy, s -> context.drawHeartIcon(ix, iy, s) },
            if (showValues) ceilToInt(health).toString() else null,
        )
        context.drawStatusBar(
            font, leftX + w + pairGap, rowY,
            hungerDisplay, hungerColor,
            { ix, iy, s -> context.drawFoodIcon(ix, iy, s) },
            if (showValues) hunger.toString() else null,
        )
        rowY += h + rowGap

        // 3. 吸收行 (可选)
        if (showAbsorption) {
            context.drawStatusBar(
                font, leftX, rowY,
                absorptionDisplay, absorptionColor,
                null,
                if (showValues) ceilToInt(absorption).toString() else null,
            )
            rowY += h + rowGap
        }

        // 4. 护甲(左) + 空气(右) 行 (可选)
        if (showArmor || showAir) {
            if (showArmor) {
                context.drawStatusBar(
                    font, leftX, rowY,
                    armorDisplay, armorColor,
                    { ix, iy, s -> context.drawShieldIcon(ix, iy, s) },
                    if (showValues) armor.toString() else null,
                )
            }
            if (showAir && air < maxAir) {
                context.drawStatusBar(
                    font, leftX + w + pairGap, rowY,
                    airDisplay, airColor,
                    { ix, iy, s -> context.drawAirIcon(ix, iy, s) },
                    if (showValues) air.toString() else null,
                )
            }
        }
    }

    /* ============================= 绘制工具 ============================= */

    /** 绘制一条 LB Status 风格的进度条 */
    private fun GuiGraphicsExtractor.drawStatusBar(
        font: Font,
        x: Float,
        y: Float,
        percent: Float,
        color: Color4b,
        icon: ((Float, Float, Float) -> Unit)?,
        label: String?,
    ) {
        val w = barWidth.toFloat()
        val h = barHeight.toFloat()

        // 背景 (半透明深色)
        if (background) {
            drawRoundedRect(x, y, x + w, y + h, radius.toFloat(), Color4b(0, 0, 0, backgroundAlpha))
        }

        // 进度 (内缩 1px)
        val fillW = (w - 2f) * percent.coerceIn(0f, 1f)
        if (fillW > 0.5f) {
            drawRoundedRect(
                x + 1f, y + 1f, x + 1f + fillW, y + h - 1f,
                (radius - 1).coerceAtLeast(0).toFloat(),
                color,
            )
        }

        // 图标 (左侧, 垂直居中)
        if (showIcons && icon != null) {
            val size = iconSize.toFloat()
            val ix = x + (h - size) / 2f
            val iy = y + (h - size) / 2f
            icon(ix, iy, size)
        }

        // 数值文字 (右侧)
        if (label != null && label.isNotEmpty()) {
            val tx = x + w - font.width(label) - 6f
            val ty = y + h / 2f + 3.5f   // baseline 垂直居中
            text(font, label, tx.roundToInt(), ty.roundToInt(), textColor.argb, textShadow)
        }
    }

    /* ============================= 图标绘制 ============================= */
    // 还原 LB 主题的白色扁平 SVG 图标 (icon-heart.svg / icon-food.svg / icon-shield.svg)

    /** 心形: 两圆 + 倒三角 */
    private fun GuiGraphicsExtractor.drawHeartIcon(x: Float, y: Float, s: Float) {
        val c = Color4b.WHITE
        drawCircle(x + s * 0.34f, y + s * 0.32f, s * 0.26f, colorGetter = { c.argb })
        drawCircle(x + s * 0.66f, y + s * 0.32f, s * 0.26f, colorGetter = { c.argb })
        drawTriangle(
            x + s * 0.06f, y + s * 0.42f,
            x + s * 0.94f, y + s * 0.42f,
            x + s * 0.50f, y + s * 0.95f,
            c, cull = false,
        )
    }

    /** 鸡腿: 大腿圆 + 腿骨 + 三趾 */
    private fun GuiGraphicsExtractor.drawFoodIcon(x: Float, y: Float, s: Float) {
        val c = Color4b.WHITE
        drawCircle(x + s * 0.52f, y + s * 0.28f, s * 0.30f, colorGetter = { c.argb })
        drawQuad(x + s * 0.40f, y + s * 0.40f, x + s * 0.64f, y + s * 0.78f, c)
        drawCircle(x + s * 0.24f, y + s * 0.86f, s * 0.13f, colorGetter = { c.argb })
        drawCircle(x + s * 0.50f, y + s * 0.92f, s * 0.13f, colorGetter = { c.argb })
        drawCircle(x + s * 0.76f, y + s * 0.86f, s * 0.13f, colorGetter = { c.argb })
    }

    /** 盾牌: 矩形 + 尖底 (护甲) */
    private fun GuiGraphicsExtractor.drawShieldIcon(x: Float, y: Float, s: Float) {
        val c = Color4b.WHITE
        drawQuad(x + s * 0.25f, y + s * 0.12f, x + s * 0.75f, y + s * 0.68f, c)
        drawTriangle(
            x + s * 0.25f, y + s * 0.68f,
            x + s * 0.75f, y + s * 0.68f,
            x + s * 0.50f, y + s * 0.92f,
            c, cull = false,
        )
    }

    /** 空气气泡: 圆形 */
    private fun GuiGraphicsExtractor.drawAirIcon(x: Float, y: Float, s: Float) {
        val c = Color4b.WHITE
        drawCircle(x + s * 0.5f, y + s * 0.5f, s * 0.38f, colorGetter = { c.argb })
    }

    /* ============================= 工具函数 ============================= */

    private fun ceilToInt(value: Float): Int = kotlin.math.ceil(value).toInt()
}
