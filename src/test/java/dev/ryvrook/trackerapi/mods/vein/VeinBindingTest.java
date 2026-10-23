package dev.ryvrook.trackerapi.mods.vein;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.api.event.VeinMined;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Vein Mining binding against the fake mod loaded through the isolating loader. Flagged breaks group into
 * veins by the 250 ms gap, the idle tick and the leave close them, unflagged breaks are ignored, the ore count
 * is written, unbind drops the listener and a renamed flag leaves the feature missing.
 */
class VeinBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final VeinBinding.Names FAKE = new VeinBinding.Names("fakes.vein.MiningManager");

    @TempDir
    Path dir;
    private Services services;
    private VeinBinding binding;
    private ClassLoader loader;
    private ThreadLocal<Boolean> flag;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
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
        services.publisher.subscribe("test", java.util.Set.of(EventKind.VEIN_MINED), Delivery.SYNC, seen::add);
        loader = FakeMods.loader();
        Class<?> manager = Class.forName(FAKE.miningManager(), true, loader);
        flag = (ThreadLocal<Boolean>) manager.getField("IS_VEIN_MINING").get(null);
        binding = new VeinBinding(FAKE);
        binding.clock = clock::get;
    }

    @AfterEach
    void tearDown() {
        flag.set(false);
        binding.unbind("test over");
    }

    private Object plugin() throws Exception {
        return Class.forName("fakes.vein.VeinMiningPlugin", true, loader).getConstructor().newInstance();
    }

    private void flagged(UUID player, String block) {
        flag.set(true);
        try {
            services.publisher.publish(TestSupport.broken(player, block));
        } finally {
            flag.set(false);
        }
    }

    private void plain(UUID player, String block) {
        services.publisher.publish(TestSupport.broken(player, block));
    }

    private VeinMined vein(int i) {
        return (VeinMined) seen.get(i);
    }

    @Test
    void bindsThroughTheModLoaderAndListsTheFeature() throws Exception {
        assertTrue(binding.bind(plugin(), "2.5.0", services));
        assertEquals(List.of(VeinBinding.F_FLAG), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("2.5.0", binding.version());
        assertNotSame(Class.forName(FAKE.miningManager()), Class.forName(FAKE.miningManager(), true, loader),
                "the fake is isolated from the test classpath");
        assertEquals(5, binding.stats().size());
        assertTrue(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/vein")));
    }

    @Test
    void flaggedBreaksFormAVeinClosedByTheGap() throws Exception {
        binding.bind(plugin(), "2.5.0", services);
        flagged(P, "Ore_Copper_Stone");
        clock.addAndGet(5);
        flagged(P, "Ore_Copper_Stone");
        clock.addAndGet(5);
        flagged(P, "Rock_Stone");
        assertTrue(seen.isEmpty(), "the vein is still open");
        clock.addAndGet(VeinBinding.GAP_MILLIS);
        flagged(P, "Ore_Iron_Stone");
        assertEquals(1, seen.size());
        VeinMined v = vein(0);
        assertEquals(P, v.player());
        assertEquals("default", v.world());
        assertEquals("vein", v.mod());
        assertEquals("Ore_Copper_Stone", v.blockId());
        assertEquals(3, v.blocks());
        assertEquals(1, services.counters.get(P, Keys.of("vein", ModKeys.VEINS)));
        assertEquals(3, services.counters.get(P, Keys.of("vein", ModKeys.BLOCKS)));
        assertEquals(3, services.counters.get(P, ModKeys.key("vein", ModKeys.BLOCKS, "Ore_Copper_Stone")));
        assertEquals(3, services.counters.get(P, Keys.of("vein", ModKeys.LARGEST)));
        assertEquals(2, services.counters.get(P, Keys.of("vein", VeinBinding.ORE_BLOCKS)));
        assertEquals(1, binding.published());

        // the second vein is open with one block and closes on the tick once idle
        binding.tick();
        assertEquals(1, seen.size(), "not idle yet");
        clock.addAndGet(VeinBinding.GAP_MILLIS);
        binding.tick();
        assertEquals(2, seen.size());
        assertEquals("Ore_Iron_Stone", vein(1).blockId());
        assertEquals(1, vein(1).blocks());
        assertEquals(2, services.counters.get(P, Keys.of("vein", ModKeys.VEINS)));
        assertEquals(3, services.counters.get(P, Keys.of("vein", ModKeys.LARGEST)));
        assertEquals(3, services.counters.get(P, Keys.of("vein", VeinBinding.ORE_BLOCKS)));
        binding.tick();
        assertEquals(2, seen.size(), "a closed vein is gone");
    }

    @Test
    void unflaggedBreaksAreIgnoredAndPlayersAreKeptApart() throws Exception {
        binding.bind(plugin(), "2.5.0", services);
        plain(P, "Ore_Copper_Stone");
        plain(P, "Rock_Stone");
        clock.addAndGet(1_000);
        binding.tick();
        assertTrue(seen.isEmpty());
        assertEquals(0, services.counters.get(P, Keys.of("vein", ModKeys.BLOCKS)));

        flagged(P, "Ore_Gold_Stone");
        flagged(Q, "Rock_Stone");
        flagged(Q, "Rock_Stone");
        clock.addAndGet(1_000);
        binding.tick();
        assertEquals(2, seen.size());
        assertEquals(1, services.counters.get(P, Keys.of("vein", ModKeys.BLOCKS)));
        assertEquals(2, services.counters.get(Q, Keys.of("vein", ModKeys.BLOCKS)));
        assertEquals(1, services.counters.get(P, Keys.of("vein", VeinBinding.ORE_BLOCKS)));
        assertEquals(0, services.counters.get(Q, Keys.of("vein", VeinBinding.ORE_BLOCKS)));
    }

    @Test
    void crackedOreIsNotAnOre() {
        assertTrue(VeinBinding.isOre("Ore_Copper_Stone"));
        assertFalse(VeinBinding.isOre("Ore_Copper_Stone_Cracked"));
        assertFalse(VeinBinding.isOre("Rock_Stone"));
        assertFalse(VeinBinding.isOre(null));
    }

    @Test
    void leavingFlushesTheOpenVein() throws Exception {
        binding.bind(plugin(), "2.5.0", services);
        flagged(P, "Ore_Silver_Stone");
        flagged(P, "Ore_Silver_Stone");
        services.publisher.publish(new PlayerLeft(TestSupport.header("default", P), "Miner", 10L));
        assertEquals(1, seen.size());
        assertEquals(2, vein(0).blocks());
        clock.addAndGet(1_000);
        binding.tick();
        assertEquals(1, seen.size(), "nothing left to close");
    }

    @Test
    void unbindDropsTheListenerAndTheOpenVeins() throws Exception {
        binding.bind(plugin(), "2.5.0", services);
        flagged(P, "Ore_Copper_Stone");
        binding.unbind("gone");
        assertFalse(binding.isBound());
        assertFalse(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/vein")));
        clock.addAndGet(1_000);
        binding.tick();
        flagged(P, "Ore_Copper_Stone");
        assertTrue(seen.isEmpty(), "an open vein at unbind is dropped and nothing is read after");
    }

    @Test
    void renamedFlagLeavesTheFeatureMissing() throws Exception {
        VeinBinding broken = new VeinBinding(new VeinBinding.Names("fakes.vein.RenamedMiningManager"));
        assertFalse(broken.bind(plugin(), "2.5.0", services));
        assertFalse(broken.isBound());
        assertEquals(List.of(VeinBinding.F_FLAG), broken.missing());
        assertTrue(broken.features().isEmpty());
        assertFalse(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/vein")));
    }

    @Test
    void breakWithoutAPlayerIsHarmless() throws Exception {
        binding.bind(plugin(), "2.5.0", services);
        flag.set(true);
        try {
            services.publisher.publish(new BlockBroken(TestSupport.header("default", null), 1, 2, 3, "Ore_Copper_Stone",
                    null, false, false));
        } finally {
            flag.set(false);
        }
        assertTrue(seen.isEmpty());
        assertTrue(binding.features().contains(VeinBinding.F_FLAG));
    }
}
