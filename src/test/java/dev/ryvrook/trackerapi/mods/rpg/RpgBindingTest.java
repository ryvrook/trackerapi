package dev.ryvrook.trackerapi.mods.rpg;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.ModChoiceChanged;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.mods.FakeMods;
import dev.ryvrook.trackerapi.mods.Handles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RPG Leveling binding against the fake mod under {@code fakes.rpg}, loaded through the isolating loader as the
 * live jar is. A full bind resolves four features and seeds the level and class of every online player, the two
 * listeners become the two records with their counters and facts, the class poll publishes a change once, a join
 * reads the new player, unbind takes the listeners and facts away, a renamed class leaves one feature missing, and
 * a callback that throws is guarded.
 */
class RpgBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final String PKG = "fakes.rpg";
    private static final RpgBinding.Names FAKE = RpgBinding.Names.inPackage(PKG);

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final FakeRpgPlayers players = new FakeRpgPlayers();
    private ClassLoader loader;
    private Object plugin;
    private Object api;
    private RpgBinding binding;

    @BeforeEach
    void setUp() throws Throwable {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        join(P, "Alder");
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        loader = FakeMods.loader();
        plugin = Class.forName(PKG + ".RPGLevelingPlugin", true, loader).getConstructor().newInstance();
        Class<?> apiType = Class.forName(PKG + ".RPGLevelingAPI", true, loader);
        api = Handles.statik(apiType, "get", apiType).invoke();
        players.put(P, playerRef(P));
        binding = new RpgBinding(FAKE, players);
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

    private Object call(String method, Object... args) throws Throwable {
        MethodHandle mh = Handles.virtualByName(api.getClass(), method, args.length);
        return mh.invokeWithArguments(prepend(api, args));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private int xpListeners() throws Throwable {
        return Handles.intOf(call("getExperienceGainedListenerCount"), -1);
    }

    private int levelListeners() throws Throwable {
        return Handles.intOf(call("getLevelUpListenerCount"), -1);
    }

    private long get(UUID uuid, String key) {
        return services.counters.get(uuid, key);
    }

    private String fact(UUID uuid, String key) {
        return services.players.get(uuid).facts.get(key);
    }

    private <T> List<T> of(Class<T> type) {
        return seen.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void bindFull() throws Throwable {
        call("setLevel", P, 12, 50);
        call("setClass", P, "warrior", "Warrior", 2);
        assertTrue(binding.bind(plugin, "0.4.2", services));
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsEveryFeatureAndSeedsLevelAndClass() throws Throwable {
        bindFull();
        assertEquals(List.of(RpgBinding.F_XP, RpgBinding.F_LEVEL_UP, RpgBinding.F_LEVEL, RpgBinding.F_CLASS), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("0.4.2", binding.version());
        assertEquals(1, xpListeners());
        assertEquals(1, levelListeners());
        // the seed of the online player
        assertEquals(1, players.visits);
        assertEquals(12, get(P, "rpg:level"));
        assertEquals("12", fact(P, "rpg.level"));
        assertEquals("Warrior", fact(P, "rpg.class"));
        assertEquals(1, get(P, "rpg:class:warrior"));
        assertEquals(1, get(P, "rpg:classes"));
        assertEquals(0, get(P, "rpg:max_level_reached:player"));
        // the first sighting of a class is not a change
        assertEquals(1, seen.size());
        assertInstanceOf(ModBound.class, seen.get(0));
        assertEquals("rpg", ((ModBound) seen.get(0)).mod());
        assertEquals(1, binding.published());
        // the join subscription is in place
        assertTrue(services.publisher.subscriptions().stream().anyMatch(s -> "mods/rpg".equals(s.owner())));
        assertEquals(Set.of(EventKind.MOD_XP_GAINED, EventKind.MOD_LEVEL_UP, EventKind.MOD_CHOICE_CHANGED), binding.kinds());
    }

    @Test
    void statsNameTheKeysTheEventsProduce() {
        List<String> keys = binding.stats().stream().map(StatSpec::key).toList();
        assertEquals(List.of("rpg:level", "rpg:level_ups", "rpg:max_level_reached", "rpg:xp", "rpg:xp", "rpg:class", "rpg:classes"), keys);
        assertEquals(StatSpec.Aggregate.MAX, binding.stats().get(0).aggregate());
        assertTrue(binding.stats().get(0).board());
        assertTrue(binding.stats().get(4).perId());
        assertEquals("Source", binding.stats().get(4).idLabel());
        assertEquals("xp", binding.stats().get(3).unit());
    }

    @Test
    void experienceBecomesModXpGainedWithItsSource() throws Throwable {
        bindFull();
        call("fireXp", P, 25.5, "ENTITY_KILL", false);
        List<ModXpGained> xp = of(ModXpGained.class);
        assertEquals(1, xp.size());
        assertEquals(P, xp.get(0).player());
        assertEquals("default", xp.get(0).world());
        assertEquals("rpg", xp.get(0).mod());
        assertEquals(ModKeys.PLAYER_TRACK, xp.get(0).track());
        assertEquals(25.5, xp.get(0).amount());
        assertEquals("ENTITY_KILL", xp.get(0).source());
        assertEquals(25, get(P, "rpg:xp"), "the half point is carried until it adds up");
        assertEquals(25, get(P, "rpg:xp:ENTITY_KILL"));

        // a cancelled event, a zero amount and a null player count nothing
        call("fireXp", P, 40.0, "COMMAND", true);
        call("fireXp", P, 0.0, "COMMAND", false);
        call("fireXp", null, 40.0, "COMMAND", false);
        assertEquals(1, of(ModXpGained.class).size());
        assertEquals(25, get(P, "rpg:xp"));

        // a source another mod created, and no source at all
        call("fireXp", P, 4.0, "TAMEWORK", false);
        call("fireXp", P, 2.0, null, false);
        List<ModXpGained> more = of(ModXpGained.class);
        assertEquals(3, more.size());
        assertEquals("TAMEWORK", more.get(1).source());
        assertNull(more.get(2).source());
        assertEquals(31, get(P, "rpg:xp"), "31.5 awarded, the half point is still carried");
        assertEquals(4, get(P, "rpg:xp:TAMEWORK"));
        assertEquals(4, binding.published());
    }

    @Test
    void levelUpBecomesModLevelUpWithTheMaxLevelFromTheRead() throws Throwable {
        bindFull();
        call("fireLevelUp", P, 12, 14);
        List<ModLevelUp> ups = of(ModLevelUp.class);
        assertEquals(1, ups.size());
        assertEquals(P, ups.get(0).player());
        assertEquals("rpg", ups.get(0).mod());
        assertEquals(ModKeys.PLAYER_TRACK, ups.get(0).track());
        assertEquals(12, ups.get(0).oldLevel());
        assertEquals(14, ups.get(0).newLevel());
        assertEquals(50, ups.get(0).maxLevel());
        assertEquals(14, get(P, "rpg:level"));
        assertEquals(2, get(P, "rpg:level_ups"));
        assertEquals("14", fact(P, "rpg.level"));
        assertEquals(0, get(P, "rpg:max_level_reached:player"));

        call("fireLevelUp", P, 49, 50);
        assertEquals(50, get(P, "rpg:level"));
        assertEquals(3, get(P, "rpg:level_ups"));
        assertEquals(1, get(P, "rpg:max_level_reached:player"));
    }

    @Test
    void levelReadSeedsTheMaxLevelMilestoneAndNeverLowersTheBest() throws Throwable {
        call("setLevel", P, 50, 50);
        assertTrue(binding.bind(plugin, "0.4.2", services));
        assertEquals(50, get(P, "rpg:level"));
        assertEquals(1, get(P, "rpg:max_level_reached:player"));
        // a reset to level 1 (the death penalty, a Zenith rank up) follows in the fact, not in the best
        call("setLevel", P, 1, 50);
        binding.poll();
        assertEquals(50, get(P, "rpg:level"));
        assertEquals("1", fact(P, "rpg.level"));
    }

    @Test
    void classChangeIsPublishedOnceAndOnlyWhenItChanges() throws Throwable {
        bindFull();
        binding.poll();
        assertEquals(2, players.visits);
        assertTrue(of(ModChoiceChanged.class).isEmpty(), "the same class again is not a change");

        call("setClass", P, "mage", "Mage", 1);
        binding.poll();
        List<ModChoiceChanged> picks = of(ModChoiceChanged.class);
        assertEquals(1, picks.size());
        assertEquals(P, picks.get(0).player());
        assertEquals("rpg", picks.get(0).mod());
        assertEquals("class", picks.get(0).slot());
        assertEquals("mage", picks.get(0).id());
        assertEquals("Mage", picks.get(0).name());
        assertEquals(1, picks.get(0).tier());
        assertEquals("Mage", fact(P, "rpg.class"));
        assertEquals(1, get(P, "rpg:class:mage"));
        assertEquals(1, get(P, "rpg:class:warrior"));
        assertEquals(2, get(P, "rpg:classes"));

        binding.poll();
        assertEquals(1, of(ModChoiceChanged.class).size());

        // a tier up of the same class is a change too
        call("setClass", P, "mage", "Mage", 2);
        binding.poll();
        assertEquals(2, of(ModChoiceChanged.class).size());
        assertEquals(2, of(ModChoiceChanged.class).get(1).tier());
        assertEquals(2, get(P, "rpg:classes"), "the same class is not discovered twice");

        // no class any more, the fact goes and nothing is published
        call("clearClass", P);
        binding.poll();
        assertNull(fact(P, "rpg.class"));
        assertEquals(2, of(ModChoiceChanged.class).size());
    }

    @Test
    void joinReadsTheNewPlayer() throws Throwable {
        bindFull();
        join(Q, "Birch");
        players.put(Q, playerRef(Q));
        call("setLevel", Q, 3, 50);
        call("setClass", Q, "ranger", "Ranger", 1);
        binding.onJoined(Q);
        assertEquals(2, players.visits);
        assertEquals(3, get(Q, "rpg:level"));
        assertEquals("3", fact(Q, "rpg.level"));
        assertEquals("Ranger", fact(Q, "rpg.class"));
        assertEquals(1, get(Q, "rpg:class:ranger"));
        assertEquals(12, get(P, "rpg:level"), "the other player was not touched");
        assertTrue(of(ModChoiceChanged.class).isEmpty());
        // a player who is not in a world yet is read by the next poll instead
        binding.onJoined(UUID.randomUUID());
        assertEquals(2, players.visits);
    }

    @Test
    void unbindRemovesTheListenersAndTheFacts() throws Throwable {
        bindFull();
        binding.unbind("the test says so");
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, xpListeners());
        assertEquals(0, levelListeners());
        assertNull(fact(P, "rpg.level"));
        assertNull(fact(P, "rpg.class"));
        assertNull(binding.apiForTest());
        assertFalse(services.publisher.subscriptions().stream().anyMatch(s -> "mods/rpg".equals(s.owner()) && s.isActive()));
        List<ModBound> bounds = of(ModBound.class);
        assertEquals(2, bounds.size());
        assertFalse(bounds.get(1).bound());

        // a listener the mod kept would do nothing now
        int before = seen.size();
        call("fireXp", P, 10.0, "ENTITY_KILL", false);
        call("fireLevelUp", P, 12, 13);
        binding.poll();
        assertEquals(before, seen.size());
        assertEquals(12, get(P, "rpg:level"));
    }

    @Test
    void bindingAgainRegistersOnceAndOnANewLoaderOnly() throws Throwable {
        bindFull();
        assertTrue(binding.bind(plugin, "0.4.2", services));
        assertEquals(1, xpListeners(), "the first bind was undone before the second");
        assertEquals(1, levelListeners());
    }

    @Test
    void renamedClassGivesAPartialBindWithTheRestWorking() throws Throwable {
        RpgBinding partial = new RpgBinding(new RpgBinding.Names(FAKE.api(), FAKE.xpListener(), FAKE.xpEvent(), FAKE.xpSource(),
                PKG + ".Missing", FAKE.levelEvent(), FAKE.levelInfo(), FAKE.classInfo()), players);
        call("setLevel", P, 12, 50);
        assertTrue(partial.bind(plugin, "0.4.2", services));
        try {
            assertEquals(List.of(RpgBinding.F_XP, RpgBinding.F_LEVEL, RpgBinding.F_CLASS), partial.features());
            assertEquals(List.of(RpgBinding.F_LEVEL_UP), partial.missing());
            assertEquals(1, xpListeners());
            assertEquals(0, levelListeners());
            assertEquals(12, get(P, "rpg:level"));
            call("fireXp", P, 5.0, "HARVEST", false);
            assertEquals(1, of(ModXpGained.class).size());
            assertEquals(5, get(P, "rpg:xp:HARVEST"));
        } finally {
            partial.unbind("test over");
        }
        assertEquals(0, xpListeners());
    }

    @Test
    void withoutTheApiClassTheBindFails() {
        RpgBinding none = new RpgBinding(RpgBinding.Names.inPackage("fakes.nowhere"), players);
        assertFalse(none.bind(plugin, "0.4.2", services));
        assertFalse(none.isBound());
        assertTrue(none.features().isEmpty());
        assertTrue(seen.isEmpty(), "no bound event for a bind that found nothing");
    }

    @Test
    void callbackThatThrowsIsGuarded() throws Throwable {
        bindFull();
        call("fireXp", P, Double.NaN, "ENTITY_KILL", false);
        assertTrue(binding.isBound());
        assertTrue(binding.features().contains(RpgBinding.F_XP), "one failure does not switch the feature off");
        assertTrue(of(ModXpGained.class).isEmpty());
        call("fireXp", P, 7.0, "ENTITY_KILL", false);
        assertEquals(1, of(ModXpGained.class).size());
        assertEquals(7, get(P, "rpg:xp"));
    }

    @Test
    void resolvesThroughTheModLoaderNotTheTestLoader() throws Throwable {
        bindFull();
        Class<?> here = Class.forName(PKG + ".RPGLevelingAPI");
        assertNotSame(here, binding.apiForTest().getClass());
        assertNotSame(here.getClassLoader(), binding.apiForTest().getClass().getClassLoader());
        assertEquals(loader, binding.apiForTest().getClass().getClassLoader());
    }

    @Test
    void productionNamesPointAtTheRealPackage() {
        RpgBinding.Names n = RpgBinding.Names.PRODUCTION;
        assertEquals("org.zuxaw.plugin.api.RPGLevelingAPI", n.api());
        assertEquals("org.zuxaw.plugin.api.RPGLevelingAPI$PlayerLevelInfo", n.levelInfo());
        assertEquals("org.zuxaw.plugin.api.RPGLevelingAPI$PlayerClassInfo", n.classInfo());
        assertEquals("org.zuxaw.plugin.api.ExperienceGainedListener", n.xpListener());
        assertEquals("org.zuxaw.plugin.api.LevelUpListener", n.levelListener());
        RpgBinding production = new RpgBinding();
        assertEquals("rpg", production.id());
        assertEquals("Zuxaw:RPGLeveling", production.pluginId());
        assertEquals("RPG Leveling", production.name());
    }
}
