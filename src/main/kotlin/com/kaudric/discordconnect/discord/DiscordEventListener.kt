package com.kaudric.discordconnect.discord

import com.kaudric.discordconnect.DiscordConnect
import com.kaudric.discordconnect.config.SendMode
import com.kaudric.discordconnect.config.ph
import com.kaudric.discordconnect.listener.isVanished
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent
import net.dv8tion.jda.api.events.session.SessionResumeEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.Commands as DiscordCommands
import net.kyori.adventure.text.minimessage.MiniMessage

class DiscordEventListener(
    private val plugin: DiscordConnect
) : ListenerAdapter() {

    private val miniMessage = MiniMessage.miniMessage()

    override fun onReady(event: ReadyEvent) {
        val self = event.jda.selfUser
        val guildCount = event.jda.guilds.size
        plugin.logger.info("Discord bot connected as ${self.name}#${self.discriminator} in $guildCount guild(s)")
        checkChannels(event.jda)

        if (plugin.pluginConfig.playerlistCommand.enabled) {
            event.jda.updateCommands().addCommands(
                DiscordCommands.slash("playerlist", "List online players"),
                DiscordCommands.slash("list", "List online players")
            ).queue()
        } else {
            event.jda.updateCommands().queue()
        }

        if (plugin.botService.startAnnounced.compareAndSet(false, true)) {
            plugin.discordSender.send(plugin.pluginConfig.events.serverStart, emptyMap(), "status", "chat", jda = event.jda)
        }
    }

    private fun checkChannels(jda: JDA) {
        for ((key, id) in plugin.pluginConfig.channels) {
            val channel = jda.getTextChannelById(id)
            if (channel == null) {
                plugin.logger.warning("Channel '$key' ($id) was not found. Check the ID and that the bot can view it.")
                continue
            }
            if (!channel.guild.selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) {
                plugin.logger.warning("Bot is missing View Channel or Send Messages in #${channel.name} ('$key').")
            }
        }
    }

    override fun onSessionDisconnect(event: SessionDisconnectEvent) {
        val closeCode = event.closeCode?.meaning ?: "Unknown"
        plugin.logger.warning("Discord gateway disconnected (Close code: $closeCode)")
    }

    override fun onSessionResume(event: SessionResumeEvent) {
        plugin.logger.info("Discord gateway session resumed successfully")
    }

    override fun onShutdown(event: ShutdownEvent) {
        plugin.logger.info("Discord gateway connection shut down")
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || event.isWebhookMessage) return

        val config = plugin.pluginConfig
        val channelId = event.channel.id

        if (channelId == config.channels["chat"]) {
            if (!config.discordToMinecraftEnabled) return
            val template = config.discordToMinecraft
            if (template.isBlank()) return

            val authorName = event.member?.effectiveName ?: event.author.name
            val content = event.message.contentDisplay.trim()
            if (content.isBlank()) return

            val messageComponent = miniMessage.deserialize(
                template,
                ph("author", authorName),
                ph("message", content)
            )
            plugin.server.broadcast(messageComponent)
        } else if (channelId == config.channels["console"]) {
            val command = event.message.contentRaw.trim().removePrefix("/")
            if (command.isNotBlank()) {
                if (!isConsoleAllowed(event)) {
                    plugin.logger.warning("Blocked console command from ${event.author.name} (${event.author.id}): /$command")
                    event.message.addReaction(Emoji.fromUnicode("❌")).queue(null) { }
                    return
                }
                plugin.logger.info("${event.author.name} (${event.author.id}) ran console command from Discord: /$command")
                plugin.server.globalRegionScheduler.execute(plugin) {
                    plugin.server.dispatchCommand(plugin.server.consoleSender, command)
                }
            }
        }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != "playerlist" && event.name != "list") return
        val cmd = plugin.pluginConfig.playerlistCommand
        if (!cmd.enabled) {
            event.reply("This command is disabled.").setEphemeral(true).queue()
            return
        }

        if (cmd.allowedChannels.isNotEmpty() && event.channel.id !in cmd.allowedChannels) {
            event.reply("This command cannot be used in this channel.").setEphemeral(true).queue()
            return
        }

        if (cmd.allowed.isNotEmpty()) {
            val userId = event.user.id
            val roles = event.member?.roles.orEmpty()
            val isAllowed = userId in cmd.allowed || roles.any { it.id in cmd.allowed }
            if (!isAllowed) {
                event.reply("You do not have permission to execute this command.").setEphemeral(true).queue()
                return
            }
        }

        val players = plugin.server.onlinePlayers.filter { !it.isVanished() }
        val playerNames = if (players.isEmpty()) "None" else players.joinToString(", ") { it.name }
        val values = mapOf(
            "online" to players.size.toString(),
            "max_players" to plugin.server.maxPlayers.toString(),
            "players" to playerNames
        )

        if (cmd.mode == SendMode.EMBED) {
            val embed = plugin.discordSender.buildEmbed(cmd.embed, values)
            if (embed != null) {
                event.replyEmbeds(embed).setEphemeral(cmd.ephemeral).queue()
                return
            }
        }

        val text = plugin.discordSender.fill(cmd.text, values, escapeMarkdown = false)
        event.reply(text.ifBlank { "No players online." }).setEphemeral(cmd.ephemeral).queue()
    }

    private fun isConsoleAllowed(event: MessageReceivedEvent): Boolean {
        val allowed = plugin.pluginConfig.consoleAllowed
        if (allowed.isEmpty()) return false
        if (event.author.id in allowed) return true
        val roles = event.member?.roles ?: return false
        return roles.any { it.id in allowed }
    }
}
