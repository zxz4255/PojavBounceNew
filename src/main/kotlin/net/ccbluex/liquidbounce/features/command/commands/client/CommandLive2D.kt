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
package net.ccbluex.liquidbounce.features.command.commands.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.ccbluex.liquidbounce.features.command.CommandRegistrar
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource
import net.ccbluex.liquidbounce.features.command.brigadier.get
import net.ccbluex.liquidbounce.features.command.brigadier.register
import net.ccbluex.liquidbounce.features.module.modules.render.live2d.ModuleLive2D
import net.ccbluex.liquidbounce.utils.client.chat

/**
 * `/live2d` — controls the Live2D HUD module.
 *
 * Subcommands: status, models, model <name>, size <px>, move <dx> <dy>,
 * reload, toggle.
 */
object CommandLive2D : CommandRegistrar {

    override fun register(dispatcher: CommandDispatcher<ClientCommandSource>) {
        dispatcher.register("live2d") {
            exec {
                chat("Live2D: ${ModuleLive2D.status()}")
                1
            }
            literal("status") {
                exec {
                    chat("Live2D: ${ModuleLive2D.status()}")
                    1
                }
            }
            literal("models") {
                exec {
                    val models = ModuleLive2D.availableModels()
                    chat(
                        if (models.isEmpty()) {
                            "No Live2D models found in config/live2d"
                        } else {
                            "Models: ${models.joinToString(", ")}"
                        }
                    )
                    1
                }
            }
            literal("model") {
                argument("name", StringArgumentType.word()) { name ->
                    exec { ctx ->
                        val value = ctx.get(name)
                        if (ModuleLive2D.selectModel(value)) {
                            chat("Live2D model set to $value")
                            1
                        } else {
                            chat("Unknown model '$value'. Use /live2d models")
                            0
                        }
                    }
                }
            }
            literal("size") {
                argument("height", IntegerArgumentType.integer(40, 800)) { height ->
                    exec { ctx ->
                        val value = ctx.get(height)
                        ModuleLive2D.setModelSize(value)
                        chat("Live2D size set to ${ModuleLive2D.status()}")
                        1
                    }
                }
            }
            literal("move") {
                argument("dx", IntegerArgumentType.integer()) { dx ->
                    argument("dy", IntegerArgumentType.integer()) { dy ->
                        exec { ctx ->
                            ModuleLive2D.moveBy(ctx.get(dx), ctx.get(dy))
                            chat("Live2D: ${ModuleLive2D.status()}")
                            1
                        }
                    }
                }
            }
            literal("reload") {
                exec {
                    ModuleLive2D.reload()
                    1
                }
            }
            literal("toggle") {
                exec {
                    ModuleLive2D.enabled = !ModuleLive2D.enabled
                    chat("Live2D ${if (ModuleLive2D.enabled) "enabled" else "disabled"}")
                    1
                }
            }
        }
    }
}