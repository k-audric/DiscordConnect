package com.kaudric.discordconnect.config

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
class Messages(private val plugin: JavaPlugin) {
    private val mm = MiniMessage.miniMessage()
    private var yaml = YamlConfiguration()
    private var prefix: TagResolver = TagResolver.empty()

    fun reload() {
        val file = plugin.dataPath.resolve("messages.yml").toFile()
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false)
        }

        yaml = YamlConfiguration.loadConfiguration(file)
        plugin.getResource("messages.yml")?.reader(Charsets.UTF_8)?.use {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(it))
        }
        prefix = Placeholder.parsed("prefix", yaml.getString("prefix") ?: "")
    }

    fun get(path: String, vararg resolvers: TagResolver): Component {
        val raw = when {
            yaml.isList(path) -> yaml.getStringList(path).joinToString("\n")
            else -> yaml.getString(path)
        } ?: return Component.text("Missing message: $path", NamedTextColor.RED)
        return mm.deserialize(raw, prefix, *resolvers)
    }

    fun send(audience: Audience, path: String, vararg resolvers: TagResolver) {
        audience.sendMessage(get(path, *resolvers))
    }
}

fun ph(key: String, value: Any): TagResolver = Placeholder.unparsed(key, value.toString())
