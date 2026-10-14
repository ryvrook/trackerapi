package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecentContainersTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void remembersTheLastBlockWithinTheWindow() {
        RecentContainers containers = new RecentContainers();
        containers.remember(alice, "default", 1, 2, 3, 0);
        containers.remember(alice, "default", 4, 5, 6, 10);
        RecentContainers.Use use = containers.recent(alice, RecentContainers.WINDOW_NANOS);
        assertNotNull(use);
        assertEquals(4, use.x());
        assertEquals(5, use.y());
        assertEquals(6, use.z());
        assertEquals("default", use.world());
        assertNull(containers.recent(bob, 0));
        assertNull(containers.recent(null, 0));
    }

    @Test
    void expiresAfterThirtySeconds() {
        RecentContainers containers = new RecentContainers();
        containers.remember(alice, "default", 1, 2, 3, 0);
        assertNull(containers.recent(alice, RecentContainers.WINDOW_NANOS + 1));
        assertNull(containers.recent(alice, 0), "an expired use is dropped on read");
    }

    @Test
    void forgettingDropsPlayersAndWorlds() {
        RecentContainers containers = new RecentContainers();
        Forgetting forgetting = new Forgetting();
        containers.registerWith(forgetting);
        containers.remember(alice, "dungeon", 1, 2, 3, 0);
        containers.remember(bob, "default", 1, 2, 3, 0);
        forgetting.forgetWorld("dungeon");
        assertNull(containers.recent(alice, 1));
        assertNotNull(containers.recent(bob, 1));
        forgetting.forget(bob);
        assertNull(containers.recent(bob, 1));
    }
}
