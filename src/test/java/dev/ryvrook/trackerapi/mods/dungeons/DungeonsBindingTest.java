package dev.ryvrook.trackerapi.mods.dungeons;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.DungeonRun;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ModBossDefeated;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.counters.CounterRules;
import dev.ryvrook.trackerapi.mods.FakeMods;
import dev.ryvrook.trackerapi.mods.Handles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Major Dungeons binding against a fake of the bundled dungeon framework API, loaded through the isolating
 * loader as the live mod's classes would be. Entered, Completed and Exited become run phases with the duration,
 * mode and party size, a death inside the run turns the exit into a failure, a boss kill credits every attacker,
 * unbind removes the four consumers and the death subscription, and a renamed method costs one feature.
 */
class DungeonsBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final String FEAR = "Dungeon_Of_Fear_I";
    private static final String WORLD = "instance-Dungeon_Of_Fear_I-7b5a4d0e-5f9c-4a9c-9a3e-0f0d2c1b3a44";
    private static final String WORLD_2 = "instance-Dungeon_Of_Fear_II-1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
    static final DungeonsBinding.Names FAKE = new DungeonsBinding.Names(
            "fakes.dungeons.DungeonFrameworkAPIProvider", "fakes.dungeons.DungeonFrameworkAPI",
            "fakes.dungeons.DungeonEnteredEvent", "fakes.dungeons.DungeonExitedEvent",
            "fakes.dungeons.DungeonCompletedEvent", "fakes.dungeons.BossKilledEvent");
    static final DungeonsBinding.Names BROKEN = new DungeonsBinding.Names(
            "fakes.dungeons.broken.DungeonFrameworkAPIProvider", "fakes.dungeons.broken.DungeonFrameworkAPI",
            FAKE.entered(), FAKE.exited(), FAKE.completed(), FAKE.bossKilled());

    @TempDir
    Path dir;
    private Services services;
    private DungeonsBinding binding;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        for (UUID u : List.of(P, Q)) {
            services.counters.join(u, u == P ? "Alder" : "Birch", 1L);
            services.players.join(u, u == P ? "Alder" : "Birch", 1L).world = "default";
        }
        services.publisher.subscribe("test", Set.of(EventKind.DUNGEON_RUN, EventKind.MOD_BOSS_DEFEATED, EventKind.MOD_BOUND),
                Delivery.SYNC, seen::add);
        binding = new DungeonsBinding(FAKE);
        binding.clock = clock::get;
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
        services.publisher.close();
    }

    // ---- fake plumbing -----------------------------------------------------------------------------------

    private static Object plugin(ClassLoader loader, String name) throws ReflectiveOperationException {
        return Class.forName(name, true, loader).getConstructor().newInstance();
    }

    private static Object api(ClassLoader loader, DungeonsBinding.Names names) throws Throwable {
        Class<?> provider = Class.forName(names.provider(), true, loader);
        Class<?> apiType = Class.forName(names.api(), true, loader);
        return Handles.statik(provider, "get", apiType).invoke();
    }

    private static int listeners(Object api) throws Throwable {
        return Handles.intOf(Handles.virtual(api.getClass(), "listenerCount", int.class).invoke(api), -1);
    }

    private static void enter(Object api, UUID player, String world, String dungeon, String mode) throws Throwable {
        Handles.virtual(api.getClass(), "enter", void.class, UUID.class, String.class, String.class, String.class)
                .invoke(api, player, world, dungeon, mode);
    }

    private static void exit(Object api, UUID player, String world, String dungeon, String mode) throws Throwable {
        Handles.virtual(api.getClass(), "exit", void.class, UUID.class, String.class, String.class, String.class)
                .invoke(api, player, world, dungeon, mode);
    }

    private static void complete(Object api, UUID player, String world, String dungeon, String mode) throws Throwable {
        Handles.virtual(api.getClass(), "complete", void.class, UUID.class, String.class, String.class, String.class)
                .invoke(api, player, world, dungeon, mode);
    }

    private static void boss(Object api, String boss, String world, String dungeon, String mode, Set<UUID> attackers) throws Throwable {
        Handles.virtual(api.getClass(), "boss", void.class, String.class, String.class, String.class, String.class, Set.class)
                .invoke(api, boss, world, dungeon, mode, attackers);
    }

    private void die(UUID player, String world) {
        services.publisher.publish(new EntityDied(TestSupport.header(world, player), Actor.player(player, "Birch"),
                Actor.creature(null, "Cult_Knight"), "Physical", null, false, false, 0, 0, 0, false, List.of(), Set.of(), false,
                null, 0));
    }

    private List<DungeonRun> runs() {
        List<DungeonRun> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (e instanceof DungeonRun r) {
                out.add(r);
            }
        }
        return out;
    }

    private List<ModBossDefeated> bosses() {
        List<ModBossDefeated> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (e instanceof ModBossDefeated b) {
                out.add(b);
            }
        }
        return out;
    }

    private long counter(UUID player, String stat) {
        return services.counters.get(player, Keys.of(DungeonsBinding.ID, stat));
    }

    private long counter(UUID player, String stat, String id) {
        return services.counters.get(player, ModKeys.key(DungeonsBinding.ID, stat, id));
    }

    private boolean deathSubscriptionOpen() {
        for (Subscription s : services.publisher.subscriptions()) {
            if (s.owner().equals("mods/dungeons") && s.isActive()) {
                return true;
            }
        }
        return false;
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsEveryFeatureThroughTheModsLoader() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        assertTrue(binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services));
        assertEquals(List.of(DungeonsBinding.F_CORE, DungeonsBinding.F_RUNS, DungeonsBinding.F_BOSSES), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("0.4.14", binding.version());
        Object api = api(loader, FAKE);
        assertEquals(4, listeners(api));
        assertSame(api, binding.api());
        assertSame(loader, binding.api().getClass().getClassLoader());
        assertNotSame(Class.forName(FAKE.api()), Class.forName(FAKE.api(), true, loader));
        assertTrue(deathSubscriptionOpen());
        assertEquals(1, seen.size());
        ModBound bound = (ModBound) seen.get(0);
        assertEquals("dungeons", bound.mod());
        assertEquals("MAJOR76:MajorDungeons", bound.pluginId());
        assertTrue(bound.bound());
        for (StatSpec spec : binding.stats()) {
            if (spec.aggregate() == StatSpec.Aggregate.MAX) {
                assertTrue(CounterRules.isMax(spec.key()), spec.key());
            }
            if (spec.aggregate() == StatSpec.Aggregate.MIN) {
                assertTrue(CounterRules.isMin(spec.key()), spec.key());
            }
        }
    }

    @Test
    void aPartyRunIsEnteredOnceEachClearedWithDurationAndFailedAfterADeath() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        seen.clear();

        enter(api, P, WORLD, FEAR, "Default");
        clock.addAndGet(5_000L);
        enter(api, P, WORLD, FEAR, "Default");
        enter(api, Q, WORLD, FEAR, "Default");
        List<DungeonRun> runs = runs();
        assertEquals(2, runs.size(), "a reconnect does not open a new run");
        assertEquals(DungeonRun.Phase.ENTERED, runs.get(0).phase());
        assertEquals(P, runs.get(0).player());
        assertEquals(WORLD, runs.get(0).world());
        assertEquals(FEAR, runs.get(0).dungeonId());
        assertEquals("Default", runs.get(0).mode());
        assertEquals(1, runs.get(0).partySize());
        assertEquals(Q, runs.get(1).player());
        assertEquals(2, runs.get(1).partySize());
        assertEquals(1, counter(P, ModKeys.RUNS));
        assertEquals(1, counter(P, ModKeys.RUNS, FEAR));
        assertEquals(1, counter(Q, ModKeys.RUNS));
        assertEquals(1, binding.trackedWorlds());

        clock.addAndGet(60_000L);
        complete(api, P, WORLD, FEAR, "Default");
        exit(api, P, WORLD, FEAR, "Default");
        runs = runs();
        assertEquals(3, runs.size(), "an exit after the clear publishes nothing");
        DungeonRun cleared = runs.get(2);
        assertEquals(DungeonRun.Phase.COMPLETED, cleared.phase());
        assertEquals(P, cleared.player());
        assertEquals(65_000L, cleared.durationMillis());
        assertEquals(2, cleared.partySize());
        assertEquals("Default", cleared.mode());
        assertEquals(1, counter(P, ModKeys.CLEARED));
        assertEquals(1, counter(P, ModKeys.CLEARED, FEAR));
        assertEquals(1, counter(P, ModKeys.CLEARED_MODE, "Default"));
        assertEquals(65_000L, counter(P, ModKeys.FASTEST));
        assertEquals(65_000L, counter(P, ModKeys.FASTEST, FEAR));
        assertEquals(2, counter(P, DungeonsBinding.PARTY_BEST));
        assertEquals(1, binding.trackedWorlds(), "Q is still inside");
        services.counters.saveAll();
        services.counters.boards().rebuild();
        assertTrue(services.counters.boardKeys().contains("dungeons:party_best"), "the binding asked for the board");
        assertTrue(services.counters.boardKeys().contains("dungeons:fastest"));
        assertTrue(services.counters.boardKeys().contains("dungeons:cleared"));

        die(Q, WORLD);
        clock.addAndGet(1_000L);
        exit(api, Q, WORLD, FEAR, "Default");
        runs = runs();
        assertEquals(4, runs.size());
        DungeonRun failed = runs.get(3);
        assertEquals(DungeonRun.Phase.FAILED, failed.phase());
        assertEquals(Q, failed.player());
        assertEquals(61_000L, failed.durationMillis());
        assertEquals(1, counter(Q, ModKeys.FAILED));
        assertEquals(1, counter(Q, ModKeys.FAILED, FEAR));
        assertEquals(0, counter(Q, ModKeys.CLEARED));
        assertEquals(0, binding.trackedWorlds(), "the world is forgotten when the last player left");

        enter(api, Q, WORLD, FEAR, "Default");
        assertEquals(5, runs().size(), "a forgotten world starts a new run");
        assertEquals(2, counter(Q, ModKeys.RUNS));
    }

    @Test
    void leavingWithoutDyingIsLeftAndADeathElsewhereDoesNotCount() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        seen.clear();
        enter(api, P, WORLD, FEAR, "Hardcore");
        die(P, "default");
        die(Q, WORLD);
        clock.addAndGet(30_000L);
        exit(api, P, WORLD, FEAR, "Hardcore");
        List<DungeonRun> runs = runs();
        assertEquals(2, runs.size());
        assertEquals(DungeonRun.Phase.LEFT, runs.get(1).phase());
        assertEquals("Hardcore", runs.get(1).mode());
        assertEquals(30_000L, runs.get(1).durationMillis());
        assertEquals(1, runs.get(1).partySize());
        assertEquals(0, counter(P, ModKeys.FAILED));
        assertEquals(0, counter(P, ModKeys.CLEARED));
        assertEquals(0, binding.trackedWorlds());
        // an exit from a world never entered publishes nothing
        exit(api, P, WORLD_2, "Dungeon_Of_Fear_II", "Default");
        assertEquals(2, runs().size());
    }

    @Test
    void hardcoreAndEmptyModesAreCountedByName() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        enter(api, P, WORLD, FEAR, "Hardcore");
        clock.addAndGet(10_000L);
        complete(api, P, WORLD, FEAR, "Hardcore");
        exit(api, P, WORLD, FEAR, "Hardcore");
        enter(api, P, WORLD_2, "Dungeon_Of_Fear_II", "");
        clock.addAndGet(4_000L);
        complete(api, P, WORLD_2, "Dungeon_Of_Fear_II", null);
        List<DungeonRun> runs = runs();
        assertEquals("Hardcore", runs.get(1).mode());
        assertEquals("Default", runs.get(2).mode());
        assertEquals("Default", runs.get(3).mode());
        assertEquals(1, counter(P, ModKeys.CLEARED_MODE, "Hardcore"));
        assertEquals(1, counter(P, ModKeys.CLEARED_MODE, "Default"));
        assertEquals(2, counter(P, ModKeys.CLEARED));
        assertEquals(4_000L, counter(P, ModKeys.FASTEST));
        assertEquals(10_000L, counter(P, ModKeys.FASTEST, FEAR));
        assertEquals(4_000L, counter(P, ModKeys.FASTEST, "Dungeon_Of_Fear_II"));
        assertEquals(1, counter(P, DungeonsBinding.PARTY_BEST));
    }

    @Test
    void aCompletionWithoutAKnownEntryHasNoDurationOrParty() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        complete(api, P, WORLD, FEAR, "Default");
        DungeonRun r = runs().get(0);
        assertEquals(DungeonRun.Phase.COMPLETED, r.phase());
        assertEquals(0L, r.durationMillis());
        assertEquals(0, r.partySize());
        assertEquals(1, counter(P, ModKeys.CLEARED));
        assertEquals(0, counter(P, ModKeys.FASTEST), "no duration, no fastest");
        assertEquals(0, counter(P, DungeonsBinding.PARTY_BEST));
    }

    @Test
    void aBossKillCreditsEveryAttackerOnce() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        boss(api, "Azaroth", WORLD, FEAR, "Default", Set.of(P, Q));
        List<ModBossDefeated> bosses = bosses();
        assertEquals(2, bosses.size());
        assertEquals(Set.of(P, Q), Set.of(bosses.get(0).player(), bosses.get(1).player()));
        for (ModBossDefeated b : bosses) {
            assertEquals("Azaroth", b.bossId());
            assertEquals(FEAR, b.dungeonId());
            assertEquals(WORLD, b.world());
            assertEquals(2, b.participants().size());
            assertTrue(b.participants().containsAll(List.of(P, Q)));
        }
        assertEquals(1, counter(P, ModKeys.BOSSES));
        assertEquals(1, counter(P, ModKeys.BOSSES, "Azaroth"));
        assertEquals(1, counter(Q, ModKeys.BOSSES, "Azaroth"));
        // a boss outside a dungeon carries no dungeon id, a boss nobody hit credits nobody
        boss(api, "Baron", "default", null, "Default", Set.of(P));
        boss(api, "Katherina", WORLD, FEAR, "Default", Set.of());
        bosses = bosses();
        assertEquals(3, bosses.size());
        assertNull(bosses.get(2).dungeonId());
        assertEquals(2, counter(P, ModKeys.BOSSES));
    }

    @Test
    void unbindRemovesTheListenersClosesTheSubscriptionAndForgetsTheRuns() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        enter(api, P, WORLD, FEAR, "Default");
        assertEquals(1, binding.trackedWorlds());
        binding.unbind("test");
        assertFalse(binding.isBound());
        assertEquals(0, listeners(api));
        assertFalse(deathSubscriptionOpen());
        assertEquals(0, binding.trackedWorlds());
        assertNull(binding.api());
        int before = seen.size();
        // a consumer the mod kept would do nothing now
        Handles.virtual(api.getClass(), "fireRaw", void.class, Object.class).invoke(api, "anything");
        enter(api, Q, WORLD, FEAR, "Default");
        assertEquals(before, seen.size());
        assertEquals(0, counter(Q, ModKeys.RUNS));
    }

    @Test
    void renamedBossMethodGivesAPartialBind() throws Throwable {
        DungeonsBinding partial = new DungeonsBinding(BROKEN);
        partial.clock = clock::get;
        ClassLoader loader = FakeMods.loader();
        assertTrue(partial.bind(plugin(loader, "fakes.dungeons.broken.MajorDungeons"), "0.4.14", services));
        assertEquals(List.of(DungeonsBinding.F_CORE, DungeonsBinding.F_RUNS), partial.features());
        assertEquals(List.of(DungeonsBinding.F_BOSSES), partial.missing());
        Object api = api(loader, BROKEN);
        assertEquals(3, listeners(api));
        enter(api, P, WORLD, FEAR, "Default");
        clock.addAndGet(2_000L);
        complete(api, P, WORLD, FEAR, "Default");
        assertEquals(2, runs().size());
        assertEquals(2_000L, runs().get(1).durationMillis());
        boss(api, "Azaroth", WORLD, FEAR, "Default", Set.of(P));
        assertTrue(bosses().isEmpty());
        partial.unbind("test");
        assertEquals(0, listeners(api));
    }

    @Test
    void noFrameworkApiMeansNoBind() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        Object plugin = plugin(loader, "fakes.dungeons.MajorDungeons");
        Class<?> provider = Class.forName(FAKE.provider(), true, loader);
        Handles.statik(provider, "unbind", void.class).invoke();
        assertFalse(binding.bind(plugin, "0.4.14", services));
        assertFalse(binding.isBound());
        assertTrue(binding.features().isEmpty());
        assertEquals(List.of(DungeonsBinding.F_CORE), binding.missing());
        assertFalse(deathSubscriptionOpen());
    }

    @Test
    void aCallbackThatThrowsIsGuarded() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        binding.bind(plugin(loader, "fakes.dungeons.MajorDungeons"), "0.4.14", services);
        Object api = api(loader, FAKE);
        MethodHandle raw = Handles.virtual(api.getClass(), "fireRaw", void.class, Object.class);
        raw.invoke(api, "not an event");
        assertTrue(binding.features().contains(DungeonsBinding.F_RUNS), "one failure does not switch the feature off");
        assertTrue(runs().isEmpty());
        enter(api, P, WORLD, FEAR, "Default");
        assertEquals(1, runs().size(), "the listener still works");
        assertNotNull(runs().get(0).header());
    }
}
