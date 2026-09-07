package com.lyttledev.lyttlenametag.types;

import com.lyttledev.lyttlenametag.LyttleNametag;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Configs {
    private final LyttleNametag plugin;
    private final File generalFile;
    private final File messagesFile;

    public YamlConfiguration general;
    public YamlConfiguration messages;
    public YamlConfiguration defaultGeneral;
    public YamlConfiguration defaultMessages;

    public Configs(LyttleNametag plugin) {
        this.plugin = plugin;
        this.generalFile = new File(plugin.getDataFolder(), "config.yml");
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        general = YamlConfiguration.loadConfiguration(generalFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        defaultGeneral = loadBundledConfig("#defaults/config.yml");
        defaultMessages = loadBundledConfig("#defaults/messages.yml");
    }

    public void saveGeneral() {
        save(general, generalFile);
    }

    public void saveMessages() {
        save(messages, messagesFile);
    }

    private YamlConfiguration loadBundledConfig(String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled configuration: " + path);
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled configuration: " + path, exception);
        }
    }

    private void save(YamlConfiguration configuration, File destination) {
        try {
            configuration.save(destination);
        } catch (IOException exception) {
            plugin.getLogger().severe("Unable to save " + destination.getName() + ": " + exception.getMessage());
        }
    }
}
