package dev.ryvrook.trackerapi.mods.lucky;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.LuckyDrop;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.mods.FakeMods;
import dev.ryvrook.trackerapi.mods.Handles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Lucky Mining binding against the fake mod loaded through the isolating loader. The test plays the mod's
 * handler by hand on the fake's static map, then publishes TrackerAPI's own break events. A moved lastBreak on
 * an existing entry is a drop with the streak from the chance, a new entry is not, the map is never written,
 * leaving forgets the player, unbind drops the listener and a renamed field leaves the feature missing.
 */
class LuckyBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final LuckyBinding.Names FAKE = new LuckyBinding.Names("fakes.lucky.BreakBlockEventSystem",
            "fakes.lucky.BreakBlockEventSystem$LuckyMiningInfo");

    @TempDir
    Path dir;
    private Services services;
    private LuckyBinding binding;
    private ClassLoader loader;
    private Map<Object, Object> map;
    private Class<?> info;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        for (UUID u : List.of(P, Q)) {
            services.counters.join(u, "Miner", 1L);
            services.players.join(u, "Miner", 1L).world = "default";
        }
        services.publisher.subscribe("test", java.util.Set.of(EventKind.LUCKY_DROP), Delivery.SYNC, seen::add);
        loader = FakeMods.loader();
        Class<?> system = Class.forName(FAKE.system(), true, loader);
        map = (Map<Object, Object>) system.getField("luckyMiningInfo").get(null);
        info = Class.forName(FAKE.info(), true, loader);
        binding = new LuckyBinding(FAKE);
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    private Object plugin() throws Exception {
        return Class.forName("fakes.lucky.LuckyMining", true, loader).getConstructor().newInstance();
    }

    /** What the mod does on the first whitelisted break of a streak. */
    private Object start(UUID player, String ore, long now) throws Exception {
        Object entry = info.getConstructor(String.class, double.class, long.class).newInstance(ore, 0.4, now);
        map.put(player, entry);
        return entry;
    }

    private void bonus(Object entry, long now) throws Throwable {
        MethodHandle mh = Handles.virtual(info, "bonus", void.class, long.class);
        mh.invoke(entry, now);
    }

    private void miss(Object entry) throws Throwable {
        Handles.virtual(info, "miss", void.class).invoke(entry);
    }

    private void broke(UUID player, String block) {
        services.publisher.publish(TestSupport.broken(player, block));
    }

    private LuckyDrop drop(int i) {
        return (LuckyDrop) seen.get(i);
    }

    @Test
    void bindsThroughTheModLoaderAndListsTheFeature() throws Exception {
        assertTrue(binding.bind(plugin(), "1.0.8", services));
        assertEquals(List.of(LuckyBinding.F_STREAKS), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("1.0.8", binding.version());
        assertNotSame(Class.forName(FAKE.system()), Class.forName(FAKE.system(), true, loader),
                "the fake is isolated from the test classpath");
        assertEquals(3, binding.stats().size());
        assertTrue(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/lucky")));
    }

    @Test
    void creativeBreakGivesACreativeDrop() throws Throwable {
        binding.bind(plugin(), "1.0.8", services);
        long now = 5_000_000L;
        Object entry = start(P, "Ore_Copper_Stone", now);
        broke(P, "Ore_Copper_Stone");
        bonus(entry, now + 100);
        services.publisher.publish(new BlockBroken(TestSupport.header("default", P), 1, 2, 3, "Ore_Copper_Stone",
                "Tool_Pickaxe_Iron", false, true));
        assertEquals(1, seen.size());
        assertTrue(drop(0).creative());
        assertEquals(0, services.counters.get(P, Keys.of("lucky", ModKeys.DROPS)), "the test config does not count creative");
        bonus(entry, now + 200);
        broke(P, "Ore_Copper_Stone");
        assertEquals(2, seen.size());
        assertFalse(drop(1).creative());
        assertEquals(1, services.counters.get(P, Keys.of("lucky", ModKeys.DROPS)));
    }

    @Test
    void movedLastBreakOnAnExistingEntryIsADrop() throws Throwable {
        binding.bind(plugin(), "1.0.8", services);
        long now = 5_000_000L;
        // the first ore starts the streak, the binding sees the entry for the first time
        Object entry = start(P, "Ore_Copper_Stone", now);
        broke(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty(), "a new entry is a streak start, not a drop");
        // a failed roll moves the chance only
        miss(entry);
        broke(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty());
        // a successful roll moves lastBreak
        bonus(entry, now + 100);
        broke(P, "Ore_Copper_Stone");
        assertEquals(1, seen.size());
        LuckyDrop d = drop(0);
        assertEquals(P, d.player());
        assertEquals("default", d.world());
        assertEquals("lucky", d.mod());
        assertEquals("Ore_Copper_Stone", d.blockId());
        assertEquals("Ore_Copper_Stone", d.itemId());
        assertEquals(3, d.streak(), "the creating break, the miss and the bonus");
        assertEquals(1, services.counters.get(P, Keys.of("lucky", ModKeys.DROPS)));
        assertEquals(1, services.counters.get(P, ModKeys.key("lucky", ModKeys.DROPS, "Ore_Copper_Stone")));
        assertEquals(3, services.counters.get(P, Keys.of("lucky", ModKeys.STREAK_BEST)));
        // the same values read again are nothing new, even on a stone break
        broke(P, "Rock_Stone");
        assertEquals(1, seen.size());
        // another bonus
        bonus(entry, now + 200);
        broke(P, "Ore_Copper_Stone");
        assertEquals(2, seen.size());
        assertEquals(4, drop(1).streak());
        assertEquals(2, services.counters.get(P, Keys.of("lucky", ModKeys.DROPS)));
        assertEquals(4, services.counters.get(P, Keys.of("lucky", ModKeys.STREAK_BEST)));
        assertEquals(2, binding.published());
        assertEquals(1, map.size(), "the binding never writes the mod's map");
    }

    @Test
    void newEntryAfterAnOldOneIsAStartNotADrop() throws Throwable {
        binding.bind(plugin(), "1.0.8", services);
        Object first = start(P, "Ore_Copper_Stone", 1_000L);
        broke(P, "Ore_Copper_Stone");
        bonus(first, 1_100L);
        broke(P, "Ore_Copper_Stone");
        assertEquals(1, seen.size());
        // the mod replaces the entry on a different ore, its lastBreak is later than anything seen
        map.remove(P);
        broke(P, "Ore_Iron_Stone");
        Object second = start(P, "Ore_Iron_Stone", 9_000L);
        broke(P, "Ore_Iron_Stone");
        assertEquals(1, seen.size(), "a fresh entry is not a drop");
        bonus(second, 9_050L);
        broke(P, "Ore_Iron_Stone");
        assertEquals(2, seen.size());
        assertEquals("Ore_Iron_Stone", drop(1).blockId());
        assertEquals(2, drop(1).streak());
        // an entry swapped without a gap in between is also a start
        Object third = start(P, "Ore_Gold_Stone", 20_000L);
        broke(P, "Ore_Gold_Stone");
        assertEquals(2, seen.size());
        bonus(third, 20_010L);
        broke(P, "Ore_Gold_Stone");
        assertEquals(3, seen.size());
    }

    @Test
    void playersAreKeptApartAndLeavingForgets() throws Throwable {
        binding.bind(plugin(), "1.0.8", services);
        Object p = start(P, "Ore_Copper_Stone", 1_000L);
        Object q = start(Q, "Ore_Gold_Stone", 1_000L);
        broke(P, "Ore_Copper_Stone");
        broke(Q, "Ore_Gold_Stone");
        bonus(q, 1_200L);
        broke(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty(), "the other player's bonus is not this one's");
        broke(Q, "Ore_Gold_Stone");
        assertEquals(1, seen.size());
        assertEquals(Q, drop(0).player());
        assertEquals(1, services.counters.get(Q, Keys.of("lucky", ModKeys.DROPS)));
        assertEquals(0, services.counters.get(P, Keys.of("lucky", ModKeys.DROPS)));

        // after a leave the entry still in the map reads as a start again, so a stale bonus is not a drop
        bonus(p, 1_300L);
        services.publisher.publish(new PlayerLeft(TestSupport.header("default", P), "Miner", 10L));
        broke(P, "Ore_Copper_Stone");
        assertEquals(1, seen.size());
        bonus(p, 1_400L);
        broke(P, "Ore_Copper_Stone");
        assertEquals(2, seen.size());
    }

    @Test
    void streakFromChanceIsClampedAtOne() {
        assertEquals(1, LuckyBinding.streakOf(0.4));
        assertEquals(2, LuckyBinding.streakOf(0.42));
        assertEquals(31, LuckyBinding.streakOf(1.0));
        assertEquals(1, LuckyBinding.streakOf(0.1));
        assertEquals(1, LuckyBinding.streakOf(0.0));
    }

    @Test
    void unbindDropsTheListenerAndTheSeenState() throws Throwable {
        binding.bind(plugin(), "1.0.8", services);
        Object entry = start(P, "Ore_Copper_Stone", 1_000L);
        broke(P, "Ore_Copper_Stone");
        binding.unbind("gone");
        assertFalse(binding.isBound());
        assertFalse(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/lucky")));
        bonus(entry, 1_100L);
        broke(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty());

        // bound again, the old entry reads as a start and only the next bonus is a drop
        assertTrue(binding.bind(plugin(), "1.0.8", services));
        broke(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty());
        bonus(entry, 1_200L);
        broke(P, "Ore_Copper_Stone");
        assertEquals(1, seen.size());
    }

    @Test
    void renamedFieldLeavesTheFeatureMissing() throws Exception {
        LuckyBinding broken = new LuckyBinding(new LuckyBinding.Names("fakes.lucky.RenamedBreakBlockEventSystem",
                "fakes.lucky.RenamedBreakBlockEventSystem$LuckyMiningInfo"));
        assertFalse(broken.bind(plugin(), "1.0.8", services));
        assertFalse(broken.isBound());
        assertEquals(List.of(LuckyBinding.F_STREAKS), broken.missing());
        assertTrue(broken.features().isEmpty());
        assertFalse(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/lucky")));
    }

    @Test
    void entryOfAnotherShapeIsGuarded() throws Exception {
        binding.bind(plugin(), "1.0.8", services);
        // an object that is not a LuckyMiningInfo in the map makes the field read throw, the guard swallows it
        map.put(P, "not an entry");
        broke(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty());
        assertTrue(binding.features().contains(LuckyBinding.F_STREAKS), "one failure does not switch the feature off");
        map.remove(P);
    }
}
