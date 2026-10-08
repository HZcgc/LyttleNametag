package com.lyttledev.lyttlenametag.handlers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Packet-thread-safe, per-viewer overlay on the server's passenger lists. */
final class NametagPassengers {
    private final Map<Integer, int[]> nametags = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, State>> viewers = new ConcurrentHashMap<>();

    private record State(int[] original, boolean visible) {}

    void register(int owner, int[] displays) {
        nametags.put(owner, displays.clone());
    }

    int[] show(UUID viewer, int owner, int[] fallback) {
        State state = states(viewer).compute(owner, (id, old) ->
                new State(old == null ? fallback.clone() : old.original, true));
        return append(state.original, nametags.getOrDefault(owner, new int[0]));
    }

    int[] hide(UUID viewer, int owner) {
        Map<Integer, State> states = viewers.get(viewer);
        if (states == null) return null;
        State state = states.computeIfPresent(owner, (id, old) -> new State(old.original, false));
        return state == null ? null : state.original.clone();
    }

    int[] refresh(UUID viewer, int owner) {
        Map<Integer, State> states = viewers.get(viewer);
        State state = states == null ? null : states.get(owner);
        if (state == null || !state.visible) return null;
        return append(state.original, nametags.getOrDefault(owner, new int[0]));
    }

    int[] rewrite(UUID viewer, int owner, int[] incoming) {
        int[] displays = nametags.get(owner);
        if (displays == null) return incoming;
        int[] original = Arrays.stream(incoming)
                .filter(id -> Arrays.stream(displays).noneMatch(display -> display == id)).distinct().toArray();
        State state = states(viewer).compute(owner, (id, old) -> new State(original, old != null && old.visible));
        return state.visible ? append(state.original, displays) : state.original.clone();
    }

    void untrack(UUID viewer, int owner) {
        Map<Integer, State> states = viewers.get(viewer);
        if (states != null) states.remove(owner);
    }

    void removeViewer(UUID viewer) { viewers.remove(viewer); }

    Map<UUID, int[]> removeOwner(int owner) {
        nametags.remove(owner);
        Map<UUID, int[]> restore = new HashMap<>();
        viewers.forEach((viewer, states) -> {
            State old = states.remove(owner);
            if (old != null && old.visible) restore.put(viewer, old.original.clone());
        });
        return restore;
    }

    void clear() { nametags.clear(); viewers.clear(); }

    private Map<Integer, State> states(UUID viewer) {
        return viewers.computeIfAbsent(viewer, id -> new ConcurrentHashMap<>());
    }

    private static int[] append(int[] original, int[] displays) {
        return java.util.stream.IntStream.concat(Arrays.stream(original), Arrays.stream(displays)).distinct().toArray();
    }
}
