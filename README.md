<div align="center">
  
# LyttleNametag

[![Paper](https://img.shields.io/badge/Paper-26.2-blue)](https://papermc.io)
[![Hangar](https://img.shields.io/badge/Hangar-download-success)](https://hangar.papermc.io/Lyttle-Development)
[![Discord](https://img.shields.io/discord/941334383216967690?color=7289DA&label=Discord&logo=discord&logoColor=ffffff)](https://discord.gg/QfqFFPFFQZ)

> ✨ **Better Nametags Plugin - Supports Newlines!** ✨

[📚 Features](#--features) • [⌨️ Commands](#-%EF%B8%8F-commands) • [🔑 Permissions](#--permissions) • [📥 Installation](#--installation) • [⚙️ Configuration](#%EF%B8%8F-configuration) • [📱 Support](#--support)

</div>

![Divider](https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png)

## 🌟 Features

<p align="center">
  <img src="https://github.com/Lyttle-Development/LyttleNametag/blob/main/LyttleNametag-Example.gif?raw=true" alt="Feature Showcase" width="500px">
</p>

### 🎯 Core Plugin Features
- Multi line nametags
- Native MiniMessage, RGB and Birdflop gradient support
- Direct LuckPerms prefix and suffix components
- PlaceholderAPI integration
- Built-in vanilla nametag hiding, including scoreboard re-sync for hub plugins
- Citizens NPCs are excluded from vanilla player nametag handling
- Uses the server's current PacketEvents installation instead of shading an outdated copy

---

### 🤌 Lyttle Certified
- Basic plugin without fluff
- No unnecessary features
- Full flexibility and configurability
- Open source and free to use (MIT License)

---

## ⌨️ Commands

> 💡 `<required>` `[optional]`

| Command               | Permission      | Description                  |
|:----------------------|:----------------|:-----------------------------|
| `/plugin reload`      | `plugin.reload` | Reloads the configuration    |

---

## 🔑 Permissions

| Permission Node       | Description                  | Default |
|:----------------------|:-----------------------------|:--------|
| `LyttleNametag.LyttleNametag` | Ability to reload the plugin | `❌`     |

---

## 📥 Installation

### Quick Start
1. Download the latest JAR from this fork's GitHub Actions or Releases page
2. Place the `.jar` file in your server's `plugins` folder
3. Restart your server
4. Edit the configuration file to customize the plugin to your needs
5. Use `/LyttleNametag reload` to apply changes

---


### 📋 Requirements
- Java 25
- Paper 26.2
- Minimum 20MB free disk space

---


### 💫 Dependencies
- PacketEvents 2.13.0+
- LuckPerms 5.5+
- PlaceholderAPI 2.12.3+

### 🎨 LuckPerms gradients

Store the prefix directly in LuckPerms as MiniMessage, for example:

```text
<gradient:#ff4fa3:#55ff55><bold>OWNER</bold></gradient><reset>
```

Then use `<luckperms_prefix>` in `config.yml`. The older
`%luckperms_prefix%` placeholder remains compatible, but no legacy `&` color
conversion is required. `<luckperms_suffix>` and `%luckperms_suffix%` are also
supported.

The built-in `<health>` and `<max_health>` placeholders read the values
directly from the player and do not require PlaceholderAPI expansions.

Set `hide_vanilla_nametag: true` to suppress Minecraft's original player name
and prevent it from overlapping the custom rank, name and health lines. The
hidden team is synchronized with scoreboards assigned by hub plugins, while
Citizens NPCs are ignored.

---


### 📝 Configuration Files
#### 🔧 `config.yml`
The main configuration file controlling plugin behavior and features.

#### 💬 `messages.yml`
Customize all plugin messages. Supports color codes and placeholders.

### 🔄 The #defaults Folder
The folder serves several important purposes: `#defaults`
1. **Backup Reference**: Contains original copies of all configuration files
2. **Reset Option**: Use these to restore default settings
3. **Update Safety**: Preserved during plugin updates
4. **Documentation**: Shows all available options with comments

> 💡 **Never modify files in the #defaults folder!** They are automatically overwritten during server restarts.

---

## 💬 Support

<div align="center">

### 🤝 Need Help?

[![Discord](https://img.shields.io/discord/941334383216967690?color=7289DA&label=Join%20Our%20Discord&logo=discord&logoColor=ffffff&style=for-the-badge)](https://discord.gg/QfqFFPFFQZ)

🐛 Found a bug? [Open an Issue](https://github.com/Lyttle-Development/LyttleNametag/issues)  
💡 Have a suggestion? [Share your idea](https://github.com/Lyttle-Development/LyttleNametag/issues)

</div>

---

## 📜 License

<div align="center">

This project is licensed under the MIT License - see the [LICENSE](https://github.com/Lyttle-Development/LyttleNametag/blob/main/LICENSE) file for details.

---

### 🌟 Made with the lyttlest details in mind by [Lyttle Development](https://www.lyttledevelopment.com)

If you enjoy this plugin, please consider:

⭐ Giving it a star on GitHub <br>
💬 Sharing it with other server owners<br>
🎁 Supporting development through [Donations](https://github.com/LyttleDevelopment)

![Divider](https://raw.githubusercontent.com/andreasbm/readme/master/assets/lines/rainbow.png)

</div>
