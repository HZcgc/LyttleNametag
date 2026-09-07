package com.lyttledev.lyttlenametag;

import com.lyttledev.lyttlenametag.commands.LyttleNametagCommand;
import com.lyttledev.lyttlenametag.formatting.NametagTextRenderer;
import com.lyttledev.lyttlenametag.handlers.NametagHandler;
import com.lyttledev.lyttlenametag.handlers.VanillaNametagHider;
import com.lyttledev.lyttlenametag.types.Configs;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class LyttleNametag extends JavaPlugin {
    public Configs config;
    public NametagHandler nametagHandler;
    public VanillaNametagHider vanillaNametagHider;
    public NametagTextRenderer textRenderer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new Configs(this);
        migrateConfig();

        RegisteredServiceProvider<LuckPerms> luckPermsRegistration =
                getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (luckPermsRegistration == null) {
            getLogger().severe("LuckPerms is required, but its API is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.textRenderer = new NametagTextRenderer(luckPermsRegistration.getProvider());

        LifecycleEventManager<Plugin> manager = getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            registerCommands(commands);
        });

        this.nametagHandler = new NametagHandler(this);
        this.vanillaNametagHider = new VanillaNametagHider(this);
        getLogger().info("Enabled with external PacketEvents and native LuckPerms MiniMessage prefix support.");
    }

    public void registerCommands(Commands commands) {
        LyttleNametagCommand.createCommand(this, commands);
    }

    public void reloadPlugin() {
        config.reload();
        migrateConfig();
        nametagHandler.reload();
        vanillaNametagHider.reload();
    }

    @Override
    public void onDisable() {
        if (vanillaNametagHider != null) {
            vanillaNametagHider.shutdown();
        }
        if (nametagHandler != null) {
            nametagHandler.removeAllNametagsOnShutdown();
        }
        // PacketEvents is an external dependency and owns its own lifecycle.
    }

    @Override
    public void saveDefaultConfig() {
        saveResourceIfMissing("config.yml");
        saveResourceIfMissing("messages.yml");
        saveResource("#defaults/config.yml", true);
        saveResource("#defaults/messages.yml", true);
    }

    private void saveResourceIfMissing(String path) {
        if (!new File(getDataFolder(), path).exists()) {
            saveResource(path, false);
        }
    }

    private void migrateConfig() {
        int version = config.general.getInt("config_version", 0);

        if (version < 1) {
            String oldNametag = config.messages.getString("nametag");
            if (oldNametag != null) {
                config.general.set("nametag", oldNametag);
                config.messages.set("nametag", null);
            }
            version = 1;
        }
        if (version < 2) {
            config.general.set("interval", config.defaultGeneral.getDouble("interval", 0.5D));
            version = 2;
        }
        if (version < 3) {
            config.general.set("view_distance", config.defaultGeneral.getInt("view_distance", 64));
            version = 3;
        }
        if (version < 4) {
            if (!config.general.contains("line_spacing")) {
                config.general.set("line_spacing", config.defaultGeneral.getDouble("line_spacing", 0.275D));
            }
            version = 4;
        }
        if (version < 5) {
            if (!config.general.contains("hide_vanilla_nametag")) {
                config.general.set(
                        "hide_vanilla_nametag",
                        config.defaultGeneral.getBoolean("hide_vanilla_nametag", true)
                );
            }
            version = 5;
        }

        config.general.set("config_version", version);
        config.saveGeneral();
        config.saveMessages();
    }
}
