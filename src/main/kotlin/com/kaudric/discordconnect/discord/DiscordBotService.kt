package com.kaudric.discordconnect.discord

import com.kaudric.discordconnect.DiscordConnect
import com.kaudric.discordconnect.config.PresenceConfig
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class DiscordBotService(private val plugin: DiscordConnect) {

    private val lock = Any()
    private var generation = 0

    @Volatile
    var jda: JDA? = null
        private set

    val startAnnounced = AtomicBoolean(false)

    val isConnected: Boolean
        get() = jda?.status == JDA.Status.CONNECTED

    fun start() {
        if (!plugin.isEnabled) return
        val config = plugin.pluginConfig
        val token = config.botToken.trim()
        if (token.isBlank() || token == "YOUR_BOT_TOKEN_HERE") {
            plugin.logger.warning("Discord bot token is not set (bot.token in config.yml). Bot will not connect.")
            return
        }
        val gen = synchronized(lock) { generation }

        plugin.server.asyncScheduler.runNow(plugin) { _ ->
            val built = try {
                plugin.logger.info("Initializing Discord gateway connection...")
                JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .setEnableShutdownHook(false)
                    .addEventListeners(DiscordEventListener(plugin))
                    .apply {
                        if (config.presence.enabled) {
                            setStatus(resolveStatus(config.presence.status))
                            setActivity(activity(config.presence))
                        }
                    }
                    .build()
            } catch (ex: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to initialize Discord gateway", ex)
                return@runNow
            }
            val superseded = synchronized(lock) {
                (generation != gen || !plugin.isEnabled).also { if (!it) jda = built }
            }
            if (superseded) built.shutdownNow()
        }
    }

    fun restart() {
        plugin.server.asyncScheduler.runNow(plugin) { _ ->
            shutdown(announceStop = false)
            start()
        }
    }

    fun shutdown(announceStop: Boolean = true) {
        val current = synchronized(lock) {
            generation++
            jda.also { jda = null }
        } ?: return

        plugin.logger.info("Shutting down Discord gateway connection...")
        if (announceStop && current.status == JDA.Status.CONNECTED) {
            plugin.discordSender.sendBlocking(current, plugin.pluginConfig.events.serverStop, emptyMap(), 3, "status", "chat")
        }
        plugin.discordSender.clearWebhooks()
        current.shutdown()
        try {
            if (!current.awaitShutdown(5, TimeUnit.SECONDS)) {
                plugin.logger.warning("Discord gateway did not shut down within 5 seconds. Forcing termination...")
                current.shutdownNow()
            }
        } catch (ex: InterruptedException) {
            current.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun updatePresence() {
        val presence = plugin.pluginConfig.presence
        if (!presence.enabled || !isConnected) return
        jda?.presence?.activity = activity(presence)
    }

    private fun activity(presence: PresenceConfig): Activity? {
        val text = presence.activityText
            .replace("{online}", plugin.server.onlinePlayers.size.toString())
            .replace("{max_players}", plugin.server.maxPlayers.toString())
            .take(128)
        if (text.isBlank()) return null
        return when (presence.activityType.uppercase()) {
            "PLAYING" -> Activity.playing(text)
            "STREAMING" -> Activity.streaming(text, null)
            "LISTENING" -> Activity.listening(text)
            "WATCHING" -> Activity.watching(text)
            "COMPETING" -> Activity.competing(text)
            else -> Activity.customStatus(text)
        }
    }

    private fun resolveStatus(status: String): OnlineStatus =
        OnlineStatus.fromKey(status.lowercase()).takeIf { it != OnlineStatus.UNKNOWN } ?: OnlineStatus.ONLINE
}
