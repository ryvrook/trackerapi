package dev.ryvrook.trackerapi.mods.parries;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Perfect Parries binding against the fake mod, loaded through the isolating loader as the live jar is. The
 * engine bus and the entity reads are a test engine that hands out refs and remembers who each one is.
 */
class ParriesBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    static final ParriesBinding.Names FAKES = new ParriesBinding.Names("fakes.parries.api.PerfectParriesAPI",
            "fakes.parries.api.events.PerfectParryEvent", "fakes.parries.api.events.PerfectCounterattackEvent");

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final TestEngine engine = new TestEngine();
    private ParriesBinding binding;
    private ClassLoader loader;
    private Object plugin;

    @BeforeEach
    void setUp() throws Exception {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        for (UUID u : List.of(P, Q)) {
            services.counters.join(u, u == P ? "Alder" : "Birch", 1L);
            services.players.join(u, u == P ? "Alder" : "Birch", 1L).world = "default";
        }
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        binding = new ParriesBinding(FAKES, engine);
        loader = FakeMods.loader();
        plugin = Class.forName("fakes.parries.PerfectParries", true, loader).getConstructor().newInstance();
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    private Object parry(Ref<EntityStore> defender, Ref<EntityStore> attacker) throws Exception {
        return Class.forName(FAKES.parryEvent(), true, loader).getConstructor(Ref.class, Ref.class).newInstance(defender, attacker);
    }

    private Object counter(Ref<EntityStore> attacker, Ref<EntityStore> target, float multiplier) throws Exception {
        return Class.forName(FAKES.counterattackEvent(), true, loader).getConstructor(Ref.class, Ref.class, float.class)
                .newInstance(attacker, target, multiplier);
    }

    private List<ModAction> actions() {
        List<ModAction> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (e instanceof ModAction a) {
                out.add(a);
            }
        }
        return out;
    }

    private long counter(UUID player, String key) {
        return services.counters.get(player, key);
    }

    @Test
    void bindResolvesEveryFeatureThroughTheModsLoader() throws Exception {
        assertTrue(binding.bind(plugin, "0.9.7", services));
        assertEquals(List.of(ParriesBinding.F_CORE, ParriesBinding.F_PARRY, ParriesBinding.F_COUNTER), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("0.9.7", binding.version());
        assertEquals(2, engine.listenerCount());
        Class<?> used = Class.forName(FAKES.parryEvent(), true, loader);
        assertTrue(engine.listeners.containsKey(used), "the binding listens for the class from the mod's loader");
        assertNotSame(Class.forName(FAKES.parryEvent()), used);
        assertEquals(1, seen.size());
        assertTrue(((ModBound) seen.get(0)).bound());
        assertEquals("parries", ((ModBound) seen.get(0)).mod());
        assertEquals(List.of("parries:parry", "parries:parry:pvp", "parries:counterattack", "parries:counterattack:pvp",
                "parries:streak_best"), binding.stats().stream().map(s -> s.key()).toList());
    }

    @Test
    void parryAgainstACreatureIsAnActionWithTheNpcType() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        engine.fire(parry(engine.player(P, "Alder"), engine.creature("Goblin_Scrapper")));
        List<ModAction> actions = actions();
        assertEquals(1, actions.size());
        ModAction a = actions.get(0);
        assertEquals(P, a.player());
        assertEquals("default", a.world());
        assertEquals("parries", a.mod());
        assertEquals("parry", a.action());
        assertFalse(a.pvp());
        assertEquals("Goblin_Scrapper", a.targetTypeId());
        assertEquals(0, a.value());
        assertEquals(1, counter(P, "parries:parry"));
        assertEquals(0, counter(P, "parries:parry:pvp"));
        assertEquals(1, counter(P, "parries:streak_best"));
        assertEquals(1, binding.published() - 1, "the bind event and the action");
    }

    @Test
    void parryAgainstAPlayerIsPvp() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        engine.fire(parry(engine.player(P, "Alder"), engine.player(Q, "Birch")));
        ModAction a = actions().get(0);
        assertTrue(a.pvp());
        assertNull(a.targetTypeId());
        assertEquals(1, counter(P, "parries:parry"));
        assertEquals(1, counter(P, "parries:parry:pvp"));
        assertEquals(0, counter(Q, "parries:parry"), "the attacker gets nothing");
    }

    @Test
    void parryByAnNpcDefenderIsLeftOut() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        engine.fire(parry(engine.creature("Trork_Warrior"), engine.player(P, "Alder")));
        engine.fire(parry(engine.world(), engine.player(P, "Alder")));
        assertTrue(actions().isEmpty());
        assertEquals(0, counter(P, "parries:parry"));
        assertEquals(0, counter(P, "parries:streak_best"));
    }

    @Test
    void streakGrowsWithParriesAndResetsOnTheDefendersDeath() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        Ref<EntityStore> alder = engine.player(P, "Alder");
        Ref<EntityStore> goblin = engine.creature("Goblin_Scrapper");
        for (int i = 0; i < 3; i++) {
            engine.fire(parry(alder, goblin));
        }
        assertEquals(3, counter(P, "parries:streak_best"));
        // someone else dying changes nothing
        services.publisher.publish(died(Q));
        engine.fire(parry(alder, goblin));
        assertEquals(4, counter(P, "parries:streak_best"));
        // the defender dies, the streak starts over and the best stays
        services.publisher.publish(died(P));
        for (int i = 0; i < 3; i++) {
            engine.fire(parry(alder, goblin));
        }
        assertEquals(4, counter(P, "parries:streak_best"));
        engine.fire(parry(alder, goblin));
        engine.fire(parry(alder, goblin));
        assertEquals(5, counter(P, "parries:streak_best"));
        // leaving drops the streak too
        services.publisher.publish(new PlayerLeft(TestSupport.header("default", P), "Alder", 1000));
        engine.fire(parry(alder, goblin));
        assertEquals(5, counter(P, "parries:streak_best"));
        assertEquals(10, counter(P, "parries:parry"));
    }

    @Test
    void counterattackCarriesTheMultiplierAndTheTarget() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        engine.fire(counter(engine.player(P, "Alder"), engine.creature("Kweebec_Sapling"), 2.5f));
        engine.fire(counter(engine.player(P, "Alder"), engine.player(Q, "Birch"), 1.5f));
        engine.fire(counter(engine.creature("Trork_Warrior"), engine.player(P, "Alder"), 3f));
        List<ModAction> actions = actions();
        assertEquals(2, actions.size());
        assertEquals("counterattack", actions.get(0).action());
        assertEquals(2.5, actions.get(0).value(), 1e-9);
        assertEquals("Kweebec_Sapling", actions.get(0).targetTypeId());
        assertFalse(actions.get(0).pvp());
        assertTrue(actions.get(1).pvp());
        assertNull(actions.get(1).targetTypeId());
        assertEquals(1.5, actions.get(1).value(), 1e-9);
        assertEquals(2, counter(P, "parries:counterattack"));
        assertEquals(1, counter(P, "parries:counterattack:pvp"));
        assertEquals(0, counter(P, "parries:streak_best"), "a counterattack is not a parry");
    }

    @Test
    void unbindStopsListeningAndClosesTheDeathSubscription() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        assertEquals(1, active("mods/parries"));
        Object event = parry(engine.player(P, "Alder"), engine.creature("Goblin_Scrapper"));
        engine.fire(event);
        binding.unbind("the mod is gone");
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, engine.listenerCount());
        assertEquals(0, active("mods/parries"));
        int before = seen.size();
        engine.fire(event);
        assertEquals(before, seen.size());
        assertEquals(1, counter(P, "parries:parry"));
        // a bind again starts the streak from nothing
        assertTrue(binding.bind(plugin, "0.9.7", services));
        engine.fire(event);
        assertEquals(1, counter(P, "parries:streak_best"));
    }

    @Test
    void renamedMethodGivesAPartialBindAndTheRestWorks() throws Exception {
        ParriesBinding.Names broken = new ParriesBinding.Names(FAKES.api(), FAKES.parryEvent(),
                "fakes.parries.api.events.BrokenCounterattackEvent");
        binding = new ParriesBinding(broken, engine);
        assertTrue(binding.bind(plugin, "0.9.8", services));
        assertEquals(List.of(ParriesBinding.F_CORE, ParriesBinding.F_PARRY), binding.features());
        assertEquals(List.of(ParriesBinding.F_COUNTER), binding.missing());
        assertEquals(1, engine.listenerCount());
        engine.fire(parry(engine.player(P, "Alder"), engine.creature("Goblin_Scrapper")));
        assertEquals(1, counter(P, "parries:parry"));
    }

    @Test
    void missingApiBindsNothing() {
        ParriesBinding.Names none = new ParriesBinding.Names("fakes.parries.api.Missing", "fakes.parries.api.events.Missing",
                "fakes.parries.api.events.Missing");
        binding = new ParriesBinding(none, engine);
        assertFalse(binding.bind(plugin, "1.0.0", services));
        assertFalse(binding.isBound());
        assertEquals(List.of(ParriesBinding.F_CORE, ParriesBinding.F_PARRY, ParriesBinding.F_COUNTER), binding.missing());
        assertTrue(seen.isEmpty());
    }

    @Test
    void throwingCallbackIsGuarded() throws Exception {
        binding.bind(plugin, "0.9.7", services);
        Object event = parry(engine.player(P, "Alder"), engine.creature("Goblin_Scrapper"));
        engine.explode = true;
        engine.fire(event);
        assertTrue(binding.isBound());
        assertTrue(binding.features().contains(ParriesBinding.F_PARRY));
        assertTrue(actions().isEmpty());
        engine.explode = false;
        engine.fire(event);
        assertEquals(1, actions().size());
    }

    private int active(String owner) {
        int n = 0;
        for (Subscription s : services.publisher.subscriptions()) {
            if (owner.equals(s.owner()) && s.isActive()) {
                n++;
            }
        }
        return n;
    }

    private static EntityDied died(UUID victim) {
        return new EntityDied(TestSupport.header("default", victim), Actor.player(victim, "x"), Actor.WORLD, "Fall", null,
                false, false, 0, 0, 0, false, List.of(), Set.of(), false, null, 0);
    }

    /** The engine of the tests. Refs are real engine refs without a store, told apart by identity. */
    static final class TestEngine implements Engine {
        final Map<Class<?>, List<Consumer<Object>>> listeners = new HashMap<>();
        final Map<Object, Actor> actors = new IdentityHashMap<>();
        volatile boolean explode;
        private int next = 1;

        @Override
        public Runnable listen(Class<?> eventClass, Consumer<Object> consumer) {
            listeners.computeIfAbsent(eventClass, c -> new ArrayList<>()).add(consumer);
            return () -> listeners.getOrDefault(eventClass, new ArrayList<>()).remove(consumer);
        }

        @Override
        public Actor actor(Object ref, Object anchor) {
            if (explode) {
                throw new IllegalStateException("no store");
            }
            return actors.getOrDefault(ref, Actor.WORLD);
        }

        Ref<EntityStore> player(UUID uuid, String name) {
            return ref(Actor.player(uuid, name));
        }

        Ref<EntityStore> creature(String type) {
            return ref(Actor.creature(null, type));
        }

        Ref<EntityStore> world() {
            return ref(Actor.WORLD);
        }

        private Ref<EntityStore> ref(Actor who) {
            Ref<EntityStore> r = new Ref<>(null, next++);
            actors.put(r, who);
            return r;
        }

        void fire(Object event) {
            for (Consumer<Object> c : List.copyOf(listeners.getOrDefault(event.getClass(), List.of()))) {
                c.accept(event);
            }
        }

        int listenerCount() {
            int n = 0;
            for (List<Consumer<Object>> l : listeners.values()) {
                n += l.size();
            }
            return n;
        }
    }
}
