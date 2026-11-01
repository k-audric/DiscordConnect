package com.kaudric.discordconnect

import com.kaudric.discordconnect.command.buildDiscordConnectCommand
import com.kaudric.discordconnect.config.Messages
import com.kaudric.discordconnect.config.PluginConfig
import com.kaudric.discordconnect.console.ConsoleLogService
import com.kaudric.discordconnect.discord.DiscordBotService
import com.kaudric.discordconnect.discord.DiscordSender
import com.kaudric.discordconnect.listener.MinecraftEventListener
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

class DiscordConnect : JavaPlugin() {

    @Volatile
    lateinit var pluginConfig: PluginConfig
        private set

    lateinit var messages: Messages
        private set

    lateinit var botService: DiscordBotService
        private set

    lateinit var consoleLogService: ConsoleLogService
        private set

    lateinit var discordSender: DiscordSender
        private set

    override fun onEnable() {
        saveDefaultConfig()

        messages = Messages(this)
        messages.reload()

        pluginConfig = PluginConfig.load(config)
        discordSender = DiscordSender(this)

        botService = DiscordBotService(this)
        botService.start()

        consoleLogService = ConsoleLogService(this)
        consoleLogService.start()

        server.pluginManager.registerEvents(MinecraftEventListener(this), this)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                buildDiscordConnectCommand(this),
                "DiscordConnect administration commands",
                listOf("dc")
            )
        }
    }

    override fun onDisable() {
        if (::consoleLogService.isInitialized) {
            consoleLogService.close()
        }
        if (::botService.isInitialized) {
            botService.shutdown()
        }
        server.globalRegionScheduler.cancelTasks(this)
        server.asyncScheduler.cancelTasks(this)
    }

    fun reloadPlugin() {
        YamlConfiguration().load(dataPath.resolve("config.yml").toFile())
        reloadConfig()
        pluginConfig = PluginConfig.load(config)
        messages.reload()
        botService.restart()
    }
}
