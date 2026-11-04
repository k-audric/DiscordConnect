package com.kaudric.discordconnect.discord

import com.kaudric.discordconnect.DiscordConnect
import com.kaudric.discordconnect.config.EmbedFormat
import com.kaudric.discordconnect.config.EventFormat
import com.kaudric.discordconnect.config.SendMode
import com.kaudric.discordconnect.config.Sender
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.utils.MarkdownSanitizer
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class DiscordSender(private val plugin: DiscordConnect) {

    private companion object {
        const val WEBHOOK_NAME = "DConnect"
        const val WEBHOOK_USERNAME_MAX_LENGTH = 80
        val BLOCKED_WORDS = listOf(Regex("(?i)(cly)d(e)"), Regex("(?i)(d)i(scord)"))
    }

    private val placeholderRegex = Regex("\\{([a-z_]+)}")

    private val webhooks = ConcurrentHashMap<String, CompletableFuture<Webhook>>()
    private val warnedChannels = ConcurrentHashMap.newKeySet<String>()

    fun send(format: EventFormat, values: Map<String, String>, vararg channelKeys: String, jda: JDA? = plugin.botService.jda) {
        if (jda == null) return
        val channel = resolveChannel(jda, *channelKeys) ?: return
        val data = build(format, values) ?: return
        deliver(channel, format, values, data).whenComplete { _, error ->
            if (error != null) {
                plugin.logger.warning("Failed to send Discord message to #${channel.name}: ${unwrap(error).message}")
            }
        }
    }

    fun sendBlocking(jda: JDA, format: EventFormat, values: Map<String, String>, timeoutSeconds: Long, vararg channelKeys: String) {
        val channel = resolveChannel(jda, *channelKeys) ?: return
        val data = build(format, values) ?: return
        try {
            deliver(channel, format, values, data).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            plugin.logger.warning("Failed to send Discord message to #${channel.name}: ${unwrap(ex).message}")
        }
    }

    fun clearWebhooks() {
        webhooks.clear()
        warnedChannels.clear()
    }

    private fun resolveChannel(jda: JDA, vararg channelKeys: String): TextChannel? =
        channelKeys.firstNotNullOfOrNull { plugin.pluginConfig.textChannel(jda, it) }

    private fun build(format: EventFormat, values: Map<String, String>): MessageCreateData? {
        if (!format.enabled) return null
        val builder = MessageCreateBuilder().setAllowedMentions(emptySet<Message.MentionType>())

        if (format.mode == SendMode.EMBED) {
            buildEmbed(format.embed, values)?.let { return builder.setEmbeds(it).build() }
        }

        val text = fill(format.text, values, escapeMarkdown = true).take(Message.MAX_CONTENT_LENGTH)
        if (text.isBlank()) return null
        return builder.setContent(text).build()
    }

    fun avatarUrl(uuid: UUID, name: String): String =
        plugin.pluginConfig.avatarUrl
            .replace("{uuid}", uuid.toString().replace("-", ""))
            .replace("{name}", name)

    private fun deliver(channel: TextChannel, format: EventFormat, values: Map<String, String>, data: MessageCreateData): CompletableFuture<*> {
        if (format.sender == Sender.WEBHOOK) {
            if (channel.guild.selfMember.hasPermission(channel, Permission.MANAGE_WEBHOOKS)) {
                return deliverViaWebhook(channel, format, values, data, retry = true)
            }
            if (warnedChannels.add(channel.id)) {
                plugin.logger.warning("Missing Manage Webhooks permission in #${channel.name}; sending as the bot instead.")
            }
        }
        return channel.sendMessage(data).submit()
    }

    private fun deliverViaWebhook(
        channel: TextChannel,
        format: EventFormat,
        values: Map<String, String>,
        data: MessageCreateData,
        retry: Boolean
    ): CompletableFuture<Message> {
        val self = channel.jda.selfUser
        val username = sanitizeUsername(fill(format.webhookName, values, escapeMarkdown = false).ifBlank { self.name })
        val avatar = fill(format.webhookAvatar, values, escapeMarkdown = false).takeIf(::isHttpUrl) ?: self.effectiveAvatarUrl

        return webhookFor(channel)
            .thenCompose { hook -> hook.sendMessage(data).setUsername(username).setAvatarUrl(avatar).submit() }
            .exceptionallyCompose { error ->
                val cause = unwrap(error)
                if (retry && cause is ErrorResponseException && cause.errorResponse == ErrorResponse.UNKNOWN_WEBHOOK) {
                    webhooks.remove(channel.id)
                    deliverViaWebhook(channel, format, values, data, retry = false)
                } else {
                    CompletableFuture.failedFuture(cause)
                }
            }
    }

    private fun webhookFor(channel: TextChannel): CompletableFuture<Webhook> {
        val future = webhooks.computeIfAbsent(channel.id) {
            val selfId = channel.jda.selfUser.id
            channel.retrieveWebhooks().submit().thenCompose { hooks ->
                val existing = hooks.firstOrNull { it.name == WEBHOOK_NAME && it.token != null && it.ownerAsUser?.id == selfId }
                if (existing != null) {
                    CompletableFuture.completedFuture(existing)
                } else {
                    channel.createWebhook(WEBHOOK_NAME).reason("DiscordConnect chat bridge").submit()
                }
            }
        }
        future.whenComplete { _, error -> if (error != null) webhooks.remove(channel.id, future) }
        return future
    }

    private fun sanitizeUsername(name: String): String {
        val cleaned = BLOCKED_WORDS.fold(name) { acc, regex -> regex.replace(acc, "$1*$2") }
        return cleaned.take(WEBHOOK_USERNAME_MAX_LENGTH)
    }

    private fun isHttpUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    private fun unwrap(error: Throwable): Throwable =
        if ((error is CompletionException || error is ExecutionException) && error.cause != null) unwrap(error.cause ?: error) else error

    fun buildEmbed(embed: EmbedFormat, values: Map<String, String>): MessageEmbed? {
        val author = fill(embed.author, values, escapeMarkdown = false).take(MessageEmbed.AUTHOR_MAX_LENGTH)
        val title = fill(embed.title, values, escapeMarkdown = false).take(MessageEmbed.TITLE_MAX_LENGTH)
        val description = fill(embed.description, values, escapeMarkdown = true).take(MessageEmbed.DESCRIPTION_MAX_LENGTH)
        val footer = fill(embed.footer, values, escapeMarkdown = false).take(MessageEmbed.TEXT_MAX_LENGTH)
        if (author.isBlank() && title.isBlank() && description.isBlank() && footer.isBlank()) return null

        val builder = EmbedBuilder()
        embed.color?.let { builder.setColor(it) }
        if (author.isNotBlank()) {
            val icon = fill(embed.authorIcon, values, escapeMarkdown = false).takeIf(::isHttpUrl)
            builder.setAuthor(author, null, icon)
        }
        if (title.isNotBlank()) builder.setTitle(title)
        if (description.isNotBlank()) builder.setDescription(description)
        if (footer.isNotBlank()) builder.setFooter(footer)
        if (embed.timestamp) builder.setTimestamp(Instant.now())
        return builder.build()
    }

    fun fill(template: String, values: Map<String, String>, escapeMarkdown: Boolean): String =
        placeholderRegex.replace(template) { match ->
            val value = values[match.groupValues[1]] ?: return@replace match.value
            if (escapeMarkdown) MarkdownSanitizer.escape(value) else value
        }
}
