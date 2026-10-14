package dev.ryvrook.trackerapi.imports;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.api.Keys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the Feats player files, {@code mods/Ryvrook_Feats/players/<uuid>.json}. Feats writes them with
 * the BSON document writer, so they are plain JSON with {@code Username} and {@code Stats}, a flat map of
 * totals and {@code prefix:id} keys. The two tables below are the whole mapping, anything not in them is
 * skipped.
 */
final class FeatsImport {
    static final String SOURCE = "feats";
    static final String FOLDER = "Ryvrook_Feats";

    /** Feats total to TrackerAPI total. */
    static final Map<String, String> TOTALS = Map.ofEntries(
            Map.entry("blocks_broken", Keys.BLOCK_BROKEN),
            Map.entry("blocks_placed", Keys.BLOCK_PLACED),
            Map.entry("crafted", Keys.ITEM_CRAFTED),
            Map.entry("picked_up", Keys.ITEM_PICKED_UP),
            Map.entry("obtained", Keys.ITEMS_GAINED),
            Map.entry("kills", Keys.KILLS),
            Map.entry("kills_player", Keys.KILLS_PLAYER),
            Map.entry("kills_ranged", Keys.KILLS_RANGED),
            Map.entry("kills_boss", Keys.KILLS_BOSS),
            Map.entry("boss_flawless", Keys.BOSS_FLAWLESS),
            Map.entry("deaths", Keys.DEATHS),
            Map.entry("damage_dealt", Keys.DAMAGE_DEALT),
            Map.entry("damage_taken", Keys.DAMAGE_TAKEN),
            Map.entry("biggest_hit", Keys.BIGGEST_HIT),
            Map.entry("zones_discovered", Keys.ZONES_DISCOVERED),
            Map.entry("distance", Keys.DISTANCE),
            Map.entry("swim_distance", Keys.DISTANCE_SWIM),
            Map.entry("glide_distance", Keys.DISTANCE_GLIDE),
            Map.entry("ride_distance", Keys.DISTANCE_RIDE),
            Map.entry("playtime", Keys.PLAYTIME),
            Map.entry("joins", Keys.JOINS),
            Map.entry("chat", Keys.CHAT),
            Map.entry("ability_casts", Keys.ABILITY_CASTS),
            Map.entry("portals_entered", Keys.PORTALS_ENTERED),
            Map.entry("memories", Keys.MEMORIES),
            Map.entry("tamed", Keys.CREATURE_TAMED),
            Map.entry("kill_streak", Keys.KILL_STREAK_BEST),
            Map.entry("longest_session", Keys.SESSION_LONGEST),
            Map.entry("food_eaten", Keys.ITEM_CONSUMED),
            Map.entry("potions_drunk", Keys.ITEM_CONSUMED));

    /** Feats prefix to TrackerAPI prefix, both without the colon. */
    static final Map<String, String> PREFIXES = Map.ofEntries(
            Map.entry("broken", Keys.P_BLOCK_BROKEN),
            Map.entry("placed", Keys.P_BLOCK_PLACED),
            Map.entry("crafted", Keys.P_ITEM_CRAFTED),
            Map.entry("picked", Keys.P_ITEM_PICKED_UP),
            Map.entry("obtained", Keys.P_ITEMS_GAINED),
            Map.entry("kill", Keys.P_KILL),
            Map.entry("death", Keys.P_DEATH),
            Map.entry("zone", Keys.P_ZONE),
            Map.entry("use", Keys.P_BLOCK_USED),
            Map.entry("cast", Keys.P_INTERACTION),
            Map.entry("portal", Keys.P_PORTAL),
            Map.entry("memory", Keys.P_MEMORY));

    /** Targets several Feats totals add up into. */
    static final Set<String> SUMMED = Set.of(Keys.ITEM_CONSUMED);

    /**
     * Prefixes whose ids are lower case by design rather than group names. Feats lower cases death causes,
     * so {@code death:fall} is a real count and is kept.
     */
    static final Set<String> LOWER_CASE_IDS = Set.of("death");

    private FeatsImport() {
    }

    static Path folder(Path dataDir) {
        Path players = dataDir.toAbsolutePath().getParent().resolve(FOLDER).resolve("players");
        return Files.isDirectory(players) ? players : null;
    }

    /** One file translated, or null when it is not a Feats player file. */
    static ImportedPlayer read(UUID uuid, JsonObject doc) {
        if (doc == null) {
            return null;
        }
        JsonObject stats = ImportedPlayer.object(doc, "Stats");
        if (stats == null && !doc.has("Username")) {
            return null;
        }
        return new ImportedPlayer(uuid, ImportedPlayer.string(doc, "Username"), map(stats));
    }

    /** The {@code Stats} map translated to TrackerAPI keys. */
    static Map<String, Long> map(JsonObject stats) {
        Map<String, Long> out = new HashMap<>();
        if (stats == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : stats.entrySet()) {
            Long value = ImportedPlayer.number(e.getValue());
            if (value == null) {
                continue;
            }
            String key = e.getKey();
            int colon = key.indexOf(':');
            if (colon < 0) {
                String target = TOTALS.get(key);
                if (target != null) {
                    ImportedPlayer.put(out, target, value, SUMMED);
                }
                continue;
            }
            String prefix = key.substring(0, colon);
            String id = key.substring(colon + 1);
            String target = PREFIXES.get(prefix);
            if (target == null || id.isEmpty() || (!LOWER_CASE_IDS.contains(prefix) && ImportedPlayer.isGroupId(id))) {
                continue;
            }
            ImportedPlayer.put(out, target + ':' + id, value, SUMMED);
        }
        return out;
    }
}
