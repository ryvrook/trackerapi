package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentStashTest {
    private final UUID alice = UUID.randomUUID();

    @Test
    void windowIsTenMinutesAndRoomIsLarger() {
        assertEquals(10L * 60 * 1_000_000_000L, RecentStash.WINDOW_NANOS);
        assertEquals(512, RecentStash.MAX_ENTRIES);
        assertTrue(RecentStash.WINDOW_NANOS > RecentDrops.WINDOW_NANOS);
    }

    @Test
    void chestRoundTripIsNotFresh() {
        RecentStash stash = new RecentStash();
        stash.record(alice, "Ore_Iron", 16, 0);
        long gained = 20;
        long fresh = gained - stash.take(alice, "Ore_Iron", gained, 1_000);
        assertEquals(4, fresh);
    }

    @Test
    void survivesPastTheDropWindowButNotItsOwn() {
        RecentStash stash = new RecentStash();
        stash.record(alice, "Ore_Iron", 5, 0);
        assertEquals(5, stash.take(alice, "Ore_Iron", 5, RecentDrops.WINDOW_NANOS + 1));
        stash.record(alice, "Ore_Iron", 5, 0);
        assertEquals(0, stash.take(alice, "Ore_Iron", 5, RecentStash.WINDOW_NANOS + 1));
    }

    @Test
    void floodStopsAtTheLimit() {
        RecentStash stash = new RecentStash();
        for (int i = 0; i < RecentStash.MAX_ENTRIES + 100; i++) {
            stash.record(alice, "Item_" + (i % 13), 1, i);
        }
        assertEquals(RecentStash.MAX_ENTRIES, stash.entries(alice));
    }

    @Test
    void forgettingDropsThePlayer() {
        RecentStash stash = new RecentStash();
        Forgetting forgetting = new Forgetting();
        stash.registerWith(forgetting);
        stash.record(alice, "Ore_Iron", 5, 0);
        forgetting.forget(alice);
        assertEquals(0, stash.entries(alice));
    }
}
