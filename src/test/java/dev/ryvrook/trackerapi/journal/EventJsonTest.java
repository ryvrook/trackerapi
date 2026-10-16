package dev.ryvrook.trackerapi.journal;

import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ServerLifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventJsonTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void headerFieldsComeFirstAndFlat() {
        BlockBroken e = new BlockBroken(new Header(1000L, 7L, "default", A), 1, 2, 3, "Rock_Stone", null, true, false);
        JsonObject o = EventJson.toJson(e);
        List<String> keys = List.copyOf(o.keySet());
        assertEquals(List.of("kind", "at", "seq", "world", "player", "x", "y", "z", "blockId", "playerPlaced", "creative", "ripeCrop"),
                keys, "a component added later goes at the end");
        assertEquals("block_broken", o.get("kind").getAsString());
        assertEquals(1000L, o.get("at").getAsLong());
        assertEquals(7L, o.get("seq").getAsLong());
        assertEquals("default", o.get("world").getAsString());
        assertEquals(A.toString(), o.get("player").getAsString());
        assertEquals("Rock_Stone", o.get("blockId").getAsString());
        assertTrue(o.get("playerPlaced").getAsBoolean());
        assertFalse(o.has("header"));
        assertFalse(o.has("toolItemId"), "null components are left out");
    }

    @Test
    void actorsBecomeObjectsAndCollectionsArrays() {
        EntityDied e = new EntityDied(new Header(5L, 1L, "w", A), Actor.creature(B, "Goblin_Duke"), Actor.player(A, "Steve"),
                "Physical", null, false, false, 1.5, 64, -3.25, true, List.of(A, B), Set.of(B), false, null, 2);
        JsonObject o = EventJson.toJson(e);
        JsonObject victim = o.getAsJsonObject("victim");
        assertEquals("CREATURE", victim.get("kind").getAsString());
        assertEquals(B.toString(), victim.get("uuid").getAsString());
        assertEquals("Goblin_Duke", victim.get("typeId").getAsString());
        assertFalse(victim.has("name"));
        assertEquals("Steve", o.getAsJsonObject("killer").get("name").getAsString());
        assertEquals(2, o.getAsJsonArray("participants").size());
        assertEquals(A.toString(), o.getAsJsonArray("participants").get(0).getAsString());
        assertEquals(B.toString(), o.getAsJsonArray("untouched").get(0).getAsString());
        assertEquals(-3.25, o.get("z").getAsDouble());
        assertEquals(2, o.get("itemsLost").getAsInt());
    }

    @Test
    void missingWorldAndPlayerAreJsonNullAndEnumsNames() {
        ServerLifecycle e = new ServerLifecycle(new Header(9L, 2L, null, null), ServerLifecycle.Phase.STOPPING);
        JsonObject o = EventJson.toJson(e);
        assertTrue(o.get("world").isJsonNull());
        assertTrue(o.get("player").isJsonNull());
        assertEquals("STOPPING", o.get("phase").getAsString());
    }

    @Test
    void worldEventEndCarriesTheEventUuid() {
        dev.ryvrook.trackerapi.api.event.WorldEventEnded e = new dev.ryvrook.trackerapi.api.event.WorldEventEnded(
                new Header(3L, 4L, "default", null), "Blood_Moon", "COMPLETED", List.of(A), B);
        JsonObject o = EventJson.toJson(e);
        assertEquals(B.toString(), o.get("eventUuid").getAsString());
        dev.ryvrook.trackerapi.api.event.WorldEventEnded old = new dev.ryvrook.trackerapi.api.event.WorldEventEnded(
                new Header(3L, 5L, "default", null), "Blood_Moon", "COMPLETED", List.of(A));
        assertEquals(null, old.eventUuid(), "the old constructor still builds the record");
        assertFalse(EventJson.toJson(old).has("eventUuid"));
    }
}
