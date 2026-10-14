package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One player's counters in memory. Guarded by its own monitor, which every write takes for a few map
 * operations. World threads write, the IO thread reads a snapshot to save, the API copies on read.
 */
public final class PlayerCounters {
    public static final int VERSION = 1;

    private final UUID uuid;
    private String name;
    private long firstSeen;
    private long lastSeen;
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
     */
    public synchronized void mergeFromDisk(PlayerCounters disk) {
        if (disk.firstSeen > 0 && disk.firstSeen < firstSeen) {
            firstSeen = disk.firstSeen;
        }
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
                if (CounterRules.isMax(key)) {
                    slot[0] = Math.max(slot[0], e.getValue()[0]);
                } else if (CounterRules.isMin(key)) {
                    slot[0] = Math.min(slot[0], e.getValue()[0]);
                } else {
                    slot[0] += e.getValue()[0];
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
