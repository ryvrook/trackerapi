package dev.ryvrook.trackerapi.mods.fish;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Angler's Almanac binding against the fake mod under {@code fakes.fish}, loaded through the isolating loader
 * as the live jar is, with a fake engine in place of the event registry and the ref reads. A full bind registers
 * the four event classes, a catch becomes tallies, actions and milestones with their counters, pages fill zones,
 * the perfect streak ends on another grade or an escape, cancelled casts and hooks are not counted, unbind drops
 * every registration, renamed classes give partial or failed binds, and a loot that throws is guarded.
 */
class AnglersBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final String PKG = "fakes.fish";
    private static final AnglersBinding.Names FAKE = AnglersBinding.Names.under(PKG);
    /** The failures of one feature before the guard switches it off, {@code AbstractBinding.FAILURES_TO_OFF}. */
    private static final int FAILURES_TO_OFF = 20;

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final FakeFishEngine engine = new FakeFishEngine();
    private ClassLoader loader;
    private Object plugin;
    private AnglersBinding binding;
    private Object book;

    @BeforeEach
    void setUp() throws Throwable {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        join(P, "Alder");
        join(Q, "Birch");
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        loader = FakeMods.loader();
        plugin = Class.forName(PKG + ".AnglersAlmanac", true, loader).getConstructor().newInstance();
        book = book(new String[]{"Global", "Common_Bluegill", "Rare_Salmon"},
                new String[]{"Zone1", "BoneFish_White", "Legendary_Pike"});
        binding = new AnglersBinding(FAKE, engine);
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

    private Object book(String[]... zones) throws ReflectiveOperationException {
        Class<?> habitat = Class.forName(FAKE.habitat(), true, loader);
        Object arr = Array.newInstance(habitat, zones.length);
        for (int i = 0; i < zones.length; i++) {
            String[] pages = java.util.Arrays.copyOfRange(zones[i], 1, zones[i].length);
            Array.set(arr, i, habitat.getConstructor(String.class, String[].class).newInstance(zones[i][0], pages));
        }
        Class<?> bookClass = Class.forName(FAKE.book(), true, loader);
        Object b = bookClass.getConstructor(arr.getClass()).newInstance(arr);
        bookClass.getMethod("setMaster", bookClass).invoke(null, b);
        return b;
    }

    private Object player(UUID uuid) throws ReflectiveOperationException {
        return Class.forName(PKG + ".FakePlayer", true, loader).getConstructor(UUID.class).newInstance(uuid);
    }

    private Object ref(UUID uuid) throws ReflectiveOperationException {
        return Class.forName(PKG + ".FakeRef", true, loader).getConstructor(UUID.class).newInstance(uuid);
    }

    private Object loot(String id, String itemId, String name, String rarity, int size, boolean broken)
            throws ReflectiveOperationException {
        return Class.forName(FAKE.lootManager(), true, loader)
                .getConstructor(String.class, String.class, String.class, String.class, int.class, boolean.class)
                .newInstance(id, itemId, name, rarity, size, broken);
    }

    private Object loot(String id, String rarity, int size) throws ReflectiveOperationException {
        return loot(id, id + "_Item", id.replace('_', ' '), rarity, size, false);
    }

    private Object caughtEvent(UUID uuid, Object loot, boolean legendary, float performance) throws ReflectiveOperationException {
        Class<?> c = Class.forName(FAKE.caughtEvent(), true, loader);
        return c.getConstructors()[0].newInstance(loot, true, legendary, uuid == null ? null : player(uuid), performance);
    }

    private void caught(UUID uuid, String id, String rarity, int size, float performance) throws ReflectiveOperationException {
        engine.fire(caughtEvent(uuid, loot(id, rarity, size), "Legendary".equals(rarity), performance));
    }

    private void failed(UUID uuid, Object loot) throws ReflectiveOperationException {
        Class<?> c = Class.forName(FAKE.failedEvent(), true, loader);
        engine.fire(c.getConstructors()[0].newInstance(loot, player(uuid)));
    }

    private Object castEvent(UUID uuid, boolean cancelled) throws ReflectiveOperationException {
        Class<?> c = Class.forName(FAKE.castEvent(), true, loader);
        Object e = c.getConstructors()[0].newInstance(ref(uuid));
        c.getMethod("setCancelled", boolean.class).invoke(e, cancelled);
        return e;
    }

    private void hooked(UUID uuid, UUID target, boolean cancelled) throws ReflectiveOperationException {
        Class<?> c = Class.forName(FAKE.pullEvent(), true, loader);
        Object e = c.getConstructors()[0].newInstance(ref(uuid), ref(target));
        c.getMethod("setCancelled", boolean.class).invoke(e, cancelled);
        engine.fire(e);
    }

    private long get(UUID uuid, String key) {
        return services.counters.get(uuid, key);
    }

    private <T> List<T> of(Class<T> t) {
        return seen.stream().filter(t::isInstance).map(t::cast).toList();
    }

    private List<ModMilestone> milestones(String category) {
        return of(ModMilestone.class).stream().filter(m -> m.category().equals(category)).toList();
    }

    private void bindFull() {
        assertTrue(binding.bind(plugin, "1.2.2", services));
    }

    private static final List<String> ALL = List.of(AnglersBinding.F_SIZE, AnglersBinding.F_PAGES, AnglersBinding.F_CATCH,
            AnglersBinding.F_FAILED, AnglersBinding.F_CAST, AnglersBinding.F_HOOK);

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsEveryFeatureAndRegistersTheFourEventClassesFromTheModLoader() throws Exception {
        bindFull();
        assertTrue(binding.isBound());
        assertEquals("1.2.2", binding.version());
        assertEquals(ALL, binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals(4, engine.registrations());
        for (Class<?> c : engine.classes()) {
            assertNotSame(Class.forName(c.getName()), c, "the binding used the mod loader's class, not the test loader's");
            assertEquals(loader, c.getClassLoader());
        }
        assertTrue(engine.atLast(Class.forName(FAKE.castEvent(), true, loader)), "the cast is heard last");
        assertTrue(engine.atLast(Class.forName(FAKE.pullEvent(), true, loader)), "the hook is heard last");
        assertFalse(engine.atLast(Class.forName(FAKE.caughtEvent(), true, loader)));
        ModBound bound = (ModBound) seen.get(0);
        assertEquals("fish", bound.mod());
        assertEquals("dev.rm20:AnglersAlmanac", bound.pluginId());
        assertEquals("Angler's Almanac", binding.name());
        for (StatSpec spec : binding.stats()) {
            assertTrue(spec.key().startsWith("fish:"), spec.key());
        }
        assertEquals(24, binding.stats().size());
    }

    @Test
    void aFishCatchBecomesTalliesActionsAndMilestonesWithCountersAndFacts() throws Exception {
        bindFull();
        engine.fire(caughtEvent(P, loot("Legendary_Pike", "Fish_Pike_Item", "Pike", "Legendary", 120, false), true, 97f));

        List<ModTally> tallies = of(ModTally.class);
        assertEquals(List.of("caught", "rarity", "grade", "performance", "perfect_streak", "size"),
                tallies.stream().map(ModTally::stat).toList());
        ModTally c = tallies.get(0);
        assertEquals(P, c.player());
        assertEquals("default", c.world());
        assertEquals("fish", c.mod());
        assertEquals("Legendary_Pike", c.id(), "the loot id, not the item id");
        assertEquals(1, c.amount());
        assertEquals("legendary", tallies.get(1).id());
        assertEquals("perfect", tallies.get(2).id());
        assertEquals(4, tallies.get(2).best());

        assertEquals(1, get(P, "fish:caught"));
        assertEquals(1, get(P, "fish:caught:Legendary_Pike"));
        assertEquals(1, get(P, "fish:rarity"));
        assertEquals(1, get(P, "fish:rarity:legendary"));
        assertEquals(1, get(P, "fish:grade"));
        assertEquals(1, get(P, "fish:grade:perfect"));
        assertEquals(4, get(P, "fish:grade_best"));
        assertEquals(97, get(P, "fish:performance_best"));
        assertEquals(1, get(P, "fish:perfect_streak_best"));
        assertEquals(120, get(P, "fish:size_best"));
        assertEquals(1, get(P, "fish:legendary"));
        assertEquals(0, get(P, "fish:golden"));
        assertEquals(1, get(P, "fish:species"));
        assertEquals(1, get(P, "fish:species:Legendary_Pike"));
        assertEquals(1, get(P, "fish:pages"));
        assertEquals(1, get(P, "fish:page:Legendary_Pike"));
        assertEquals(0, get(P, "fish:zones"));

        ModMilestone species = milestones("species").get(0);
        assertEquals("Legendary_Pike", species.id());
        assertEquals("Pike", species.name());
        assertEquals(1, milestones("page").size());
        assertEquals("1", services.players.get(P).facts.get("fish.species"));
        assertEquals("1", services.players.get(P).facts.get("fish.legendary"));
        assertEquals(1, services.counters.server("fish:caught"));

        engine.fire(caughtEvent(P, loot("Legendary_Pike", "Fish_Pike_Item", "Pike", "Legendary", 120, false), true, 50f));
        assertEquals(2, get(P, "fish:caught:Legendary_Pike"));
        assertEquals(1, get(P, "fish:grade:good"));
        assertEquals(1, milestones("species").size(), "a known species is not announced again");
        assertEquals(1, milestones("page").size());
        assertEquals(1, get(P, "fish:species"));
        assertEquals("2", services.players.get(P).facts.get("fish.legendary"));
    }

    @Test
    void goldenFishAndSpeciesWithoutAPageAreCountedWithoutAPage() throws Exception {
        bindFull();
        caught(P, "Golden_Minnow", "Common", 5, 85f);
        assertEquals(1, get(P, "fish:golden"));
        assertEquals(1, get(P, "fish:species"));
        assertEquals(0, get(P, "fish:pages"));
        assertTrue(milestones("page").isEmpty());
        assertEquals(1, get(P, "fish:grade:great"));
        assertEquals(3, get(P, "fish:grade_best"));
        assertEquals(0, get(P, "fish:perfect_streak_best"));
    }

    @Test
    void junkIsCountedApartAndDiscoversNothing() throws Exception {
        bindFull();
        caught(P, "Gold_Ore", "Junk", 0, 60f);
        caught(P, "Essence_Of_Life", "Essences", 0, 60f);
        assertEquals(0, get(P, "fish:caught"));
        assertEquals(2, get(P, "fish:junk"));
        assertEquals(1, get(P, "fish:junk:Gold_Ore"));
        assertEquals(1, get(P, "fish:junk:Essence_Of_Life"));
        assertEquals(2, get(P, "fish:rarity"));
        assertEquals(1, get(P, "fish:rarity:junk"));
        assertEquals(1, get(P, "fish:rarity:essences"));
        assertEquals(0, get(P, "fish:species"));
        assertEquals(0, get(P, "fish:size_best"));
        assertTrue(of(ModMilestone.class).isEmpty());
    }

    @Test
    void theLastPageOfAZoneCompletesItOnceAndTheLayoutIsReadOnce() throws Exception {
        bindFull();
        caught(P, "BoneFish_White", "Epic", 40, 70f);
        assertEquals(0, get(P, "fish:zones"));
        caught(P, "Legendary_Pike", "Legendary", 120, 70f);
        assertEquals(2, get(P, "fish:pages"));
        assertEquals(1, get(P, "fish:zones"));
        assertEquals(1, get(P, "fish:zone:Zone1"));
        List<ModMilestone> zones = milestones("zone");
        assertEquals(1, zones.size());
        assertEquals("Zone1", zones.get(0).id());
        caught(P, "Legendary_Pike", "Legendary", 120, 70f);
        caught(Q, "Legendary_Pike", "Legendary", 120, 70f);
        assertEquals(1, milestones("zone").size(), "neither a repeat nor another player's half zone completes it");
        assertEquals(0, get(Q, "fish:zones"));
        assertEquals(1, book.getClass().getField("reads").getInt(book), "the layout is read on the first catch only");
    }

    @Test
    void theZoneTitlePageIsNotAFishPage() throws Exception {
        bindFull();
        caught(P, "Global", "Common", 1, 70f);
        assertTrue(milestones("page").isEmpty());
        caught(P, "Common_Bluegill", "Common", 10, 70f);
        caught(P, "Rare_Salmon", "Rare", 40, 70f);
        assertEquals(1, get(P, "fish:zone:Global"));
    }

    @Test
    void thePerfectStreakEndsOnAnotherGradeAnEscapeOrLeaving() throws Exception {
        bindFull();
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        caught(P, "Common_Bluegill", "Common", 10, 96f);
        assertEquals(2, get(P, "fish:perfect_streak_best"));
        caught(P, "Common_Bluegill", "Common", 10, 81f);
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(2, get(P, "fish:perfect_streak_best"));
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        failed(P, null);
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(2, get(P, "fish:perfect_streak_best"), "the escape ended the run of two");
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(3, get(P, "fish:perfect_streak_best"));
        caught(Q, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(1, get(Q, "fish:perfect_streak_best"), "streaks are per player");
    }

    @Test
    void withoutTheMinigameTheGradeIsNilWithoutBests() throws Exception {
        bindFull();
        caught(P, "Common_Minnow", "Common", 5, -1f);
        assertEquals(1, get(P, "fish:grade:nil"));
        assertEquals(0, get(P, "fish:grade_best"));
        assertEquals(0, get(P, "fish:performance_best"));
    }

    @Test
    void anEscapeCountsWithTheMissedLootWhenTheModKnowsIt() throws Exception {
        bindFull();
        failed(P, loot("Legendary_Shark", "Legendary", 900));
        failed(P, null);
        List<ModAction> actions = of(ModAction.class);
        assertEquals(2, actions.size());
        assertEquals("escaped", actions.get(0).action());
        assertEquals("Legendary_Shark", actions.get(0).targetTypeId());
        assertNull(actions.get(1).targetTypeId());
        assertEquals(2, get(P, "fish:escaped"));
    }

    @Test
    void castsAndHooksCountOnlyWhenNotCancelledAndHookingAPlayerIsPvp() throws Exception {
        bindFull();
        engine.fire(castEvent(P, false));
        engine.fire(castEvent(P, true));
        engine.fire(castEvent(null, false));
        assertEquals(1, get(P, "fish:cast"));
        hooked(P, null, false);
        hooked(P, Q, false);
        hooked(P, Q, true);
        assertEquals(2, get(P, "fish:hooked"));
        assertEquals(1, get(P, "fish:hooked:pvp"));
        assertEquals(0, get(Q, "fish:hooked"));
    }

    @Test
    void eventsWithoutAPlayerOrLootAreSkipped() throws Exception {
        bindFull();
        caught(null, "Common_Bluegill", "Common", 10, 99f);
        engine.fire(caughtEvent(P, null, false, 99f));
        assertEquals(1, seen.size(), "only the bind event");
    }

    @Test
    void everyCounterWrittenIsNamedInStats() throws Exception {
        bindFull();
        caught(P, "BoneFish_White", "Epic", 40, 99f);
        caught(P, "Legendary_Pike", "Legendary", 120, 50f);
        caught(P, "Golden_Minnow", "Common", 5, -1f);
        caught(P, "Gold_Ore", "Junk", 0, 10f);
        failed(P, null);
        engine.fire(castEvent(P, false));
        hooked(P, Q, false);
        List<StatSpec> specs = binding.stats();
        Map<String, Long> all = services.counters.all(P);
        int fish = 0;
        for (String key : all.keySet()) {
            if (!key.startsWith("fish:")) {
                continue;
            }
            fish++;
            boolean named = specs.stream().anyMatch(s -> s.key().equals(key) || (s.perId() && key.startsWith(s.key() + ":")));
            assertTrue(named, key + " is not named in stats()");
        }
        assertTrue(fish > 20, "the catches wrote the counters");
        for (StatSpec s : specs) {
            if (!s.perId()) {
                assertTrue(all.containsKey(s.key()), s.key() + " is named but not written");
            }
        }
    }

    @Test
    void unbindDropsEveryRegistrationAndAKeptConsumerDoesNothing() throws Exception {
        bindFull();
        Class<?> caughtClass = Class.forName(FAKE.caughtEvent(), true, loader);
        List<Consumer<Object>> kept = engine.consumersOf(caughtClass);
        assertEquals(1, kept.size());
        binding.unbind("test");
        assertFalse(binding.isBound());
        assertEquals(0, engine.registrations());
        assertFalse(((ModBound) seen.get(seen.size() - 1)).bound());
        assertNull(services.players.get(P).facts.get("fish.species"));
        int before = seen.size();
        kept.get(0).accept(caughtEvent(P, loot("Common_Bluegill", "Common", 10), false, 99f));
        assertEquals(before, seen.size(), "nothing after unbind");
    }

    @Test
    void renamedCastEventGivesAPartialBindWithCatchesStillCounted() throws Exception {
        AnglersBinding.Names broken = new AnglersBinding.Names(FAKE.caughtEvent(), FAKE.failedEvent(),
                PKG + ".IEvents.FishingRodThrowEvent", FAKE.pullEvent(), FAKE.fishLoot(), FAKE.lootManager(),
                FAKE.book(), FAKE.habitat(), FAKE.fishEntry());
        binding = new AnglersBinding(broken, engine);
        bindFull();
        assertEquals(List.of(AnglersBinding.F_CAST), binding.missing());
        assertEquals(3, engine.registrations());
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(1, get(P, "fish:caught"));
    }

    @Test
    void renamedCatchEventSwitchesPagesAndSizeOffToo() throws Exception {
        AnglersBinding.Names broken = new AnglersBinding.Names(PKG + ".IEvents.FishCaughtEvent", FAKE.failedEvent(),
                FAKE.castEvent(), FAKE.pullEvent(), FAKE.fishLoot(), FAKE.lootManager(),
                FAKE.book(), FAKE.habitat(), FAKE.fishEntry());
        binding = new AnglersBinding(broken, engine);
        bindFull();
        assertEquals(List.of(AnglersBinding.F_FAILED, AnglersBinding.F_CAST, AnglersBinding.F_HOOK), binding.features());
        assertEquals(List.of(AnglersBinding.F_CATCH, AnglersBinding.F_PAGES, AnglersBinding.F_SIZE), binding.missing());
        engine.fire(castEvent(P, false));
        assertEquals(1, get(P, "fish:cast"));
    }

    @Test
    void renamedBookGivesAPartialBindWithoutPages() throws Exception {
        AnglersBinding.Names broken = new AnglersBinding.Names(FAKE.caughtEvent(), FAKE.failedEvent(),
                FAKE.castEvent(), FAKE.pullEvent(), FAKE.fishLoot(), FAKE.lootManager(),
                PKG + ".Models.AlmanacBook", FAKE.habitat(), FAKE.fishEntry());
        binding = new AnglersBinding(broken, engine);
        bindFull();
        assertEquals(List.of(AnglersBinding.F_PAGES), binding.missing());
        caught(P, "Legendary_Pike", "Legendary", 120, 99f);
        assertEquals(1, get(P, "fish:species"));
        assertEquals(0, get(P, "fish:pages"));
    }

    @Test
    void missingFishLootFailsTheWholeBind() {
        AnglersBinding.Names broken = new AnglersBinding.Names(FAKE.caughtEvent(), FAKE.failedEvent(),
                FAKE.castEvent(), FAKE.pullEvent(), PKG + ".Models.Loot", FAKE.lootManager(),
                FAKE.book(), FAKE.habitat(), FAKE.fishEntry());
        binding = new AnglersBinding(broken, engine);
        assertFalse(binding.bind(plugin, "1.2.2", services));
        assertEquals(0, engine.registrations());
        assertTrue(seen.isEmpty());
    }

    @Test
    void aLootThatThrowsIsGuardedAndSwitchesTheCatchOffAtTheLimit() throws Exception {
        bindFull();
        Object bad = loot("Common_Bluegill", "Fish_Bluegill_Item", "Bluegill", "Common", 10, true);
        engine.fire(caughtEvent(P, bad, false, 99f));
        assertTrue(binding.isBound());
        assertEquals(ALL, binding.features(), "one failure keeps the feature");
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(1, get(P, "fish:caught"));
        for (int i = 1; i < FAILURES_TO_OFF; i++) {
            engine.fire(caughtEvent(P, bad, false, 99f));
        }
        assertTrue(binding.missing().contains(AnglersBinding.F_CATCH));
        caught(P, "Common_Bluegill", "Common", 10, 99f);
        assertEquals(1, get(P, "fish:caught"), "the feature is off");
        engine.fire(castEvent(P, false));
        assertEquals(1, get(P, "fish:cast"), "the other features still work");
    }
}
