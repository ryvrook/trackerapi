package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Chronicle Stats world key rules, ported, and the guess for names nobody learned yet. */
class WorldKeysTest {

    @Test
    void persistentWorldsKeepTheirNameLowerCased() {
        assertEquals("default", WorldKeys.compute("default", null, null));
        assertEquals("my_world_2", WorldKeys.compute("My World 2", null, null));
        assertEquals(WorldKeys.OTHER, WorldKeys.compute(null, null, null));
        assertEquals(64, WorldKeys.compute("x".repeat(80), null, null).length());
    }

    @Test
    void portalBeatsInstanceBeatsName() {
        assertEquals("portal.portal_void", WorldKeys.compute("instance-Void-1", "Portal_Void", "Void"));
        assertEquals("instance.dungeon_goblin", WorldKeys.compute("instance-Dungeon_Goblin-1", null, "Dungeon_Goblin"));
        assertEquals("instance.instance-dungeon_goblin",
                WorldKeys.compute("instance-Dungeon_Goblin-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", null, ""),
                "an instance without a template id falls back to the name without its run suffix");
        assertEquals("instance.arena", WorldKeys.compute("arena_7", null, "arena_7"), "a run id in the key is ignored");
    }

    @Test
    void guessFoldsInstanceRunsToTheirTemplate() {
        assertEquals("instance.dungeon_goblin",
                WorldKeys.guess("instance-Dungeon_Goblin-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"));
        assertEquals("instance.dungeon_goblin", WorldKeys.guess("instance-Dungeon_Goblin-3"));
        assertEquals("default", WorldKeys.guess("default"));
        assertEquals("instance-", WorldKeys.guess("instance-"));
    }

    @Test
    void learnReplacesAGuessAndForgetDropsIt() {
        WorldKeys keys = new WorldKeys(null);
        assertEquals("instance.void", keys.keyOf("instance-Void-11111111-2222-3333-4444-555555555555"));
        assertFalse(keys.isLearned("instance-Void-11111111-2222-3333-4444-555555555555"));
        keys.learn("instance-Void-11111111-2222-3333-4444-555555555555", "Portal_Void", "");
        assertTrue(keys.isLearned("instance-Void-11111111-2222-3333-4444-555555555555"));
        assertEquals("portal.portal_void", keys.keyOf("instance-Void-11111111-2222-3333-4444-555555555555"));
        keys.forget("instance-Void-11111111-2222-3333-4444-555555555555");
        assertFalse(keys.isLearned("instance-Void-11111111-2222-3333-4444-555555555555"));
        assertNull(keys.keyOf(null));
    }

    @Test
    void distinctKeysAreCappedIntoOther() {
        WorldKeys keys = new WorldKeys(null, 3);
        assertEquals("a", keys.keyOf("a"));
        assertEquals("b", keys.keyOf("B"));
        assertEquals("c", keys.keyOf("c"));
        assertEquals(WorldKeys.OTHER, keys.keyOf("d"));
        assertEquals("a", keys.keyOf("A"), "an admitted key keeps being given out");
        assertEquals(3, keys.admittedCount());
    }

    @Test
    void manyInstanceRunsMakeOneKey() {
        WorldKeys keys = new WorldKeys(null);
        for (int i = 0; i < 500; i++) {
            keys.keyOf("instance-Dungeon_Goblin-" + java.util.UUID.randomUUID());
        }
        assertEquals(1, keys.admittedCount());
    }
}
