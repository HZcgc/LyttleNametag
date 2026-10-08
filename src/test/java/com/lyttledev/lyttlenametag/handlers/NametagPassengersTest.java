package com.lyttledev.lyttlenametag.handlers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NametagPassengersTest {
    private final UUID viewer = UUID.randomUUID();
    private final UUID otherViewer = UUID.randomUUID();
    private NametagPassengers passengers;

    @BeforeEach
    void setup() {
        passengers = new NametagPassengers();
        passengers.register(42, new int[]{100, 101, 102});
    }

    @Test
    void serverDetachCannotLeaveNametagFloating() {
        assertArrayEquals(new int[]{100, 101, 102}, passengers.show(viewer, 42, new int[0]));
        // Vanilla sends an empty mount list after a pose/vehicle transition.
        assertArrayEquals(new int[]{100, 101, 102}, passengers.rewrite(viewer, 42, new int[0]));
        assertArrayEquals(new int[]{100, 101, 102}, passengers.refresh(viewer, 42));
    }

    @Test
    void existingAndNewForeignPassengersSurviveNametagRefreshAndRemoval() {
        assertArrayEquals(new int[]{9}, passengers.rewrite(viewer, 42, new int[]{9}));
        assertArrayEquals(new int[]{9, 100, 101, 102}, passengers.show(viewer, 42, new int[0]));
        assertArrayEquals(new int[]{10, 100, 101, 102}, passengers.rewrite(viewer, 42, new int[]{10}));
        assertArrayEquals(new int[]{10, 100, 101, 102}, passengers.refresh(viewer, 42));
        assertArrayEquals(new int[]{10}, passengers.removeOwner(42).get(viewer));
        assertArrayEquals(new int[]{10}, passengers.rewrite(viewer, 42, new int[]{10}));
    }

    @Test
    void untrackingAndReturningStartsAFreshViewerMount() {
        passengers.show(viewer, 42, new int[]{9});
        passengers.untrack(viewer, 42);
        assertNull(passengers.refresh(viewer, 42));
        // Re-entering tracking range does not retain a despawned pet or an old
        // "visible" state. New player spawn is followed by this fresh mount.
        assertArrayEquals(new int[]{100, 101, 102}, passengers.show(viewer, 42, new int[0]));
    }

    @Test
    void hideAndWorldResetDoNotResurrectHiddenDisplays() {
        passengers.show(viewer, 42, new int[]{9});
        assertArrayEquals(new int[]{9}, passengers.hide(viewer, 42));
        assertArrayEquals(new int[]{9}, passengers.rewrite(viewer, 42, new int[]{9, 100, 101, 102}));
        assertNull(passengers.refresh(viewer, 42));
        passengers.removeViewer(viewer);
        assertNull(passengers.refresh(viewer, 42));
        assertArrayEquals(new int[]{11}, passengers.rewrite(viewer, 42, new int[]{11}));
    }

    @Test
    void viewerSpecificVisibilityAndOriginalMountsStaySeparate() {
        passengers.show(viewer, 42, new int[]{9});
        passengers.rewrite(otherViewer, 42, new int[]{12});
        assertArrayEquals(new int[]{12}, passengers.rewrite(otherViewer, 42, new int[]{12}));
        assertArrayEquals(new int[]{9, 100, 101, 102}, passengers.refresh(viewer, 42));
        passengers.show(otherViewer, 42, new int[0]);
        passengers.hide(viewer, 42);
        assertArrayEquals(new int[]{12, 100, 101, 102}, passengers.refresh(otherViewer, 42));
        assertNull(passengers.refresh(viewer, 42));
    }

    @Test
    void ownPacketsDoNotDuplicateDisplaysOrMutateSavedPassengerArrays() {
        int[] original = new int[]{9};
        passengers.show(viewer, 42, original);
        original[0] = 999;
        int[] wire = passengers.refresh(viewer, 42);
        assertArrayEquals(new int[]{9, 100, 101, 102}, wire);
        assertArrayEquals(wire, passengers.rewrite(viewer, 42, wire));
        wire[0] = 888;
        assertArrayEquals(new int[]{9, 100, 101, 102}, passengers.refresh(viewer, 42));
        assertArrayEquals(new int[]{7}, passengers.rewrite(viewer, 777, new int[]{7}));
    }
}
