package dev.ryvrook.trackerapi.mods;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.TrackerConfig;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModInfo;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binding infrastructure against the fake demo mod, loaded through an isolating loader as a live mod is. The
 * poll binds, binds again on a new instance, lets go when the mod is gone or switched off, and remembers a bind
 * that matched nothing. The bound binding turns the mod's events into TrackerAPI events, counters and facts, and
 * unbind removes its listener from the mod.
 */
class ModBindingsTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @TempDir
    Path dir;
    private Services services;
    private TrackerConfig config;
    private final Map<String, Object> enabled = new HashMap<>();
    private final ModBindings.Plugins plugins = new ModBindings.Plugins() {
        @Override
        public Object enabled(String pluginId) {
            return enabled.get(pluginId);
        }

        @Override
        public String version(Object plugin) {
            return "1.2.3";
        }
    };
    private DemoBinding binding;
    private ModBindings mods;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        config = TestSupport.config();
        services = Services.forTests(TestSupport.LOGGER, dir, config, TestSupport.DIRECT);
        services.counters.loadServer();
        services.counters.join(P, "Alder", 1L);
        services.players.join(P, "Alder", 1L).world = "default";
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        binding = new DemoBinding();
        mods = new ModBindings(List.of(binding), plugins);
        mods.attach(services);
    }

    @AfterEach
    void tearDown() {
        mods.stop();
    }

    private Object fakeApi(ClassLoader loader) throws Throwable {
        Class<?> api = Class.forName(DemoBinding.API, true, loader);
        MethodHandle get = Handles.statik(api, "get", api);
        return get.invoke();
    }

    private int listeners(Object api) throws Throwable {
        return Handles.intOf(Handles.virtual(api.getClass(), "listenerCount", int.class).invoke(api), -1);
    }

    private void fire(Object api, int from, int to) throws Throwable {
        Handles.virtual(api.getClass(), "fire", void.class, UUID.class, int.class, int.class).invoke(api, P, from, to);
    }

    @Test
    void pollBindsWhenTheModIsEnabledAndCountsItsEvents() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(loader));
        mods.poll();
        assertTrue(binding.isBound());
        assertEquals("1.2.3", binding.version());
        assertEquals(List.of(DemoBinding.F_LEVELS, DemoBinding.F_READS), binding.features());
        assertTrue(binding.missing().isEmpty());
        Object api = fakeApi(loader);
        assertEquals(1, listeners(api));
        assertEquals(7, binding.level(P));
        // the bind event
        assertEquals(1, seen.size());
        ModBound bound = (ModBound) seen.get(0);
        assertEquals("demo", bound.mod());
        assertTrue(bound.bound());
        assertNull(bound.player());

        fire(api, 7, 9);
        assertEquals(2, seen.size());
        ModLevelUp up = (ModLevelUp) seen.get(1);
        assertEquals(P, up.player());
        assertEquals("default", up.world());
        assertEquals(7, up.oldLevel());
        assertEquals(9, up.newLevel());
        assertEquals(9, services.counters.get(P, Keys.of("demo", ModKeys.LEVEL)));
        assertEquals(2, services.counters.get(P, Keys.of("demo", ModKeys.LEVEL_UPS)));
        assertEquals("9", services.players.get(P).facts.get("demo.level"));
        services.counters.saveAll();
        services.counters.boards().rebuild();
        assertTrue(services.counters.boardKeys().contains("demo:level"), "the binding asked for the board");
        assertEquals(2, binding.published());

        // the catalogue
        List<ModInfo> infos = mods.infos();
        assertEquals(1, infos.size());
        assertTrue(infos.get(0).bound());
        assertEquals("Demo Mod", infos.get(0).name());
        assertEquals(2, infos.get(0).stats().size());
        assertTrue(mods.statusLine().contains("Demo Mod 1.2.3 bound (2 features)"));
    }

    @Test
    void sameInstanceIsNotBoundAgainAndANewInstanceIs() throws Throwable {
        ClassLoader first = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(first));
        mods.poll();
        mods.poll();
        assertEquals(1, listeners(fakeApi(first)));
        assertEquals(1, seen.size());

        ClassLoader second = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(second));
        mods.poll();
        assertTrue(binding.isBound());
        assertEquals(0, listeners(fakeApi(first)), "the old instance lost its listener");
        assertEquals(1, listeners(fakeApi(second)));
        // unbound then bound again
        assertEquals(3, seen.size());
        assertFalse(((ModBound) seen.get(1)).bound());
        assertTrue(((ModBound) seen.get(2)).bound());
    }

    @Test
    void goneModIsLetGoAndFactsAreCleared() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(loader));
        mods.poll();
        Object api = fakeApi(loader);
        fire(api, 1, 2);
        assertEquals("2", services.players.get(P).facts.get("demo.level"));

        enabled.clear();
        mods.poll();
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, listeners(api));
        assertNull(services.players.get(P).facts.get("demo.level"));
        assertTrue(mods.statusLine().contains("Demo Mod not installed"));

        // a listener the mod kept would do nothing now
        int before = seen.size();
        fire(api, 2, 3);
        assertEquals(before, seen.size());
    }

    @Test
    void neverInTheConfigLeavesTheModAlone() throws Throwable {
        config.mods.put("demo", "never");
        enabled.put("Fakes:Demo", FakeMods.plugin(FakeMods.loader()));
        mods.poll();
        assertFalse(binding.isBound());
        assertTrue(mods.statusLine().contains("Demo Mod off"));
        config.mods.put("demo", "auto");
        mods.poll();
        assertTrue(binding.isBound());
        config.mods.put("demo", "never");
        mods.poll();
        assertFalse(binding.isBound());
    }

    @Test
    void partialBindListsTheMissingFeatureAndKeepsTheRest() throws Throwable {
        binding.breakListener = true;
        ClassLoader loader = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(loader));
        mods.poll();
        assertTrue(binding.isBound());
        assertEquals(List.of(DemoBinding.F_READS), binding.features());
        assertEquals(List.of(DemoBinding.F_LEVELS), binding.missing());
        assertEquals(0, listeners(fakeApi(loader)));
        assertEquals(7, binding.level(P));
        assertTrue(mods.statusLine().contains("1 of 2, missing level up listener"));
    }

    @Test
    void bindThatMatchesNothingIsRememberedForThatInstance() throws Throwable {
        ModBinding none = new AbstractBinding("demo", "Fakes:Demo", "Demo Mod") {
            int attempts;

            @Override
            public java.util.Set<EventKind> kinds() {
                return java.util.Set.of();
            }

            @Override
            public List<dev.ryvrook.trackerapi.api.StatSpec> stats() {
                return List.of();
            }

            @Override
            protected void resolve(Object plugin) {
                attempts++;
                feature("anything", () -> Handles.type(loader, "fakes.demo.Missing"));
            }

            @Override
            public String toString() {
                return Integer.toString(attempts);
            }
        };
        ModBindings m = new ModBindings(List.of(none), plugins);
        m.attach(services);
        Object plugin = FakeMods.plugin(FakeMods.loader());
        enabled.put("Fakes:Demo", plugin);
        m.poll();
        m.poll();
        m.poll();
        assertFalse(none.isBound());
        assertEquals("1", none.toString(), "one attempt for one instance");
        assertTrue(m.statusLine().contains("Demo Mod not matched"));
        enabled.put("Fakes:Demo", FakeMods.plugin(FakeMods.loader()));
        m.poll();
        assertEquals("2", none.toString(), "a new instance is tried once more");
        m.stop();
    }

    @Test
    void stopUnbindsEverything() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(loader));
        mods.poll();
        mods.stop();
        assertFalse(binding.isBound());
        assertEquals(0, listeners(fakeApi(loader)));
    }

    @Test
    void stopDuringAPollUndoesTheBindThatRacedIt() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        Object plugin = FakeMods.plugin(loader);
        ModBindings[] holder = new ModBindings[1];
        ModBindings.Plugins stopping = new ModBindings.Plugins() {
            @Override
            public Object enabled(String pluginId) {
                // TrackerAPI stops while the poll is between the lookup and the bind
                holder[0].stop();
                return plugin;
            }

            @Override
            public String version(Object p) {
                return "1.2.3";
            }
        };
        mods.stop();
        mods = new ModBindings(List.of(binding), stopping);
        holder[0] = mods;
        mods.attach(services);
        mods.poll();
        assertFalse(binding.isBound(), "the bind that raced the stop was undone");
        assertEquals(0, listeners(fakeApi(loader)), "nothing of TrackerAPI is left in the mod");
        assertEquals(0, mods.infos().stream().filter(ModInfo::bound).count());
        // a start arms the poll again
        mods.start();
        mods.stop();
    }

    @Test
    void stopDuringAPollEndsThePassBeforeTheNextBinding() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        Object plugin = FakeMods.plugin(loader);
        DemoBinding second = new DemoBinding();
        ModBindings[] holder = new ModBindings[1];
        int[] looked = {0};
        ModBindings.Plugins stopping = new ModBindings.Plugins() {
            @Override
            public Object enabled(String pluginId) {
                looked[0]++;
                if (looked[0] == 1) {
                    holder[0].stop();
                }
                return plugin;
            }

            @Override
            public String version(Object p) {
                return "1.2.3";
            }
        };
        mods.stop();
        mods = new ModBindings(List.of(binding, second), stopping);
        holder[0] = mods;
        mods.attach(services);
        mods.poll();
        assertEquals(1, looked[0], "the second binding was not looked up after the stop");
        assertFalse(binding.isBound());
        assertFalse(second.isBound());
    }

    @Test
    void resetRunsAtUnbindAndAgainBeforeTheNextBind() throws Throwable {
        List<String> calls = new ArrayList<>();
        AbstractBinding counting = new AbstractBinding("demo", "Fakes:Demo", "Demo Mod") {
            @Override
            public java.util.Set<EventKind> kinds() {
                return java.util.Set.of();
            }

            @Override
            public List<dev.ryvrook.trackerapi.api.StatSpec> stats() {
                return List.of();
            }

            @Override
            protected void resolve(Object plugin) {
                calls.add("resolve");
                feature("anything", () -> Handles.type(loader, DemoBinding.API));
            }

            @Override
            protected void reset() {
                calls.add(isBound() ? "reset while bound" : "reset");
            }
        };
        Object plugin = FakeMods.plugin(FakeMods.loader());
        assertTrue(counting.bind(plugin, "1", services));
        assertEquals(List.of("reset", "resolve"), calls, "the state is dropped before the bound flag is set");
        counting.unbind("done");
        assertEquals(List.of("reset", "resolve", "reset"), calls);
        assertTrue(counting.bind(plugin, "1", services));
        assertEquals(List.of("reset", "resolve", "reset", "reset", "resolve"), calls);
        counting.unbind("done");
    }

    @Test
    void unbindDropsTheModLoaderAndTheHandleCache() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(loader));
        mods.poll();
        assertSame(loader, binding.loader);
        // a uuid read of a mod object fills the class keyed cache
        Object event = Class.forName(DemoBinding.EVENT, true, loader).getConstructors()[0].newInstance(P, 1, 2);
        assertEquals(P, binding.uuidOf(event));
        assertEquals(1, binding.uuidGetterCacheSize());
        enabled.clear();
        mods.poll();
        assertFalse(binding.isBound());
        assertNull(binding.loader, "nothing pins the mod's loader after unbind");
        assertEquals(0, binding.uuidGetterCacheSize());
    }

    @Test
    void discoveryFamiliesAreDeclaredAsCountsPerIdInEveryBinding() {
        int families = 0;
        for (ModBinding b : ModBindings.all()) {
            Map<String, dev.ryvrook.trackerapi.api.StatSpec> totals = new HashMap<>();
            for (var spec : b.stats()) {
                if (!spec.perId()) {
                    totals.put(spec.key(), spec);
                }
            }
            for (var spec : b.stats()) {
                if (!spec.perId()) {
                    continue;
                }
                String stat = spec.key().substring(spec.key().indexOf(':') + 1);
                var total = totals.get(Keys.of(b.id(), ModKeys.plural(stat)));
                if (total != null && total.aggregate() == dev.ryvrook.trackerapi.api.StatSpec.Aggregate.COUNT) {
                    families++;
                    assertEquals(dev.ryvrook.trackerapi.api.StatSpec.Aggregate.COUNT, spec.aggregate(),
                            spec.key() + " is a discovery family, its per id spec must say COUNT like its total");
                    assertFalse(spec.board(), spec.key());
                }
            }
        }
        assertEquals(15, families, "class (rpg, mmo), quest, bounty, achievement (mmo, endgame), bestiary, rune (endgame, arcanum), wave, fish species, page and zone, song, instrument");
        var discovery = dev.ryvrook.trackerapi.api.StatSpec.discovery("rpg:class", "Class", "Class");
        assertEquals(dev.ryvrook.trackerapi.api.StatSpec.Aggregate.COUNT, discovery.aggregate());
        assertTrue(discovery.perId());
        assertFalse(discovery.board());
        assertFalse(Keys.MOD_PREFIXES.contains("endless"), "no binding exists for it, so no config key either");
    }

    @Test
    void failingCallbacksSwitchTheFeatureOff() throws Throwable {
        ClassLoader loader = FakeMods.loader();
        enabled.put("Fakes:Demo", FakeMods.plugin(loader));
        mods.poll();
        // the guard counts failures per feature and switches it off at the limit
        for (int i = 0; i < AbstractBinding.FAILURES_TO_OFF; i++) {
            binding.guardForTest(DemoBinding.F_READS, () -> {
                throw new IllegalStateException("boom");
            });
        }
        assertEquals(-1, binding.level(P));
        assertEquals(List.of(DemoBinding.F_READS), binding.missing());
        assertTrue(binding.has(DemoBinding.F_LEVELS));
    }

    @Test
    void isolatingLoaderGivesDistinctClasses() throws Exception {
        Class<?> a = Class.forName(DemoBinding.API, true, FakeMods.loader());
        Class<?> b = Class.forName(DemoBinding.API, true, FakeMods.loader());
        Class<?> here = Class.forName(DemoBinding.API);
        assertNotNull(a);
        assertFalse(a == b);
        assertFalse(a == here);
        assertSame(String.class, Class.forName("java.lang.String", true, FakeMods.loader()));
    }

    @Test
    void eventKindCountCoversTheNewKinds() {
        assertEquals(77, EventKind.COUNT);
        List<EventKind> high = new ArrayList<>();
        for (EventKind k : EventKind.values()) {
            if (k.highVolume()) {
                high.add(k);
            }
        }
        assertTrue(high.contains(EventKind.MOD_XP_GAINED));
        assertTrue(high.contains(EventKind.ENCHANTMENT_TRIGGERED));
        assertFalse(high.contains(EventKind.DUNGEON_RUN));
    }
}
