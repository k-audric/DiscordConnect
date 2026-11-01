package com.kaudric.discordconnect.discord

import com.kaudric.discordconnect.DiscordConnect
import com.kaudric.discordconnect.config.ph
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent
import net.dv8tion.jda.api.events.session.SessionResumeEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
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

    private fun isConsoleAllowed(event: MessageReceivedEvent): Boolean {
        val allowed = plugin.pluginConfig.consoleAllowed
        if (allowed.isEmpty()) return false
        if (event.author.id in allowed) return true
        val roles = event.member?.roles ?: return false
        return roles.any { it.id in allowed }
    }
}
