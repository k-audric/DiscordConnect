package com.kaudric.discordconnect.command

import com.kaudric.discordconnect.DiscordConnect
import com.kaudric.discordconnect.config.ph
import com.mojang.brigadier.Command
import net.dv8tion.jda.api.JDA
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

fun buildDiscordConnectCommand(plugin: DiscordConnect): LiteralCommandNode<CommandSourceStack> =
    Commands.literal("discordconnect")
        .requires { it.sender.hasPermission("discordconnect.command.reload") || it.sender.hasPermission("discordconnect.command.status") }
        .then(
            Commands.literal("reload")
                .requires { it.sender.hasPermission("discordconnect.command.reload") }
                .executes { ctx ->
                    try {
                        plugin.reloadPlugin()
                        plugin.messages.send(ctx.source.sender, "commands.reload-success")
                    } catch (e: Exception) {
                        plugin.messages.send(ctx.source.sender, "errors.reload-failed", ph("error", e.message ?: "Unknown error"))
                    }
                    Command.SINGLE_SUCCESS
                }
        )
        .then(
            Commands.literal("status")
                .requires { it.sender.hasPermission("discordconnect.command.status") }
                .executes { ctx ->
                    val jda = plugin.botService.jda
                    if (jda != null && jda.status == JDA.Status.CONNECTED) {
                        plugin.messages.send(
                            ctx.source.sender,
                            "commands.status-online",
                            ph("bot_tag", jda.selfUser.name),
                            ph("guilds", jda.guilds.size)
                        )
                    } else {
                        plugin.messages.send(ctx.source.sender, "commands.status-offline")
                    }
                    Command.SINGLE_SUCCESS
                }
        )
        .build()
