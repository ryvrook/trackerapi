package dev.ryvrook.trackerapi;

import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.util.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mods block of TrackerAPI.json. */
class ModsConfigTest {
    private static final HytaleLogger LOGGER = HytaleLogger.get("ModsConfigTest");

    @TempDir
    Path dir;

    @Test
    void defaultsListEveryBindingAsAutoAndAreWrittenBack() throws Exception {
        Path file = dir.resolve("TrackerAPI.json");
        TrackerConfig c = TrackerConfig.load(file, LOGGER);
        assertEquals(5, c.modsPollSeconds);
        for (String id : Keys.MOD_PREFIXES) {
            assertEquals("auto", c.mods.get(id));
            assertTrue(c.modEnabled(id));
        }
        JsonObject o = JsonFiles.read(file);
        JsonObject mods = o.getAsJsonObject("mods");
        assertEquals(5, mods.get("pollSeconds").getAsInt());
        assertEquals("auto", mods.get("rpg").getAsString());
    }

    @Test
    void neverAndPollSecondsAreRead() throws Exception {
        Path file = dir.resolve("TrackerAPI.json");
        Files.writeString(file, "{\"mods\":{\"pollSeconds\":120,\"rpg\":\"never\",\"vein\":\"AUTO\",\"zephyr\":\"maybe\",\"other\":\"never\"}}");
        TrackerConfig c = TrackerConfig.load(file, LOGGER);
        assertEquals(60, c.modsPollSeconds, "clamped");
        assertFalse(c.modEnabled("rpg"));
        assertTrue(c.modEnabled("vein"));
        assertTrue(c.modEnabled("zephyr"), "an unknown value falls back to auto");
        assertFalse(c.modEnabled("other"), "an id TrackerAPI does not ship is kept as written");
        assertTrue(c.modEnabled("dungeons"));
    }
}
