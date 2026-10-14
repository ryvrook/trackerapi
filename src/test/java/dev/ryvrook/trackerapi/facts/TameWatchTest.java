package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameWatchTest {
    private final UUID cow = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    @Test
    void wildToTamedCreditsTheFeeder() {
        TameWatch watch = new TameWatch();
        watch.leaving(cow, "Cow", player, 1_000);
        assertEquals(player, watch.arrived(cow, "Tamed_Cow", 1_000));
        assertNull(watch.arrived(cow, "Tamed_Cow", 1_000), "one tame counts once");
        assertTrue(watch.idle());
    }

    @Test
    void growingUpAndChunkLoadsAreNotTames() {
        TameWatch watch = new TameWatch();
        watch.leaving(cow, "Tamed_Cow_Calf", player, 0);
        assertNull(watch.arrived(cow, "Tamed_Cow", 0));
        assertNull(watch.arrived(UUID.randomUUID(), "Tamed_Pig", 0)); // loaded from disk, nothing pending
        watch.leaving(cow, "Cow", player, 0);
        assertNull(watch.arrived(cow, "Cow_Angry", 0)); // a role change that is not a tame
    }

    @Test
    void staleNotesAndTheCapDropOut() {
        TameWatch watch = new TameWatch();
        watch.leaving(cow, "Cow", player, 0);
        assertNull(watch.arrived(cow, "Tamed_Cow", TameWatch.WINDOW_MILLIS + 1));
        for (int i = 0; i < TameWatch.MAX_PENDING + 10; i++) {
            watch.leaving(UUID.randomUUID(), "Sheep", player, 0);
        }
        assertEquals(TameWatch.MAX_PENDING, watch.size());
    }

    @Test
    void theTameKeepsTheWildRole() {
        TameWatch watch = new TameWatch();
        watch.leaving(cow, "Cow", player, 0);
        assertEquals(new TameWatch.Tame(player, "Cow"), watch.arrive(cow, "Tamed_Cow", 10));
        assertTrue(watch.idle());
    }
}
