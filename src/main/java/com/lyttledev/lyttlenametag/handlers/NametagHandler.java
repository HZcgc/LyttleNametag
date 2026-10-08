package com.lyttledev.lyttlenametag.handlers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.lyttledev.lyttlenametag.LyttleNametag;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class NametagHandler implements Listener {
    private final LyttleNametag plugin;
    private final Map<UUID, NametagEntity> playerNametags = new ConcurrentHashMap<>();
    private final AtomicInteger entityIdCounter = new AtomicInteger(Integer.MAX_VALUE / 2);
    private BukkitTask timer;
    private BukkitTask hardReloadTimer;
    private BukkitTask visibilityTimer; // Fast, lightweight visibility enforcement (vanish/world) without flicker
    private final Map<UUID, Map<UUID, Boolean>> viewerVisibility = new ConcurrentHashMap<>(); // viewer -> (owner -> visible)
    private final double nametagSpawnHeight = 1.8; // Height above player's head for nametag
    private final NametagPassengers passengers = new NametagPassengers();
    private final PacketListenerAbstract passengerListener;

    public NametagHandler(LyttleNametag plugin) {
        this(plugin, true);
    }

    NametagHandler(LyttleNametag plugin, boolean startTasks) {
        this.plugin = plugin;
        this.passengerListener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.isCancelled() || event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS) return;
                UUID viewer = event.getUser().getUUID();
                if (viewer == null) return;
                WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
                int[] rewritten = passengers.rewrite(viewer, packet.getEntityId(), packet.getPassengers());
                if (!Arrays.equals(rewritten, packet.getPassengers())) {
                    packet.setPassengers(rewritten);
                    event.markForReEncode(true);
                }
            }
        };
        if (startTasks) {
            PacketEvents.getAPI().getEventManager().registerListener(passengerListener);
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            startTimer();
            startHardReloadTimer();
            startVisibilityTimer();
        }
    }

    public void reload() {
        startTimer();
        reloadNametags();
        startHardReloadTimer();
        startVisibilityTimer();
    }

    private void startTimer() {
        if (timer != null) {
            timer.cancel();
        }

        double interval = Math.max(0.05D, plugin.config.general.getDouble("interval", 0.5D));

        // Periodic cleanup and update
        this.timer = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOrphans();
                // Enforce viewer visibility each cycle to catch vanish changes even if lines didn't change
                enforceVisibilityMatrix();
                updateNametagTexts();
            }
        }.runTaskTimer(plugin, 0, Math.round(interval * 20));
    }

    private void startHardReloadTimer() {
        if (hardReloadTimer != null) {
            hardReloadTimer.cancel();
        }
        // Soft refresh every 60 seconds (1 minute) without destroying/spawning to avoid flicker
        this.hardReloadTimer = new BukkitRunnable() {
            @Override
            public void run() {
                softRefreshNametags();
            }
        }.runTaskTimer(plugin, 0, 20 * 60); // 20 ticks per second * 60 seconds
    }

    // Fast visibility enforcement loop (every 5 ticks) to react quickly to vanish/unvanish and cross-world moves
    private void startVisibilityTimer() {
        if (visibilityTimer != null) {
            visibilityTimer.cancel();
        }
        this.visibilityTimer = new BukkitRunnable() {
            private int cycles;
            @Override
            public void run() {
                enforceVisibilityMatrix();
                // Client movement follows the player directly. A small mount
                // refresh repairs an external detach without recreating text.
                if (++cycles % 4 == 0) {
                    for (Player owner : Bukkit.getOnlinePlayers()) {
                        NametagEntity entity = playerNametags.get(owner.getUniqueId());
                        if (entity != null) resendPassengers(owner, entity);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 5L); // ~0.25s
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            spawnNametag(event.getPlayer());
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.equals(event.getPlayer())) continue;
                // Only show nametags of players in the same world as the joining player and that the viewer can see
                if (shouldHideForViewer(onlinePlayer, event.getPlayer())) {
                    NametagEntity e = playerNametags.get(onlinePlayer.getUniqueId());
                    if (e != null) {
                        sendDestroyToViewer(event.getPlayer(), e.getEntityIds());
                    }
                    // Update cache as hidden
                    setLastVisibility(event.getPlayer(), onlinePlayer, false);
                    continue;
                }
                showNametagToPlayer(onlinePlayer, event.getPlayer());
            }
        }, 10L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clear this player as a viewer from the visibility cache
        clearViewerFromVisibilityCache(event.getPlayer().getUniqueId());
        passengers.removeViewer(event.getPlayer().getUniqueId());
        removeNametag(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        removeNametag(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        refreshAfterTransition(event.getPlayer(), true);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        refreshAfterTransition(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        refreshAfterTransition(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        refreshAfterTransition(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTrackEntity(PlayerTrackEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner) || owner.hasMetadata("NPC")) return;
        Player viewer = event.getPlayer();
        // The real player's spawn packet must arrive before any mount packet.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (shouldHideForViewer(owner, viewer)) return;
            NametagEntity entity = playerNametags.get(owner.getUniqueId());
            if (entity == null) {
                spawnNametag(owner);
            } else {
                sendDestroyToViewer(viewer, entity.getEntityIds());
                setLastVisibility(viewer, owner, false);
                showNametagToPlayer(owner, viewer);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerUntrackEntity(PlayerUntrackEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner)) return;
        NametagEntity entity = playerNametags.get(owner.getUniqueId());
        if (entity == null) return;
        hideNametagFromViewer(owner, event.getPlayer(), entity, false);
        passengers.untrack(event.getPlayer().getUniqueId(), entity.ownerEntityId);
    }

    // Pose changes update existing text rather than recreate display entities.
    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        // Bukkit applies the new pose after this event, so read it next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            updateOwnerVisibilityNametag(event.getPlayer());
            enforceVisibilityMatrix();
        });
    }

    // Instant update when invisibility potion is applied/removed
    @EventHandler
    public void onEntityPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getModifiedType() != PotionEffectType.INVISIBILITY) return;
        Player player = (Player) event.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> {
            updateOwnerVisibilityNametag(player);
            enforceVisibilityMatrix();
        });
    }

    private void refreshAfterTransition(Player player, boolean resetViewer) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (resetViewer) {
                Map<UUID, Boolean> seen = viewerVisibility.get(player.getUniqueId());
                if (seen != null) seen.forEach((ownerId, visible) -> {
                    NametagEntity entity = playerNametags.get(ownerId);
                    if (visible && entity != null) sendDestroyToViewer(player, entity.getEntityIds());
                });
                clearViewerFromVisibilityCache(player.getUniqueId());
                passengers.removeViewer(player.getUniqueId());
            }
            NametagEntity entity = playerNametags.get(player.getUniqueId());
            if (entity == null || entity.ownerEntityId != player.getEntityId()) spawnNametag(player);
            else resendPassengers(player, entity);
            enforceVisibilityMatrix();
        }, 2L);
    }

    private void spawnNametag(Player player) {
        removeNametag(player);
        if (!player.isOnline() || player.isDead() || player.hasMetadata("NPC")) return;

        org.bukkit.Location baseLoc = player.getLocation().clone();
        baseLoc.setY(baseLoc.getY() + nametagSpawnHeight);

        // Render bottom-up; every line rides the player and uses its own translation.
        // NOTE: We always allocate the full template line count to avoid re-spawn flicker on visibility toggles.
        String nametagTemplate = plugin.config.general.getString("nametag", "<luckperms_prefix>\n<gray><player></gray>");
        List<Component> linesBottomUp = renderLinesBottomUp(nametagTemplate, player, baseLoc);

        // Create entity IDs for each line (one Text Display per line)
        List<Integer> entityIds = new ArrayList<>(linesBottomUp.size());
        for (int i = 0; i < linesBottomUp.size(); i++) {
            entityIds.add(entityIdCounter.decrementAndGet());
        }

        NametagEntity nametagEntity = new NametagEntity(
                player.getEntityId(),
                entityIds,
                linesBottomUp
        );

        playerNametags.put(player.getUniqueId(), nametagEntity);
        passengers.register(player.getEntityId(), entityIds.stream().mapToInt(Integer::intValue).toArray());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player)) {
                // Only show to viewers in the same world who can see the player (not vanished for them)
                if (!shouldHideForViewer(player, viewer)) {
                    showNametagToPlayer(player, viewer);
                } else {
                    // Ensure it's hidden for non-eligible viewers
                    sendDestroyToViewer(viewer, nametagEntity.getEntityIds());
                    setLastVisibility(viewer, player, false);
                }
            }
        }

        // If the player is currently globally hidden (sneak/invis), immediately hide by swapping text to empty (no respawn).
        if (isGloballyHidden(player)) {
            updateOwnerVisibilityNametag(player);
        }
    }

    private List<Component> renderLinesBottomUp(
            String nametagTemplate,
            Player player,
            org.bukkit.Location location
    ) {
        // Split by newline, preserve trailing empty lines, then render each line separately
        String[] rawLines = nametagTemplate.split("\\R", -1);
        List<Component> topDown = new ArrayList<>(rawLines.length);
        for (String rawLine : rawLines) {
            Component lineComponent = plugin.textRenderer.render(rawLine, player, location);
            topDown.add(lineComponent);
        }
        // Reverse to bottom-up so the last (often empty) line becomes the bottom-most rider
        List<Component> bottomUp = new ArrayList<>(topDown);
        Collections.reverse(bottomUp);
        return bottomUp;
    }

    private void showNametagToPlayer(Player owner, Player viewer) {
        NametagEntity entity = playerNametags.get(owner.getUniqueId());
        if (entity == null) return;

        // Only show if owner and viewer are in the same world and viewer can see owner; otherwise ensure it's destroyed for this viewer
        if (shouldHideForViewer(owner, viewer)) {
            hideNametagFromViewer(owner, viewer, entity, true);
            return;
        }

        int ownerId = owner.getEntityId();

        try {
            org.bukkit.Location bukkit_location = owner.getLocation().clone();
            bukkit_location.setY(bukkit_location.getY() + nametagSpawnHeight);

            Location packetevents_location = new Location(
                    bukkit_location.getX(),
                    bukkit_location.getY(),
                    bukkit_location.getZ(),
                    bukkit_location.getYaw(),
                    bukkit_location.getPitch()
            );

            float defaultViewDistance = 1.0f; // default (1 unit) equals 64 blocks
            // Tested: Default is 80 for our metadata.
            // Default Minecraft client nametag distance is 64 blocks.
            float blocksPerDefault = 80.0f;
            float oneBlockViewDistance = defaultViewDistance / blocksPerDefault;

            int blocksConfig = plugin.config.general.getInt("view_distance", 64);
            int blocks = blocksConfig > 0 ? blocksConfig : 64; // Default to 64 blocks if not set

            // Configurable line spacing in blocks (world units). Default ~0.275 blocks.
            double lineSpacing = plugin.config.general.getDouble("line_spacing", 0.275D);

            // Create the spawn packet for each text display entity (one per line), bottom-up
            List<Integer> lineEntityIds = entity.getEntityIds();
            List<Component> linesBottomUp = entity.getLines(); // bottom-up order

            for (int i = 0; i < lineEntityIds.size(); i++) {
                int lineEntityId = lineEntityIds.get(i);

                WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                        lineEntityId,
                        UUID.randomUUID(),
                        EntityTypes.TEXT_DISPLAY,
                        packetevents_location,
                        0f, // yaw
                        0,  // data
                        new Vector3d(0, 0, 0) // velocity
                );

                // Add metadata to the Text Display, see protocol info here: https://minecraft.wiki/w/Java_Edition_protocol/Entity_metadata#Text_Display
                List<EntityData<?>> metadata = new ArrayList<>();

                // Display entity flags: enable transformations + centered billboard so it faces viewers
                metadata.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) 0x03));

                // View distance
                metadata.add(new EntityData<>(17, EntityDataTypes.FLOAT, oneBlockViewDistance * blocks));

                // Text-display passengers share the same base height, so every line needs
                // its own increasing translation to form a readable vertical stack.
                float yOffset = (float) ((i + 1) * lineSpacing);
                metadata.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(0f, yOffset, 0f)));

                // Set the text content of this line (each line is its own display)
                Component lineText = linesBottomUp.get(i);
                // If owner is globally hidden, ensure we send empty text to this viewer too
                if (isGloballyHidden(owner)) {
                    lineText = Component.text("");
                }
                metadata.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, lineText));

                // Remove the vanilla translucent rectangles behind every line.
                metadata.add(new EntityData<>(25, EntityDataTypes.INT, 0));

                WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(lineEntityId, metadata);

                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, spawnPacket);
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
            }

            // Every line rides the PLAYER directly; translations supply line
            // spacing. Never overwrite real or other plugins' passengers.
            int[] mounted = passengers.show(viewer.getUniqueId(), ownerId,
                    owner.getPassengers().stream().mapToInt(org.bukkit.entity.Entity::getEntityId).toArray());
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                    new WrapperPlayServerSetPassengers(ownerId, mounted));

            // Mark as visible for this viewer-owner pair
            setLastVisibility(viewer, owner, true);
        } catch (Exception e) {
            passengers.hide(viewer.getUniqueId(), ownerId);
            setLastVisibility(viewer, owner, false);
            // A partial spawn must not be cached as visible: the visibility
            // loop can retry this viewer without a plugin reload.
            try { sendDestroyToViewer(viewer, entity.getEntityIds()); }
            catch (Exception cleanup) { e.addSuppressed(cleanup); }
            plugin.getLogger().severe("Error showing nametag: " + e.getMessage());
        }
    }

    private void updateNametagTexts() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            NametagEntity entity = playerNametags.get(player.getUniqueId());
            if (entity == null) continue;

            org.bukkit.Location baseLoc = player.getLocation().clone();
            baseLoc.setY(baseLoc.getY() + nametagSpawnHeight);

            // When globally hidden (sneaking/invisible), keep entity count stable and set all lines to empty to avoid respawn flicker.
            List<Component> newLinesBottomUp;
            if (isGloballyHidden(player)) {
                newLinesBottomUp = emptyLines(entity.getEntityIds().size());
            } else {
                String nametagTemplate = plugin.config.general.getString("nametag", "<luckperms_prefix>\n<gray><player></gray>");
                List<Component> rendered = renderLinesBottomUp(nametagTemplate, player, baseLoc);
                // Normalize to the current entity count to avoid destroy/spawn
                newLinesBottomUp = normalizeToSize(rendered, entity.getEntityIds().size());
            }

            // Update changed lines only (no destroy/spawn to prevent flicker)
            boolean anyChanged = false;
            List<Component> old = entity.getLines();
            if (old.size() != newLinesBottomUp.size()) {
                // Sizes should generally match due to normalization; if not, normalize old view and continue
                newLinesBottomUp = normalizeToSize(newLinesBottomUp, old.size());
            }
            for (int i = 0; i < newLinesBottomUp.size(); i++) {
                if (!newLinesBottomUp.get(i).equals(old.get(i))) {
                    anyChanged = true;
                    break;
                }
            }

            if (anyChanged) {
                entity.setLines(newLinesBottomUp);
                sendNametagTextUpdate(player, entity);
            }
        }
    }

    // Instant metadata-only swap for visibility state (sneak/invisible) (no destroy/spawn, no delay).
    private void updateOwnerVisibilityNametag(Player player) {
        NametagEntity entity = playerNametags.get(player.getUniqueId());
        if (entity == null) return;

        List<Component> target;
        if (isGloballyHidden(player)) {
            target = emptyLines(entity.getEntityIds().size());
        } else {
            org.bukkit.Location baseLoc = player.getLocation().clone();
            baseLoc.setY(baseLoc.getY() + nametagSpawnHeight);

            String nametagTemplate = plugin.config.general.getString("nametag", "<luckperms_prefix>\n<gray><player></gray>");
            List<Component> rendered = renderLinesBottomUp(nametagTemplate, player, baseLoc);
            target = normalizeToSize(rendered, entity.getEntityIds().size());
        }

        // Only send updates for lines that changed
        boolean anyChanged = false;
        List<Component> old = entity.getLines();
        for (int i = 0; i < target.size(); i++) {
            if (!target.get(i).equals(old.get(i))) {
                anyChanged = true;
                break;
            }
        }
        if (!anyChanged) return;

        entity.setLines(target);
        sendNametagTextUpdate(player, entity);
    }

    // Non-destructive periodic refresh: re-apply text state and mounts without destroy/spawn.
    private void softRefreshNametags() {
        cleanupOrphans();
        for (Player owner : Bukkit.getOnlinePlayers()) {
            NametagEntity entity = playerNametags.get(owner.getUniqueId());
            if (entity == null) {
                // If somehow missing, spawn anew (rare). This will only affect that player’s nametag.
                spawnNametag(owner);
                continue;
            }
            // Re-apply current visibility state instantly (metadata only)
            updateOwnerVisibilityNametag(owner);
            resendPassengers(owner, entity);
        }
    }

    // Enforce per-viewer visibility transitions (spawn/destroy) based on world and vanish state.
    private void enforceVisibilityMatrix() {
        for (Player owner : Bukkit.getOnlinePlayers()) {
            if (!playerNametags.containsKey(owner.getUniqueId()) && owner.isOnline() && !owner.isDead()
                    && !owner.hasMetadata("NPC")) spawnNametag(owner);
        }
        // Iterate owners with nametags
        for (Map.Entry<UUID, NametagEntity> entry : playerNametags.entrySet()) {
            UUID ownerId = entry.getKey();
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) continue;

            NametagEntity entity = entry.getValue();
            Set<Player> tracked = owner.getTrackedBy();

            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(ownerId)) continue;

                boolean desiredVisible = !shouldHideForViewer(owner, viewer, tracked);
                boolean lastVisible = getLastVisibility(viewer, owner);

                if (desiredVisible && !lastVisible) {
                    // Spawn to this viewer now
                    showNametagToPlayer(owner, viewer);
                } else if (!desiredVisible && lastVisible) {
                    // Destroy for this viewer now
                    hideNametagFromViewer(owner, viewer, entity, true);
                }
            }
        }
    }

    private void resendPassengers(Player owner, NametagEntity entity) {
        Set<Player> tracked = owner.getTrackedBy();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!getLastVisibility(viewer, owner)) continue;
            if (shouldHideForViewer(owner, viewer, tracked)) {
                hideNametagFromViewer(owner, viewer, entity, true);
                continue;
            }
            int[] mounted = passengers.refresh(viewer.getUniqueId(), entity.ownerEntityId);
            if (mounted != null) PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                    new WrapperPlayServerSetPassengers(entity.ownerEntityId, mounted));
        }
    }

    private void hideNametagFromViewer(Player owner, Player viewer, NametagEntity entity, boolean restoreMounts) {
        int[] original = passengers.hide(viewer.getUniqueId(), entity.ownerEntityId);
        if (restoreMounts && original != null && owner.getTrackedBy().contains(viewer) && sameWorld(owner, viewer)) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                    new WrapperPlayServerSetPassengers(entity.ownerEntityId, original));
        }
        sendDestroyToViewer(viewer, entity.getEntityIds());
        setLastVisibility(viewer, owner, false);
    }

    boolean isGloballyHidden(Player owner) {
        // Hide nametag when sneaking or invisible via potion/flag
        return owner.isSneaking()
                || owner.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || owner.isInvisible();
    }

    private boolean shouldHideForViewer(Player owner, Player viewer) {
        return shouldHideForViewer(owner, viewer, owner.getTrackedBy());
    }

    private boolean shouldHideForViewer(Player owner, Player viewer, Set<Player> tracked) {
        if (!owner.isOnline() || !viewer.isOnline() || owner.isDead() || owner.hasMetadata("NPC")
                || owner.equals(viewer) || !sameWorld(owner, viewer) || !viewer.canSee(owner)
                || !tracked.contains(viewer)) return true;
        double distance = Math.max(1D, plugin.config.general.getInt("view_distance", 64));
        return owner.getLocation().distanceSquared(viewer.getLocation()) > distance * distance;
    }

    private boolean sameWorld(Player a, Player b) {
        return a.getWorld().getUID().equals(b.getWorld().getUID());
    }

    private boolean getLastVisibility(Player viewer, Player owner) {
        Map<UUID, Boolean> m = viewerVisibility.get(viewer.getUniqueId());
        if (m == null) return false;
        return m.getOrDefault(owner.getUniqueId(), false);
    }

    private void setLastVisibility(Player viewer, Player owner, boolean visible) {
        viewerVisibility
                .computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(owner.getUniqueId(), visible);
    }

    private void clearOwnerFromVisibilityCache(UUID ownerId) {
        for (Map<UUID, Boolean> m : viewerVisibility.values()) {
            if (m != null) {
                m.remove(ownerId);
            }
        }
    }

    private void clearViewerFromVisibilityCache(UUID viewerId) {
        viewerVisibility.remove(viewerId);
    }

    private List<Component> normalizeToSize(List<Component> src, int size) {
        List<Component> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (i < src.size()) {
                out.add(src.get(i));
            } else {
                out.add(Component.text(""));
            }
        }
        // If src is longer, trim to size (avoid respawn)
        if (out.size() > size) {
            return new ArrayList<>(out.subList(0, size));
        }
        return out;
    }

    private List<Component> emptyLines(int size) {
        List<Component> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(Component.text(""));
        }
        return out;
    }

    private void sendNametagTextUpdate(Player owner, NametagEntity entity) {
        List<Integer> ids = entity.getEntityIds();
        List<Component> lines = entity.getLines();
        List<WrapperPlayServerEntityMetadata> updates = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            updates.add(new WrapperPlayServerEntityMetadata(ids.get(i),
                    List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, lines.get(i)))));
        }
        Set<Player> tracked = owner.getTrackedBy();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!getLastVisibility(viewer, owner)) continue;
            if (shouldHideForViewer(owner, viewer, tracked)) {
                hideNametagFromViewer(owner, viewer, entity, true);
                continue;
            }
            for (WrapperPlayServerEntityMetadata update : updates)
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, update);
        }
    }

    private void sendDestroyToViewer(Player viewer, List<Integer> entityIds) {
        for (int entityId : entityIds) {
            WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(entityId);
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroyPacket);
        }
    }

    private void removeNametag(Player player) {
        NametagEntity entity = playerNametags.remove(player.getUniqueId());
        // Remove this owner from all viewer caches
        clearOwnerFromVisibilityCache(player.getUniqueId());
        if (entity != null) {
            Map<UUID, int[]> restore = passengers.removeOwner(entity.ownerEntityId);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                int[] original = restore.get(viewer.getUniqueId());
                if (original != null && sameWorld(player, viewer) && player.getTrackedBy().contains(viewer)) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                            new WrapperPlayServerSetPassengers(entity.ownerEntityId, original));
                }
            }
            // Destroy all line entities for this player's nametag
            for (int entityId : entity.getEntityIds()) {
                WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(entityId);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroyPacket);
                }
            }
        }
    }

    private void cleanupOrphans() {
        // Remove owners who are offline from internal maps
        for (UUID uuid : new ArrayList<>(playerNametags.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                NametagEntity entity = playerNametags.remove(uuid);
                if (entity != null) {
                    passengers.removeOwner(entity.ownerEntityId);
                    for (Player viewer : Bukkit.getOnlinePlayers()) sendDestroyToViewer(viewer, entity.getEntityIds());
                }
                clearOwnerFromVisibilityCache(uuid);
            }
        }
        // Remove offline viewers from cache
        for (UUID viewerId : new ArrayList<>(viewerVisibility.keySet())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                clearViewerFromVisibilityCache(viewerId);
                passengers.removeViewer(viewerId);
            }
        }
    }

    public void removeAllNametagsOnShutdown() {
        // Restore underlying mounts before forgetting any packet overlay.
        for (Player player : Bukkit.getOnlinePlayers()) removeNametag(player);
        for (Map.Entry<UUID, NametagEntity> entry : playerNametags.entrySet()) {
            // Destroy all line entities for each nametag
            for (int entityId : entry.getValue().getEntityIds()) {
                WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(entityId);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, destroyPacket);
                }
            }
        }
        playerNametags.clear();
        viewerVisibility.clear();
        passengers.clear();
    }

    public void shutdown() {
        if (timer != null) timer.cancel();
        if (hardReloadTimer != null) hardReloadTimer.cancel();
        if (visibilityTimer != null) visibilityTimer.cancel();
        removeAllNametagsOnShutdown();
        PacketEvents.getAPI().getEventManager().unregisterListener(passengerListener);
    }

    private void reloadNametags() {
        removeAllNametagsOnShutdown();
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            spawnNametag(onlinePlayer);
        }
    }

    public static class NametagEntity {
        private final int ownerEntityId;
        private final List<Integer> entityIds; // bottom-up order
        private List<Component> lines; // bottom-up order

        public NametagEntity(int ownerEntityId, List<Integer> entityIds, List<Component> lines) {
            this.ownerEntityId = ownerEntityId;
            this.entityIds = entityIds;
            this.lines = lines;
        }

        public List<Integer> getEntityIds() {
            return entityIds;
        }

        public List<Component> getLines() {
            return lines;
        }

        public void setLines(List<Component> lines) {
            this.lines = lines;
        }
    }
}
