package dev.ryvrook.trackerapi.mods.arcanum;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.mods.FakeMods;
import dev.ryvrook.trackerapi.mods.Handles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Arcanum binding against the fake mod under {@code fakes.arcanum}, loaded through the isolating loader. The
 * bind lists both features, registers one cast listener with the mod's event class and reads the runes of every
 * online player, a completed cast becomes a spell cast with its energy and bests, failures count by stage, the
 * rune reads publish new runes only and respect the per player gap, unbind removes the listener, a renamed method
 * gives a partial bind and a throwing read is guarded.
 */
class ArcanumBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final String FAKE_MOD = "fakes.arcanum";
    private static final String EVENT = "fakes.arcanum.cad.casting.CadCastOutcomeEvent";
    private static final String PROVIDER = "fakes.arcanum.internal.ArcanumApiProvider";

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final FakeEngine engine = new FakeEngine();
    private ArcanumBinding binding;
    private ClassLoader loader;
    private Object plugin;
    private long now = 1_000_000_000L;

    /** The engine stand in, one listener list and reads run on the calling thread with a bare ref. */
    final class FakeEngine implements ArcanumEngine {
        final List<Object[]> listeners = new ArrayList<>();
        final Map<UUID, Ref<EntityStore>> refs = new HashMap<>();
        final Map<String, String> items = new HashMap<>();
        int reads;

        Ref<EntityStore> refOf(UUID uuid) {
            return refs.computeIfAbsent(uuid, u -> new Ref<>(null, refs.size() + 1));
        }

        @Override
        public Runnable listen(Class<?> eventClass, Object preparedType, CastListener listener) {
            Object[] entry = {eventClass, preparedType, listener};
            listeners.add(entry);
            return () -> listeners.remove(entry);
        }

        void fire(Object event, UUID player, Object prepared) {
            for (Object[] l : List.copyOf(listeners)) {
                if (l[0] == event.getClass()) {
                    ((CastListener) l[2]).onCast(event, player, prepared, (section, slot) -> items.get(section + "/" + slot));
                }
            }
        }

        @Override
        public void readAll(Reader reader) {
            for (var o : services.players.all()) {
                read(o.uuid, reader);
            }
        }

        @Override
        public void read(UUID uuid, Reader reader) {
            if (!services.players.isOnline(uuid)) {
                return;
            }
            reads++;
            reader.read(uuid, refOf(uuid), null);
        }
    }

    @BeforeEach
    void setUp() throws Throwable {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        services.counters.join(P, "Alder", 1L);
        services.players.join(P, "Alder", 1L).world = "default";
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        binding = new ArcanumBinding(new ArcanumNames(FAKE_MOD, engine));
        binding.clock = () -> now;
        loader = FakeMods.loader();
        plugin = Class.forName("fakes.arcanum.ArcanumPlugin", true, loader).getConstructor().newInstance();
        engine.items.put("-1/3", "Copper_Cad");
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private void bind() {
        assertTrue(binding.bind(plugin, "0.6.3", services));
    }

    private Object outcome(String factory) throws Throwable {
        Class<?> type = Class.forName(EVENT, true, loader);
        return type.getMethod(factory).invoke(null);
    }

    private Object prepared(String name, int energy, int complexity, int runes) throws Throwable {
        Class<?> node = Class.forName("fakes.arcanum.spell.compile.CompiledNode", true, loader);
        Class<?> stats = Class.forName("fakes.arcanum.spell.compile.CompiledSpellStats", true, loader);
        Class<?> spell = Class.forName("fakes.arcanum.spell.compile.CompiledSpell", true, loader);
        Class<?> component = Class.forName("fakes.arcanum.cad.casting.PreparedCadCastComponent", true, loader);
        Map<Object, Object> nodes = new LinkedHashMap<>();
        for (int i = 0; i < runes; i++) {
            nodes.put(i, node.getConstructors()[0].newInstance(i, "arcanum:damage", 1));
        }
        Object st = stats.getConstructors()[0].newInstance(complexity, 1, 1, 1, 1.0);
        Object sp = spell.getConstructors()[0].newInstance(name, nodes, st);
        Constructor<?> c = component.getConstructors()[0];
        return c.newInstance(-1, (short) 3, sp, name, energy);
    }

    private void learn(UUID uuid, String piece) throws Throwable {
        Class<?> provider = Class.forName(PROVIDER, true, loader);
        provider.getMethod("learn", Ref.class, String.class).invoke(null, engine.refOf(uuid), piece);
    }

    private void failing(boolean on) throws Throwable {
        Class.forName(PROVIDER, true, loader).getMethod("setFailing", boolean.class).invoke(null, on);
    }

    private <T extends TrackerEvent> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private long counter(String key) {
        return services.counters.get(P, key);
    }

    private void later() {
        now += TimeUnit.SECONDS.toNanos(ArcanumBinding.POLL_SECONDS);
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsBothFeaturesRegistersTheListenerAndReadsTheRunes() throws Throwable {
        bind();
        assertEquals(List.of(ArcanumBinding.F_CASTS, ArcanumBinding.F_RUNES), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("0.6.3", binding.version());
        assertEquals(1, engine.listeners.size());
        Class<?> preparedClass = Class.forName("fakes.arcanum.cad.casting.PreparedCadCastComponent", true, loader);
        assertSame(Handles.statikByName(preparedClass, "getComponentType", 0).invoke(), engine.listeners.get(0)[1],
                "the component type of the mod's loader is handed to the listener");
        assertEquals(1, engine.reads);
        List<ModMilestone> runes = of(ModMilestone.class);
        assertEquals(Set.of("damage", "heal", "connector"), new TreeSet<>(runes.stream().map(ModMilestone::id).toList()));
        assertEquals("rune", runes.get(0).category());
        assertEquals(3, counter("arcanum:runes"));
        assertEquals(1, counter("arcanum:rune:damage"));
        assertEquals("3", services.players.get(P).facts.get("arcanum.runes"));
        for (StatSpec spec : binding.stats()) {
            assertTrue(spec.key().startsWith("arcanum:"), spec.key());
        }
        services.counters.saveAll();
        services.counters.boards().rebuild();
        assertTrue(services.counters.boardKeys().contains("arcanum:runes"));
    }

    @Test
    void completedCastsBecomeSpellCastsWithEnergyAndBests() throws Throwable {
        bind();
        engine.fire(outcome("completionSuccess"), P, prepared("Arc Cascade!", 120, 7, 3));
        List<ModAbilityUsed> casts = of(ModAbilityUsed.class);
        assertEquals(1, casts.size());
        assertEquals("arc_cascade", casts.get(0).abilityId());
        assertEquals("Copper_Cad", casts.get(0).itemId());
        assertNull(casts.get(0).targetTypeId());
        assertEquals(P, casts.get(0).player());
        assertEquals("default", casts.get(0).world());
        assertEquals(1, counter("arcanum:abilities"));
        assertEquals(1, counter("arcanum:abilities:arc_cascade"));
        assertEquals(120, counter("arcanum:energy"));
        assertEquals(120, counter("arcanum:energy_best"));
        assertEquals(7, counter("arcanum:complexity_best"));
        assertEquals(3, counter("arcanum:size_best"));
        assertEquals(0, counter("arcanum:complexity"), "a best only tally adds nothing");
        assertEquals(3, of(ModTally.class).size());

        // a smaller spell adds energy and leaves the bests, which are not published again
        engine.fire(outcome("completionSuccess"), P, prepared("Spark", 80, 2, 1));
        assertEquals(200, counter("arcanum:energy"));
        assertEquals(120, counter("arcanum:energy_best"));
        assertEquals(7, counter("arcanum:complexity_best"));
        assertEquals(3, counter("arcanum:size_best"));
        assertEquals(4, of(ModTally.class).size());
        assertEquals(1, counter("arcanum:abilities:spark"));
        assertEquals(2, counter("arcanum:abilities"));

        // a bigger one raises them
        engine.fire(outcome("completionSuccess"), P, prepared("Arc Cascade", 300, 9, 5));
        assertEquals(300, counter("arcanum:energy_best"));
        assertEquals(9, counter("arcanum:complexity_best"));
        assertEquals(5, counter("arcanum:size_best"));
        assertEquals(2, counter("arcanum:abilities:arc_cascade"));
    }

    @Test
    void failuresCountByStageAndACastWithoutItsComponentIsUnknown() throws Throwable {
        bind();
        engine.fire(outcome("preparationFailure"), P, null);
        engine.fire(outcome("completionFailure"), P, prepared("Spark", 80, 2, 1));
        engine.fire(outcome("completionFailure"), P, null);
        assertEquals(3, counter("arcanum:failed"));
        assertEquals(1, counter("arcanum:failed:prepare"));
        assertEquals(2, counter("arcanum:failed:cast"));
        assertTrue(of(ModAbilityUsed.class).isEmpty(), "a failure is no cast");
        assertEquals(0, counter("arcanum:energy"));

        engine.fire(outcome("completionSuccess"), P, null);
        List<ModAbilityUsed> casts = of(ModAbilityUsed.class);
        assertEquals(1, casts.size());
        assertEquals("unknown", casts.get(0).abilityId());
        assertNull(casts.get(0).itemId());
        assertEquals(1, counter("arcanum:abilities:unknown"));
        // a cast of an entity that is no player counts for nothing
        engine.fire(outcome("completionSuccess"), null, prepared("Spark", 80, 2, 1));
        assertEquals(1, counter("arcanum:abilities"));
    }

    @Test
    void spellNamesAreNormalisedAndCappedPerPlayer() throws Throwable {
        assertEquals("fire_ball", ArcanumBinding.normalise("  Fire--Ball  "));
        assertEquals("unnamed", ArcanumBinding.normalise(""));
        assertEquals("unnamed", ArcanumBinding.normalise("!!!"));
        assertEquals("unnamed", ArcanumBinding.normalise(null));
        assertEquals("n_code_2", ArcanumBinding.normalise("Ünïcode 2"));
        String longName = "a".repeat(30) + " " + "b".repeat(30);
        String key = ArcanumBinding.normalise(longName);
        assertEquals(ArcanumBinding.SPELL_KEY_LENGTH, key.length());
        assertTrue(key.matches("[a-z0-9_]+"));
        assertFalse(ArcanumBinding.normalise("a".repeat(47) + " b").endsWith("_"));

        bind();
        for (int i = 0; i < ArcanumBinding.SPELL_KEYS_PER_PLAYER; i++) {
            engine.fire(outcome("completionSuccess"), P, prepared("Spell " + i, 10, 1, 1));
        }
        engine.fire(outcome("completionSuccess"), P, prepared("One Too Many", 10, 1, 1));
        engine.fire(outcome("completionSuccess"), P, prepared("Spell 0", 10, 1, 1));
        assertEquals(1, counter("arcanum:abilities:other"));
        assertEquals(0, counter("arcanum:abilities:one_too_many"));
        assertEquals(2, counter("arcanum:abilities:spell_0"), "a held key keeps counting");
    }

    @Test
    void runeReadsPublishNewRunesOnlyAndKeepTheGap() throws Throwable {
        bind();
        assertEquals(3, of(ModMilestone.class).size());
        learn(P, "arcanum:freeze");
        binding.poll();
        assertEquals(2, engine.reads);
        assertEquals(3, of(ModMilestone.class).size(), "read within the gap does nothing");

        later();
        binding.poll();
        List<ModMilestone> runes = of(ModMilestone.class);
        assertEquals(4, runes.size());
        assertEquals("freeze", runes.get(3).id());
        assertEquals(4, counter("arcanum:runes"));
        assertEquals("4", services.players.get(P).facts.get("arcanum.runes"));

        later();
        binding.poll();
        assertEquals(4, of(ModMilestone.class).size(), "nothing new, nothing published");
    }

    @Test
    void leaveForgetsThePlayerAndTheNextSessionPublishesOnlyWhatIsNew() throws Throwable {
        bind();
        services.publisher.publish(new PlayerLeft(services.header("default", P), "Alder", 60_000L));
        services.dispatcher.drainHere();
        learn(P, "othermod:odd");
        services.publisher.publish(new PlayerJoined(services.header("default", P), "Alder", false));
        services.dispatcher.drainHere();
        assertEquals(2, engine.reads, "the join reads at once, the gap went with the leave");
        List<ModMilestone> runes = of(ModMilestone.class);
        assertEquals(4, runes.size(), "the runes the counters hold are not published again");
        assertEquals("othermod_odd", runes.get(3).id());
        assertEquals(4, counter("arcanum:runes"));

        services.counters.join(Q, "Birch", 2L);
        services.players.join(Q, "Birch", 2L).world = "default";
        services.publisher.publish(new PlayerJoined(services.header("default", Q), "Birch", true));
        services.dispatcher.drainHere();
        assertEquals(3, services.counters.get(Q, "arcanum:runes"));
    }

    @Test
    void unbindRemovesTheListenerAndStaleCallbacksDoNothing() throws Throwable {
        bind();
        Object[] kept = engine.listeners.get(0);
        binding.unbind("test");
        assertFalse(binding.isBound());
        assertTrue(engine.listeners.isEmpty());
        assertNull(services.players.get(P).facts.get("arcanum.runes"));
        int before = seen.size();
        ((ArcanumEngine.CastListener) kept[2]).onCast(outcome("completionSuccess"), P, prepared("Spark", 80, 2, 1), null);
        binding.poll();
        assertEquals(before, seen.size());
        assertEquals(1, engine.reads);

        // a rebind on a new instance registers a fresh listener for the new event class
        ClassLoader second = FakeMods.loader();
        Object again = Class.forName("fakes.arcanum.ArcanumPlugin", true, second).getConstructor().newInstance();
        assertTrue(binding.bind(again, "0.6.3", services));
        assertEquals(1, engine.listeners.size());
        assertSame(Class.forName(EVENT, true, second), engine.listeners.get(0)[0]);
        assertNotSame(kept[0], engine.listeners.get(0)[0]);
        assertEquals(2, engine.reads);
    }

    @Test
    void renamedMethodGivesAPartialBind() throws Throwable {
        binding.breakRunes = true;
        bind();
        assertEquals(List.of(ArcanumBinding.F_RUNES), binding.missing());
        assertEquals(List.of(ArcanumBinding.F_CASTS), binding.features());
        assertEquals(0, engine.reads);
        engine.fire(outcome("completionSuccess"), P, prepared("Spark", 80, 2, 1));
        assertEquals(1, counter("arcanum:abilities"));
    }

    @Test
    void throwingReadsAreGuarded() throws Throwable {
        bind();
        int before = seen.size();
        engine.fire(outcome("completionSuccess"), P, prepared("throw", 80, 2, 1));
        assertEquals(before, seen.size());
        assertTrue(binding.features().contains(ArcanumBinding.F_CASTS), "one failure does not switch the feature off");
        engine.fire(outcome("completionSuccess"), P, prepared("Spark", 80, 2, 1));
        assertEquals(1, counter("arcanum:abilities"));

        failing(true);
        later();
        binding.poll();
        assertTrue(binding.features().contains(ArcanumBinding.F_RUNES));
        failing(false);
        learn(P, "arcanum:ignite");
        later();
        binding.poll();
        assertEquals(1, counter("arcanum:rune:ignite"));
    }

    @Test
    void bindingResolvesThroughTheModLoaderNotTheTestClasspath() throws Throwable {
        bind();
        Class<?> used = (Class<?>) engine.listeners.get(0)[0];
        assertSame(loader, used.getClassLoader());
        assertNotSame(Class.forName(EVENT), used);
    }

    @Test
    void castSystemTakesTheModEventClassAndCloses() throws Throwable {
        Class<?> event = Class.forName(EVENT, true, loader);
        ArcanumCastSystem system = new ArcanumCastSystem(event, null, (e, u, p, s) -> { });
        assertSame(event, system.getEventType());
        assertTrue(system.isOpen());
        system.close();
        assertFalse(system.isOpen());
    }

    @Test
    void runeIdsDropTheModNamespace() {
        assertEquals("damage", ArcanumBinding.runeId("arcanum:damage"));
        assertEquals("x_y", ArcanumBinding.runeId("x:y"));
        assertNull(ArcanumBinding.runeId(" "));
        assertNull(ArcanumBinding.runeId("arcanum:"));
    }
}
