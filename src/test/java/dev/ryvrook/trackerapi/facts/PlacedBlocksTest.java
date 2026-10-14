package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedBlocksTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void firstPlacementIsFreshAndRepeatIsNot() {
        PlacedBlocks placed = new PlacedBlocks();
        assertTrue(placed.place(alice, "default", 1, 2, 3));
        assertFalse(placed.place(alice, "default", 1, 2, 3));
        assertTrue(placed.place(alice, "default", 1, 2, 4));
        assertTrue(placed.place(alice, "other", 1, 2, 3), "another world is another spot");
    }

    @Test
    void placedHereIsPerPlayerAndPerWorld() {
        PlacedBlocks placed = new PlacedBlocks();
        placed.place(alice, "default", 5, 64, 5);
        assertTrue(placed.placedHere(alice, "default", 5, 64, 5));
        assertFalse(placed.placedHere(bob, "default", 5, 64, 5));
        assertFalse(placed.placedHere(alice, "other", 5, 64, 5));
        assertFalse(placed.placedHere(null, "default", 5, 64, 5));
        assertTrue(placed.place(null, "default", 5, 64, 5), "no player always counts");
    }

    @Test
    void oldestSpotFallsOffAfterTheLimit() {
        PlacedBlocks placed = new PlacedBlocks();
        for (int i = 0; i <= PlacedBlocks.PER_PLAYER; i++) {
            placed.place(alice, "default", i, 0, 0);
        }
        assertEquals(PlacedBlocks.PER_PLAYER, placed.size(alice));
        assertFalse(placed.placedHere(alice, "default", 0, 0, 0));
        assertTrue(placed.placedHere(alice, "default", 1, 0, 0));
        assertTrue(placed.placedHere(alice, "default", PlacedBlocks.PER_PLAYER, 0, 0));
    }

    @Test
    void placingAgainRefreshesTheSpot() {
        PlacedBlocks placed = new PlacedBlocks();
        placed.place(alice, "default", 0, 0, 0);
        for (int i = 1; i < PlacedBlocks.PER_PLAYER; i++) {
            placed.place(alice, "default", i, 0, 0);
        }
        placed.place(alice, "default", 0, 0, 0); // now the newest
        placed.place(alice, "default", 9999, 0, 0); // pushes out spot 1, not spot 0
        assertTrue(placed.placedHere(alice, "default", 0, 0, 0));
        assertFalse(placed.placedHere(alice, "default", 1, 0, 0));
    }

    @Test
    void forgettingDropsPlayersAndWorlds() {
        PlacedBlocks placed = new PlacedBlocks();
        Forgetting forgetting = new Forgetting();
        placed.registerWith(forgetting);
        placed.place(alice, "default", 1, 1, 1);
        placed.place(alice, "dungeon", 2, 2, 2);
        placed.place(bob, "default", 3, 3, 3);

        forgetting.forgetWorld("dungeon");
        assertFalse(placed.placedHere(alice, "dungeon", 2, 2, 2));
        assertTrue(placed.placedHere(alice, "default", 1, 1, 1));

        forgetting.forget(alice);
        assertFalse(placed.placedHere(alice, "default", 1, 1, 1));
        assertTrue(placed.placedHere(bob, "default", 3, 3, 3));
    }
}
