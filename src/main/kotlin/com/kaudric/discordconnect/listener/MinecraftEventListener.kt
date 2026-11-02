package com.kaudric.discordconnect.listener

import com.kaudric.discordconnect.DiscordConnect
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class MinecraftEventListener(
    private val plugin: DiscordConnect
) : Listener {

    private val plainSerializer = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAsyncChat(event: AsyncChatEvent) {
        if (plugin.pluginConfig.chatPublicOnly && !isSeenByEveryone(event)) return
        val values = playerValues(event.player) + ("message" to plainSerializer.serialize(event.message()))
        plugin.discordSender.send(plugin.pluginConfig.events.chat, values, "chat")
    }

    private fun isSeenByEveryone(event: AsyncChatEvent): Boolean {
        val viewerIds = event.viewers().mapNotNullTo(HashSet()) { (it as? Player)?.uniqueId }
        return plugin.server.onlinePlayers.all { it.uniqueId in viewerIds }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!event.player.isVanished() && event.joinMessage() != null) {
            plugin.discordSender.send(plugin.pluginConfig.events.join, playerValues(event.player), "status", "chat")
        }
        refreshPresence()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        if (!event.player.isVanished() && event.quitMessage() != null) {
            plugin.discordSender.send(plugin.pluginConfig.events.leave, playerValues(event.player), "status", "chat")
        }
        refreshPresence()
    }

    private fun refreshPresence() {
        plugin.server.globalRegionScheduler.execute(plugin) { plugin.botService.updatePresence() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (event.player.isVanished()) return
        val deathMessage = event.deathMessage()?.let(plainSerializer::serialize) ?: return
        val values = playerValues(event.player) + ("death_message" to deathMessage)
        plugin.discordSender.send(plugin.pluginConfig.events.death, values, "chat")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancementDone(event: PlayerAdvancementDoneEvent) {
        if (event.player.isVanished()) return
        val display = event.advancement.display ?: return
        if (!display.doesAnnounceToChat()) return

        val values = playerValues(event.player) + ("advancement" to plainSerializer.serialize(display.title()))
        plugin.discordSender.send(plugin.pluginConfig.events.advancement, values, "chat")
    }

    private fun playerValues(player: Player): Map<String, String> = mapOf(
        "player" to player.name,
        "avatar" to plugin.discordSender.avatarUrl(player.uniqueId, player.name)
    )
}

fun Player.isVanished(): Boolean {
    val keys = listOf("vanish", "vanished", "vanishes", "vanishs", "hidden")
    for (key in keys) {
        for (meta in getMetadata(key)) {
            if (meta.asBoolean()) return true
        }
    }
    return false
}
