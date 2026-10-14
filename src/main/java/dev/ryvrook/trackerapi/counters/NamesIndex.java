package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Uuid to last name and lower case name to uuid, so offline lookups by name work. Saved with the autosave. */
public final class NamesIndex {
    public static final int VERSION = 1;

    private record Entry(String name, long at) {
    }

    private final Path file;
    private final HytaleLogger logger;
    private final Map<UUID, Entry> byUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    NamesIndex(Path file, HytaleLogger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void seen(UUID uuid, String name, long now) {
        Entry prev = byUuid.put(uuid, new Entry(name, now));
        if (prev != null && !prev.name.equalsIgnoreCase(name)) {
            byName.remove(prev.name.toLowerCase(Locale.ROOT), uuid);
        }
        byName.put(name.toLowerCase(Locale.ROOT), uuid);
        dirty = true;
    }

    public Optional<String> nameOf(UUID uuid) {
        Entry e = uuid == null ? null : byUuid.get(uuid);
        return e == null ? Optional.empty() : Optional.of(e.name);
    }

    public Optional<UUID> uuidOf(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Set<UUID> known() {
        return Set.copyOf(byUuid.keySet());
    }

    void load() {
        try {
            JsonObject o = JsonFiles.read(file);
            if (o == null) {
                return;
            }
            JsonElement p = o.get("players");
            if (p != null && p.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : p.getAsJsonObject().entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(e.getKey());
                        JsonObject v = e.getValue().getAsJsonObject();
                        String name = v.get("name").getAsString();
                        long at = v.has("at") ? v.get("at").getAsLong() : 0L;
                        byUuid.put(uuid, new Entry(name, at));
                        byName.put(name.toLowerCase(Locale.ROOT), uuid);
                    } catch (RuntimeException ignored) {
                        // one bad entry is skipped
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not read the names index");
            JsonFiles.quarantine(file);
        }
    }

    void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonObject o = new JsonObject();
        o.addProperty("Version", VERSION);
        JsonObject p = new JsonObject();
        byUuid.forEach((uuid, e) -> {
            JsonObject v = new JsonObject();
            v.addProperty("name", e.name);
            v.addProperty("at", e.at);
            p.add(uuid.toString(), v);
        });
        o.add("players", p);
        try {
            JsonFiles.write(file, o, false);
        } catch (IOException e) {
            dirty = true;
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not save the names index");
        }
    }
}
