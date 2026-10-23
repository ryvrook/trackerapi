package dev.ryvrook.trackerapi.mods.endgame;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.TrackerConfig;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.DungeonRun;
import dev.ryvrook.trackerapi.api.event.ModBossDefeated;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.mods.FakeMods;
import dev.ryvrook.trackerapi.mods.ModBindings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Endgame binding against the fake mod, loaded through the isolating loader as the live plugin's classes
 * are. Boss kills, achievements and dungeon entries arrive on the fake bus, trials through the callbacks proxy,
 * and the seeds come from the per player component at bind and on join. The test never names a fake type, it
 * reaches the fake through the loader as the binding does.
 */
class EndgameBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID R = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final String ROOT = "fakes.endgame";
    private static final EndgameBinding.Names NAMES = EndgameBinding.Names.inPackage(ROOT);
    /** The failure count at which the binding switches a feature off, as {@code AbstractBinding} has it. */
    private static final int FAILURES_TO_OFF = 20;
    private static final List<String> ALL_FEATURES = List.of(EndgameBinding.F_BOSSES, EndgameBinding.F_ACHIEVEMENTS,
            EndgameBinding.F_DUNGEONS, EndgameBinding.F_TRIALS, EndgameBinding.F_SEEDS);

    @TempDir
    Path dir;
    private Services services;
    private TrackerConfig config;
    /** No engine in tests, the binding is bound and unbound directly as the poll would. */
    private final ModBindings.Plugins plugins = new ModBindings.Plugins() {
        @Override
        public Object enabled(String pluginId) {
            return null;
        }

        @Override
        public String version(Object plugin) {
            return "6.0.4";
        }
    };
    /** The world seam, runs the seed on the calling thread for everyone online and counts the posts. */
    private final class FakeWorlds implements EndgameBinding.Worlds {
        int all;
        int one;

        @Override
        public void seedAll(java.util.function.Consumer<UUID> seed) {
            all++;
            for (var o : services.players.all()) {
                seed.accept(o.uuid);
            }
        }

        @Override
        public void seedOne(UUID uuid, java.util.function.Consumer<UUID> seed) {
            if (services.players.isOnline(uuid)) {
                one++;
                seed.accept(uuid);
            }
        }
    }

    private final FakeWorlds worlds = new FakeWorlds();
    private EndgameBinding binding;
    private ModBindings mods;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private ClassLoader loader;
    private Object plugin;

    @BeforeEach
    void setUp() throws Throwable {
        config = TestSupport.config();
        services = Services.forTests(TestSupport.LOGGER, dir, config, TestSupport.DIRECT);
        services.counters.loadServer();
        join(P, "Alder");
        join(Q, "Birch");
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        binding = new EndgameBinding(NAMES, worlds);
        mods = new ModBindings(List.of(binding), plugins);
        mods.attach(services);
        loader = FakeMods.loader();
        plugin = newFake(loader, NAMES.plugin());
    }

    @AfterEach
    void tearDown() {
        mods.stop();
    }

    // ---- reaching the fake through the loader -------------------------------------------------------------

    private void join(UUID uuid, String name) {
        services.counters.join(uuid, name, 1L);
        services.players.join(uuid, name, 1L).world = "default";
    }

    private static Object newFake(ClassLoader loader, String name, Object... args) throws Throwable {
        Class<?> type = Class.forName(name, true, loader);
        for (Constructor<?> c : type.getConstructors()) {
            if (c.getParameterCount() == args.length) {
                return c.newInstance(args);
            }
        }
        throw new NoSuchMethodException(name + " with " + args.length + " parameters");
    }

    private static Object call(Object target, String name, Object... args) throws Throwable {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && !Modifier.isStatic(m.getModifiers())) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private Object callStatic(String className, String name, Object... args) throws Throwable {
        Class<?> type = Class.forName(className, true, loader);
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && Modifier.isStatic(m.getModifiers())) {
                return m.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(className + "." + name);
    }

    private Object bus() throws Throwable {
        return call(plugin, "getGameEventBus");
    }

    private int listeners(String eventClass) throws Throwable {
        return (int) call(bus(), "listenerCount", Class.forName(eventClass, true, loader));
    }

    private int listeners() throws Throwable {
        return (int) call(bus(), "listenerCount");
    }

    private int callbacks() throws Throwable {
        return (int) callStatic(NAMES.arenaApi(), "callbackCount");
    }

    private void publish(String eventClass, Object... args) throws Throwable {
        call(bus(), "publish", newFake(loader, eventClass, args));
    }

    private void bossKill(String boss, UUID... credited) throws Throwable {
        publish(NAMES.bossKill(), Set.of(credited), credited[0], boss, "A boss", 120L, Set.of());
    }

    /** A component for the player on the fake plugin, with the given discovered creatures, runes and combo best. */
    private Object component(UUID uuid, List<String> discovered, List<String> runes, int comboBest) throws Throwable {
        Object comp = newFake(loader, NAMES.component());
        Object bestiary = call(comp, "getBestiaryState");
        for (String npc : discovered) {
            call(call(bestiary, "getOrCreate", npc), "markDiscovered");
        }
        Object runeState = call(comp, "getRuneCollection");
        for (String rune : runes) {
            call(runeState, "markFound", rune);
        }
        call(comp, "setComboPersonalBest", comboBest);
        call(plugin, "onPlayerComponentReady", uuid, comp);
        return comp;
    }

    private void bind() {
        binding.bind(plugin, "6.0.4", services);
    }

    private <T extends TrackerEvent> List<T> seen(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private long counter(UUID uuid, String key) {
        return services.counters.get(uuid, key);
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsEveryFeatureAndRegistersWithTheMod() throws Throwable {
        bind();
        assertTrue(binding.isBound());
        assertEquals("6.0.4", binding.version());
        assertEquals(ALL_FEATURES, binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals(1, listeners(NAMES.bossKill()));
        assertEquals(1, listeners(NAMES.achievementUnlock()));
        assertEquals(1, listeners(NAMES.dungeonEnter()));
        assertEquals(0, listeners(ROOT + ".plugin.events.domain.GameEvent$ComboTierChangeEvent"), "combo tiers are left out");
        assertEquals(3, listeners());
        assertEquals(1, callbacks());
        assertEquals(1, seen.size());
        ModBound bound = (ModBound) seen.get(0);
        assertEquals("endgame", bound.mod());
        assertEquals(EndgameBinding.PLUGIN_ID, bound.pluginId());
        assertTrue(bound.bound());
        assertTrue(mods.statusLine().contains("Endgame and QoL 6.0.4 bound (5 features)"));
        assertEquals(Set.of(EventKind.MOD_BOSS_DEFEATED, EventKind.MOD_MILESTONE, EventKind.DUNGEON_RUN), binding.kinds());
        // the boards asked for, listed once a key holds data
        bossKill("Boss_Frost_Dragon", P);
        callStatic(NAMES.arenaApi(), "fireWaveClear", P, "frozen_trial", 3, 5);
        services.counters.saveAll();
        services.counters.boards().rebuild();
        assertTrue(services.counters.boardKeys().contains("endgame:bosses"), "the binding asked for the board");
        assertTrue(services.counters.boardKeys().contains("endgame:wave_best"));
        assertTrue(services.counters.boardKeys().contains("endgame:waves"));
        assertFalse(services.counters.boardKeys().contains("endgame:bosses:Boss_Frost_Dragon"), "per id keys have no board");
    }

    @Test
    void isolatingLoaderGivesTheBindingDifferentClassesThanTheTest() throws Exception {
        Class<?> here = Class.forName(NAMES.bus());
        Class<?> there = Class.forName(NAMES.bus(), true, loader);
        assertNotSame(here, there);
        assertNotSame(Class.forName(NAMES.arenaApi()), Class.forName(NAMES.arenaApi(), true, loader));
    }

    @Test
    void bossKillIsOneDefeatPerCreditedPlayer() throws Throwable {
        bind();
        bossKill("Boss_Frost_Dragon", P, Q);
        List<ModBossDefeated> defeats = seen(ModBossDefeated.class);
        assertEquals(2, defeats.size());
        assertEquals(Set.of(P, Q), Set.of(defeats.get(0).player(), defeats.get(1).player()));
        for (ModBossDefeated d : defeats) {
            assertEquals("endgame", d.mod());
            assertEquals("Boss_Frost_Dragon", d.bossId());
            assertNull(d.dungeonId());
            assertEquals(2, d.participants().size());
            assertTrue(d.participants().containsAll(List.of(P, Q)));
            assertEquals("default", d.world());
        }
        assertEquals(1, counter(P, "endgame:bosses"));
        assertEquals(1, counter(P, "endgame:bosses:Boss_Frost_Dragon"));
        assertEquals(1, counter(Q, "endgame:bosses"));
        assertEquals(2, services.counters.serverCounters().get("endgame:bosses"));
        assertEquals(3, binding.published(), "the bind event and two defeats");
        // an event with nobody credited publishes nothing
        publish(NAMES.bossKill(), Set.of(), null, "Boss_Hedera", "Hedera", 10L, Set.of());
        assertEquals(2, seen(ModBossDefeated.class).size());
    }

    @Test
    void achievementUnlockIsAMilestoneCountedOnce() throws Throwable {
        bind();
        publish(NAMES.achievementUnlock(), P, "boss_frost_dragon", "Frost Dragon Slayer");
        List<ModMilestone> milestones = seen(ModMilestone.class);
        assertEquals(1, milestones.size());
        ModMilestone m = milestones.get(0);
        assertEquals(P, m.player());
        assertEquals("endgame", m.mod());
        assertEquals("achievement", m.category());
        assertEquals("boss_frost_dragon", m.id());
        assertEquals("Frost Dragon Slayer", m.name());
        assertEquals(1, counter(P, "endgame:achievement:boss_frost_dragon"));
        assertEquals(1, counter(P, "endgame:achievements"));
        publish(NAMES.achievementUnlock(), P, "boss_frost_dragon", "Frost Dragon Slayer");
        assertEquals(1, counter(P, "endgame:achievements"), "a repeat is not a second achievement");
        publish(NAMES.achievementUnlock(), P, "mine_first", "First Ore");
        assertEquals(2, counter(P, "endgame:achievements"));
    }

    @Test
    void dungeonEntryIsARunEntered() throws Throwable {
        bind();
        publish(NAMES.dungeonEnter(), Q, "frozen_dungeon");
        List<DungeonRun> runs = seen(DungeonRun.class);
        assertEquals(1, runs.size());
        DungeonRun run = runs.get(0);
        assertEquals(Q, run.player());
        assertEquals("frozen_dungeon", run.dungeonId());
        assertEquals(DungeonRun.Phase.ENTERED, run.phase());
        assertNull(run.mode());
        assertEquals(0, run.partySize());
        assertEquals(0L, run.durationMillis());
        assertEquals(1, counter(Q, "endgame:runs"));
        assertEquals(1, counter(Q, "endgame:runs:frozen_dungeon"));
    }

    @Test
    void comboTierChangesAreIgnored() throws Throwable {
        bind();
        publish(ROOT + ".plugin.events.domain.GameEvent$ComboTierChangeEvent", P, 0, 1, 3);
        assertEquals(1, seen.size(), "only the bind event");
    }

    @Test
    void trialWavesCompletionAndFailureBecomeMilestonesAndRuns() throws Throwable {
        long[] now = {1_000_000L};
        binding.trials.clock(() -> now[0]);
        bind();
        String arena = "frozen_trial";
        callStatic(NAMES.arenaApi(), "fireCountdown", P, arena, 5);
        callStatic(NAMES.arenaApi(), "fireWaveStart", P, arena, 1, 5);
        now[0] += 30_000L;
        callStatic(NAMES.arenaApi(), "fireWaveClear", P, arena, 1, 5);
        callStatic(NAMES.arenaApi(), "fireMobKilled", P, arena);
        callStatic(NAMES.arenaApi(), "fireWaveStart", P, arena, 2, 5);
        now[0] += 45_000L;
        callStatic(NAMES.arenaApi(), "fireWaveClear", P, arena, 2, 5);
        List<ModMilestone> waves = seen(ModMilestone.class);
        assertEquals(2, waves.size());
        assertEquals("wave", waves.get(0).category());
        assertEquals(arena + "/1", waves.get(0).id());
        assertEquals(arena + " wave 1", waves.get(0).name());
        assertEquals(arena + "/2", waves.get(1).id());
        assertEquals(1, counter(P, "endgame:wave:" + arena + "/1"));
        assertEquals(2, counter(P, "endgame:waves"));
        assertEquals(2, counter(P, "endgame:wave_best"));

        callStatic(NAMES.arenaApi(), "fireCompleted", P, arena, 2);
        List<DungeonRun> runs = seen(DungeonRun.class);
        assertEquals(1, runs.size());
        DungeonRun done = runs.get(0);
        assertEquals(P, done.player());
        assertEquals(arena, done.dungeonId());
        assertEquals(DungeonRun.Phase.COMPLETED, done.phase());
        assertNull(done.mode());
        assertEquals(75_000L, done.durationMillis());
        assertEquals(1, counter(P, "endgame:cleared"));
        assertEquals(1, counter(P, "endgame:cleared:" + arena));
        assertEquals(75_000L, counter(P, "endgame:fastest"));
        assertEquals(75_000L, counter(P, "endgame:fastest:" + arena));
        assertEquals(0, binding.trials.size(), "the clock is dropped at the end");

        // a second run that fails, with its own time
        callStatic(NAMES.arenaApi(), "fireWaveStart", P, arena, 1, 5);
        now[0] += 20_000L;
        callStatic(NAMES.arenaApi(), "fireFailed", P, arena, 1, "PLAYER_DEATH");
        runs = seen(DungeonRun.class);
        assertEquals(2, runs.size());
        DungeonRun failed = runs.get(1);
        assertEquals(DungeonRun.Phase.FAILED, failed.phase());
        assertEquals(arena, failed.dungeonId());
        assertEquals(20_000L, failed.durationMillis());
        assertEquals(1, counter(P, "endgame:failed"));
        assertEquals(1, counter(P, "endgame:failed:" + arena));
        assertEquals(75_000L, counter(P, "endgame:fastest"), "a failure does not touch the best");

        // a run the binding did not see start has no duration, and a cleanup drops a clock
        callStatic(NAMES.arenaApi(), "fireCompleted", Q, arena, 5);
        assertEquals(0L, seen(DungeonRun.class).get(2).durationMillis());
        callStatic(NAMES.arenaApi(), "fireWaveStart", Q, arena, 1, 5);
        assertEquals(1, binding.trials.size());
        callStatic(NAMES.arenaApi(), "fireOwnerChanged", Q, P, arena);
        callStatic(NAMES.arenaApi(), "fireCleanup", P);
        assertEquals(0, binding.trials.size());
    }

    @Test
    void seedsAtBindAndOnJoinPublishOnlyWhatIsNew() throws Throwable {
        component(P, List.of("Goblin_Scrapper", "Trork_Warrior"), List.of("rune_frost"), 12);
        Object undiscovered = component(R, List.of(), List.of(), 0);
        call(call(call(undiscovered, "getBestiaryState"), "getOrCreate", "Yeti"), "recordKill");
        bind();
        List<ModMilestone> seeded = seen(ModMilestone.class);
        assertEquals(3, seeded.size(), "two creatures and one rune for the online player with data");
        Map<String, Integer> byCategory = new HashMap<>();
        for (ModMilestone m : seeded) {
            assertEquals(P, m.player());
            byCategory.merge(m.category(), 1, Integer::sum);
        }
        assertEquals(Map.of("bestiary", 2, "rune", 1), byCategory);
        assertEquals(1, worlds.all, "the bind time seed went to the worlds once");
        assertEquals(0, worlds.one);
        assertEquals(1, counter(P, "endgame:bestiary:Goblin_Scrapper"));
        assertEquals(1, counter(P, "endgame:bestiary:Trork_Warrior"));
        assertEquals(2, counter(P, "endgame:bestiaries"));
        assertEquals(1, counter(P, "endgame:rune:rune_frost"));
        assertEquals(1, counter(P, "endgame:runes"));
        assertEquals(12, counter(P, "endgame:combo_best"));
        assertEquals(0, counter(Q, "endgame:bestiaries"), "no component, nothing seeded");

        // a join seeds the joining player, and a repeat of a known player adds nothing
        int before = seen.size();
        component(Q, List.of("Goblin_Scrapper"), List.of(), 4);
        services.publisher.publish(new PlayerJoined(TestSupport.header("default", Q), "Birch", false));
        services.publisher.publish(new PlayerJoined(TestSupport.header("default", P), "Alder", false));
        services.dispatcher.drainHere();
        assertEquals(2, worlds.one, "each join posted one seed to the player's world");
        List<ModMilestone> afterJoin = seen(ModMilestone.class).subList(3, seen(ModMilestone.class).size());
        assertEquals(1, afterJoin.size());
        assertEquals(Q, afterJoin.get(0).player());
        assertEquals("bestiary", afterJoin.get(0).category());
        assertEquals(1, counter(Q, "endgame:bestiaries"));
        assertEquals(4, counter(Q, "endgame:combo_best"));
        assertEquals(12, counter(P, "endgame:combo_best"));
        assertEquals(2, counter(P, "endgame:bestiaries"), "the repeat changed nothing");
        assertEquals(before + 2 + 1, seen.size(), "two joins and one new milestone");

        // a lower combo best never lowers the counter
        call(call(plugin, "getPlayerComponent", P), "setComboPersonalBest", 3);
        services.publisher.publish(new PlayerJoined(TestSupport.header("default", P), "Alder", false));
        services.dispatcher.drainHere();
        assertEquals(12, counter(P, "endgame:combo_best"));
    }

    @Test
    void seedWaitsForThePlayerFileInsteadOfPublishingEveryMilestoneAgain() throws Throwable {
        bind();
        component(Q, List.of("Goblin_Scrapper"), List.of("rune_frost"), 6);
        // Q's counter file is still being read, the counters answer 0 for everything until the merge
        services.counters.live(Q).setMerged(false);
        services.publisher.publish(new PlayerJoined(TestSupport.header("default", Q), "Birch", false));
        services.dispatcher.drainHere();
        assertTrue(seen(ModMilestone.class).isEmpty(), "nothing is published while the file is unread");
        assertEquals(0, counter(Q, "endgame:combo_best"));
        assertEquals(1, binding.deferredSeeds());
        // the timer fires before the merge landed, the seed waits on
        binding.retryDeferred();
        assertTrue(seen(ModMilestone.class).isEmpty());
        assertEquals(1, binding.deferredSeeds());
        // the merge lands, the next retry seeds
        services.counters.live(Q).setMerged(true);
        services.counters.live(Q).set("endgame:bestiary:Goblin_Scrapper", 1L);
        binding.retryDeferred();
        List<ModMilestone> seeded = seen(ModMilestone.class);
        assertEquals(1, seeded.size(), "the creature was in the file, only the rune is new");
        assertEquals("rune", seeded.get(0).category());
        assertEquals(Q, seeded.get(0).player());
        assertEquals(6, counter(Q, "endgame:combo_best"));
        assertEquals(0, binding.deferredSeeds());
        // a retry with nothing deferred is harmless, and unbind drops the list
        binding.retryDeferred();
        services.counters.live(Q).setMerged(false);
        services.publisher.publish(new PlayerJoined(TestSupport.header("default", Q), "Birch", false));
        services.dispatcher.drainHere();
        assertEquals(1, binding.deferredSeeds());
        binding.unbind("gone");
        assertEquals(0, binding.deferredSeeds());
    }

    @Test
    void unbindRemovesEveryRegistrationAndTheJoinSubscription() throws Throwable {
        bind();
        assertEquals(3, listeners());
        assertEquals(1, callbacks());
        int subscriptions = services.publisher.subscriptions().size();
        binding.unbind("the mod is gone");
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, listeners());
        assertEquals(0, callbacks());
        assertEquals(subscriptions - 1, services.publisher.subscriptions().size(), "the join subscription is closed");
        assertFalse(mods.statusLine().contains("bound"));
        // the unbind event, then nothing more
        int before = seen.size();
        assertFalse(((ModBound) seen.get(before - 1)).bound());
        bossKill("Boss_Hedera", P);
        callStatic(NAMES.arenaApi(), "fireCompleted", P, "frozen_trial", 5);
        assertEquals(before, seen.size());
    }

    @Test
    void reloadMovesTheListenersToTheNewInstance() throws Throwable {
        bind();
        Object old = plugin;
        ClassLoader second = FakeMods.loader();
        plugin = newFake(second, NAMES.plugin());
        loader = second;
        bind();
        assertTrue(binding.isBound());
        assertEquals(3, seen(ModBound.class).size(), "bound, let go, bound again");
        assertFalse(seen(ModBound.class).get(1).bound());
        assertEquals(0, (int) call(call(old, "getGameEventBus"), "listenerCount"), "the old instance lost its listeners");
        assertEquals(3, listeners());
        assertEquals(1, callbacks());
    }

    @Test
    void partialBindListsTheMissingFeatureAndKeepsTheRest() throws Throwable {
        EndgameBinding.Names broken = new EndgameBinding.Names(NAMES.plugin(), NAMES.bus(), NAMES.bossKill(),
                ROOT + ".plugin.events.domain.GameEvent$Missing", NAMES.dungeonEnter(), NAMES.arenaApi(),
                NAMES.arenaCallbacks(), NAMES.component(), NAMES.bestiaryState(), NAMES.bestiaryEntry(), NAMES.runeState());
        mods.stop();
        binding = new EndgameBinding(broken, worlds);
        mods = new ModBindings(List.of(binding), plugins);
        mods.attach(services);
        bind();
        assertTrue(binding.isBound());
        assertEquals(List.of(EndgameBinding.F_BOSSES, EndgameBinding.F_DUNGEONS, EndgameBinding.F_TRIALS, EndgameBinding.F_SEEDS),
                binding.features());
        assertEquals(List.of(EndgameBinding.F_ACHIEVEMENTS), binding.missing());
        assertEquals(2, listeners());
        assertEquals(0, listeners(NAMES.achievementUnlock()));
        assertTrue(mods.statusLine().contains("4 of 5, missing achievement events"));
        bossKill("Boss_Frost_Dragon", P);
        assertEquals(1, seen(ModBossDefeated.class).size());
    }

    @Test
    void waveArenaEngineDownLeavesTrialsMissing() throws Throwable {
        callStatic(NAMES.arenaApi(), "setAvailable", false);
        bind();
        assertTrue(binding.isBound());
        assertEquals(List.of(EndgameBinding.F_TRIALS), binding.missing());
        assertEquals(0, callbacks());
        assertEquals(3, listeners());
    }

    @Test
    void busThatCannotBeReachedLeavesTheBusFeaturesMissing() throws Throwable {
        EndgameBinding.Names broken = new EndgameBinding.Names(NAMES.plugin(), ROOT + ".plugin.events.domain.NoBus",
                NAMES.bossKill(), NAMES.achievementUnlock(), NAMES.dungeonEnter(), NAMES.arenaApi(),
                NAMES.arenaCallbacks(), NAMES.component(), NAMES.bestiaryState(), NAMES.bestiaryEntry(), NAMES.runeState());
        mods.stop();
        binding = new EndgameBinding(broken, worlds);
        mods = new ModBindings(List.of(binding), plugins);
        mods.attach(services);
        bind();
        assertTrue(binding.isBound());
        assertEquals(List.of(EndgameBinding.F_TRIALS, EndgameBinding.F_SEEDS), binding.features());
        assertEquals(List.of(EndgameBinding.F_BOSSES, EndgameBinding.F_ACHIEVEMENTS, EndgameBinding.F_DUNGEONS), binding.missing());
        assertEquals(0, listeners());
    }

    @Test
    void failingSeedIsGuardedAndSwitchesTheFeatureOffAtTheLimit() throws Throwable {
        bind();
        component(Q, List.of(), List.of(), -1);
        for (int i = 0; i < FAILURES_TO_OFF - 1; i++) {
            services.publisher.publish(new PlayerJoined(TestSupport.header("default", Q), "Birch", false));
        }
        services.dispatcher.drainHere();
        assertTrue(binding.isBound());
        assertTrue(binding.missing().isEmpty(), "under the limit the feature stays");
        services.publisher.publish(new PlayerJoined(TestSupport.header("default", Q), "Birch", false));
        services.dispatcher.drainHere();
        assertEquals(List.of(EndgameBinding.F_SEEDS), binding.missing());
        assertTrue(binding.features().contains(EndgameBinding.F_BOSSES));
        bossKill("Boss_Frost_Dragon", P);
        assertEquals(1, seen(ModBossDefeated.class).size(), "the other features still work");
    }

    @Test
    void statsNameEveryKeyTheEventsProduce() {
        List<String> keys = new ArrayList<>();
        for (StatSpec spec : binding.stats()) {
            keys.add(spec.key() + (spec.perId() ? ":<id>" : ""));
        }
        assertTrue(keys.containsAll(List.of("endgame:bosses", "endgame:bosses:<id>", "endgame:achievements",
                "endgame:achievement:<id>", "endgame:runs", "endgame:runs:<id>", "endgame:cleared", "endgame:cleared:<id>",
                "endgame:fastest", "endgame:fastest:<id>", "endgame:failed", "endgame:failed:<id>", "endgame:waves",
                "endgame:wave:<id>", "endgame:wave_best", "endgame:bestiaries", "endgame:bestiary:<id>", "endgame:runes",
                "endgame:rune:<id>", "endgame:combo_best")), keys.toString());
        assertEquals(20, keys.size());
        for (StatSpec spec : binding.stats()) {
            assertFalse(spec.label().contains(":"), spec.label());
            if (spec.key().endsWith("_best")) {
                assertEquals(StatSpec.Aggregate.MAX, spec.aggregate(), spec.key());
            }
        }
    }
}
