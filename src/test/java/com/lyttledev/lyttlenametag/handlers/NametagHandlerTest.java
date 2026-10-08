package com.lyttledev.lyttlenametag.handlers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.lyttledev.lyttlenametag.LyttleNametag;
import com.lyttledev.lyttlenametag.types.Configs;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NametagHandlerTest {
    private PacketEventsAPI<?> previousApi;
    private PacketEventsAPI<?> api;
    private PlayerManager playerManager;
    private NametagHandler handler;
    private Player owner;
    private Player viewer;
    private World world;
    private Set<Player> tracked;
    private final List<PacketWrapper<?>> packets = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        previousApi = PacketEvents.getAPI();
        api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        when(api.getSettings()).thenReturn(new PacketEventsSettings().customResourceProvider(
                name -> PacketEvents.class.getClassLoader().getResourceAsStream(name)));
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_11);
        playerManager = api.getPlayerManager();
        PacketEvents.setAPI(api);

        LyttleNametag plugin = mock(LyttleNametag.class);
        plugin.config = mock(Configs.class);
        plugin.config.general = new YamlConfiguration();
        plugin.config.general.set("view_distance", 64);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        handler = spy(new NametagHandler(plugin, false));
        // Paper's potion registries require a running server; these tests
        // exercise packet tracking with an ordinarily visible owner.
        doReturn(false).when(handler).isGloballyHidden(any(Player.class));

        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        owner = mock(Player.class);
        viewer = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(owner.getEntityId()).thenReturn(42);
        when(owner.getWorld()).thenReturn(world);
        when(viewer.getWorld()).thenReturn(world);
        when(owner.isOnline()).thenReturn(true);
        when(viewer.isOnline()).thenReturn(true);
        when(owner.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        when(viewer.getLocation()).thenReturn(new Location(world, 11, 64, 10));
        when(viewer.canSee(owner)).thenReturn(true);
        when(owner.getPassengers()).thenReturn(List.of());
        tracked = new HashSet<>();
        tracked.add(viewer);
        when(owner.getTrackedBy()).thenReturn(tracked);

        nametags().put(owner.getUniqueId(), new NametagHandler.NametagEntity(42,
                List.of(100, 101, 102), List.of(Component.text("name"), Component.text("rank"), Component.text("town"))));
        mounts().register(42, new int[]{100, 101, 102});
        doAnswer(call -> { packets.add(call.getArgument(1)); return null; })
                .when(playerManager).sendPacket(eq(viewer), any(PacketWrapper.class));
    }

    @AfterEach
    void restoreApi() { PacketEvents.setAPI(previousApi); }

    @Test
    void eachLineRidesTheRealPlayerDirectly() throws Exception {
        show();
        List<WrapperPlayServerSetPassengers> mounts = packets.stream()
                .filter(WrapperPlayServerSetPassengers.class::isInstance)
                .map(WrapperPlayServerSetPassengers.class::cast).toList();
        assertEquals(1, mounts.size());
        assertEquals(42, mounts.getFirst().getEntityId());
        assertArrayEquals(new int[]{100, 101, 102}, mounts.getFirst().getPassengers());
        assertEquals(3, packets.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
        assertTrue(visible());
    }

    @Test
    void noFloatingTagSpawnsWithoutTheRealPlayerBeingTracked() throws Exception {
        tracked.clear();
        show();
        assertFalse(packets.stream().anyMatch(WrapperPlayServerSpawnEntity.class::isInstance));
        assertFalse(visible());
        tracked.add(viewer);
        packets.clear();
        show();
        assertEquals(3, packets.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
        assertTrue(visible());
    }

    @Test
    void failedSendDoesNotMarkTagVisibleAndNextAttemptRecovers() throws Exception {
        doThrow(new IllegalStateException("test failed packet")).doAnswer(call -> {
            packets.add(call.getArgument(1)); return null;
        }).when(playerManager).sendPacket(eq(viewer), any(PacketWrapper.class));
        show();
        assertFalse(visible());
        packets.clear();
        show();
        assertTrue(visible());
        assertEquals(3, packets.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count());
    }

    @Test
    void vanishAndWorldMismatchDoNotSpawnDisplays() throws Exception {
        when(viewer.canSee(owner)).thenReturn(false);
        show();
        assertFalse(visible());
        assertFalse(packets.stream().anyMatch(WrapperPlayServerSpawnEntity.class::isInstance));
        when(viewer.canSee(owner)).thenReturn(true);
        World otherWorld = mock(World.class);
        when(otherWorld.getUID()).thenReturn(UUID.randomUUID());
        when(viewer.getWorld()).thenReturn(otherWorld);
        show();
        assertFalse(visible());
        assertFalse(packets.stream().anyMatch(WrapperPlayServerSpawnEntity.class::isInstance));
    }

    @Test
    void configuredRangeIsRespectedAndReturningShowsTheTagAgain() throws Exception {
        when(viewer.getLocation()).thenReturn(new Location(world, 100, 64, 10));
        show();
        assertFalse(visible());
        assertFalse(packets.stream().anyMatch(WrapperPlayServerSpawnEntity.class::isInstance));
        when(viewer.getLocation()).thenReturn(new Location(world, 11, 64, 10));
        show();
        assertTrue(visible());
    }

    private void show() throws Exception {
        Method method = NametagHandler.class.getDeclaredMethod("showNametagToPlayer", Player.class, Player.class);
        method.setAccessible(true);
        method.invoke(handler, owner, viewer);
    }

    private boolean visible() throws Exception {
        Method method = NametagHandler.class.getDeclaredMethod("getLastVisibility", Player.class, Player.class);
        method.setAccessible(true);
        return (boolean) method.invoke(handler, viewer, owner);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, NametagHandler.NametagEntity> nametags() throws Exception {
        Field field = NametagHandler.class.getDeclaredField("playerNametags");
        field.setAccessible(true);
        return (Map<UUID, NametagHandler.NametagEntity>) field.get(handler);
    }

    private NametagPassengers mounts() throws Exception {
        Field field = NametagHandler.class.getDeclaredField("passengers");
        field.setAccessible(true);
        return (NametagPassengers) field.get(handler);
    }
}
