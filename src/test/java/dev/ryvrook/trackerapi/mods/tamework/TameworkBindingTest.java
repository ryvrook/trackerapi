package dev.ryvrook.trackerapi.mods.tamework;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.TrackerConfig;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.CompanionChanged;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.counters.CounterRules;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tamework binding against the fake mod, loaded through the isolating loader as the live one is. A full bind
 * lists every feature and subscribes on the bus and the feed, each event becomes the right record with counters
 * and facts, the null API window completes on the retry, unbind closes every subscription, a missing capability or
 * a broken feed gives a partial bind, and a callback that throws is guarded.
 */
class TameworkBindingTest {
    static final Names NAMES = new Names("fakes.tamework.Tamework", "fakes.tamework.api");
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID NPC2 = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    /** The failure count of {@code AbstractBinding} after which a feature is switched off, package private there. */
    private static final int FAILURES_TO_OFF = 20;
    private static final List<String> ALL_FEATURES = List.of(TameworkBinding.F_CORE, TameworkBinding.F_LIFECYCLE, TameworkBinding.F_XP,
            TameworkBinding.F_ACTIVITY, TameworkBinding.F_PROGRESSION, TameworkBinding.F_POPULATION);

    @TempDir
    Path dir;
    private Services services;
    private TameworkBinding binding;
    private ClassLoader loader;
    private Object plugin;
    private Object api;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TrackerConfig config = TestSupport.config();
        services = Services.forTests(TestSupport.LOGGER, dir, config, TestSupport.DIRECT);
        services.counters.loadServer();
        services.counters.join(P, "Alder", 1L);
        services.players.join(P, "Alder", 1L).world = "default";
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        binding = new TameworkBinding(NAMES, () -> "4.1.1");
        loader = FakeMods.loader();
        plugin = Class.forName(NAMES.plugin(), true, loader).getConstructor().newInstance();
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    // ---- driving the fake through the loader ---------------------------------------------------------------

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name + "/" + args.length);
    }

    private void install() throws Exception {
        api = call(plugin, "install");
    }

    private boolean bind() {
        return binding.bind(plugin, "5.5.1", services);
    }

    private int busCount() throws Exception {
        return (Integer) call(api, "busCount");
    }

    private int feedCount() throws Exception {
        return (Integer) call(api, "feedCount");
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

    private long counter(String stat) {
        return services.counters.get(P, Keys.of(TameworkBinding.ID, stat));
    }

    private long counter(String stat, String id) {
        return services.counters.get(P, ModKeys.key(TameworkBinding.ID, stat, id));
    }

    private String fact(UUID player, String key) {
        return services.players.get(player).facts.get(TameworkBinding.ID + '.' + key);
    }

    // ---- bind ----------------------------------------------------------------------------------------------

    @Test
    void fullBindListsEveryFeatureAndSubscribesOnTheBusAndTheFeed() throws Exception {
        install();
        assertTrue(bind());
        assertTrue(binding.isBound());
        assertEquals("5.5.1", binding.version());
        assertEquals(ALL_FEATURES, binding.features());
        assertTrue(binding.missing().isEmpty());
        assertFalse(binding.retrying());
        assertEquals(4, busCount(), "profile changes, deaths, lost and xp");
        assertEquals(1, feedCount());
        assertEquals("tamework:breed_success,tamework:cull_success,tamework:harvest", call(api, "feedActions"));
        assertEquals("MANAGED_CARE_PRODUCTION", call(api, "feedDomains"));
        assertEquals("4.1.1", fact(P, "husbandry"));
        assertNull(fact(P, "companions"), "no group is known yet, so no count is read");
        assertEquals(1, seen.size());
        ModBound bound = (ModBound) seen.get(0);
        assertEquals("tamework", bound.mod());
        assertEquals(TameworkBinding.PLUGIN_ID, bound.pluginId());
        assertTrue(bound.bound());
        // the join subscription is TrackerAPI's own
        boolean joins = false;
        for (Subscription s : services.publisher.subscriptions()) {
            joins |= s.owner().equals("mods/tamework");
        }
        assertTrue(joins);
        // the fake was resolved through the mod's loader, not the test classpath
        assertNotSame(Class.forName(NAMES.type("TameworkApi")), Class.forName(NAMES.type("TameworkApi"), true, loader));
        assertTrue(Class.forName(NAMES.type("TameworkApi"), true, loader).isInstance(api));
    }

    @Test
    void statsNameTheKeysTheSinkWritesAndTheBestIsKnownAsAMax() {
        List<String> keys = new ArrayList<>();
        for (StatSpec s : binding.stats()) {
            keys.add(s.key() + (s.perId() ? ":<" + s.idLabel() + ">" : ""));
            assertTrue(Keys.isMod(s.key()));
            assertFalse(s.label().contains(":"));
        }
        assertTrue(keys.contains("tamework:tamed"));
        assertTrue(keys.contains("tamework:tamed:<Species>"));
        assertTrue(keys.contains("tamework:bred"));
        assertTrue(keys.contains("tamework:companion_level_ups"));
        assertTrue(keys.contains("tamework:companion_level_best"));
        assertTrue(keys.contains("tamework:companions_lost"));
        assertTrue(keys.contains("tamework:xp"));
        assertTrue(keys.contains("tamework:xp:<Track>"));
        assertTrue(keys.contains("tamework:harvest"));
        assertTrue(keys.contains("tamework:cull"));
        assertTrue(CounterRules.isMax("tamework:companion_level_best"));
        assertEquals(3, binding.kinds().size());
        assertTrue(binding.kinds().contains(EventKind.COMPANION_CHANGED));
    }

    // ---- events ---------------------------------------------------------------------------------------------

    @Test
    void tameRenameAndReleaseBecomeCompanionChanged() throws Exception {
        install();
        call(api, "setOwned", P, 3L);
        assertTrue(bind());
        call(api, "fireTamed", P, NPC, "Tamed_Cow", "livestock");
        List<CompanionChanged> changes = seen(CompanionChanged.class);
        assertEquals(1, changes.size());
        CompanionChanged tamed = changes.get(0);
        assertEquals(CompanionChanged.Change.TAMED, tamed.change());
        assertEquals(P, tamed.player());
        assertEquals("default", tamed.world());
        assertEquals(NPC.toString(), tamed.companionId());
        assertEquals("Cow", tamed.npcTypeId());
        assertEquals("tamework", tamed.mod());
        assertEquals(1, counter(ModKeys.TAMED));
        assertEquals(1, counter(ModKeys.TAMED, "Cow"));
        // the group came with the event, so the owned count was read right after the tame
        assertEquals("3", fact(P, "companions"));
        assertEquals("livestock", call(api, "lastCountGroups"));

        call(api, "fireRenamed", P, NPC, "Tamed_Cow", "Daisy");
        call(api, "fireReleased", P, NPC, "Tamed_Cow");
        changes = seen(CompanionChanged.class);
        assertEquals(3, changes.size());
        assertEquals(CompanionChanged.Change.RENAMED, changes.get(1).change());
        assertEquals(CompanionChanged.Change.RELEASED, changes.get(2).change());
        assertEquals(P, changes.get(2).player(), "the owner of a released companion comes from the profile before the release");
        assertEquals("Cow", changes.get(2).npcTypeId());
        assertEquals(1, counter(ModKeys.TAMED), "renames and releases count nothing");
        assertEquals(4, binding.published(), "the bind event and three changes");
    }

    @Test
    void deathsAndLostCompanionsCountAsLost() throws Exception {
        install();
        assertTrue(bind());
        call(api, "fireDeath", P, NPC, "Tamed_Wolf_Black", true);
        call(api, "fireDeath", P, NPC2, "Wolf_Black", false);
        call(api, "fireLost", P, NPC2, "Tamed_Horse");
        List<CompanionChanged> changes = seen(CompanionChanged.class);
        assertEquals(2, changes.size());
        assertEquals(CompanionChanged.Change.DIED, changes.get(0).change());
        assertEquals("Wolf_Black", changes.get(0).npcTypeId());
        assertEquals(NPC.toString(), changes.get(0).companionId());
        assertEquals(CompanionChanged.Change.DIED, changes.get(1).change());
        assertEquals("Horse", changes.get(1).npcTypeId());
        assertEquals(2, counter(ModKeys.COMPANIONS_LOST));
        // the wolf's role resolved to a group, so the count was read with it
        assertEquals("beasts", call(api, "lastCountGroups"));
    }

    @Test
    void companionXpBecomesModXpGainedAndLevelUps() throws Exception {
        install();
        assertTrue(bind());
        call(api, "fireXp", P, NPC, "Tamed_Horse", "FEED", 12.5, 3, 3, false);
        List<ModXpGained> xp = seen(ModXpGained.class);
        assertEquals(1, xp.size());
        assertEquals(TameworkBinding.TRACK, xp.get(0).track());
        assertEquals(12.5, xp.get(0).amount());
        assertEquals("FEED", xp.get(0).source());
        assertEquals(P, xp.get(0).player());
        assertEquals(13, counter(ModKeys.XP));
        assertEquals(13, counter(ModKeys.XP, TameworkBinding.TRACK));
        assertTrue(seen(CompanionChanged.class).isEmpty());

        call(api, "fireXp", P, NPC, "Tamed_Horse", "HARVEST", 40.0, 3, 4, true);
        List<CompanionChanged> changes = seen(CompanionChanged.class);
        assertEquals(1, changes.size());
        assertEquals(CompanionChanged.Change.LEVEL_UP, changes.get(0).change());
        assertEquals(4, changes.get(0).level());
        assertEquals("Horse", changes.get(0).npcTypeId());
        assertEquals(1, counter(ModKeys.COMPANION_LEVEL_UPS));
        assertEquals(4, counter(ModKeys.COMPANION_LEVEL_BEST));

        // an event without the level falls back to the progression read
        call(api, "setLevel", NPC2, 9);
        call(api, "fireXp", P, NPC2, "Tamed_Cow", "BREEDING", 5.0, 0, 0, true);
        changes = seen(CompanionChanged.class);
        assertEquals(2, changes.size());
        assertEquals(9, changes.get(1).level());
        assertEquals(9, counter(ModKeys.COMPANION_LEVEL_BEST));
        assertEquals(2, counter(ModKeys.COMPANION_LEVEL_UPS));
        assertEquals(58, counter(ModKeys.XP));
    }

    @Test
    void feedBreedingsHarvestsAndCullsArePublishedAndFeedsAreFilteredOut() throws Exception {
        install();
        assertTrue(bind());
        call(api, "fireBreed", P, NPC, "Tamed_Cow", 2);
        List<CompanionChanged> changes = seen(CompanionChanged.class);
        assertEquals(2, changes.size(), "one per offspring");
        assertEquals(CompanionChanged.Change.BRED, changes.get(0).change());
        assertEquals(new UUID(0xB0L, 1).toString(), changes.get(0).companionId());
        assertEquals(new UUID(0xB0L, 2).toString(), changes.get(1).companionId());
        assertEquals("Cow", changes.get(0).npcTypeId());
        assertEquals(P, changes.get(0).player());
        assertEquals(2, counter(ModKeys.BRED));
        assertEquals(2, counter(ModKeys.BRED, "Cow"));

        call(api, "fireBreedWithoutActor", P, NPC, "Tamed_Sheep");
        changes = seen(CompanionChanged.class);
        assertEquals(3, changes.size());
        assertEquals(P, changes.get(2).player(), "the owner comes from the participant when there is no actor");
        assertEquals("Sheep", changes.get(2).npcTypeId());

        call(api, "fireHarvest", P, NPC, "Tamed_Cow", "Milk_Bucket", 3);
        call(api, "fireCull", P, NPC, "Tamed_Cow", "Raw_Beef", 4);
        call(api, "fireFeedAction", P, NPC, "Tamed_Cow");
        List<ModAction> actions = seen(ModAction.class);
        assertEquals(2, actions.size(), "the feed action never reached the binding");
        assertEquals(TameworkBinding.HARVEST, actions.get(0).action());
        assertEquals("Cow", actions.get(0).targetTypeId());
        assertEquals(3.0, actions.get(0).value());
        assertFalse(actions.get(0).pvp());
        assertEquals(TameworkBinding.CULL, actions.get(1).action());
        assertEquals(4.0, actions.get(1).value());
        assertEquals(1, counter(TameworkBinding.HARVEST));
        assertEquals(1, counter(TameworkBinding.CULL));
    }

    // ---- the null API window ---------------------------------------------------------------------------------

    @Test
    void bindWithANullApiIsNotAFailureAndCompletesWhenTheApiAppears() throws Exception {
        assertTrue(bind(), "the classes resolved, the API is just not there yet");
        assertTrue(binding.isBound());
        assertEquals(ALL_FEATURES, binding.features());
        assertTrue(binding.missing().isEmpty());
        assertTrue(binding.retrying());
        assertEquals(1, seen.size(), "the bind event");

        // the API is still null, a tick changes nothing
        binding.retryTick();
        assertTrue(binding.retrying());

        install();
        assertEquals(0, busCount());
        binding.retryTick();
        assertFalse(binding.retrying());
        assertEquals(4, busCount());
        assertEquals(1, feedCount());
        assertEquals("4.1.1", fact(P, "husbandry"));
        assertEquals(ALL_FEATURES, binding.features());

        call(api, "fireTamed", P, NPC, "Tamed_Cow", "livestock");
        List<CompanionChanged> changes = seen(CompanionChanged.class);
        assertEquals(1, changes.size());
        assertEquals(CompanionChanged.Change.TAMED, changes.get(0).change());

        binding.unbind("done");
        assertEquals(0, busCount());
        assertEquals(0, feedCount());
        assertFalse(binding.retrying());
    }

    @Test
    void retryGivesUpAfterTheWindowAndStaysBound() {
        assertTrue(bind());
        for (int i = 0; i < TameworkBinding.RETRY_ATTEMPTS; i++) {
            assertTrue(binding.retrying());
            binding.retryTick();
        }
        assertFalse(binding.retrying());
        assertTrue(binding.isBound());
        assertEquals(ALL_FEATURES, binding.features());
    }

    @Test
    void unbindCancelsTheRetry() {
        assertTrue(bind());
        assertTrue(binding.retrying());
        binding.unbind("gone");
        assertFalse(binding.retrying());
        assertFalse(binding.isBound());
        // a late tick is harmless
        binding.retryTick();
        assertFalse(binding.retrying());
    }

    @Test
    void apiMissingCapabilitiesWhenItAppearsSwitchesThoseFeaturesOff() throws Exception {
        assertTrue(bind());
        install();
        call(api, "withoutCapability", "COMPANION_XP_EVENTS");
        call(api, "withoutCapability", "ACTIVITY_FEED_V2");
        binding.retryTick();
        assertEquals(List.of(TameworkBinding.F_CORE, TameworkBinding.F_LIFECYCLE, TameworkBinding.F_PROGRESSION, TameworkBinding.F_POPULATION),
                binding.features());
        assertEquals(List.of(TameworkBinding.F_XP, TameworkBinding.F_ACTIVITY), binding.missing());
        assertEquals(3, busCount());
        assertEquals(0, feedCount());
    }

    // ---- unbind ---------------------------------------------------------------------------------------------

    @Test
    void unbindClosesEverySubscriptionAndClearsTheFacts() throws Exception {
        install();
        call(api, "setOwned", P, 2L);
        assertTrue(bind());
        call(api, "fireTamed", P, NPC, "Tamed_Cow", "livestock");
        assertEquals("2", fact(P, "companions"));
        assertEquals("4.1.1", fact(P, "husbandry"));

        binding.unbind("Alec's Tamework was reloaded");
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, busCount());
        assertEquals(0, feedCount());
        assertNull(fact(P, "companions"));
        assertNull(fact(P, "husbandry"));
        for (Subscription s : services.publisher.subscriptions()) {
            assertFalse(s.owner().equals("mods/tamework"), "the join subscription is closed");
        }
        int before = seen.size();
        assertFalse(((ModBound) seen.get(before - 1)).bound());
        // a subscription the mod kept would do nothing now
        call(api, "fireWrongType");
        assertEquals(before, seen.size());
    }

    // ---- partial binds --------------------------------------------------------------------------------------

    @Test
    void missingCapabilityGivesAPartialBindAndTheRestWorks() throws Exception {
        install();
        call(api, "withoutCapability", "COMPANION_XP_EVENTS");
        assertTrue(bind());
        assertEquals(List.of(TameworkBinding.F_CORE, TameworkBinding.F_LIFECYCLE, TameworkBinding.F_ACTIVITY, TameworkBinding.F_PROGRESSION,
                TameworkBinding.F_POPULATION), binding.features());
        assertEquals(List.of(TameworkBinding.F_XP), binding.missing());
        assertEquals(3, busCount(), "no xp subscription, so the mod never builds xp events");
        call(api, "fireXp", P, NPC, "Tamed_Horse", "FEED", 12.5, 3, 3, false);
        assertTrue(seen(ModXpGained.class).isEmpty());
        call(api, "fireTamed", P, NPC, "Tamed_Cow", "livestock");
        assertEquals(1, seen(CompanionChanged.class).size());
    }

    @Test
    void brokenFeedGivesAPartialBindAndTheBusStillWorks() throws Exception {
        install();
        call(api, "breakFeed");
        assertTrue(bind());
        assertEquals(List.of(TameworkBinding.F_ACTIVITY), binding.missing());
        assertEquals(0, feedCount());
        assertEquals(4, busCount());
        call(api, "fireDeath", P, NPC, "Tamed_Wolf_Black", true);
        assertEquals(1, counter(ModKeys.COMPANIONS_LOST));
    }

    @Test
    void renamedPluginClassFailsTheCoreAndTheBind() {
        TameworkBinding wrong = new TameworkBinding(new Names("fakes.tamework.Framework", NAMES.api()), () -> null);
        assertFalse(wrong.bind(plugin, "5.5.1", services));
        assertFalse(wrong.isBound());
        assertEquals(List.of(TameworkBinding.F_CORE), wrong.missing());
        assertTrue(wrong.features().isEmpty());
    }

    // ---- guard ----------------------------------------------------------------------------------------------

    @Test
    void throwingCallbacksAreGuardedAndTheFeatureStaysOnUntilTheLimit() throws Exception {
        install();
        assertTrue(bind());
        call(api, "fireWrongType");
        assertTrue(binding.isBound());
        assertEquals(ALL_FEATURES, binding.features());
        call(api, "fireTamed", P, NPC, "Tamed_Cow", "livestock");
        assertEquals(1, seen(CompanionChanged.class).size(), "the next good event still lands");
        for (int i = 0; i < FAILURES_TO_OFF; i++) {
            call(api, "fireWrongType");
        }
        assertTrue(binding.missing().contains(TameworkBinding.F_LIFECYCLE));
        assertTrue(binding.missing().contains(TameworkBinding.F_XP));
        call(api, "fireTamed", P, NPC2, "Tamed_Cow", "livestock");
        assertEquals(1, seen(CompanionChanged.class).size(), "a feature switched off publishes nothing");
    }

    // ---- players -------------------------------------------------------------------------------------------

    @Test
    void aJoiningPlayerGetsTheFacts() throws Exception {
        install();
        call(api, "setOwned", Q, 5L);
        assertTrue(bind());
        // a group has to be known before a count can be asked for
        call(api, "fireTamed", P, NPC, "Tamed_Cow", "livestock");
        services.counters.join(Q, "Birch", 2L);
        services.players.join(Q, "Birch", 2L).world = "default";
        assertNull(fact(Q, "husbandry"));
        services.publisher.publish(new PlayerJoined(services.header("default", Q), "Birch", false));
        services.dispatcher.drainHere();
        assertEquals("4.1.1", fact(Q, "husbandry"));
        assertEquals("5", fact(Q, "companions"));
    }

    @Test
    void speciesIsTheRoleWithoutThePrefix() {
        assertEquals("Cow", TameworkBinding.species("Tamed_Cow"));
        assertEquals("Wolf_Black", TameworkBinding.species("Tamed_Wolf_Black"));
        assertEquals("AH_Raccoon", TameworkBinding.species("Tamed_AH_Raccoon"));
        assertEquals("Cow", TameworkBinding.species("Cow"));
        assertEquals("Tamed_", TameworkBinding.species("Tamed_"));
        assertNull(TameworkBinding.species(null));
        assertNull(TameworkBinding.species(""));
    }
}
