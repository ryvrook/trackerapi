package dev.ryvrook.trackerapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * The owner's settings, one Gson file {@code TrackerAPI.json} in the data folder. Missing keys are written
 * back with their defaults, out of range values are clamped with one warning, and a file that does not
 * parse keeps the defaults and is left alone. {@code /trackerapi reload} reads it again.
 */
public final class TrackerConfig {
    /** Kinds that are not tracked at all. Empty by default. */
    public Set<EventKind> disabledKinds = EnumSet.noneOf(EventKind.class);
    /** Count block, item and combat events of players in Creative mode. */
    public boolean countCreative = false;
    /** Keep per world copies of the totals under {@code world:<name>:<total>}. */
    public boolean perWorldCounters = true;
    /** Seconds between autosaves of the counters. */
    public int autosaveSeconds = 60;
    /** Seconds between leaderboard rebuilds, 0 turns them off. */
    public int boardSeconds = 60;
    /** Size of the async queue. Events past this are dropped and counted. */
    public int queueCapacity = 65_536;
    /** Milliseconds each async listener may take per batch before it is warned, then suspended. */
    public int listenerBudgetMillis = 50;
    /** Seconds between position samples, which drive PLAYER_MOVED and the snapshots. */
    public int sampleSeconds = 1;
    /** Seconds between VITALS_SAMPLED events, 0 turns them off. */
    public int vitalsSeconds = 0;
    /** Record the text of chat messages on PLAYER_CHATTED. Off, the length only. */
    public boolean chatRecordText = false;
    /** Journal settings. */
    public boolean journalEnabled = true;
    public Set<EventKind> journalKinds = defaultJournalKinds();
    public int journalRetentionDays = 14;
    public int journalMaxMegabytes = 1024;
    /** Read the Feats and Chronicle Stats player files once at first boot. */
    public boolean importOnFirstBoot = true;
    /** Turn on the debug commands. */
    public boolean debugCommands = false;
    /** Seconds between looks for the mods TrackerAPI reads, 2 to 60. */
    public int modsPollSeconds = 5;
    /** Per binding id, {@code auto} binds when the mod is present, {@code never} leaves it alone. */
    public Map<String, String> mods = defaultMods();

    public static Map<String, String> defaultMods() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String id : dev.ryvrook.trackerapi.api.Keys.MOD_PREFIXES) {
            m.put(id, "auto");
        }
        return m;
    }

    /** True when the binding with this id may bind. Unknown ids are on. */
    public boolean modEnabled(String id) {
        return !"never".equalsIgnoreCase(mods.getOrDefault(id, "auto"));
    }

    public static Set<EventKind> defaultJournalKinds() {
        EnumSet<EventKind> kinds = EnumSet.allOf(EventKind.class);
        for (EventKind k : EventKind.values()) {
            if (k.highVolume()) {
                kinds.remove(k);
            }
        }
        return kinds;
    }

    public Set<EventKind> trackedKinds() {
        EnumSet<EventKind> on = EnumSet.allOf(EventKind.class);
        on.removeAll(disabledKinds);
        return on;
    }

    // ---- file ------------------------------------------------------------------------------------------

    /** Reads the file, fixes it and writes missing keys back. Never throws. */
    public static TrackerConfig load(Path file, HytaleLogger logger) {
        TrackerConfig c = new TrackerConfig();
        JsonObject o;
        try {
            o = JsonFiles.read(file);
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not read %s and uses its defaults", file.getFileName());
            return c;
        }
        if (o != null) {
            c.read(o, logger);
        }
        try {
            JsonFiles.write(file, c.toJson(), true);
        } catch (IOException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not write %s", file.getFileName());
        }
        return c;
    }

    /** Reads the file for a reload. Null when the file is missing or does not parse, so the old settings stay. */
    public static TrackerConfig reload(Path file, HytaleLogger logger) {
        try {
            JsonObject o = JsonFiles.read(file);
            if (o == null) {
                return null;
            }
            TrackerConfig c = new TrackerConfig();
            c.read(o, logger);
            return c;
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("TrackerAPI refused to reload %s", file.getFileName());
            return null;
        }
    }

    private void read(JsonObject o, HytaleLogger logger) {
        disabledKinds = kinds(o, "disabledKinds", disabledKinds, logger);
        countCreative = bool(o, "countCreative", countCreative);
        perWorldCounters = bool(o, "perWorldCounters", perWorldCounters);
        autosaveSeconds = clamp(o, "autosaveSeconds", autosaveSeconds, 10, 3600, logger);
        boardSeconds = clamp(o, "boardSeconds", boardSeconds, 0, 3600, logger);
        queueCapacity = clamp(o, "queueCapacity", queueCapacity, 1024, 1 << 20, logger);
        listenerBudgetMillis = clamp(o, "listenerBudgetMillis", listenerBudgetMillis, 1, 1000, logger);
        sampleSeconds = clamp(o, "sampleSeconds", sampleSeconds, 1, 60, logger);
        vitalsSeconds = clamp(o, "vitalsSeconds", vitalsSeconds, 0, 3600, logger);
        chatRecordText = bool(o, "chatRecordText", chatRecordText);
        JsonElement j = o.get("journal");
        if (j != null && j.isJsonObject()) {
            JsonObject jo = j.getAsJsonObject();
            journalEnabled = bool(jo, "enabled", journalEnabled);
            journalKinds = kinds(jo, "kinds", journalKinds, logger);
            journalRetentionDays = clamp(jo, "retentionDays", journalRetentionDays, 1, 3650, logger);
            journalMaxMegabytes = clamp(jo, "maxMegabytes", journalMaxMegabytes, 16, 1_000_000, logger);
        }
        importOnFirstBoot = bool(o, "importOnFirstBoot", importOnFirstBoot);
        debugCommands = bool(o, "debugCommands", debugCommands);
        JsonElement m = o.get("mods");
        if (m != null && m.isJsonObject()) {
            JsonObject mo = m.getAsJsonObject();
            modsPollSeconds = clamp(mo, "pollSeconds", modsPollSeconds, 2, 60, logger);
            for (Map.Entry<String, JsonElement> e : mo.entrySet()) {
                if (e.getKey().equals("pollSeconds") || !e.getValue().isJsonPrimitive()) {
                    continue;
                }
                String v = e.getValue().getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                if (!v.equals("auto") && !v.equals("never")) {
                    logger.at(Level.WARNING).log("TrackerAPI mods.%s is %s, expected auto or never, using auto", e.getKey(), v);
                    v = "auto";
                }
                mods.put(e.getKey(), v);
            }
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("disabledKinds", kindsJson(disabledKinds));
        o.addProperty("countCreative", countCreative);
        o.addProperty("perWorldCounters", perWorldCounters);
        o.addProperty("autosaveSeconds", autosaveSeconds);
        o.addProperty("boardSeconds", boardSeconds);
        o.addProperty("queueCapacity", queueCapacity);
        o.addProperty("listenerBudgetMillis", listenerBudgetMillis);
        o.addProperty("sampleSeconds", sampleSeconds);
        o.addProperty("vitalsSeconds", vitalsSeconds);
        o.addProperty("chatRecordText", chatRecordText);
        JsonObject j = new JsonObject();
        j.addProperty("enabled", journalEnabled);
        j.add("kinds", kindsJson(journalKinds));
        j.addProperty("retentionDays", journalRetentionDays);
        j.addProperty("maxMegabytes", journalMaxMegabytes);
        o.add("journal", j);
        o.addProperty("importOnFirstBoot", importOnFirstBoot);
        o.addProperty("debugCommands", debugCommands);
        JsonObject mo = new JsonObject();
        mo.addProperty("pollSeconds", modsPollSeconds);
        for (Map.Entry<String, String> e : mods.entrySet()) {
            mo.addProperty(e.getKey(), e.getValue());
        }
        o.add("mods", mo);
        return o;
    }

    private static JsonArray kindsJson(Set<EventKind> kinds) {
        JsonArray a = new JsonArray();
        List<EventKind> sorted = new ArrayList<>(kinds);
        sorted.sort(null);
        for (EventKind k : sorted) {
            a.add(k.id());
        }
        return a;
    }

    private static Set<EventKind> kinds(JsonObject o, String key, Set<EventKind> fallback, HytaleLogger logger) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            return fallback;
        }
        EnumSet<EventKind> out = EnumSet.noneOf(EventKind.class);
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                continue;
            }
            EventKind k = EventKind.byId(item.getAsString());
            if (k == null) {
                logger.at(Level.WARNING).log("TrackerAPI %s names an unknown kind %s, ignored", key, item.getAsString());
            } else {
                out.add(k);
            }
        }
        return out;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    private static int clamp(JsonObject o, String key, int fallback, int min, int max, HytaleLogger logger) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        int v = e.getAsInt();
        if (v < min || v > max) {
            int fixed = Math.max(min, Math.min(max, v));
            logger.at(Level.WARNING).log("TrackerAPI %s %d is outside %d to %d and uses %d", key, v, min, max, fixed);
            return fixed;
        }
        return v;
    }
}
