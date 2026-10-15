package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.util.JsonFiles;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CounterStoreTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @TempDir
    Path dir;

    @Test
    void joinLeaveSavesTheFileAndForgetsThePlayer() throws Exception {
        CounterStore store = TestSupport.store(dir);
        store.loadServer();
        PlayerCounters pc = store.join(A, "Alder", 10L);
        assertTrue(store.isLoaded(A));
        assertTrue(pc.isMerged(), "no file yet, merged at once");
        store.add(A, Keys.BLOCK_BROKEN, 3, "default");
        store.addId(A, Keys.P_KILL, "Goblin_Scrapper", Keys.KILLS, 2, "default");
        assertEquals(1, store.loadedCount());
        assertEquals(Optional.of("Alder"), store.nameOf(A).get());

        store.leave(A);
        assertFalse(store.isLoaded(A));
        assertEquals(0, store.get(A, Keys.BLOCK_BROKEN), "offline players answer zero from memory");
        assertTrue(Files.exists(store.fileOf(A)));
        JsonObject saved = JsonFiles.read(store.fileOf(A));
        JsonObject counters = saved.getAsJsonObject("counters");
        assertEquals(3, counters.get(Keys.BLOCK_BROKEN).getAsLong());
        assertEquals(3, counters.get("world:default:block_broken").getAsLong());
        assertEquals(2, counters.get("kill:Goblin_Scrapper").getAsLong());
        assertEquals("Alder", saved.get("name").getAsString());
        store.leave(A); // leaving twice is harmless
    }

    @Test
    void rejoinReadsTheFileBackAndAddsToIt() {
        CounterStore first = TestSupport.store(dir);
        first.join(A, "Alder", 1L);
        first.add(A, Keys.BLOCK_BROKEN, 5, null);
        first.raise(A, Keys.BIGGEST_HIT, 40);
        first.lower(A, Keys.LOWEST_Y, -3);
        first.leave(A);

        CounterStore second = TestSupport.store(dir);
        second.join(A, "Alder", 2L);
        second.add(A, Keys.BLOCK_BROKEN, 1, null);
        second.raise(A, Keys.BIGGEST_HIT, 10);
        assertEquals(6, second.get(A, Keys.BLOCK_BROKEN));
        assertEquals(40, second.get(A, Keys.BIGGEST_HIT));
        assertEquals(-3, second.get(A, Keys.LOWEST_Y));
    }

    @Test
    void writesBeforeTheFileReadLandAreKept() {
        CounterStore seed = TestSupport.store(dir);
        seed.join(A, "Alder", 1L);
        seed.add(A, Keys.BLOCK_BROKEN, 100, null);
        seed.raise(A, Keys.BIGGEST_HIT, 50);
        seed.leave(A);

        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        PlayerCounters pc = store.join(A, "Alder", 2L);
        assertFalse(pc.isMerged());
        assertFalse(store.isLoaded(A), "not loaded until the file read landed");
        store.add(A, Keys.BLOCK_BROKEN, 2, null);
        store.raise(A, Keys.BIGGEST_HIT, 20);
        store.autosave();
        io.runAll();
        assertTrue(pc.isMerged());
        assertEquals(102, store.get(A, Keys.BLOCK_BROKEN));
        assertEquals(50, store.get(A, Keys.BIGGEST_HIT));
    }

    @Test
    void anUnmergedPlayerIsNotSavedOverTheirFile() throws Exception {
        CounterStore seed = TestSupport.store(dir);
        seed.join(A, "Alder", 1L);
        seed.add(A, Keys.BLOCK_BROKEN, 100, null);
        seed.leave(A);

        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        PlayerCounters pc = store.join(A, "Alder", 2L);
        store.add(A, Keys.BLOCK_BROKEN, 1, null);
        store.save(pc); // called directly before the read ran
        assertEquals(100, JsonFiles.read(store.fileOf(A)).getAsJsonObject("counters").get(Keys.BLOCK_BROKEN).getAsLong());
        io.runAll();
        store.save(pc);
        assertEquals(101, JsonFiles.read(store.fileOf(A)).getAsJsonObject("counters").get(Keys.BLOCK_BROKEN).getAsLong());
    }

    @Test
    void comingStraightBackPicksUpTheObjectStillBeingSaved() {
        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        PlayerCounters pc = store.join(A, "Alder", 1L);
        io.runAll();
        store.add(A, Keys.BLOCK_BROKEN, 7, null);
        store.leave(A);
        PlayerCounters back = store.join(A, "Alder", 2L);
        assertTrue(back == pc, "the same object, not a read of a file being written");
        io.runAll();
        assertEquals(7, store.get(A, Keys.BLOCK_BROKEN));
    }

    @Test
    void autosaveWritesDirtyPlayersServerTotalsAndNames() throws Exception {
        CounterStore store = TestSupport.store(dir);
        store.loadServer();
        store.join(A, "Alder", 1L);
        store.add(A, Keys.JOINS, 1, null);
        store.autosave();
        assertTrue(Files.exists(store.fileOf(A)));
        assertTrue(Files.exists(dir.resolve("server").resolve("totals.json")));
        assertTrue(Files.exists(dir.resolve("names.json")));
        long before = Files.getLastModifiedTime(store.fileOf(A)).toMillis();
        Files.setLastModifiedTime(store.fileOf(A), java.nio.file.attribute.FileTime.fromMillis(before - 10_000));
        store.autosave();
        assertEquals(before - 10_000, Files.getLastModifiedTime(store.fileOf(A)).toMillis(), "a clean player is not written again");
    }

    @Test
    void serverTotalsAndNamesSurviveARestart() throws Exception {
        CounterStore store = TestSupport.store(dir);
        store.loadServer();
        store.join(A, "Alder", 1L);
        store.join(B, "Birch", 1L);
        store.add(A, Keys.BLOCK_BROKEN, 4, null);
        store.add(B, Keys.BLOCK_BROKEN, 6, null);
        long since = store.serverCounters().since();
        store.join(A, "AlderRenamed", 5L);
        store.saveAll();

        CounterStore again = TestSupport.store(dir);
        again.loadServer();
        assertEquals(10, again.server(Keys.BLOCK_BROKEN));
        assertEquals(since, again.serverCounters().since());
        assertEquals(Map.of(Keys.BLOCK_BROKEN, 10L), again.serverAll());
        assertEquals(Optional.of("AlderRenamed"), again.names().nameOf(A));
        assertEquals(Optional.of(A), again.uuidOf("alderrenamed").get(), "names match ignoring case");
        assertEquals(Optional.empty(), again.uuidOf("Alder").get(), "the old name no longer points anywhere");
        assertEquals(Optional.of(B), again.uuidOf("BIRCH").get());
        assertEquals(java.util.Set.of(A, B), again.known());
        assertEquals(Optional.of("Birch"), again.nameOf(B).get(), "offline names come from the index");
    }

    @Test
    void offlineLoadReadsTheFile() throws Exception {
        CounterStore store = TestSupport.store(dir);
        store.join(A, "Alder", 1L);
        store.add(A, Keys.KILLS, 9, null);
        Map<String, Long> online = store.load(A).get(1, TimeUnit.SECONDS);
        assertEquals(9L, online.get(Keys.KILLS));
        store.leave(A);
        assertEquals(9L, store.load(A).get(1, TimeUnit.SECONDS).get(Keys.KILLS));
        assertEquals(Map.of(), store.load(C).get(1, TimeUnit.SECONDS), "never seen answers empty");
        assertEquals(9L, store.snapshotAny(A).get(Keys.KILLS));
        assertTrue(store.snapshotAny(C).isEmpty());
        assertEquals(List.of(A), store.filesOnDisk());
    }

    @Test
    void aCorruptFileIsMovedAsideAndThePlayerStartsFresh() throws Exception {
        Files.createDirectories(dir.resolve("players"));
        Files.writeString(dir.resolve("players").resolve(A + ".json"), "{ not json");
        Files.writeString(dir.resolve("players").resolve("notes.json"), "{}");
        CounterStore store = TestSupport.store(dir);
        assertNull(store.readFile(A));
        assertFalse(Files.exists(store.fileOf(A)));
        try (var files = Files.list(dir.resolve("players"))) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith(A + ".corrupt-")));
        }
        assertTrue(store.filesOnDisk().isEmpty(), "quarantined and foreign files are not players");
        store.join(A, "Alder", 1L);
        assertTrue(store.isLoaded(A));
        assertEquals(0, store.get(A, Keys.KILLS));
    }

    @Test
    void otherPluginsWriteOnlyTheirOwnPrefix() {
        CounterStore store = TestSupport.store(dir);
        store.join(A, "Alder", 1L);
        assertTrue(store.add(A, "feats:points", 5));
        assertTrue(store.raiseTo(A, "feats:best", 9));
        assertTrue(store.raiseTo(A, "feats:best", 3));
        assertEquals(5, store.get(A, "feats:points"));
        assertEquals(9, store.get(A, "feats:best"));
        assertFalse(store.add(A, Keys.KILLS, 1), "a total belongs to TrackerAPI");
        assertFalse(store.add(A, "kill:Goblin", 1), "so does a reserved prefix");
        assertFalse(store.add(A, "feats:points", 0));
        assertFalse(store.raiseTo(A, "world:x:kills", 1));
        assertFalse(store.add(null, "feats:points", 1));
        assertEquals(5, store.server("feats:points"), "plugin keys feed the server totals too");
    }

    @Test
    void anOfflinePlayerGetsPluginCountersInTheirFile() throws Exception {
        CounterStore store = TestSupport.store(dir);
        store.join(A, "Alder", 1L);
        store.add(A, "dominion:sieges_won", 2);
        store.leave(A);

        assertTrue(store.add(A, "dominion:sieges_won", 1), "queued for the file");
        assertTrue(store.raiseTo(A, "dominion:longest_siege", 40));
        assertTrue(store.raiseTo(A, "dominion:longest_siege", 15));
        JsonObject counters = JsonFiles.read(store.fileOf(A)).getAsJsonObject("counters");
        assertEquals(3, counters.get("dominion:sieges_won").getAsLong());
        assertEquals(40, counters.get("dominion:longest_siege").getAsLong());
        assertEquals(3, store.server("dominion:sieges_won"));
        store.watchBoard("dominion:sieges_won");
        store.boards().rebuild();
        assertEquals(List.of(Map.entry(A, 3L)), List.copyOf(store.top("dominion:sieges_won", 5)));

        assertTrue(store.add(B, "coinage:earned:gold", 7), "accepted, then dropped on IO");
        assertFalse(Files.exists(store.fileOf(B)), "no file is made for a player TrackerAPI never saw");
    }

    @Test
    void offlineWritesWaitingOnIoReachAPlayerWhoJoinsMeanwhile() throws Exception {
        CounterStore seed = TestSupport.store(dir);
        seed.join(A, "Alder", 1L);
        seed.add(A, "coinage:earned:gold", 100);
        seed.leave(A);

        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        assertTrue(store.add(A, "coinage:earned:gold", 5));
        assertTrue(store.add(A, "coinage:earned:gold", 6));
        assertEquals(1, io.pending(), "one IO pass for every queued write");
        store.join(A, "Alder", 2L); // its file read queues behind the pass
        io.runAll();
        assertEquals(111, store.get(A, "coinage:earned:gold"));
    }

    @Test
    void aPluginBestRaisedBeforeTheFileReadIsNotSummed() {
        CounterStore seed = TestSupport.store(dir);
        seed.join(A, "Alder", 1L);
        seed.raiseTo(A, "dominion:longest_siege", 50);
        seed.add(A, "dominion:claims", 4);
        seed.leave(A);

        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        store.join(A, "Alder", 2L);
        store.raiseTo(A, "dominion:longest_siege", 30);
        store.add(A, "dominion:claims", 1);
        io.runAll();
        assertEquals(50, store.get(A, "dominion:longest_siege"));
        assertEquals(5, store.get(A, "dominion:claims"));
    }

    @Test
    void boardsRankEveryPlayerOnlineOrNot() throws Exception {
        CounterStore store = TestSupport.store(dir);
        store.join(A, "Alder", 1L);
        store.join(B, "Birch", 1L);
        store.join(C, "Cedar", 1L);
        store.add(A, Keys.KILLS, 5, null);
        store.add(B, Keys.KILLS, 9, null);
        store.add(C, Keys.KILLS, 1, null);
        store.lower(A, Keys.LOWEST_Y, -10);
        store.lower(B, Keys.LOWEST_Y, 20);
        store.addId(A, Keys.P_KILL, "Goblin_Scrapper", null, 3, null);
        store.addId(B, Keys.P_KILL, "Goblin_Scrapper", null, 4, null);
        store.leave(C); // offline, read from its file

        assertTrue(store.top(Keys.KILLS, 10).isEmpty(), "empty until the first build");
        store.watchBoard("kill:Goblin_Scrapper");
        store.boards().rebuild();
        List<Map.Entry<UUID, Long>> top = store.top(Keys.KILLS, 10);
        assertEquals(List.of(B, A, C), top.stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of(9L, 5L, 1L), top.stream().map(Map.Entry::getValue).toList());
        assertEquals(1, store.top(Keys.KILLS, 1).size());
        assertEquals(List.of(A, B), store.top(Keys.LOWEST_Y, 10).stream().map(Map.Entry::getKey).toList(),
                "lowest y ranks the smallest first");
        assertEquals(B, store.top("kill:Goblin_Scrapper", 10).get(0).getKey());
        assertTrue(store.top("kill:Other", 10).isEmpty());
        assertTrue(store.boardKeys().contains(Keys.KILLS));
        assertFalse(store.boardKeys().contains("world_entered:default"));
        assertEquals(3, store.boards().players());
        assertTrue(store.boards().builtAt() > 0);
        assertNotNull(store.boards().keys());
    }

    @Test
    void aLeaveRacingAJoinNeverLosesTheObject() throws Exception {
        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        store.join(A, "Alder", 1L);
        io.runAll();
        store.add(A, Keys.BLOCK_BROKEN, 1, null);
        for (int round = 0; round < 200; round++) {
            Thread leaver = new Thread(() -> store.leave(A));
            leaver.start();
            store.join(A, "Alder", 2L);
            leaver.join();
            if (store.live(A) == null) {
                store.join(A, "Alder", 3L); // the leave won the race, the next join picks the object back up
            }
            io.runAll();
            store.add(A, Keys.BLOCK_BROKEN, 1, null);
        }
        assertEquals(201, store.get(A, Keys.BLOCK_BROKEN), "every session wrote into the same object");
    }

    @Test
    void aSessionStartIsKeptForAReloadAndClearedOnARealLeave() throws Exception {
        CounterStore before = TestSupport.store(dir);
        before.join(A, "Alder", 1_000L);
        before.startSession(A, 1_000L);
        before.live(A).touch(60_000L); // the shutdown marks when it wrote the file
        before.leave(A);
        assertEquals(1_000L, JsonFiles.read(before.fileOf(A)).get(PlayerCounters.SESSION_START).getAsLong());

        CounterStore after = TestSupport.store(dir);
        after.join(A, "Alder", 61_000L);
        long[] resumed = {0};
        after.resumeSession(A, 61_000L, start -> resumed[0] = start);
        assertEquals(1_000L, resumed[0], "the reload carries the session on");

        after.endSession(A);
        after.leave(A);
        assertFalse(JsonFiles.read(after.fileOf(A)).has(PlayerCounters.SESSION_START));

        CounterStore next = TestSupport.store(dir);
        next.join(A, "Alder", 70_000L);
        next.resumeSession(A, 70_000L, start -> resumed[0] = start);
        assertEquals(70_000L, resumed[0], "no start on file, the session starts now");
    }

    @Test
    void aRunningSessionRaisesTheLongest() {
        CounterStore store = TestSupport.store(dir);
        store.join(A, "Alder", 1L);
        store.raiseSession(A, 1_000L, 3_601_000L);
        store.raiseSession(A, 1_000L, 61_000L);
        store.raiseSession(A, 0L, 9_000_000L);
        assertEquals(3600, store.get(A, Keys.SESSION_LONGEST));
    }

    @Test
    void fileWorkAfterTheExecutorIsGoneIsSkippedNotRunInline() {
        java.util.concurrent.Executor gone = task -> {
            throw new java.util.concurrent.RejectedExecutionException("stopped");
        };
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, gone, new Perf());
        store.stop();
        store.join(A, "Alder", 1L);
        store.add(A, Keys.BLOCK_BROKEN, 1, null);
        store.leave(A);
        store.autosave();
        assertFalse(Files.exists(store.fileOf(A)), "nothing ran on the calling thread");
    }
}
