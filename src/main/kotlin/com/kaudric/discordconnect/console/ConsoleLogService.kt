package com.kaudric.discordconnect.console

import com.kaudric.discordconnect.DiscordConnect
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConsoleLogService(private val plugin: DiscordConnect) {

    private companion object {
        const val APPENDER_NAME = "DiscordConnectConsole"
        const val MAX_CHUNK_LENGTH = 1900
        const val MAX_QUEUED_LINES = 5000
        val IGNORED_LOGGERS = listOf("com.kaudric.discordconnect.libs")
        val ANSI_REGEX = Regex("\u001B\\[[;\\d]*m")
        val SECTION_CODE_REGEX = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
    }

    private val rootLogger = LogManager.getRootLogger() as? Logger
    private val appender = ConsoleAppender()
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUED_LINES)
    private val failureLogged = AtomicBoolean(false)

    fun start() {
        val root = rootLogger ?: run {
            plugin.logger.warning("Console streaming is unavailable: the server is not using Log4j 2.")
            return
        }
        appender.start()
        root.addAppender(appender)
        plugin.server.asyncScheduler.runAtFixedRate(plugin, { _ -> flush() }, 1, 1, TimeUnit.SECONDS)
    }

    fun close() {
        rootLogger?.removeAppender(appender)
        appender.stop()
        queue.clear()
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val jda = plugin.botService.jda?.takeIf { it.status == JDA.Status.CONNECTED } ?: return
        val channel = plugin.pluginConfig.textChannel(jda, "console") ?: return

        val chunk = StringBuilder()
        while (true) {
            val line = queue.peek()?.replace("```", "'''")?.take(MAX_CHUNK_LENGTH - 1) ?: break
            if (chunk.length + line.length + 1 > MAX_CHUNK_LENGTH) break
            queue.poll()
            chunk.append(line).append('\n')
        }
        if (chunk.isNotEmpty()) send(channel, chunk.toString())
    }

    private fun send(channel: TextChannel, content: String) {
        channel.sendMessage("```prolog\n$content```")
            .setAllowedMentions(emptySet<Message.MentionType>())
            .queue({ failureLogged.set(false) }) { error ->
                if (failureLogged.compareAndSet(false, true)) {
                    plugin.logger.warning("Failed to stream console to #${channel.name}: ${error.message}")
                }
            }
    }

    private fun format(event: LogEvent): String? {
        val loggerName = event.loggerName.orEmpty()
        if (IGNORED_LOGGERS.any { loggerName.startsWith(it) }) return null

        val message = event.message?.formattedMessage.orEmpty()
        val trace = event.thrown?.let { "\n" + it.stackTraceToString() }.orEmpty()
        val clean = SECTION_CODE_REGEX.replace(ANSI_REGEX.replace(message + trace, ""), "").trimEnd()
        if (clean.isBlank()) return null

        val showName = loggerName.isNotBlank() && !loggerName.startsWith("net.minecraft") && loggerName != "Minecraft"
        val source = if (showName) "[$loggerName] " else ""
        return "[${event.level.name()}] $source$clean"
    }

    private inner class ConsoleAppender : AbstractAppender(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if ("console" !in plugin.pluginConfig.channels) return
            format(event)?.let { queue.offer(it) }
        }
    }
}
