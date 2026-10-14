package dev.ryvrook.trackerapi.imports;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.api.Keys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the Chronicle Stats player files, {@code mods/Ryvrook_ChronicleStats/players/<uuid>.json} or the
 * older {@code mods/Ryvrook_Chronicle} folder. Gson JSON with {@code name}, {@code totals} (sums),
 * {@code records} (bests as {@code {"v": value, "at": millis}}), {@code perId} (prefix without colon to a
 * map of id to value) and {@code worlds} (world key to a map of totals, imported for the totals TrackerAPI keeps per world). The tables below are the whole
 * mapping, anything not in them is skipped.
 */
final class ChronicleImport {
    static final String SOURCE = "chronicle";
    static final List<String> FOLDERS = List.of("Ryvrook_ChronicleStats", "Ryvrook_Chronicle");

    /** Chronicle total or record to TrackerAPI total. The Feats names Chronicle shares, plus its own. */
    static final Map<String, String> TOTALS;

    static {
        Map<String, String> t = new HashMap<>(FeatsImport.TOTALS);
        t.put("jumps", Keys.JUMPS);
        t.put("falls", Keys.FALLS);
        t.put("fall_distance", Keys.FALL_DISTANCE);
        t.put("longest_fall", Keys.LONGEST_FALL);
        t.put("highest_y", Keys.HIGHEST_Y);
        t.put("lowest_y", Keys.LOWEST_Y);
        t.put("respawns", Keys.RESPAWNS);
        t.put("shots_fired", Keys.SHOTS_FIRED);
        t.put("shots_hit", Keys.SHOTS_HIT);
        t.put("items_dropped", Keys.ITEM_DROPPED);
        t.put("mounts", Keys.MOUNTS);
        t.put("teleports", Keys.TELEPORTS);
        t.put("gained", Keys.ITEMS_GAINED);
        t.put("recipes_known", Keys.RECIPES_KNOWN);
        t.put("objectives_completed", Keys.OBJECTIVES_COMPLETED);
        t.put("instances_discovered", Keys.INSTANCES_DISCOVERED);
        t.put("effects_received", Keys.EFFECTS_RECEIVED);
        t.put("best_killstreak", Keys.KILL_STREAK_BEST);
        TOTALS = Map.copyOf(t);
    }

    /**
     * Chronicle prefix to TrackerAPI prefix, both without the colon. Chronicle's {@code world} prefix is
     * seconds spent in a world, so the world entries come from {@code visit}, its count of world visits.
     */
    static final Map<String, String> PREFIXES = Map.ofEntries(
            Map.entry("broken", Keys.P_BLOCK_BROKEN),
            Map.entry("placed", Keys.P_BLOCK_PLACED),
            Map.entry("crafted", Keys.P_ITEM_CRAFTED),
            Map.entry("picked", Keys.P_ITEM_PICKED_UP),
            Map.entry("gained", Keys.P_ITEMS_GAINED),
            Map.entry("obtained", Keys.P_ITEMS_GAINED),
            Map.entry("dropped", Keys.P_ITEM_DROPPED),
            Map.entry("consumed", Keys.P_ITEM_CONSUMED),
            Map.entry("kill", Keys.P_KILL),
            Map.entry("killedby", Keys.P_KILLED_BY),
            Map.entry("death", Keys.P_DEATH),
            Map.entry("dmgcause", Keys.P_DAMAGE_CAUSE),
            Map.entry("zone", Keys.P_ZONE),
            Map.entry("use", Keys.P_BLOCK_USED),
            Map.entry("cast", Keys.P_INTERACTION),
            Map.entry("portal", Keys.P_PORTAL),
            Map.entry("memory", Keys.P_MEMORY),
            Map.entry("effect", Keys.P_EFFECT),
            Map.entry("visit", Keys.P_WORLD_ENTERED));

    /** Prefixes whose ids are block or item asset ids, where a lower case id is a group such as {@code use:chest}. */
    static final Set<String> ASSET_IDS = Set.of("broken", "placed", "crafted", "picked", "gained", "obtained", "dropped",
            "consumed", "use");

    private ChronicleImport() {
    }

    static Path folder(Path dataDir) {
        Path mods = dataDir.toAbsolutePath().getParent();
        for (String name : FOLDERS) {
            Path players = mods.resolve(name).resolve("players");
            if (Files.isDirectory(players)) {
                return players;
            }
        }
        return null;
    }

    /** One file translated, or null when it is not a Chronicle player file. */
    static ImportedPlayer read(UUID uuid, JsonObject doc) {
        if (doc == null || (!doc.has("totals") && !doc.has("perId"))) {
            return null;
        }
        return new ImportedPlayer(uuid, ImportedPlayer.string(doc, "name"), map(doc));
    }

    static Map<String, Long> map(JsonObject doc) {
        Map<String, Long> out = new HashMap<>();
        JsonObject totals = ImportedPlayer.object(doc, "totals");
        if (totals != null) {
            for (Map.Entry<String, JsonElement> e : totals.entrySet()) {
                total(out, e.getKey(), ImportedPlayer.number(e.getValue()), null);
            }
        }
        JsonObject records = ImportedPlayer.object(doc, "records");
        if (records != null) {
            for (Map.Entry<String, JsonElement> e : records.entrySet()) {
                JsonElement v = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("v") : e.getValue();
                total(out, e.getKey(), ImportedPlayer.number(v), null);
            }
        }
        JsonObject perId = ImportedPlayer.object(doc, "perId");
        if (perId != null) {
            for (Map.Entry<String, JsonElement> p : perId.entrySet()) {
                String prefix = p.getKey().endsWith(":") ? p.getKey().substring(0, p.getKey().length() - 1) : p.getKey();
                String target = PREFIXES.get(prefix);
                if (target == null || !p.getValue().isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> e : p.getValue().getAsJsonObject().entrySet()) {
                    String id = e.getKey();
                    Long value = ImportedPlayer.number(e.getValue());
                    if (value == null || id.isEmpty() || (ASSET_IDS.contains(prefix) && ImportedPlayer.isGroupId(id))) {
                        continue;
                    }
                    ImportedPlayer.put(out, target + ':' + id, value, FeatsImport.SUMMED);
                }
            }
        }
        JsonObject worlds = ImportedPlayer.object(doc, "worlds");
        if (worlds != null) {
            for (Map.Entry<String, JsonElement> w : worlds.entrySet()) {
                if (w.getKey().isEmpty() || !w.getValue().isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> e : w.getValue().getAsJsonObject().entrySet()) {
                    total(out, e.getKey(), ImportedPlayer.number(e.getValue()), w.getKey());
                }
            }
        }
        return out;
    }

    private static void total(Map<String, Long> out, String key, Long value, String world) {
        String target = TOTALS.get(key);
        if (target == null || value == null || (world != null && !Keys.PER_WORLD_TOTALS.contains(target))) {
            return; // TrackerAPI keeps per world copies of a few totals only
        }
        String full = world == null ? target : Keys.P_WORLD + ':' + world + ':' + target;
        ImportedPlayer.put(out, full, value, FeatsImport.SUMMED);
    }
}
