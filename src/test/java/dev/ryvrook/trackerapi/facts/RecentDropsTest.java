package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentDropsTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void takesBackWhatWasDroppedAndNoMore() {
        RecentDrops drops = new RecentDrops();
        drops.record(alice, "Ingredient_Stick", 10, 0);
        assertEquals(4, drops.take(alice, "Ingredient_Stick", 4, 1));
        assertEquals(6, drops.take(alice, "Ingredient_Stick", 20, 2));
        assertEquals(0, drops.take(alice, "Ingredient_Stick", 1, 3));
    }

    @Test
    void takesOldestFirstAcrossEntries() {
        RecentDrops drops = new RecentDrops();
        drops.record(alice, "Rock_Stone", 3, 0);
        drops.record(alice, "Rock_Stone", 5, 10);
        assertEquals(2, drops.entries(alice));
        assertEquals(4, drops.take(alice, "Rock_Stone", 4, 20));
        assertEquals(1, drops.entries(alice));
        assertEquals(4, drops.take(alice, "Rock_Stone", 99, 30));
        assertEquals(0, drops.entries(alice));
    }

    @Test
    void separatePlayersAndItems() {
        RecentDrops drops = new RecentDrops();
        drops.record(alice, "Rock_Stone", 3, 0);
        assertEquals(0, drops.take(bob, "Rock_Stone", 3, 1));
        assertEquals(0, drops.take(alice, "Wood_Oak_Trunk", 3, 1));
        assertEquals(3, drops.take(alice, "Rock_Stone", 3, 1));
    }

    @Test
    void entriesExpireAfterFiveMinutes() {
        RecentDrops drops = new RecentDrops();
        drops.record(alice, "Rock_Stone", 3, 0);
        assertEquals(3, drops.take(alice, "Rock_Stone", 3, RecentDrops.WINDOW_NANOS));
        drops.record(alice, "Rock_Stone", 3, 0);
        assertEquals(0, drops.take(alice, "Rock_Stone", 3, RecentDrops.WINDOW_NANOS + 1));
    }

    @Test
    void floodStopsAtTheLimit() {
        RecentDrops drops = new RecentDrops();
        for (int i = 0; i < RecentDrops.MAX_ENTRIES + 50; i++) {
            drops.record(alice, "Item_" + (i % 7), 1, i);
        }
        assertEquals(RecentDrops.MAX_ENTRIES, drops.entries(alice));
    }

    @Test
    void ignoresNonsenseAndForgets() {
        RecentDrops drops = new RecentDrops();
        Forgetting forgetting = new Forgetting();
        drops.registerWith(forgetting);
        drops.record(null, "Rock_Stone", 3, 0);
        drops.record(alice, null, 3, 0);
        drops.record(alice, "Rock_Stone", 0, 0);
        assertEquals(0, drops.entries(alice));
        drops.record(alice, "Rock_Stone", 3, 0);
        assertEquals(0, drops.take(alice, "Rock_Stone", 0, 1));
        forgetting.forget(alice);
        assertEquals(0, drops.take(alice, "Rock_Stone", 3, 1));
    }
}
