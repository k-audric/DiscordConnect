package com.kaudric.discordconnect.config

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.bukkit.configuration.file.FileConfiguration

data class PresenceConfig(
    val enabled: Boolean,
    val status: String,
    val activityType: String,
    val activityText: String
)

enum class SendMode { PLAIN, EMBED }

enum class Sender { BOT, WEBHOOK }

data class EmbedFormat(
    val color: Int?,
    val author: String,
    val authorIcon: String,
    val title: String,
    val description: String,
    val footer: String,
    val timestamp: Boolean
)

data class EventFormat(
    val enabled: Boolean,
    val mode: SendMode,
    val sender: Sender,
    val webhookName: String,
    val webhookAvatar: String,
    val text: String,
    val embed: EmbedFormat
)

data class EventFormats(
    val chat: EventFormat,
    val join: EventFormat,
    val leave: EventFormat,
    val death: EventFormat,
    val advancement: EventFormat,
    val serverStart: EventFormat,
    val serverStop: EventFormat
)

data class PluginConfig(
    val botToken: String,
    val channels: Map<String, String>,
    val consoleAllowed: Set<String>,
    val presence: PresenceConfig,
    val discordToMinecraftEnabled: Boolean,
    val discordToMinecraft: String,
    val avatarUrl: String,
    val chatPublicOnly: Boolean,
    val events: EventFormats
) {
    fun textChannel(jda: JDA, key: String): TextChannel? = channels[key]?.let(jda::getTextChannelById)

    companion object {
        fun load(config: FileConfiguration): PluginConfig {
            val channels = config.getConfigurationSection("channels")?.let { section ->
                section.getKeys(false).mapNotNull { key ->
                    section.getString(key)?.trim()?.takeIf { it.toLongOrNull() != null }?.let { key.lowercase() to it }
                }.toMap()
            }.orEmpty()

            val presence = PresenceConfig(
                enabled = config.getBoolean("bot.presence.enabled"),
                status = config.getString("bot.presence.status") ?: "ONLINE",
                activityType = config.getString("bot.presence.activity.type") ?: "CUSTOM",
                activityText = config.getString("bot.presence.activity.text") ?: ""
            )

            return PluginConfig(
                botToken = config.getString("bot.token") ?: "",
                channels = channels,
                consoleAllowed = config.getList("console.allowed").orEmpty()
                    .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .toSet(),
                presence = presence,
                discordToMinecraftEnabled = config.getBoolean("discord-to-minecraft.enabled"),
                discordToMinecraft = config.getString("discord-to-minecraft.format") ?: "",
                avatarUrl = config.getString("minecraft-to-discord.avatar-url") ?: "",
                chatPublicOnly = config.getBoolean("minecraft-to-discord.events.chat.public-only"),
                events = EventFormats(
                    chat = loadEvent(config, "chat"),
                    join = loadEvent(config, "join"),
                    leave = loadEvent(config, "leave"),
                    death = loadEvent(config, "death"),
                    advancement = loadEvent(config, "advancement"),
                    serverStart = loadEvent(config, "server-start"),
                    serverStop = loadEvent(config, "server-stop")
                )
            )
        }

        private fun loadEvent(config: FileConfiguration, key: String): EventFormat {
            val path = "minecraft-to-discord.events.$key"
            val mode = if (config.getString("$path.mode").equals("EMBED", ignoreCase = true)) SendMode.EMBED else SendMode.PLAIN
            return EventFormat(
                enabled = config.getBoolean("$path.enabled"),
                mode = mode,
                sender = if (config.getBoolean("$path.webhook.enabled")) Sender.WEBHOOK else Sender.BOT,
                webhookName = config.getString("$path.webhook.name") ?: "",
                webhookAvatar = config.getString("$path.webhook.avatar") ?: "",
                text = config.getString("$path.text") ?: "",
                embed = EmbedFormat(
                    color = config.getString("$path.embed.color")?.trim()?.removePrefix("#")?.toIntOrNull(16),
                    author = config.getString("$path.embed.author") ?: "",
                    authorIcon = config.getString("$path.embed.author-icon") ?: "",
                    title = config.getString("$path.embed.title") ?: "",
                    description = config.getString("$path.embed.description") ?: "",
                    footer = config.getString("$path.embed.footer") ?: "",
                    timestamp = config.getBoolean("$path.embed.timestamp")
                )
            )
        }
    }
}
