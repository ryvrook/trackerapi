package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Server wide totals, summed over every player as events arrive. One file, saved with the autosave. */
public final class ServerCounters {
    public static final int VERSION = 1;
    private static final int MAX_KEYS = 50_000;

    private final Path file;
    private final HytaleLogger logger;
    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private long since;

    ServerCounters(Path file, HytaleLogger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void add(String key, long amount) {
        if (amount == 0) {
            return;
        }
        AtomicLong v = values.get(key);
        if (v == null) {
            if (values.size() >= MAX_KEYS) {
                return;
            }
            v = values.computeIfAbsent(key, k -> new AtomicLong());
        }
        v.addAndGet(amount);
        dirty = true;
    }

    public void raiseTo(String key, long value) {
        AtomicLong v = values.computeIfAbsent(key, k -> new AtomicLong());
        long seen = v.get();
        while (value > seen && !v.compareAndSet(seen, value)) {
            seen = v.get();
        }
        dirty = true;
    }

    public long get(String key) {
        AtomicLong v = values.get(key);
        return v == null ? 0L : v.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new HashMap<>(values.size() * 2);
        values.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }

    /** When the totals started, the first boot with TrackerAPI. */
    public long since() {
        return since;
    }

    void load() {
        try {
            JsonObject o = JsonFiles.read(file);
            if (o == null) {
                since = System.currentTimeMillis();
                dirty = true;
                return;
            }
            JsonElement s = o.get("since");
            since = s != null && s.isJsonPrimitive() ? s.getAsLong() : System.currentTimeMillis();
            JsonElement c = o.get("counters");
            if (c != null && c.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : c.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive() && ((JsonPrimitive) e.getValue()).isNumber()) {
                        values.put(e.getKey(), new AtomicLong(e.getValue().getAsLong()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not read the server totals, starting from zero");
            JsonFiles.quarantine(file);
            since = System.currentTimeMillis();
        }
    }

    void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonObject o = new JsonObject();
        o.addProperty("Version", VERSION);
        o.addProperty("since", since);
        JsonObject c = new JsonObject();
        snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> c.addProperty(e.getKey(), e.getValue()));
        o.add("counters", c);
        try {
            JsonFiles.write(file, o, false);
        } catch (IOException e) {
            dirty = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not save the server totals");
        }
    }
}
