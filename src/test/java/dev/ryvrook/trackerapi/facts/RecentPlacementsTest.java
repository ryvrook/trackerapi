package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentPlacementsTest {
    private final UUID alice = UUID.randomUUID();

    @Test
    void aPlacementExplainsOneRemovalOfTheSameItem() {
        RecentPlacements placements = new RecentPlacements();
        placements.record(alice, "Potion_Health", 0);
        assertFalse(placements.consume(alice, "Food_Bread", 1), "another item is not the placement");
        assertTrue(placements.consume(alice, "Potion_Health", 2));
        assertFalse(placements.consume(alice, "Potion_Health", 3), "consumed once");
    }

    @Test
    void windowIsFiveSeconds() {
        RecentPlacements placements = new RecentPlacements();
        placements.record(alice, "Potion_Health", 0);
        assertTrue(placements.consume(alice, "Potion_Health", RecentPlacements.WINDOW_NANOS));
        placements.record(alice, "Potion_Health", 0);
        assertFalse(placements.consume(alice, "Potion_Health", RecentPlacements.WINDOW_NANOS + 1));
    }

    @Test
    void newerPlacementReplacesTheOlder() {
        RecentPlacements placements = new RecentPlacements();
        placements.record(alice, "Rock_Stone", 0);
        placements.record(alice, "Potion_Health", 1);
        assertFalse(placements.consume(alice, "Rock_Stone", 2));
        assertTrue(placements.consume(alice, "Potion_Health", 2));
    }

    @Test
    void nullsAndForgetting() {
        RecentPlacements placements = new RecentPlacements();
        Forgetting forgetting = new Forgetting();
        placements.registerWith(forgetting);
        placements.record(null, "Rock_Stone", 0);
        placements.record(alice, null, 0);
        assertFalse(placements.consume(null, "Rock_Stone", 0));
        assertFalse(placements.consume(alice, null, 0));
        placements.record(alice, "Rock_Stone", 0);
        forgetting.forget(alice);
        assertFalse(placements.consume(alice, "Rock_Stone", 1));
    }
}
