package dev.ryvrook.trackerapi.mods.zephyr;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zephyr binding against the fake mod, loaded through the isolating loader as the live jar is. The engine bus,
 * the entity reads and the per world component poll are a test engine that hands out refs and components.
 */
class ZephyrBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000402");
    static final ZephyrBinding.Names FAKES = new ZephyrBinding.Names("fakes.zephyr.api.ZephyrAPI", "fakes.zephyr.Zephyr",
            "fakes.zephyr.api.events.PerfectDodgeEvent", "fakes.zephyr.api.events.dodge.DodgeIFrameStartEvent",
            "fakes.zephyr.api.events.doublejump.DoubleJumpEvent", "fakes.zephyr.api.events.walljump.WallJumpEvent",
            "fakes.zephyr.api.events.grapple.GrappleStartEvent", "fakes.zephyr.api.events.vanish.VanishStartEvent",
            "fakes.zephyr.items.utility.zipline.ZiplineRideComponent");

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final TestEngine engine = new TestEngine();
    private ZephyrBinding binding;
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
        binding = new ZephyrBinding(FAKES, engine);
        loader = FakeMods.loader();
        plugin = Class.forName(FAKES.plugin(), true, loader).getConstructor().newInstance();
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    private Object event(String className, Ref<EntityStore> player) throws Exception {
        return Class.forName(className, true, loader).getConstructor(Ref.class).newInstance(player);
    }

    private Object perfectDodge(Ref<EntityStore> defender, Ref<EntityStore> attacker) throws Exception {
        return Class.forName(FAKES.perfectDodgeEvent(), true, loader).getConstructor(Ref.class, Ref.class).newInstance(defender, attacker);
    }

    private Object zipline(int rides) throws Exception {
        Class<?> type = Class.forName(FAKES.ziplineComponent(), true, loader);
        Object c = type.getConstructor().newInstance();
        rides(c, rides);
        return c;
    }

    private static void rides(Object component, int rides) throws Exception {
        Field f = component.getClass().getField("ziplineRideCount");
        f.setInt(component, rides);
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
        assertTrue(binding.bind(plugin, "0.4.2", services));
        assertEquals(List.of(ZephyrBinding.F_CORE, ZephyrBinding.F_DODGE, ZephyrBinding.F_JUMPS, ZephyrBinding.F_GRAPPLE,
                ZephyrBinding.F_VANISH, ZephyrBinding.F_ZIPLINE), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals(6, engine.listenerCount(), "six event classes");
        Class<?> used = Class.forName(FAKES.dodgeStartEvent(), true, loader);
        assertTrue(engine.listeners.containsKey(used), "the binding listens for the class from the mod's loader");
        assertNotSame(Class.forName(FAKES.dodgeStartEvent()), used);
        assertEquals(1, seen.size());
        assertTrue(((ModBound) seen.get(0)).bound());
        assertEquals("zephyr", ((ModBound) seen.get(0)).mod());
        assertEquals(List.of("zephyr:dodge", "zephyr:perfect_dodge", "zephyr:perfect_dodge:pvp", "zephyr:double_jump",
                "zephyr:wall_jump", "zephyr:grapple", "zephyr:vanish", "zephyr:zipline"),
                binding.stats().stream().map(s -> s.key()).toList());
    }

    @Test
    void eachMovementEventBecomesItsAction() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        Ref<EntityStore> alder = engine.player(P, "Alder");
        engine.fire(event(FAKES.dodgeStartEvent(), alder));
        engine.fire(event(FAKES.doubleJumpEvent(), alder));
        engine.fire(event(FAKES.doubleJumpEvent(), alder));
        engine.fire(event(FAKES.wallJumpEvent(), alder));
        engine.fire(event(FAKES.grappleStartEvent(), alder));
        engine.fire(event(FAKES.vanishStartEvent(), alder));
        List<ModAction> actions = actions();
        assertEquals(List.of("dodge", "double_jump", "double_jump", "wall_jump", "grapple", "vanish"),
                actions.stream().map(ModAction::action).toList());
        for (ModAction a : actions) {
            assertEquals(P, a.player());
            assertEquals("default", a.world());
            assertEquals("zephyr", a.mod());
            assertFalse(a.pvp());
            assertNull(a.targetTypeId());
            assertEquals(0, a.value());
        }
        assertEquals(1, counter(P, "zephyr:dodge"));
        assertEquals(2, counter(P, "zephyr:double_jump"));
        assertEquals(1, counter(P, "zephyr:wall_jump"));
        assertEquals(1, counter(P, "zephyr:grapple"));
        assertEquals(1, counter(P, "zephyr:vanish"));
        assertEquals(7, binding.published(), "the bind event and six actions");
    }

    @Test
    void perfectDodgeCarriesPvpAndTheAttackerType() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        Ref<EntityStore> alder = engine.player(P, "Alder");
        engine.fire(perfectDodge(alder, engine.creature("Trork_Warrior")));
        engine.fire(perfectDodge(alder, engine.player(Q, "Birch")));
        engine.fire(perfectDodge(engine.creature("Trork_Warrior"), alder));
        List<ModAction> actions = actions();
        assertEquals(2, actions.size());
        assertEquals("perfect_dodge", actions.get(0).action());
        assertFalse(actions.get(0).pvp());
        assertEquals("Trork_Warrior", actions.get(0).targetTypeId());
        assertTrue(actions.get(1).pvp());
        assertNull(actions.get(1).targetTypeId());
        assertEquals(2, counter(P, "zephyr:perfect_dodge"));
        assertEquals(1, counter(P, "zephyr:perfect_dodge:pvp"));
        assertEquals(0, counter(Q, "zephyr:perfect_dodge"));
    }

    @Test
    void eventsForSomethingThatIsNotAPlayerAreLeftOut() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        engine.fire(event(FAKES.wallJumpEvent(), engine.creature("Goblin_Scrapper")));
        engine.fire(event(FAKES.wallJumpEvent(), engine.world()));
        assertTrue(actions().isEmpty());
    }

    @Test
    void ziplinePollPublishesTheRidesSinceTheLastReadAndResetsOnAJoin() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        Object alder = zipline(2);
        engine.components.put(P, alder);
        binding.pollZiplines();
        assertTrue(actions().isEmpty(), "the first read is the baseline");
        assertEquals(1, engine.polls);
        assertNotSame(Class.forName(FAKES.ziplineComponent()), engine.lastType.getClass().getEnclosingClass(),
                "the component type came from the mod's class");
        rides(alder, 5);
        binding.pollZiplines();
        assertEquals(3, actions().size());
        assertEquals("zipline", actions().get(0).action());
        assertEquals(P, actions().get(0).player());
        assertEquals(3, counter(P, "zephyr:zipline"));
        binding.pollZiplines();
        assertEquals(3, counter(P, "zephyr:zipline"), "nothing new, nothing published");
        // the player joined again, the component starts from nothing and one ride happened since
        rides(alder, 1);
        binding.pollZiplines();
        assertEquals(4, counter(P, "zephyr:zipline"));
        // a second player appears with rides from before the binding looked, those are not counted
        Object birch = zipline(9);
        engine.components.put(Q, birch);
        binding.pollZiplines();
        assertEquals(0, counter(Q, "zephyr:zipline"));
        rides(birch, 10);
        binding.pollZiplines();
        assertEquals(1, counter(Q, "zephyr:zipline"));
        // a burst beyond the cap is not a player riding
        rides(alder, 1 + ZephyrBinding.ZIPLINE_BURST + 50);
        binding.pollZiplines();
        assertEquals(4 + ZephyrBinding.ZIPLINE_BURST, counter(P, "zephyr:zipline"));
    }

    @Test
    void ziplineBaselineIsDroppedWhenThePlayerLeaves() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        Object alder = zipline(4);
        engine.components.put(P, alder);
        binding.pollZiplines();
        // offline, the worlds list no component for the player and the baseline goes
        services.players.leave(P);
        engine.components.remove(P);
        binding.pollZiplines();
        // back online with a fresh component, the first read is a baseline again
        services.players.join(P, "Alder", 2L).world = "default";
        rides(alder, 1);
        engine.components.put(P, alder);
        binding.pollZiplines();
        assertEquals(0, counter(P, "zephyr:zipline"));
        rides(alder, 2);
        binding.pollZiplines();
        assertEquals(1, counter(P, "zephyr:zipline"));
    }

    @Test
    void unbindStopsListeningAndThePoll() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        Object event = event(FAKES.grappleStartEvent(), engine.player(P, "Alder"));
        engine.fire(event);
        engine.components.put(P, zipline(1));
        binding.pollZiplines();
        binding.unbind("the mod is gone");
        assertFalse(binding.isBound());
        assertEquals(0, engine.listenerCount());
        int before = seen.size();
        int polls = engine.polls;
        engine.fire(event);
        binding.pollZiplines();
        assertEquals(before, seen.size());
        assertEquals(polls, engine.polls, "the poll does not reach the engine while unbound");
        assertEquals(1, counter(P, "zephyr:grapple"));
    }

    @Test
    void renamedMethodGivesAPartialBindAndTheRestWorks() throws Exception {
        ZephyrBinding.Names broken = new ZephyrBinding.Names(FAKES.api(), FAKES.plugin(), FAKES.perfectDodgeEvent(),
                FAKES.dodgeStartEvent(), FAKES.doubleJumpEvent(), FAKES.wallJumpEvent(),
                "fakes.zephyr.api.events.grapple.BrokenGrappleStartEvent", FAKES.vanishStartEvent(), FAKES.ziplineComponent());
        binding = new ZephyrBinding(broken, engine);
        assertTrue(binding.bind(plugin, "0.4.3", services));
        assertEquals(List.of(ZephyrBinding.F_CORE, ZephyrBinding.F_DODGE, ZephyrBinding.F_JUMPS, ZephyrBinding.F_VANISH,
                ZephyrBinding.F_ZIPLINE), binding.features());
        assertEquals(List.of(ZephyrBinding.F_GRAPPLE), binding.missing());
        assertEquals(5, engine.listenerCount());
        engine.fire(event(FAKES.wallJumpEvent(), engine.player(P, "Alder")));
        assertEquals(1, counter(P, "zephyr:wall_jump"));
    }

    @Test
    void missingZiplineFieldLeavesThatFeatureOutOnly() throws Exception {
        ZephyrBinding.Names broken = new ZephyrBinding.Names(FAKES.api(), FAKES.plugin(), FAKES.perfectDodgeEvent(),
                FAKES.dodgeStartEvent(), FAKES.doubleJumpEvent(), FAKES.wallJumpEvent(), FAKES.grappleStartEvent(),
                FAKES.vanishStartEvent(), "fakes.zephyr.api.ZephyrAPI");
        binding = new ZephyrBinding(broken, engine);
        assertTrue(binding.bind(plugin, "0.4.3", services));
        assertEquals(List.of(ZephyrBinding.F_ZIPLINE), binding.missing());
        binding.pollZiplines();
        assertEquals(0, engine.polls);
    }

    @Test
    void throwingCallbackIsGuarded() throws Exception {
        binding.bind(plugin, "0.4.2", services);
        Object event = event(FAKES.doubleJumpEvent(), engine.player(P, "Alder"));
        engine.explode = true;
        engine.fire(event);
        assertTrue(binding.isBound());
        assertTrue(binding.features().contains(ZephyrBinding.F_JUMPS));
        assertTrue(actions().isEmpty());
        engine.explode = false;
        engine.fire(event);
        assertEquals(1, actions().size());
    }

    /** The engine of the tests. Refs are real engine refs without a store, told apart by identity. */
    static final class TestEngine implements Engine {
        final Map<Class<?>, List<Consumer<Object>>> listeners = new HashMap<>();
        final Map<Object, Actor> actors = new IdentityHashMap<>();
        final Map<UUID, Object> components = new LinkedHashMap<>();
        volatile boolean explode;
        int polls;
        Object lastType;
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

        @Override
        public void eachPlayer(Object componentType, BiConsumer<UUID, Object> visit) {
            polls++;
            lastType = componentType;
            for (Map.Entry<UUID, Object> e : components.entrySet()) {
                visit.accept(e.getKey(), e.getValue());
            }
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
