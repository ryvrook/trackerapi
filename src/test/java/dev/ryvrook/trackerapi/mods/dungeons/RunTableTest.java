package dev.ryvrook.trackerapi.mods.dungeons;

import dev.ryvrook.trackerapi.api.event.DungeonRun;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run table on its own, with the enter against exit race played out through the test hook. */
class RunTableTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final String WORLD = "dungeon_fear_1";

    @Test
    void firstEntryOpensARunAndTheLastExitForgetsTheWorld() {
        RunTable table = new RunTable();
        RunTable.Entered a = table.enter(WORLD, "Fear", "Default", P, 1_000L);
        assertTrue(a.first());
        assertEquals(1, a.partySize());
        RunTable.Entered again = table.enter(WORLD, "Fear", null, P, 2_000L);
        assertTrue(!again.first(), "a reconnect is the same run");
        RunTable.Entered b = table.enter(WORLD, "Fear", null, Q, 3_000L);
        assertTrue(b.first());
        assertEquals(2, b.partySize());
        assertEquals("Fear", table.dungeonOf(WORLD));

        RunTable.Outcome left = table.exit(WORLD, P, 10_000L);
        assertEquals(DungeonRun.Phase.LEFT, left.phase());
        assertEquals(9_000L, left.durationMillis());
        assertEquals(1, table.size(), "Q is still inside");
        table.died(WORLD, Q);
        RunTable.Outcome failed = table.exit(WORLD, Q, 12_000L);
        assertEquals(DungeonRun.Phase.FAILED, failed.phase());
        assertEquals(0, table.size());
        assertNull(table.dungeonOf(WORLD));
    }

    @Test
    void enterThatRacesTheLastExitStartsAFreshRunInsteadOfWritingToTheDroppedOne() {
        RunTable table = new RunTable();
        table.enter(WORLD, "Fear", "Default", P, 1_000L);
        boolean[] raced = {false};
        // between Q's lookup of the run and Q's lock on it, P leaves and the world entry goes
        table.afterLookup = () -> {
            if (!raced[0]) {
                raced[0] = true;
                table.exit(WORLD, P, 5_000L);
            }
        };
        RunTable.Entered q = table.enter(WORLD, "Fear", "Hardcore", Q, 6_000L);
        assertTrue(raced[0]);
        assertTrue(q.first());
        assertEquals(1, q.partySize(), "P's run is gone, Q is alone in a new one");
        assertEquals(1, table.size(), "the new run is in the table, not lost in the dropped one");
        assertEquals("Fear", table.dungeonOf(WORLD));
        RunTable.Outcome done = table.complete(WORLD, null, Q, 9_000L);
        assertEquals("Hardcore", done.mode());
        assertEquals(1, done.partySize());
        assertEquals(3_000L, done.durationMillis(), "timed from Q's own entry");
    }
}
