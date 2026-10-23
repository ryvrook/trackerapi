package dev.ryvrook.trackerapi.mods.enchant;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.EnchantmentTriggered;
import dev.ryvrook.trackerapi.api.event.ItemEnchanted;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Simple Enchantments binding against the fake mod under {@code fakes.enchant}, loaded through the isolating
 * loader as the live jar is, with a fake engine bus in place of the event registry. A full bind registers for the
 * two event classes, an enchant becomes {@link ItemEnchanted} with its counters, activations become
 * {@link EnchantmentTriggered} once per player and enchant id per second, unbind drops both registrations and a
 * consumer the engine kept does nothing, a renamed event class leaves one feature missing, and a type that throws
 * is guarded.
 */
class EnchantBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final String PKG = "fakes.enchant";
    private static final EnchantBinding.Names FAKE = EnchantBinding.Names.under(PKG);
    /** The failures of one feature before the guard switches it off, {@code AbstractBinding.FAILURES_TO_OFF}. */
    private static final int FAILURES_TO_OFF = 20;

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final FakeEngineBus bus = new FakeEngineBus();
    private final AtomicLong clock = new AtomicLong(10_000);
    private ClassLoader loader;
    private Object plugin;
    private EnchantBinding binding;

    @BeforeEach
    void setUp() throws Throwable {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        join(P, "Alder");
        join(Q, "Birch");
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        loader = FakeMods.loader();
        plugin = Class.forName(PKG + ".SimpleEnchanting", true, loader).getConstructor().newInstance();
        binding = new EnchantBinding(FAKE, bus, new ProcGate(clock::get));
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private void join(UUID uuid, String name) {
        services.counters.join(uuid, name, 1L);
        services.players.join(uuid, name, 1L).world = "default";
    }

    private Object playerRef(UUID uuid) throws ReflectiveOperationException {
        return Class.forName(PKG + ".FakePlayerRef", true, loader).getConstructor(UUID.class).newInstance(uuid);
    }

    private Object type(String id, int maxLevel, boolean legendary, boolean broken) throws ReflectiveOperationException {
        Class<?> t = Class.forName(FAKE.enchantmentType(), true, loader);
        return t.getConstructor(String.class, int.class, boolean.class, boolean.class).newInstance(id, maxLevel, legendary, broken);
    }

    private Object item(String itemId) throws ReflectiveOperationException {
        return Class.forName(PKG + ".FakeItemStack", true, loader).getConstructor(String.class).newInstance(itemId);
    }

    private Object event(String className, UUID uuid, Object item, Object type, int level) throws ReflectiveOperationException {
        Class<?> c = Class.forName(className, true, loader);
        Constructor<?> ctor = c.getConstructors()[0];
        return ctor.newInstance(uuid == null ? null : playerRef(uuid), item, type, level);
    }

    private void enchanted(UUID uuid, String itemId, String enchantId, int level) throws ReflectiveOperationException {
        bus.fire(event(FAKE.enchantedEvent(), uuid, itemId == null ? null : item(itemId), type(enchantId, 5, false, false), level));
    }

    private void activated(UUID uuid, String itemId, String enchantId) throws ReflectiveOperationException {
        bus.fire(event(FAKE.activatedEvent(), uuid, itemId == null ? null : item(itemId), type(enchantId, 5, false, false), 1));
    }

    private long get(UUID uuid, String key) {
        return services.counters.get(uuid, key);
    }

    private <T> List<T> of(Class<T> t) {
        return seen.stream().filter(t::isInstance).map(t::cast).toList();
    }

    private void bindFull() {
        assertTrue(binding.bind(plugin, "1.2.1", services));
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsBothFeaturesAndRegistersTheTwoEventClassesFromTheModLoader() throws Exception {
        bindFull();
        assertTrue(binding.isBound());
        assertEquals("1.2.1", binding.version());
        assertEquals(List.of(EnchantBinding.F_ENCHANTED, EnchantBinding.F_ACTIVATIONS), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals(2, bus.registrations());
        for (Class<?> c : bus.classes()) {
            assertNotSame(Class.forName(c.getName()), c, "the binding used the mod loader's class, not the test loader's");
            assertEquals(loader, c.getClassLoader());
        }
        ModBound bound = (ModBound) seen.get(0);
        assertEquals("enchant", bound.mod());
        assertEquals("org.herolias:SimpleEnchantments", bound.pluginId());
        assertTrue(bound.bound());
        assertNull(bound.player());
        for (StatSpec spec : binding.stats()) {
            assertTrue(spec.key().startsWith("enchant:"), spec.key());
        }
        assertEquals(5, binding.stats().size());
    }

    @Test
    void enchantBecomesItemEnchantedWithCountersAndHighestLevel() throws Exception {
        bindFull();
        enchanted(P, "Weapon_Sword_Iron", "sharpness", 3);
        List<ItemEnchanted> events = of(ItemEnchanted.class);
        assertEquals(1, events.size());
        ItemEnchanted e = events.get(0);
        assertEquals(P, e.player());
        assertEquals("default", e.world());
        assertEquals("enchant", e.mod());
        assertEquals("Weapon_Sword_Iron", e.itemId());
        assertEquals("sharpness", e.enchantId());
        assertEquals(3, e.level());
        assertEquals(1, get(P, "enchant:items"));
        assertEquals(1, get(P, "enchant:items:sharpness"));
        assertEquals(3, get(P, "enchant:highest_level"));

        enchanted(P, "Armor_Helmet_Iron", "protection", 5);
        enchanted(P, null, "sharpness", 1);
        events = of(ItemEnchanted.class);
        assertEquals(3, events.size());
        assertNull(events.get(2).itemId(), "no stack gives no item id");
        assertEquals(3, get(P, "enchant:items"));
        assertEquals(2, get(P, "enchant:items:sharpness"));
        assertEquals(1, get(P, "enchant:items:protection"));
        assertEquals(5, get(P, "enchant:highest_level"));
        assertEquals(4, binding.published(), "the bind event and three enchants");
        assertEquals(3, services.counters.server("enchant:items"));
    }

    @Test
    void activationsAreCappedToOnePerPlayerAndEnchantPerSecond() throws Exception {
        bindFull();
        for (int i = 0; i < 5; i++) {
            activated(P, "Weapon_Sword_Iron", "sharpness");
        }
        assertEquals(1, of(EnchantmentTriggered.class).size(), "five hits in one second count once");
        EnchantmentTriggered t = of(EnchantmentTriggered.class).get(0);
        assertEquals(P, t.player());
        assertEquals("enchant", t.mod());
        assertEquals("sharpness", t.enchantId());
        assertEquals("Weapon_Sword_Iron", t.itemId());

        activated(P, "Weapon_Sword_Iron", "life_leech");
        assertEquals(2, of(EnchantmentTriggered.class).size(), "another enchant of the same player passes");
        activated(Q, "Weapon_Sword_Iron", "sharpness");
        assertEquals(3, of(EnchantmentTriggered.class).size(), "the same enchant of another player passes");

        clock.addAndGet(ProcGate.WINDOW_MILLIS - 1);
        activated(P, "Weapon_Sword_Iron", "sharpness");
        assertEquals(3, of(EnchantmentTriggered.class).size(), "still inside the window");
        clock.addAndGet(1);
        activated(P, "Weapon_Sword_Iron", "sharpness");
        assertEquals(4, of(EnchantmentTriggered.class).size(), "the window passed");

        assertEquals(3, get(P, "enchant:procs"));
        assertEquals(2, get(P, "enchant:procs:sharpness"));
        assertEquals(1, get(P, "enchant:procs:life_leech"));
        assertEquals(1, get(Q, "enchant:procs"));
        assertEquals(1, get(Q, "enchant:procs:sharpness"));
    }

    @Test
    void eventsWithoutAPlayerOrAnEnchantmentAreSkipped() throws Exception {
        bindFull();
        activated(null, "Weapon_Sword_Iron", "sharpness");
        enchanted(null, "Weapon_Sword_Iron", "sharpness", 1);
        bus.fire(event(FAKE.enchantedEvent(), P, item("Weapon_Sword_Iron"), null, 2));
        bus.fire(event(FAKE.activatedEvent(), P, item("Weapon_Sword_Iron"), null, 2));
        assertEquals(1, seen.size(), "only the bind event");
        assertEquals(0, get(P, "enchant:items"));
        assertEquals(0, get(P, "enchant:procs"));
    }

    @Test
    void unbindDropsBothRegistrationsAndAKeptConsumerDoesNothing() throws Exception {
        bindFull();
        Class<?> activatedClass = Class.forName(FAKE.activatedEvent(), true, loader);
        List<Consumer<Object>> kept = bus.consumersOf(activatedClass);
        assertEquals(1, kept.size());
        activated(P, "Weapon_Sword_Iron", "sharpness");
        assertEquals(1, of(EnchantmentTriggered.class).size());

        binding.unbind("test");
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, bus.registrations());
        ModBound gone = (ModBound) seen.get(seen.size() - 1);
        assertFalse(gone.bound());

        int before = seen.size();
        clock.addAndGet(5_000);
        kept.get(0).accept(event(FAKE.activatedEvent(), P, item("Weapon_Sword_Iron"), type("sharpness", 5, false, false), 1));
        activated(P, "Weapon_Sword_Iron", "sharpness");
        enchanted(P, "Weapon_Sword_Iron", "sharpness", 1);
        assertEquals(before, seen.size(), "nothing after unbind");
    }

    @Test
    void renamedActivatedEventGivesAPartialBindWithEnchantsStillCounted() throws Exception {
        EnchantBinding.Names broken = new EnchantBinding.Names(FAKE.enchantedEvent(), PKG + ".api.event.EnchantmentProcEvent",
                FAKE.enchantmentType());
        binding = new EnchantBinding(broken, bus, new ProcGate(clock::get));
        bindFull();
        assertEquals(List.of(EnchantBinding.F_ENCHANTED), binding.features());
        assertEquals(List.of(EnchantBinding.F_ACTIVATIONS), binding.missing());
        assertEquals(1, bus.registrations());
        enchanted(P, "Weapon_Sword_Iron", "sharpness", 2);
        assertEquals(1, of(ItemEnchanted.class).size());
        assertEquals(1, get(P, "enchant:items"));
        activated(P, "Weapon_Sword_Iron", "sharpness");
        assertEquals(0, of(EnchantmentTriggered.class).size());
    }

    @Test
    void missingEnchantmentTypeFailsTheWholeBind() {
        EnchantBinding.Names broken = new EnchantBinding.Names(FAKE.enchantedEvent(), FAKE.activatedEvent(),
                PKG + ".enchantment.Enchantment");
        binding = new EnchantBinding(broken, bus, new ProcGate(clock::get));
        assertFalse(binding.bind(plugin, "1.2.1", services));
        assertFalse(binding.isBound());
        assertEquals(0, bus.registrations());
        assertTrue(seen.isEmpty(), "no bind event for a bind that matched nothing");
    }

    @Test
    void aTypeThatThrowsIsGuardedAndSwitchesTheFeatureOffAtTheLimit() throws Exception {
        bindFull();
        Object brokenType = type("sharpness", 5, false, true);
        bus.fire(event(FAKE.enchantedEvent(), P, item("Weapon_Sword_Iron"), brokenType, 2));
        assertEquals(0, of(ItemEnchanted.class).size());
        assertTrue(binding.isBound());
        assertEquals(List.of(EnchantBinding.F_ENCHANTED, EnchantBinding.F_ACTIVATIONS), binding.features(), "one failure keeps the feature");
        enchanted(P, "Weapon_Sword_Iron", "sharpness", 2);
        assertEquals(1, of(ItemEnchanted.class).size(), "the next good event is counted");

        for (int i = 1; i < FAILURES_TO_OFF; i++) {
            bus.fire(event(FAKE.enchantedEvent(), P, item("Weapon_Sword_Iron"), brokenType, 2));
        }
        assertEquals(List.of(EnchantBinding.F_ACTIVATIONS), binding.features());
        assertEquals(List.of(EnchantBinding.F_ENCHANTED), binding.missing());
        enchanted(P, "Weapon_Sword_Iron", "sharpness", 2);
        assertEquals(1, of(ItemEnchanted.class).size(), "the feature is off");
        activated(P, "Weapon_Sword_Iron", "sharpness");
        assertEquals(1, of(EnchantmentTriggered.class).size(), "the other feature still works");
    }

    @Test
    void procGateForgetsAPlayerAndClears() {
        ProcGate gate = new ProcGate(clock::get);
        assertTrue(gate.pass(P, "sharpness"));
        assertFalse(gate.pass(P, "sharpness"));
        assertTrue(gate.pass(Q, "sharpness"));
        assertEquals(2, gate.players());
        gate.forget(P);
        assertEquals(1, gate.players());
        assertTrue(gate.pass(P, "sharpness"), "a player who left and came back starts fresh");
        gate.forget(null);
        gate.clear();
        assertEquals(0, gate.players());
        assertTrue(gate.pass(Q, "sharpness"));
    }
}
