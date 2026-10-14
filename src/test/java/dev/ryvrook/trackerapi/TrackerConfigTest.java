package dev.ryvrook.trackerapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.util.JsonFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerConfigTest {
    private static final HytaleLogger LOGGER = HytaleLogger.get("TrackerConfigTest");

    @TempDir
    Path dir;
    /** Every record the server logger writes while a test runs, through the backend's own subscription. */
    private final CopyOnWriteArrayList<LogRecord> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() {
        HytaleLoggerBackend.subscribe(records);
    }

    @AfterEach
    void stopListening() {
        HytaleLoggerBackend.unsubscribe(records);
    }

    private List<String> warnings() {
        List<String> out = new ArrayList<>();
        for (LogRecord r : records) {
            if (r.getLevel().intValue() < Level.WARNING.intValue()) {
                continue;
            }
            String text = r.getMessage();
            Object[] params = r.getParameters();
            if (text != null && params != null && params.length > 0) {
                try {
                    text = String.format(text, params);
                } catch (RuntimeException ignored) {
                    // keep the raw text
                }
            }
            out.add(String.valueOf(text));
        }
        return out;
    }

    private Path file() {
        return dir.resolve("TrackerAPI.json");
    }

    @Test
    void aMissingFileIsWrittenWithEveryDefault() throws Exception {
        TrackerConfig c = TrackerConfig.load(file(), LOGGER);
        assertTrue(Files.exists(file()));
        JsonObject o = JsonFiles.read(file());
        assertEquals(new TrackerConfig().toJson(), o);
        assertEquals(60, c.autosaveSeconds);
        assertEquals(60, c.boardSeconds);
        assertEquals(65_536, c.queueCapacity);
        assertEquals(5, c.listenerBudgetMillis);
        assertEquals(1, c.sampleSeconds);
        assertEquals(0, c.vitalsSeconds);
        assertFalse(c.countCreative);
        assertTrue(c.perWorldCounters);
        assertFalse(c.chatRecordText);
        assertTrue(c.journalEnabled);
        assertEquals(14, c.journalRetentionDays);
        assertEquals(1024, c.journalMaxMegabytes);
        assertTrue(c.importOnFirstBoot);
        assertFalse(c.debugCommands);
        assertTrue(c.disabledKinds.isEmpty());
        assertEquals(EnumSet.allOf(EventKind.class), c.trackedKinds());
        for (EventKind k : EventKind.values()) {
            assertEquals(!k.highVolume(), c.journalKinds.contains(k), k.id());
        }
        assertTrue(o.getAsJsonObject("journal").has("kinds"));
    }

    @Test
    void missingKeysAreWrittenBackAndSetOnesKept() throws Exception {
        Files.writeString(file(), "{\"autosaveSeconds\": 120, \"countCreative\": true, \"journal\": {\"enabled\": false}}",
                StandardCharsets.UTF_8);
        TrackerConfig c = TrackerConfig.load(file(), LOGGER);
        assertEquals(120, c.autosaveSeconds);
        assertTrue(c.countCreative);
        assertFalse(c.journalEnabled);
        JsonObject o = JsonFiles.read(file());
        assertEquals(120, o.get("autosaveSeconds").getAsInt());
        assertTrue(o.has("queueCapacity"), "a key the owner left out is written back");
        assertEquals(14, o.getAsJsonObject("journal").get("retentionDays").getAsInt());
        assertFalse(o.getAsJsonObject("journal").get("enabled").getAsBoolean());
    }

    @Test
    void outOfRangeValuesAreClampedWithAWarning() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("autosaveSeconds", 1);
        o.addProperty("boardSeconds", -5);
        o.addProperty("queueCapacity", 10);
        o.addProperty("listenerBudgetMillis", 99_999);
        o.addProperty("sampleSeconds", 600);
        o.addProperty("vitalsSeconds", 7200);
        o.addProperty("perWorldCounters", "yes");
        JsonObject j = new JsonObject();
        j.addProperty("retentionDays", 0);
        j.addProperty("maxMegabytes", 1);
        o.add("journal", j);
        JsonFiles.write(file(), o, true);

        TrackerConfig c = TrackerConfig.load(file(), LOGGER);
        assertEquals(10, c.autosaveSeconds);
        assertEquals(0, c.boardSeconds);
        assertEquals(1024, c.queueCapacity);
        assertEquals(1000, c.listenerBudgetMillis);
        assertEquals(60, c.sampleSeconds);
        assertEquals(3600, c.vitalsSeconds);
        assertTrue(c.perWorldCounters, "a value of the wrong type keeps the default");
        assertEquals(1, c.journalRetentionDays);
        assertEquals(16, c.journalMaxMegabytes);
        assertTrue(warnings().stream().anyMatch(w -> w.contains("autosaveSeconds")), String.valueOf(warnings()));
        assertTrue(warnings().stream().anyMatch(w -> w.contains("maxMegabytes")));
        assertEquals(10, JsonFiles.read(file()).get("autosaveSeconds").getAsInt(), "the clamped value is written back");
    }

    @Test
    void anUnknownKindIsIgnoredWithAWarning() throws Exception {
        JsonObject o = new JsonObject();
        JsonArray disabled = new JsonArray();
        disabled.add("chunk_loaded");
        disabled.add("Block-Damaged");
        disabled.add("no_such_kind");
        o.add("disabledKinds", disabled);
        JsonObject j = new JsonObject();
        JsonArray kinds = new JsonArray();
        kinds.add("block_broken");
        kinds.add("made_up");
        j.add("kinds", kinds);
        o.add("journal", j);
        JsonFiles.write(file(), o, true);

        TrackerConfig c = TrackerConfig.load(file(), LOGGER);
        assertEquals(EnumSet.of(EventKind.CHUNK_LOADED, EventKind.BLOCK_DAMAGED), c.disabledKinds);
        assertFalse(c.trackedKinds().contains(EventKind.CHUNK_LOADED));
        assertTrue(c.trackedKinds().contains(EventKind.BLOCK_BROKEN));
        assertEquals(EnumSet.of(EventKind.BLOCK_BROKEN), c.journalKinds);
        assertTrue(warnings().stream().anyMatch(w -> w.contains("no_such_kind")), String.valueOf(warnings()));
        assertTrue(warnings().stream().anyMatch(w -> w.contains("made_up")));
        JsonArray written = JsonFiles.read(file()).getAsJsonArray("disabledKinds");
        assertEquals(2, written.size(), "the unknown kind is dropped from the file");
    }

    @Test
    void aBrokenFileKeepsTheDefaultsAndIsLeftAlone() throws Exception {
        String broken = "{ \"autosaveSeconds\": 30, ";
        Files.writeString(file(), broken, StandardCharsets.UTF_8);
        TrackerConfig c = TrackerConfig.load(file(), LOGGER);
        assertEquals(60, c.autosaveSeconds);
        assertEquals(broken, Files.readString(file()), "the owner's file is not overwritten");
    }

    @Test
    void reloadRefusesABrokenOrMissingFile() throws Exception {
        assertNull(TrackerConfig.reload(file(), LOGGER), "missing");
        Files.writeString(file(), "{ \"boardSeconds\": ", StandardCharsets.UTF_8);
        assertNull(TrackerConfig.reload(file(), LOGGER), "broken");
        Files.writeString(file(), "[1, 2]", StandardCharsets.UTF_8);
        assertNull(TrackerConfig.reload(file(), LOGGER), "not an object");
        Files.writeString(file(), "{\"boardSeconds\": 30, \"chatRecordText\": true}", StandardCharsets.UTF_8);
        TrackerConfig c = TrackerConfig.reload(file(), LOGGER);
        assertNotNull(c);
        assertEquals(30, c.boardSeconds);
        assertTrue(c.chatRecordText);
        assertEquals(60, c.autosaveSeconds);
        assertEquals("{\"boardSeconds\": 30, \"chatRecordText\": true}", Files.readString(file()), "reload never writes");
    }
}
