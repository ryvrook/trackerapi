package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.trackerapi.api.Keys;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One player's counters in memory. Guarded by its own monitor, which every write takes for a few map
 * operations. World threads write, the IO thread reads a snapshot to save, the API copies on read.
 */
public final class PlayerCounters {
    public static final int VERSION = 1;
    /** The file field that carries the start of a running session across a plugin reload. */
    public static final String SESSION_START = "session_start";
    /** A session start read from disk is taken only when the file was written this recently before the join. */
    public static final long SESSION_RESUME_MILLIS = 10 * 60_000L;

    private final UUID uuid;
    private String name;
    private long firstSeen;
    private long lastSeen;
    private long sessionStart;
    private final Map<String, long[]> counters = new HashMap<>(256);
    private JsonObject imported;
    private boolean dirty;
    /** Set while the file read that fills this object has not finished, so writes queue in memory first. */
    private volatile boolean merged = true;

    public PlayerCounters(UUID uuid, String name, long now) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.firstSeen = now;
        this.lastSeen = now;
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized String name() {
        return name;
    }

    public synchronized void setName(String name, long now) {
        if (name != null && !name.isEmpty() && !name.equals(this.name)) {
            this.name = name;
            dirty = true;
        }
        lastSeen = now;
    }

    public synchronized long firstSeen() {
        return firstSeen;
    }

    public synchronized long lastSeen() {
        return lastSeen;
    }

    /** When the running session began, zero when none is running. */
    public synchronized long sessionStart() {
        return sessionStart;
    }

    public synchronized void setSessionStart(long start) {
        if (start != sessionStart) {
            sessionStart = Math.max(0L, start);
            dirty = true;
        }
    }

    public synchronized void touch(long now) {
        lastSeen = now;
        dirty = true;
    }

    public synchronized void add(String key, long amount) {
        if (amount == 0) {
            return;
        }
        long[] slot = counters.get(key);
        if (slot == null) {
            counters.put(key, new long[]{amount});
        } else {
            slot[0] += amount;
        }
        dirty = true;
    }

    public synchronized void raiseTo(String key, long value) {
        long[] slot = counters.get(key);
        if (slot == null) {
            counters.put(key, new long[]{value});
            dirty = true;
        } else if (value > slot[0]) {
            slot[0] = value;
            dirty = true;
        }
    }

    public synchronized void lowerTo(String key, long value) {
        long[] slot = counters.get(key);
        if (slot == null) {
            counters.put(key, new long[]{value});
            dirty = true;
        } else if (value < slot[0]) {
            slot[0] = value;
            dirty = true;
        }
    }

    public synchronized void set(String key, long value) {
        long[] slot = counters.get(key);
        if (slot == null) {
            counters.put(key, new long[]{value});
        } else {
            slot[0] = value;
        }
        dirty = true;
    }

    public synchronized long get(String key) {
        long[] slot = counters.get(key);
        return slot == null ? 0L : slot[0];
    }

    public synchronized Map<String, Long> snapshot() {
        Map<String, Long> copy = new HashMap<>(counters.size() * 2);
        counters.forEach((k, v) -> copy.put(k, v[0]));
        return copy;
    }

    public synchronized int size() {
        return counters.size();
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void clearDirty() {
        dirty = false;
    }

    public boolean isMerged() {
        return merged;
    }

    public void setMerged(boolean merged) {
        this.merged = merged;
    }

    public synchronized JsonObject imported() {
        return imported == null ? null : imported.deepCopy();
    }

    public synchronized void setImported(JsonObject imported) {
        this.imported = imported;
        dirty = true;
    }

    /**
     * Merges what was read from disk into this object, which may already hold increments made since the
     * player joined. Disk values add to in memory values, so nothing counted during the read is lost.
     * Bests take the larger, the lowest the smaller, and a discovery such as {@code zone:<id>} that was made
     * again before the read landed stays at one and is taken off its total once, so it is not counted twice.
     * A session start on disk is taken only when this object has none and the file was written within
     * {@link #SESSION_RESUME_MILLIS} before the join, which is the reload case.
     */
    public synchronized void mergeFromDisk(PlayerCounters disk) {
        if (disk.firstSeen > 0 && disk.firstSeen < firstSeen) {
            firstSeen = disk.firstSeen;
        }
        if (sessionStart <= 0 && disk.sessionStart > 0 && disk.lastSeen > 0
                && lastSeen - disk.lastSeen <= SESSION_RESUME_MILLIS) {
            sessionStart = disk.sessionStart;
        }
        Map<String, Integer> rediscovered = null;
        if (name.isEmpty() && !disk.name.isEmpty()) {
            name = disk.name;
        }
        for (Map.Entry<String, long[]> e : disk.counters.entrySet()) {
            long[] slot = counters.get(e.getKey());
            if (slot == null) {
                counters.put(e.getKey(), new long[]{e.getValue()[0]});
            } else {
                String key = e.getKey();
                // Bests and firsts are not sums.
                String prefix = Keys.prefixOf(key);
                if (CounterRules.isMax(key)) {
                    slot[0] = Math.max(slot[0], e.getValue()[0]);
                } else if (!prefix.equals(key) && CounterRules.isDiscovery(prefix)) {
                    if (slot[0] > 0 && e.getValue()[0] > 0) {
                        if (rediscovered == null) {
                            rediscovered = new HashMap<>();
                        }
                        rediscovered.merge(CounterRules.discoveryTotal(prefix), 1, Integer::sum);
                    }
                    slot[0] = Math.max(slot[0], e.getValue()[0]);
                } else if (CounterRules.isMin(key)) {
                    slot[0] = Math.min(slot[0], e.getValue()[0]);
                } else {
                    slot[0] += e.getValue()[0];
                }
            }
        }
        if (rediscovered != null) {
            for (Map.Entry<String, Integer> r : rediscovered.entrySet()) {
                long[] total = counters.get(r.getKey());
                if (total != null) {
                    total[0] = Math.max(0L, total[0] - r.getValue());
                }
            }
        }
        if (imported == null && disk.imported != null) {
            imported = disk.imported;
        }
        dirty = true;
        merged = true;
    }

    /** Imports counters, taking the larger of the stored and the imported value per key. */
    public synchronized void importMax(Map<String, Long> values) {
        for (Map.Entry<String, Long> e : values.entrySet()) {
            long[] slot = counters.get(e.getKey());
            if (slot == null) {
                counters.put(e.getKey(), new long[]{e.getValue()});
            } else if (CounterRules.isMin(e.getKey())) {
                slot[0] = Math.min(slot[0], e.getValue());
            } else {
                slot[0] = Math.max(slot[0], e.getValue());
            }
        }
        dirty = true;
    }

    // ---- json -------------------------------------------------------------------------------------------

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("Version", VERSION);
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.addProperty("firstSeen", firstSeen);
        o.addProperty("lastSeen", lastSeen);
        if (sessionStart > 0) {
            o.addProperty(SESSION_START, sessionStart);
        }
        JsonObject c = new JsonObject();
        counters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> c.addProperty(e.getKey(), e.getValue()[0]));
        o.add("counters", c);
        if (imported != null) {
            o.add("imported", imported.deepCopy());
        }
        return o;
    }

    public static PlayerCounters fromJson(UUID uuid, JsonObject o) {
        PlayerCounters p = new PlayerCounters(uuid, str(o, "name"), 0L);
        p.firstSeen = num(o, "firstSeen");
        p.lastSeen = num(o, "lastSeen");
        p.sessionStart = num(o, SESSION_START);
        JsonElement c = o.get("counters");
        if (c != null && c.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : c.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive() && ((JsonPrimitive) e.getValue()).isNumber()) {
                    p.counters.put(e.getKey(), new long[]{e.getValue().getAsLong()});
                }
            }
        }
        JsonElement imp = o.get("imported");
        if (imp != null && imp.isJsonObject()) {
            p.imported = imp.getAsJsonObject();
        }
        return p;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber() ? e.getAsLong() : 0L;
    }
}
