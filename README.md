# DiscordConnect

A lightweight, asynchronous Discord bridge for Paper and Folia servers.

## Features

- Two-way chat bridging between Minecraft and Discord.
- Join, leave, death, and advancement announcements.
- Server start and stop notifications.
- Remote console channel: real-time Log4j log streaming and Discord-to-console command execution.
- Configurable output modes: plain text or Discord embeds.
- Optional webhook avatar/name spoofing per event.
- Folia and Paper regionized scheduling with zero main-thread blocking.

## Requirements

- Paper or Folia 1.21+
- Java 21+
- A Discord Bot with **Message Content Intent** enabled in the [Discord Developer Portal](https://discord.com/developers/applications).

## Discord Bot Setup

1. Create an application in the [Discord Developer Portal](https://discord.com/developers/applications).
2. Navigate to **Bot** and click **Reset Token** to copy your bot token.
3. Scroll down to **Privileged Gateway Intents** and enable **Message Content Intent**.
4. Invite the bot to your Discord server using OAuth2 URL Generator with the `bot` scope and the following permissions:
   - **View Channels**
   - **Send Messages**
   - **Embed Links**
   - **Read Message History**
   - **Add Reactions** (optional; marks denied console commands with ❌)
   - **Manage Webhooks** (optional; required only if webhook sending is enabled)

## Installation & Configuration

1. Place `DiscordConnect-1.0.0.jar` into your server's `plugins/` directory and start the server to generate default configurations.
2. Open `plugins/DiscordConnect/config.yml`.
3. Set `bot.token` to your bot's token.
4. Fill in channel IDs under `channels`:
   - `chat`: Main channel for two-way chat, deaths, and advancements.
   - `status`: Dedicated channel for join/quit and server start/stop (leave empty to fall back to `chat`).
   - `console`: Dedicated channel for live console logs and commands (leave empty to disable).
5. Run `/dc reload` in-game or from the server console.

### Channel IDs

To get a channel ID in Discord:
1. Open Discord Settings -> **Advanced** -> Enable **Developer Mode**.
2. Right-click the desired channel and select **Copy Channel ID**.

### Console Channel & Security

The console channel streams console logs and executes commands sent into the channel as the Minecraft console.

- By default, `console.allowed` is empty (`[]`), blocking everyone from executing commands.
- Add Discord user IDs or role IDs to `console.allowed` to grant access:

```yaml
console:
  allowed:
    - "123456789012345678" # User ID
    - "876543210987654321" # Role ID
```

Commands can be prefixed with `/` or typed directly. Every executed command is logged to the server with the Discord author's username and ID.

### Message & Event Formatting

All outgoing Discord events support two delivery styles configured under `minecraft-to-discord.events.<event>`:

- `mode: PLAIN`: Sends the raw text defined in `text`.
- `mode: EMBED`: Sends an embed using the fields under `embed` (color, author, title, description, footer, timestamp).
- `webhook.enabled: true`: Sends the message using a webhook instead of the bot profile, allowing dynamic avatars and names (requires `Manage Webhooks` permission).

#### Placeholders

| Event | Available Placeholders |
| :--- | :--- |
| `chat` | `{player}`, `{message}`, `{avatar}` |
| `join` | `{player}`, `{avatar}` |
| `leave` | `{player}`, `{avatar}` |
| `death` | `{player}`, `{death_message}`, `{avatar}` |
| `advancement` | `{player}`, `{advancement}`, `{avatar}` |
| `server-start` | *(None)* |
| `server-stop` | *(None)* |

Minecraft chat uses Adventure MiniMessage syntax in `discord-to-minecraft.format`. Available tags: `<author>`, `<message>`.

## Commands & Permissions

| Command | Permission | Default | Description |
| :--- | :--- | :--- | :--- |
| `/discordconnect reload` (alias: `/dc reload`) | `discordconnect.command.reload` | `op` | Reloads `config.yml` and `messages.yml` without restarting the server. |
| `/discordconnect status` (alias: `/dc status`) | `discordconnect.command.status` | `op` | Displays Discord bot connection status and guild count. |

Parent permission `discordconnect.admin` grants access to all subcommands.

## Building from Source

Build the shadow jar using Gradle:

```bash
./gradlew build        # Linux / macOS
gradlew.bat build      # Windows
```

The compiled jar will be in `build/libs/DiscordConnect-1.0.0.jar`.
