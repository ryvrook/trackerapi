package dev.ryvrook.trackerapi.imports;

import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.counters.PlayerCounters;
import dev.ryvrook.trackerapi.util.JsonFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImporterTest {
    static final UUID ALDER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final UUID BIRCH = UUID.fromString("22222222-0000-0000-0000-000000000010");
    static final UUID CEDAR = UUID.fromString("33333333-0000-0000-0000-000000000001");
    static final UUID TESTER = UUID.fromString("b201ec49-ec49-31e8-9400-3388054b589b");
    static final UUID DUNE = UUID.fromString("44444444-0000-0000-0000-000000000001");

    /** What the synthetic Feats file must turn into, every row of the Feats tables. */
    static final Map<String, Long> CEDAR_EXPECTED = Map.ofEntries(
            entry("block_broken", 1001L), entry("block_broken:Rock_Stone", 600L),
            entry("block_placed", 502L), entry("block_placed:Wood_Oak_Planks", 300L),
            entry("item_crafted", 43L), entry("item_crafted:Tool_Pickaxe_Iron", 4L),
            entry("item_picked_up", 250L), entry("item_picked_up:Ingredient_Stick", 120L),
            entry("items_gained", 900L), entry("items_gained:Ore_Copper", 77L),
            entry("kills", 64L), entry("kills_player", 3L), entry("kills_ranged", 12L), entry("kills_boss", 2L),
            entry("boss_flawless", 1L), entry("deaths", 9L), entry("kill:Goblin_Scrapper", 30L), entry("death:fall", 4L),
            entry("damage_dealt", 8800L), entry("damage_taken", 1500L), entry("biggest_hit", 95L),
            entry("zones_discovered", 3L), entry("zone:Emerald_Wilds", 1L),
            entry("distance", 54000L), entry("distance_swim", 800L), entry("distance_glide", 1200L), entry("distance_ride", 3000L),
            entry("playtime", 36000L), entry("joins", 14L), entry("chat", 55L),
            entry("block_used:Bench_Workbench", 11L), entry("interaction:Ability_Dash", 9L), entry("ability_casts", 25L),
            entry("portals_entered", 5L), entry("portal:Portal_Device", 5L),
            entry("memories", 6L), entry("memory:Memory_Goblin", 1L),
            entry("creature_tamed", 2L), entry("kill_streak_best", 7L), entry("session_longest", 7200L),
            entry("item_consumed", 25L));

    /** What the synthetic Chronicle file must turn into. */
    static final Map<String, Long> DUNE_EXPECTED = Map.ofEntries(
            entry("block_broken", 2000L), entry("jumps", 3000L), entry("falls", 40L), entry("fall_distance", 900L),
            entry("respawns", 8L), entry("shots_fired", 120L), entry("shots_hit", 80L), entry("item_dropped", 33L),
            entry("mounts", 4L), entry("teleports", 6L), entry("items_gained", 1400L), entry("recipes_known", 22L),
            entry("objectives_completed", 3L), entry("instances_discovered", 2L), entry("effects_received", 19L),
            entry("chat", 71L), entry("item_consumed", 12L), entry("playtime", 50000L),
            entry("longest_fall", 45L), entry("highest_y", 310L), entry("lowest_y", -12L), entry("kill_streak_best", 11L),
            entry("biggest_hit", 140L), entry("session_longest", 9000L),
            entry("block_broken:Rock_Stone", 900L), entry("block_placed:Wood_Oak_Planks", 100L),
            entry("item_crafted:Tool_Pickaxe_Iron", 2L), entry("item_picked_up:Ingredient_Stick", 30L),
            entry("items_gained:Ore_Iron", 50L), entry("items_gained:Ore_Gold", 5L), entry("item_dropped:Rock_Stone", 12L),
            entry("item_consumed:Food_Apple", 8L), entry("kill:Goblin_Scrapper", 12L),
            entry("killed_by:Goblin_Scrapper", 2L), entry("killed_by:fall", 1L), entry("death:fall", 3L),
            entry("damage_cause:physical", 700L), entry("zone:Howling_Sands", 1L), entry("block_used:Bench_Workbench", 4L),
            entry("interaction:Ability_Dash", 3L), entry("portal:Portal_Device", 1L), entry("memory:Memory_Goblin", 1L),
            entry("effect:Burn", 2L), entry("world_entered:default", 5L),
            entry("world:default:block_broken", 1800L), entry("world:default:kills", 10L),
            entry("world:default:playtime", 45000L), entry("world:other:chat", 5L));

    @TempDir
    Path root;
    private Path data;
    private Services services;

    @BeforeEach
    void setUp() throws Exception {
        data = root.resolve("mods").resolve("Ryvrook_TrackerAPI");
        Files.createDirectories(data);
        copy("feats", root.resolve("mods").resolve("Ryvrook_Feats").resolve("players"));
        copy("chronicle", root.resolve("mods").resolve("Ryvrook_Chronicle").resolve("players"));
        services = build(TestSupport.DIRECT);
    }

    private Services build(Executor io) {
        Services s = Services.forTests(TestSupport.LOGGER, data, TestSupport.config(), io);
        s.counters.loadServer();
        return s;
    }

    static void copy(String resource, Path target) throws Exception {
        Path source = Path.of(ImporterTest.class.getResource("/imports/" + resource).toURI());
        Files.createDirectories(target);
        try (var files = Files.list(source)) {
            for (Path f : files.toList()) {
                Files.copy(f, target.resolve(f.getFileName().toString()));
            }
        }
    }

    private static JsonObject resource(String path) throws Exception {
        return JsonFiles.read(Path.of(ImporterTest.class.getResource("/imports/" + path).toURI()));
    }

    private Map<String, Long> onDisk(UUID uuid) {
        PlayerCounters pc = services.counters.readFile(uuid);
        assertNotNull(pc, "a TrackerAPI file for " + uuid);
        return pc.snapshot();
    }

    private static String await(java.util.concurrent.CompletableFuture<String> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    // ---- mapping -----------------------------------------------------------------------------------------

    @Test
    void theSyntheticFeatsFileUsesEveryRowOfTheTables() throws Exception {
        JsonObject stats = resource("feats/" + CEDAR + ".json").getAsJsonObject("Stats");
        for (String key : FeatsImport.TOTALS.keySet()) {
            assertTrue(stats.has(key), "the sample has " + key);
        }
        Set<String> prefixes = new HashSet<>();
        for (String key : stats.keySet()) {
            if (key.indexOf(':') > 0) {
                prefixes.add(key.substring(0, key.indexOf(':')));
            }
        }
        assertTrue(prefixes.containsAll(FeatsImport.PREFIXES.keySet()), "the sample has every prefix");
    }

    @Test
    void featsMapping() throws Exception {
        assertEquals(CEDAR_EXPECTED, FeatsImport.map(resource("feats/" + CEDAR + ".json").getAsJsonObject("Stats")));
    }

    @Test
    void featsRealSamples() throws Exception {
        ImportedPlayer alder = FeatsImport.read(ALDER, resource("feats/" + ALDER + ".json"));
        assertEquals("Alder", alder.name());
        assertEquals(Map.of("joins", 1L, "zones_discovered", 2L, "zone:Emerald_Wilds", 1L, "zone:Howling_Sands", 1L,
                "item_crafted:Portal_Device", 1L, "deaths", 1L), alder.counters());
        assertEquals("Birch", FeatsImport.read(BIRCH, resource("feats/" + BIRCH + ".json")).name());
        assertNull(FeatsImport.read(UUID.randomUUID(), JsonFiles.GSON.fromJson("{\"note\": 1}", JsonObject.class)));
    }

    @Test
    void theSyntheticChronicleFileUsesEveryRowOfItsOwnTables() throws Exception {
        JsonObject doc = resource("chronicle/" + DUNE + ".json");
        JsonObject totals = doc.getAsJsonObject("totals");
        JsonObject records = doc.getAsJsonObject("records");
        for (String key : ChronicleImport.TOTALS.keySet()) {
            if (!FeatsImport.TOTALS.containsKey(key)) {
                assertTrue(totals.has(key) || records.has(key), "the sample has " + key);
            }
        }
        assertTrue(doc.getAsJsonObject("perId").keySet().containsAll(ChronicleImport.PREFIXES.keySet()));
    }

    @Test
    void chronicleMapping() throws Exception {
        assertEquals(DUNE_EXPECTED, ChronicleImport.map(resource("chronicle/" + DUNE + ".json")));
    }

    @Test
    void chronicleRealSample() throws Exception {
        ImportedPlayer tester = ChronicleImport.read(TESTER, resource("chronicle/" + TESTER + ".json"));
        assertEquals("Tester", tester.name());
        assertEquals(Map.of("block_broken", 5L, "kill:Goblin_Scrapper", 3L), tester.counters());
    }

    @Test
    void numbersInEveryFormTheWritersUse() {
        assertEquals(12L, ImportedPlayer.number(JsonFiles.GSON.fromJson("12", com.google.gson.JsonElement.class)));
        assertEquals(13L, ImportedPlayer.number(JsonFiles.GSON.fromJson("12.6", com.google.gson.JsonElement.class)));
        assertEquals(9_000_000_000L, ImportedPlayer.number(JsonFiles.GSON.fromJson("{\"$numberLong\": \"9000000000\"}",
                com.google.gson.JsonElement.class)));
        assertNull(ImportedPlayer.number(JsonFiles.GSON.fromJson("\"x\"", com.google.gson.JsonElement.class)));
        assertNull(ImportedPlayer.number(JsonFiles.GSON.fromJson("{\"a\": 1}", com.google.gson.JsonElement.class)));
        assertNull(ImportedPlayer.number(null));
    }

    // ---- runs ---------------------------------------------------------------------------------------------

    @Test
    void featsRunSeedsFilesNamesAndState() throws Exception {
        String summary = await(new Importer(services).runFeats(false));
        assertEquals("Feats import read 3 player files and skipped 1 it could not read, seeded 3 players and wrote 53 counters.",
                summary);
        assertEquals(CEDAR_EXPECTED, onDisk(CEDAR));
        assertEquals(1L, onDisk(ALDER).get(Keys.JOINS));
        PlayerCounters cedar = services.counters.readFile(CEDAR);
        assertEquals("Cedar", cedar.name());
        assertEquals(41, cedar.imported().getAsJsonObject("feats").get("keys").getAsLong());
        assertEquals(Optional.of("Birch"), services.counters.names().nameOf(BIRCH));
        assertEquals(Optional.of(CEDAR), services.counters.uuidOf("cedar").get());
        assertTrue(Files.exists(data.resolve("names.json")), "the names index is saved with the import");
        assertEquals(1001L, services.counters.top(Keys.BLOCK_BROKEN, 1).get(0).getValue(), "boards rebuilt");

        ImportState.Run run = ImportState.load(data, TestSupport.LOGGER).run("feats");
        assertNotNull(run);
        assertEquals(3, run.files());
        assertEquals(1, run.failed());
        assertEquals(3, run.players());
        assertEquals(53, run.keys());
        assertTrue(run.folder().endsWith("players"));
        assertNull(ImportState.load(data, TestSupport.LOGGER).run("chronicle"));
    }

    @Test
    void aSecondRunNeedsForceAndChangesNothing() throws Exception {
        Importer importer = new Importer(services);
        await(importer.runFeats(false));
        Map<String, Long> before = onDisk(CEDAR);
        assertEquals("Feats import already ran, add --force to read the files again.", await(importer.runFeats(false)));
        assertEquals("Feats import read 3 player files and skipped 1 it could not read, seeded 0 players and wrote 0 counters.",
                await(importer.runFeats(true)));
        assertEquals(before, onDisk(CEDAR), "the max rule makes a second run harmless");
    }

    @Test
    void theLargerValueWinsAndTrackerApiValuesAreNeverLowered() throws Exception {
        PlayerCounters existing = new PlayerCounters(DUNE, "DuneNow", 5L);
        existing.add(Keys.BLOCK_BROKEN, 5000);
        existing.add(Keys.JUMPS, 10);
        existing.lowerTo(Keys.LOWEST_Y, -40);
        existing.raiseTo(Keys.HIGHEST_Y, 100);
        existing.add("feats:points", 77);
        services.counters.save(existing);

        assertEquals("Chronicle Stats import read 2 player files, seeded 2 players and wrote 48 counters.",
                await(new Importer(services).runChronicle(false)));
        Map<String, Long> dune = onDisk(DUNE);
        assertEquals(5000L, dune.get(Keys.BLOCK_BROKEN), "a larger TrackerAPI value stays");
        assertEquals(3000L, dune.get(Keys.JUMPS), "a smaller one is raised");
        assertEquals(-40L, dune.get(Keys.LOWEST_Y), "the lowest point keeps the smaller value");
        assertEquals(310L, dune.get(Keys.HIGHEST_Y));
        assertEquals(77L, dune.get("feats:points"), "keys the import does not know are left alone");
        assertEquals("DuneNow", services.counters.readFile(DUNE).name(), "the TrackerAPI name is kept");
        assertTrue(services.counters.readFile(DUNE).imported().has("chronicle"));
    }

    @Test
    void anOnlinePlayerIsSeededLive() throws Exception {
        services.counters.join(CEDAR, "Cedar", 1L);
        services.counters.add(CEDAR, Keys.KILLS, 100, null);
        services.counters.add(CEDAR, Keys.BLOCK_BROKEN, 1, null);
        await(new Importer(services).runFeats(false));
        assertEquals(100, services.counters.get(CEDAR, Keys.KILLS));
        assertEquals(1001, services.counters.get(CEDAR, Keys.BLOCK_BROKEN));
        services.counters.add(CEDAR, Keys.BLOCK_BROKEN, 1, null);
        assertEquals(1002, services.counters.get(CEDAR, Keys.BLOCK_BROKEN), "counting carries on from the seeded value");
        assertEquals(1001L, onDisk(CEDAR).get(Keys.BLOCK_BROKEN), "the import saved the live object");
        assertTrue(services.counters.live(CEDAR).imported().has("feats"));
    }

    @Test
    void anOnlinePlayerWhoseFileReadIsPendingIsSeededThroughTheFile() throws Exception {
        TestSupport.Manual io = new TestSupport.Manual();
        services = build(io);
        services.counters.join(CEDAR, "Cedar", 1L);
        services.counters.add(CEDAR, Keys.KILLS, 2, null);
        await(new Importer(services).runFeats(false));
        assertEquals(64L, onDisk(CEDAR).get(Keys.KILLS));
        io.runAll(); // the join's file read lands now
        assertEquals(66, services.counters.get(CEDAR, Keys.KILLS), "session kills add to the seeded file");
        assertEquals(1001, services.counters.get(CEDAR, Keys.BLOCK_BROKEN));
    }

    @Test
    void firstBootRunsBothOnceAndAnEmptyServerStillWritesState() throws Exception {
        Importer importer = new Importer(services);
        importer.runFirstBootIfNeeded();
        assertTrue(ImportState.exists(data));
        assertNotNull(ImportState.load(data, TestSupport.LOGGER).run("feats"));
        assertNotNull(ImportState.load(data, TestSupport.LOGGER).run("chronicle"));
        assertEquals(DUNE_EXPECTED, onDisk(DUNE));
        Files.delete(services.counters.fileOf(DUNE));
        importer.runFirstBootIfNeeded();
        assertFalse(Files.exists(services.counters.fileOf(DUNE)), "the first boot import never runs twice");

        Path empty = root.resolve("empty").resolve("mods").resolve("Ryvrook_TrackerAPI");
        Files.createDirectories(empty);
        Services bare = Services.forTests(TestSupport.LOGGER, empty, TestSupport.config(), TestSupport.DIRECT);
        new Importer(bare).runFirstBootIfNeeded();
        ImportState.Run feats = ImportState.load(empty, TestSupport.LOGGER).run("feats");
        assertNotNull(feats);
        assertNull(feats.folder());
        assertEquals(0, feats.files());
        assertEquals("Feats import found no player files to read. Chronicle Stats import found no player files to read.",
                await(new Importer(bare).runAll(true)));
    }

    @Test
    void runAllJoinsBothSummariesAndPrefersTheChronicleStatsFolder() throws Exception {
        Path stats = root.resolve("mods").resolve("Ryvrook_ChronicleStats").resolve("players");
        Files.createDirectories(stats);
        Files.copy(root.resolve("mods").resolve("Ryvrook_Chronicle").resolve("players").resolve(TESTER + ".json"),
                stats.resolve(TESTER + ".json"));
        String summary = await(new Importer(services).runAll(false));
        assertEquals("Feats import read 3 player files and skipped 1 it could not read, seeded 3 players and wrote 53 counters. "
                + "Chronicle Stats import read 1 player file, seeded 1 player and wrote 2 counters.", summary);
        assertFalse(Files.exists(services.counters.fileOf(DUNE)), "the older folder is not read when the new one exists");
        assertEquals(stats.toString(), ImportState.load(data, TestSupport.LOGGER).run("chronicle").folder());
    }
}
