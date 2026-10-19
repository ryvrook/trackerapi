package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.facts.StashBalance;
import dev.ryvrook.trackerapi.util.JsonFiles;
import dev.ryvrook.trackerapi.util.Perf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persistent stash balance that decides what part of a gain is fresh. */
class StashBalanceTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000401");

    @TempDir
    Path dir;

    private StashBalance balance(CounterStore store) {
        store.join(A, "Alder", 1L);
        return new StashBalance(store);
    }

    @Test
    void aPlacedContainerIsNeverFreshAndAGeneratedOneIsFreshLessTheBalance() {
        CounterStore store = TestSupport.store(dir);
        StashBalance stash = balance(store);
        // Loot in a player's chest is nobody's new find, even with nothing on the balance.
        assertEquals(0, stash.fresh(A, "Ore_Gold", 30, true));
        // A world generated chest with 30 gold is new.
        assertEquals(30, stash.fresh(A, "Ore_Gold", 30, false));
        // Ten put into some chest come back as not fresh, the rest of a bigger take is.
        stash.put(A, "Ore_Gold", 10);
        assertEquals(15, stash.fresh(A, "Ore_Gold", 25, false));
        assertEquals(0, stash.balance(A, "Ore_Gold"));
        // Taking from a placed chest still takes the balance down, so it cannot hide a later real find.
        stash.put(A, "Ore_Gold", 10);
        assertEquals(0, stash.fresh(A, "Ore_Gold", 4, true));
        assertEquals(6, stash.balance(A, "Ore_Gold"));
    }

    @Test
    void aRoundTripAfterElevenMinutesIsStillNotFresh() throws Exception {
        CounterStore store = TestSupport.store(dir);
        StashBalance stash = balance(store);
        stash.put(A, "Ingredient_Bar_Iron", 64);
        // The balance has no clock, so the 10 minute window of 1.1.1 is gone. Nothing ages it, autosaves included.
        store.autosave();
        Thread.sleep(5);
        store.autosave();
        assertEquals(0, stash.fresh(A, "Ingredient_Bar_Iron", 64, false));
    }

    @Test
    void aRoundTripAfterARestartIsNotFresh() {
        CounterStore first = TestSupport.store(dir);
        StashBalance stash = balance(first);
        stash.put(A, "Ingredient_Bar_Iron", 40);
        first.leave(A);
        first.saveAll();

        // A new store over the same files is a restart. The balance comes back with the player.
        CounterStore second = TestSupport.store(dir);
        StashBalance again = balance(second);
        assertEquals(40, again.balance(A, "Ingredient_Bar_Iron"));
        assertEquals(5, again.fresh(A, "Ingredient_Bar_Iron", 45, false));
    }

    @Test
    void putsBeforeTheFileReadLandAddToWhatTheFileHolds() {
        CounterStore seed = TestSupport.store(dir);
        new StashBalance(seed);
        seed.join(A, "Alder", 1L);
        new StashBalance(seed).put(A, "Rock_Stone", 100);
        seed.leave(A);

        TestSupport.Manual io = new TestSupport.Manual();
        CounterStore store = new CounterStore(dir, TestSupport.LOGGER, io, new Perf());
        PlayerCounters pc = store.join(A, "Alder", 2L);
        StashBalance stash = new StashBalance(store);
        stash.put(A, "Rock_Stone", 5);
        io.runAll();
        assertTrue(pc.isMerged());
        assertEquals(105, stash.balance(A, "Rock_Stone"));
    }

    @Test
    void theBalanceKeepsAtMostTheCapOfIdsAndDropsTheSmallest() {
        CounterStore store = TestSupport.store(dir);
        StashBalance stash = balance(store);
        stash.put(A, "Big", 1_000);
        for (int i = 0; i < PlayerCounters.MAX_STASH_IDS + 100; i++) {
            stash.put(A, "Item_" + i, 2);
        }
        PlayerCounters pc = store.live(A);
        assertEquals(PlayerCounters.MAX_STASH_IDS, pc.stashSize());
        assertEquals(1_000, stash.balance(A, "Big"), "the large balance is kept");

        // A file written past the cap by hand is trimmed when read.
        JsonObject o = pc.toJson();
        JsonObject counters = o.getAsJsonObject("counters");
        for (int i = 0; i < 50; i++) {
            counters.addProperty("stash:Extra_" + i, 1);
        }
        assertEquals(PlayerCounters.MAX_STASH_IDS, PlayerCounters.fromJson(A, o).stashSize());
    }

    @Test
    void creativeDepositsGoOnTheBalanceSoTheyAreNotFreshLater() {
        CounterStore store = TestSupport.store(dir);
        StashBalance stash = balance(store);
        // In Creative the player puts 64 diamonds from the item library into a world generated chest.
        stash.creative(A, Map.of("Rock_Gem_Diamond", 64L), null);
        // Back in Adventure, taking them out of that chest is not a find.
        assertEquals(0, stash.fresh(A, "Rock_Gem_Diamond", 64, false));
        // Taking out in Creative counts nothing and takes the balance down.
        stash.creative(A, Map.of("Rock_Gem_Ruby", 10L), null);
        stash.creative(A, null, Map.of("Rock_Gem_Ruby", 10L));
        assertEquals(0, stash.balance(A, "Rock_Gem_Ruby"));
        assertEquals(7, stash.fresh(A, "Rock_Gem_Ruby", 7, false));
    }

    @Test
    void stashKeysAreNeverAStatABoardATotalOrAnImport() throws Exception {
        CounterStore store = TestSupport.store(dir);
        StashBalance stash = balance(store);
        stash.put(A, "Ore_Iron", 12);
        store.add(A, Keys.BLOCK_BROKEN, 1, null);

        String key = Keys.of(Keys.P_STASH, "Ore_Iron");
        assertTrue(Keys.isHidden(key));
        assertTrue(Keys.isReserved(key));
        assertFalse(Keys.isExternal(key));
        assertTrue(Keys.RESERVED_PREFIXES.contains(Keys.P_STASH));
        assertFalse(Keys.PER_WORLD_TOTALS.contains(Keys.P_STASH));
        assertFalse(Keys.isHidden("stash"), "a bare total of that name is not the balance");

        // API reads
        assertEquals(0, store.get(A, key));
        assertTrue(store.all(A).keySet().stream().noneMatch(Keys::isHidden));
        assertTrue(store.load(A).get().keySet().stream().noneMatch(Keys::isHidden));
        assertTrue(store.serverAll().keySet().stream().noneMatch(Keys::isHidden));
        // Another plugin cannot write it
        assertFalse(store.add(A, key, 5));
        assertFalse(store.raiseTo(A, key, 5));

        // Boards, even when asked for
        store.boards().watch(key);
        store.boards().rebuild();
        assertTrue(store.boards().keys().stream().noneMatch(Keys::isHidden));
        assertTrue(store.top(key, 10).isEmpty());

        // Imports
        store.live(A).importMax(Map.of(Keys.of(Keys.P_STASH, "Ore_Gold"), 99L));
        assertEquals(0, stash.balance(A, "Ore_Gold"));

        // Saved in the player's file and back out of every read after a leave
        store.leave(A);
        JsonObject saved = JsonFiles.read(store.fileOf(A)).getAsJsonObject("counters");
        assertEquals(12, saved.get(key).getAsLong());
        assertTrue(store.snapshotAny(A).keySet().stream().noneMatch(Keys::isHidden));
        assertTrue(store.load(A).get().keySet().stream().noneMatch(Keys::isHidden));
        store.boards().rebuild();
        assertTrue(store.boards().keys().stream().noneMatch(Keys::isHidden));
    }
}
