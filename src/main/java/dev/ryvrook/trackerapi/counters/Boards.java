package dev.ryvrook.trackerapi.counters;

import dev.ryvrook.trackerapi.api.Keys;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Leaderboards over every player file, rebuilt on the IO thread every {@code boardSeconds}. Every total
 * is kept, plus any key another plugin asked for with {@link #watch}, such as {@code dominion:claims}.
 * Per world copies and per id keys get a board only when watched. A build reads every file once, so
 * it is bounded by the player count, about a millisecond per hundred files.
 */
public final class Boards {
    private static final int MAX_TOP = 1000;

    private final CounterStore store;
    private final HytaleLogger logger;
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private volatile Map<String, List<Map.Entry<UUID, Long>>> boards = Map.of();
    private volatile long builtAt;
    private volatile int players;

    Boards(CounterStore store, HytaleLogger logger) {
        this.store = store;
        this.logger = logger;
    }

    public void watch(String key) {
        if (key != null && !key.isEmpty() && key.length() <= 128 && !Keys.isHidden(key)) {
            watched.add(key);
        }
    }

    public Set<String> keys() {
        return Set.copyOf(boards.keySet());
    }

    public List<Map.Entry<UUID, Long>> top(String key, int n) {
        List<Map.Entry<UUID, Long>> board = boards.get(key);
        if (board == null) {
            return List.of();
        }
        return board.subList(0, Math.min(board.size(), Math.max(0, Math.min(n, MAX_TOP))));
    }

    public long builtAt() {
        return builtAt;
    }

    public int players() {
        return players;
    }

    /** IO thread. Reads every file and sorts. */
    public void rebuild() {
        try {
            Set<UUID> uuids = new LinkedHashSet<>(store.filesOnDisk());
            for (PlayerCounters pc : store.loadedPlayers()) {
                uuids.add(pc.uuid());
            }
            Map<String, List<Map.Entry<UUID, Long>>> next = new HashMap<>();
            for (UUID uuid : uuids) {
                Map<String, Long> counters = store.snapshotAny(uuid);
                for (Map.Entry<String, Long> e : counters.entrySet()) {
                    String key = e.getKey();
                    if (e.getValue() <= 0 && !CounterRules.isMin(key)) {
                        continue; // a lowest value of zero or below is a real record, not an empty slot
                    }
                    boolean total = key.indexOf(':') < 0;
                    if (!total && !watched.contains(key)) {
                        continue;
                    }
                    next.computeIfAbsent(key, k -> new ArrayList<>()).add(new AbstractMap.SimpleImmutableEntry<>(uuid, e.getValue()));
                }
            }
            for (Map.Entry<String, List<Map.Entry<UUID, Long>>> e : next.entrySet()) {
                List<Map.Entry<UUID, Long>> list = e.getValue();
                boolean ascending = CounterRules.isMin(e.getKey());
                list.sort((a, b) -> ascending ? Long.compare(a.getValue(), b.getValue()) : Long.compare(b.getValue(), a.getValue()));
                if (list.size() > MAX_TOP) {
                    list = new ArrayList<>(list.subList(0, MAX_TOP));
                }
                e.setValue(Collections.unmodifiableList(list));
            }
            boards = Map.copyOf(next);
            players = uuids.size();
            builtAt = System.currentTimeMillis();
        } catch (RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not rebuild the leaderboards");
        }
    }
}
