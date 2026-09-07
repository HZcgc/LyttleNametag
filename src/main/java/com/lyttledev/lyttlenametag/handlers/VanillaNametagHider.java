package com.lyttledev.lyttlenametag.handlers;

import com.lyttledev.lyttlenametag.LyttleNametag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Hides Minecraft's built-in player nametag so only the packet-based multiline
 * nametag remains visible. The team is installed on every scoreboard currently
 * assigned to a real online player, which keeps it compatible with hub plugins
 * that replace a player's scoreboard after joining.
 */
public final class VanillaNametagHider implements Listener {
    private static final String TEAM_NAME = "lyttle_hide_nt";

    private final LyttleNametag plugin;
    private final Set<Scoreboard> touchedScoreboards =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private BukkitTask syncTask;

    public VanillaNametagHider(LyttleNametag plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        reload();
    }

    public void reload() {
        stopSyncTask();

        if (!isEnabled()) {
            restoreVanillaNametags();
            return;
        }

        syncNow();
        // DeluxeHub and similar plugins may assign a new scoreboard shortly
        // after join. Re-checking once per second keeps the hidden team present
        // without replacing or clearing the scoreboard itself.
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncNow, 20L, 20L);
    }

    public void shutdown() {
        stopSyncTask();
        restoreVanillaNametags();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (isCitizensNpc(event.getPlayer())) {
            return;
        }
        scheduleSync(1L);
        scheduleSync(80L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!isCitizensNpc(event.getPlayer())) {
            scheduleSync(1L);
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!isCitizensNpc(event.getPlayer())) {
            scheduleSync(1L);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!isCitizensNpc(event.getPlayer())) {
            scheduleSync(1L);
        }
    }

    private void scheduleSync(long delay) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::syncNow, delay);
        }
    }

    private void syncNow() {
        if (!isEnabled()) {
            return;
        }

        Set<String> realPlayerNames = new HashSet<>();
        Set<Scoreboard> activeScoreboards =
                Collections.newSetFromMap(new IdentityHashMap<>());

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isCitizensNpc(player)) {
                continue;
            }
            realPlayerNames.add(player.getName());
            activeScoreboards.add(player.getScoreboard());
        }

        for (Scoreboard scoreboard : activeScoreboards) {
            Team team = scoreboard.getTeam(TEAM_NAME);
            if (team == null) {
                team = scoreboard.registerNewTeam(TEAM_NAME);
            }

            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

            for (String staleEntry : new HashSet<>(team.getEntries())) {
                if (!realPlayerNames.contains(staleEntry)) {
                    team.removeEntry(staleEntry);
                }
            }
            for (String playerName : realPlayerNames) {
                if (!team.hasEntry(playerName)) {
                    team.addEntry(playerName);
                }
            }

            touchedScoreboards.add(scoreboard);
        }
    }

    private void restoreVanillaNametags() {
        for (Scoreboard scoreboard : new HashSet<>(touchedScoreboards)) {
            Team team = scoreboard.getTeam(TEAM_NAME);
            if (team != null) {
                team.unregister();
            }
        }
        touchedScoreboards.clear();
    }

    private void stopSyncTask() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    private boolean isEnabled() {
        return plugin.config.general.getBoolean("hide_vanilla_nametag", true);
    }

    private boolean isCitizensNpc(Player player) {
        return player.hasMetadata("NPC");
    }
}
