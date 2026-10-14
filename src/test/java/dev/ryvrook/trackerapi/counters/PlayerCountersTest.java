package dev.ryvrook.trackerapi.counters;

import com.google.gson.JsonObject;
import dev.ryvrook.trackerapi.api.Keys;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCountersTest {
    private static final UUID U = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void addRaiseLowerAndSet() {
        PlayerCounters pc = new PlayerCounters(U, "Alder", 100L);
        assertFalse(pc.isDirty());
        pc.add("block_broken", 2);
        pc.add("block_broken", 3);
        pc.add("block_broken", 0);
        assertEquals(5, pc.get("block_broken"));
        assertTrue(pc.isDirty());

        pc.raiseTo(Keys.BIGGEST_HIT, 10);
        pc.raiseTo(Keys.BIGGEST_HIT, 7);
        assertEquals(10, pc.get(Keys.BIGGEST_HIT));
        pc.lowerTo(Keys.LOWEST_Y, 40);
        pc.lowerTo(Keys.LOWEST_Y, 50);
        pc.lowerTo(Keys.LOWEST_Y, -3);
        assertEquals(-3, pc.get(Keys.LOWEST_Y));
        pc.set("reputation:Kweebec", 12);
        pc.set("reputation:Kweebec", -4);
        assertEquals(-4, pc.get("reputation:Kweebec"));
        assertEquals(0, pc.get("missing"));
        assertEquals(4, pc.size());

        pc.clearDirty();
        pc.raiseTo(Keys.BIGGEST_HIT, 9);
        assertFalse(pc.isDirty(), "a raise that changes nothing leaves the object clean");
    }

    @Test
    void mergeFromDiskSumsTotalsAndKeepsBests() {
        PlayerCounters disk = new PlayerCounters(U, "OldName", 50L);
        disk.add(Keys.BLOCK_BROKEN, 100);
        disk.raiseTo(Keys.BIGGEST_HIT, 30);
        disk.raiseTo(Keys.KILL_STREAK_BEST, 4);
        disk.raiseTo(Keys.SESSION_LONGEST, 9000);
        disk.raiseTo(Keys.LONGEST_FALL, 20);
        disk.raiseTo(Keys.HIGHEST_Y, 200);
        disk.lowerTo(Keys.LOWEST_Y, 10);
        disk.add("kill:Goblin_Scrapper", 7);
        JsonObject imported = new JsonObject();
        imported.addProperty("feats", 1);
        disk.setImported(imported);

        PlayerCounters live = new PlayerCounters(U, "", 100L);
        live.setMerged(false);
        live.add(Keys.BLOCK_BROKEN, 5);
        live.raiseTo(Keys.BIGGEST_HIT, 50);
        live.raiseTo(Keys.KILL_STREAK_BEST, 2);
        live.raiseTo(Keys.HIGHEST_Y, 150);
        live.lowerTo(Keys.LOWEST_Y, -5);
        live.add(Keys.JOINS, 1);

        live.mergeFromDisk(disk);
        assertTrue(live.isMerged());
        assertEquals(105, live.get(Keys.BLOCK_BROKEN), "sums add");
        assertEquals(50, live.get(Keys.BIGGEST_HIT), "max keeps the larger");
        assertEquals(4, live.get(Keys.KILL_STREAK_BEST));
        assertEquals(9000, live.get(Keys.SESSION_LONGEST));
        assertEquals(20, live.get(Keys.LONGEST_FALL));
        assertEquals(200, live.get(Keys.HIGHEST_Y));
        assertEquals(-5, live.get(Keys.LOWEST_Y), "min keeps the smaller");
        assertEquals(7, live.get("kill:Goblin_Scrapper"));
        assertEquals(1, live.get(Keys.JOINS));
        assertEquals("OldName", live.name(), "an empty name takes the one on disk");
        assertEquals(50L, live.firstSeen(), "first seen is the earlier one");
        assertNotNull(live.imported());
    }

    @Test
    void importMaxNeverLowersAndTakesTheSmallerLowestY() {
        PlayerCounters pc = new PlayerCounters(U, "Alder", 1L);
        pc.add(Keys.BLOCK_BROKEN, 500);
        pc.add(Keys.KILLS, 3);
        pc.lowerTo(Keys.LOWEST_Y, 12);
        pc.importMax(Map.of(Keys.BLOCK_BROKEN, 100L, Keys.KILLS, 40L, Keys.LOWEST_Y, -20L, Keys.DEATHS, 2L));
        assertEquals(500, pc.get(Keys.BLOCK_BROKEN));
        assertEquals(40, pc.get(Keys.KILLS));
        assertEquals(-20, pc.get(Keys.LOWEST_Y));
        assertEquals(2, pc.get(Keys.DEATHS));
        pc.importMax(Map.of(Keys.LOWEST_Y, 5L));
        assertEquals(-20, pc.get(Keys.LOWEST_Y));
    }

    @Test
    void jsonRoundTrip() {
        PlayerCounters pc = new PlayerCounters(U, "Alder", 123L);
        pc.add(Keys.BLOCK_BROKEN, 9);
        pc.add("world:default:block_broken", 9);
        pc.lowerTo(Keys.LOWEST_Y, -7);
        pc.touch(456L);
        JsonObject imported = new JsonObject();
        JsonObject feats = new JsonObject();
        feats.addProperty("at", 77L);
        imported.add("feats", feats);
        pc.setImported(imported);

        JsonObject json = pc.toJson();
        assertEquals(PlayerCounters.VERSION, json.get("Version").getAsInt());
        PlayerCounters back = PlayerCounters.fromJson(U, json);
        assertEquals(pc.snapshot(), back.snapshot());
        assertEquals("Alder", back.name());
        assertEquals(123L, back.firstSeen());
        assertEquals(456L, back.lastSeen());
        assertEquals(77L, back.imported().getAsJsonObject("feats").get("at").getAsLong());
        assertTrue(back.isMerged());
        assertFalse(back.isDirty());
    }

    @Test
    void fromJsonSkipsValuesThatAreNotNumbers() {
        JsonObject o = new JsonObject();
        JsonObject c = new JsonObject();
        c.addProperty("kills", 3);
        c.addProperty("bad", "x");
        c.add("nested", new JsonObject());
        o.add("counters", c);
        PlayerCounters pc = PlayerCounters.fromJson(U, o);
        assertEquals(Map.of("kills", 3L), pc.snapshot());
        assertEquals("", pc.name());
    }
}
