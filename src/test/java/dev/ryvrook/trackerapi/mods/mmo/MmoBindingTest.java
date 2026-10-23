package dev.ryvrook.trackerapi.mods.mmo;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModChoiceChanged;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.mods.FakeMods;
import dev.ryvrook.trackerapi.mods.Handles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * The MMO Skill Tree binding against the fake mod under {@code fakes.mmo}, loaded through the isolating loader. The
 * bind lists every feature and seeds the levels and the class, the XP listener turns XP and level ups into events
 * and counters, the bus events give ability casts, quests, bounties and achievements, unbind removes the XP
 * listener and the bus registrations, a renamed method gives a partial bind, and a throwing event is guarded.
 */
class MmoBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final String API = "fakes.mmo.api.MMOSkillTreeAPI";
    private static final String FAKE_MOD = "fakes.mmo";
    private static final String FAKE_COMMON = "fakes.mmo.common";

    @TempDir
    Path dir;
    private Services services;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();
    private final FakeBus bus = new FakeBus();
    private final FakeWorlds worlds = new FakeWorlds();
    private MmoBinding binding;
    private ClassLoader loader;
    private Object plugin;

    /** The engine bus stand in, consumers per event class. */
    static final class FakeBus implements MmoNames.Bus {
        final Map<Class<?>, List<Consumer<Object>>> consumers = new HashMap<>();

        @Override
        public Runnable listen(Class<?> eventClass, Consumer<Object> consumer) {
            consumers.computeIfAbsent(eventClass, c -> new ArrayList<>()).add(consumer);
            return () -> consumers.getOrDefault(eventClass, List.of()).remove(consumer);
        }

        void fire(Object event) {
            for (Consumer<Object> c : List.copyOf(consumers.getOrDefault(event.getClass(), List.of()))) {
                c.accept(event);
            }
        }

        int count() {
            int n = 0;
            for (List<Consumer<Object>> l : consumers.values()) {
                n += l.size();
            }
            return n;
        }
    }

    /** The world reads stand in, runs the reader on the calling thread with a null store and a bare ref. */
    final class FakeWorlds implements MmoNames.Worlds {
        final Map<Object, UUID> players = new HashMap<>();
        int reads;

        Ref<EntityStore> refOf(UUID uuid) {
            for (Map.Entry<Object, UUID> e : players.entrySet()) {
                if (e.getValue().equals(uuid)) {
                    @SuppressWarnings("unchecked")
                    Ref<EntityStore> r = (Ref<EntityStore>) e.getKey();
                    return r;
                }
            }
            Ref<EntityStore> r = new Ref<>(null, players.size() + 1);
            players.put(r, uuid);
            return r;
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
            try {
                reader.read(uuid, null, refOf(uuid));
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        }

        @Override
        public UUID playerOf(Object ref) {
            return players.get(ref);
        }
    }

    @BeforeEach
    void setUp() throws Throwable {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        services.counters.join(P, "Alder", 1L);
        services.players.join(P, "Alder", 1L).world = "default";
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        binding = new MmoBinding(new MmoNames(FAKE_MOD, FAKE_COMMON, bus, worlds));
        loader = FakeMods.loader();
        plugin = Class.forName("fakes.mmo.MmoPlugin", true, loader).getConstructor().newInstance();
        api("setLevel", String.class, int.class).invoke("mining", 12);
        api("setLevel", String.class, int.class).invoke("woodcutting", 3);
        api("setTotalLevel", int.class).invoke(15);
        api("setClass", String.class).invoke("warrior");
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private MethodHandle api(String name, Class<?>... params) throws Exception {
        Class<?> type = Class.forName(API, true, loader);
        Class<?> returns = type.getMethod(name, params).getReturnType();
        return Handles.statik(type, name, returns, params);
    }

    private int listeners() throws Throwable {
        return Handles.intOf(api("listenerCount").invoke(), -1);
    }

    private static PlayerRef playerRef(UUID uuid) {
        return new PlayerRef(null, uuid, "Alder", "en", null, null);
    }

    private void xp(UUID uuid, String skill, long amount, int before, int after) throws Throwable {
        api("fire", PlayerRef.class, String.class, long.class, int.class, int.class).invoke(playerRef(uuid), skill, amount, before, after);
    }

    private Object event(String name, Object... args) throws Exception {
        Class<?> type = Class.forName(name, true, loader);
        Constructor<?> c = type.getConstructors()[0];
        return c.newInstance(args);
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

    private void bind() {
        assertTrue(binding.bind(plugin, "1.7.3", services));
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void fullBindListsEveryFeatureAndSeedsLevelsAndClass() throws Throwable {
        bind();
        assertEquals(List.of(MmoBinding.F_XP, MmoBinding.F_ABILITIES, MmoBinding.F_QUESTS, MmoBinding.F_ACHIEVEMENTS, MmoBinding.F_SEED),
                binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("1.7.3", binding.version());
        assertEquals(1, listeners(), "the XP listener is in the fake's list");
        assertEquals(4, bus.count(), "resolved casts, quest completed, quest claimed, achievement unlocked");
        // the seed read every online player
        assertEquals(1, worlds.reads);
        assertEquals(12, counter("mmo:skill_level:mining"));
        assertEquals(3, counter("mmo:skill_level:woodcutting"));
        assertEquals(15, counter("mmo:level"));
        assertEquals("warrior", services.players.get(P).facts.get("mmo.class"));
        List<ModChoiceChanged> picks = of(ModChoiceChanged.class);
        assertEquals(1, picks.size());
        assertEquals("class", picks.get(0).slot());
        assertEquals("warrior", picks.get(0).id());
        assertEquals(1, counter("mmo:class:warrior"));
        assertEquals(1, counter("mmo:classes"));
        // the board for the max stats
        services.counters.saveAll();
        services.counters.boards().rebuild();
        assertTrue(services.counters.boardKeys().contains("mmo:level"));
        // every stat key is under the prefix
        for (var spec : binding.stats()) {
            assertTrue(spec.key().startsWith("mmo:"), spec.key());
        }
    }

    @Test
    void xpEventsBecomeXpGainedAndLevelUps() throws Throwable {
        bind();
        int before = seen.size();
        xp(P, "mining", 40, 12, 12);
        List<ModXpGained> gains = of(ModXpGained.class);
        assertEquals(1, gains.size());
        assertEquals(P, gains.get(0).player());
        assertEquals("default", gains.get(0).world());
        assertEquals("mining", gains.get(0).track());
        assertEquals(40.0, gains.get(0).amount());
        assertNull(gains.get(0).source());
        assertEquals(40, counter("mmo:xp"));
        assertEquals(40, counter("mmo:xp:mining"));
        assertTrue(of(ModLevelUp.class).isEmpty());
        assertEquals(1, worlds.reads, "no level up, no read");

        api("setLevel", String.class, int.class).invoke("mining", 13);
        api("setTotalLevel", int.class).invoke(16);
        xp(P, "mining", 10, 12, 13);
        List<ModLevelUp> ups = of(ModLevelUp.class);
        assertEquals(1, ups.size());
        assertEquals("mining", ups.get(0).track());
        assertEquals(12, ups.get(0).oldLevel());
        assertEquals(13, ups.get(0).newLevel());
        assertEquals(0, ups.get(0).maxLevel());
        assertEquals(13, counter("mmo:skill_level:mining"));
        assertEquals(1, counter("mmo:skill_level_ups"));
        assertEquals(50, counter("mmo:xp"));
        assertEquals(2, worlds.reads, "a level up reads the player again");
        assertEquals(16, counter("mmo:level"));
        assertEquals(1, of(ModChoiceChanged.class).size(), "the same class is not a new pick");
        assertEquals(before + 3, seen.size());
        assertEquals(5, binding.published(), "the bind event, the pick, two gains and a level up");
    }

    @Test
    void joinSeedsThePlayerAndAClassChangeIsAPick() throws Throwable {
        bind();
        services.counters.join(Q, "Birch", 2L);
        services.players.join(Q, "Birch", 2L).world = "default";
        services.publisher.publish(new PlayerJoined(services.header("default", Q), "Birch", true));
        services.dispatcher.drainHere();
        assertEquals(2, worlds.reads);
        assertEquals(12, services.counters.get(Q, "mmo:skill_level:mining"));
        assertEquals("warrior", services.players.get(Q).facts.get("mmo.class"));
        assertEquals(2, of(ModChoiceChanged.class).size(), "one pick per player");

        api("setClass", String.class).invoke("mage");
        xp(Q, "mining", 10, 12, 13);
        assertEquals("mage", services.players.get(Q).facts.get("mmo.class"));
        List<ModChoiceChanged> picks = of(ModChoiceChanged.class);
        assertEquals(3, picks.size());
        assertEquals(Q, picks.get(2).player());
        assertEquals("mage", picks.get(2).id());
        assertEquals(2, services.counters.get(Q, "mmo:classes"));
        assertEquals("warrior", services.players.get(P).facts.get("mmo.class"), "the other player is untouched");
    }

    @Test
    void resolvedCastsOfPlayersBecomeAbilityUsed() throws Throwable {
        bind();
        Ref<EntityStore> caster = worlds.refOf(P);
        bus.fire(event("fakes.mmo.ability.event.MmoAbilityResolvedEvent", "fireball", caster, true, null, 1L));
        bus.fire(event("fakes.mmo.ability.event.MmoAbilityResolvedEvent", "fireball", caster, true, null, 2L));
        bus.fire(event("fakes.mmo.ability.event.MmoAbilityResolvedEvent", "dash", caster, true, null, 3L));
        // a failed gate and a caster that is not a player count for nothing
        bus.fire(event("fakes.mmo.ability.event.MmoAbilityResolvedEvent", "fireball", caster, false, "cooldown", 4L));
        bus.fire(event("fakes.mmo.ability.event.MmoAbilityResolvedEvent", "fireball", new Ref<EntityStore>(null, 99), true, null, 5L));
        List<ModAbilityUsed> used = of(ModAbilityUsed.class);
        assertEquals(3, used.size());
        assertEquals("fireball", used.get(0).abilityId());
        assertNull(used.get(0).itemId());
        assertEquals(P, used.get(2).player());
        assertEquals(3, counter("mmo:abilities"));
        assertEquals(2, counter("mmo:abilities:fireball"));
        assertEquals(1, counter("mmo:abilities:dash"));
    }

    @Test
    void questsAndBountiesAreMilestonesOnce() throws Throwable {
        bind();
        // parked completions wait for the claim
        bus.fire(event("fakes.mmo.common.quest.event.QuestCompletedEvent", "gather_wood", P, true, List.of()));
        assertTrue(of(ModMilestone.class).isEmpty());
        bus.fire(event("fakes.mmo.common.quest.event.QuestClaimedEvent", "gather_wood", P, 1, 0, 0, List.of()));
        List<ModMilestone> marks = of(ModMilestone.class);
        assertEquals(1, marks.size());
        assertEquals("quest", marks.get(0).category());
        assertEquals("gather_wood", marks.get(0).id());
        assertEquals(1, counter("mmo:quests"));
        assertEquals(1, counter("mmo:quest:gather_wood"));
        // an instant completion publishes at once and the claim that follows is not a second one
        bus.fire(event("fakes.mmo.common.quest.event.QuestCompletedEvent", "bounty_daily_1", P, false, List.of()));
        bus.fire(event("fakes.mmo.common.quest.event.QuestClaimedEvent", "bounty_daily_1", P, 1, 0, 0, List.of()));
        marks = of(ModMilestone.class);
        assertEquals(2, marks.size());
        assertEquals("bounty", marks.get(1).category());
        assertEquals(1, counter("mmo:bounties"));
        assertEquals(1, counter("mmo:bounty:bounty_daily_1"));
        assertEquals(1, counter("mmo:quests"));
        // a player TrackerAPI does not know gives no counter but is still an event
        bus.fire(event("fakes.mmo.common.quest.event.QuestClaimedEvent", "gather_wood", Q, 1, 0, 0, List.of()));
        assertEquals(3, of(ModMilestone.class).size());
        assertEquals(1, counter("mmo:quests"));
    }

    @Test
    void achievementsOfTheModAreMilestones() throws Throwable {
        bind();
        bus.fire(event("fakes.mmo.common.achievement.event.AchievementUnlockedEvent", "mmo_first_steps", P, 10, false, List.of()));
        bus.fire(event("fakes.mmo.common.achievement.event.AchievementUnlockedEvent", "dungeons_first_clear", P, 10, false, List.of()));
        bus.fire(event("fakes.mmo.common.achievement.event.AchievementUnlockedEvent", "mmo_first_steps", P, 10, false, List.of()));
        List<ModMilestone> marks = of(ModMilestone.class);
        assertEquals(2, marks.size(), "another mod's achievement is not counted here");
        assertEquals("achievement", marks.get(0).category());
        assertEquals("mmo_first_steps", marks.get(0).id());
        assertEquals(1, counter("mmo:achievements"), "a discovery counts once");
        assertEquals(1, counter("mmo:achievement:mmo_first_steps"));
    }

    @Test
    void unbindRemovesEverythingAndStaleCallbacksDoNothing() throws Throwable {
        bind();
        binding.unbind("test");
        assertFalse(binding.isBound());
        assertEquals(0, listeners(), "the XP listener left the fake's list");
        assertEquals(0, bus.count(), "the bus registrations were undone");
        assertNull(services.players.get(P).facts.get("mmo.class"));
        int before = seen.size();
        // a listener the mod kept would do nothing
        api("addXpListener", Consumer.class);
        xp(P, "mining", 40, 12, 13);
        assertEquals(before, seen.size());
        assertEquals(1, worlds.reads, "no read after unbind");

        // a rebind on a new instance registers everything again and seeds again
        ClassLoader second = FakeMods.loader();
        Object again = Class.forName("fakes.mmo.MmoPlugin", true, second).getConstructor().newInstance();
        assertTrue(binding.bind(again, "1.7.3", services));
        assertEquals(5, binding.features().size());
        assertEquals(4, bus.count());
        assertEquals(2, worlds.reads);
        assertEquals(0, listeners(), "the old class keeps no listener");
        Class<?> newApi = Class.forName(API, true, second);
        assertEquals(1, Handles.intOf(Handles.statik(newApi, "listenerCount", int.class).invoke(), -1));
    }

    @Test
    void renamedMethodGivesAPartialBind() throws Throwable {
        binding.breakXp = true;
        bind();
        assertEquals(List.of(MmoBinding.F_XP), binding.missing());
        assertEquals(List.of(MmoBinding.F_ABILITIES, MmoBinding.F_QUESTS, MmoBinding.F_ACHIEVEMENTS, MmoBinding.F_SEED), binding.features());
        assertEquals(0, listeners());
        assertEquals(12, counter("mmo:skill_level:mining"), "the seed still ran");
        bus.fire(event("fakes.mmo.common.achievement.event.AchievementUnlockedEvent", "mmo_first_steps", P, 10, false, List.of()));
        assertEquals(1, of(ModMilestone.class).size());
    }

    @Test
    void throwingEventIsGuarded() throws Throwable {
        bind();
        int before = seen.size();
        xp(P, "mining", -1, 12, 13);
        assertEquals(before, seen.size());
        assertTrue(binding.features().contains(MmoBinding.F_XP), "one failure does not switch the feature off");
        xp(P, "mining", 5, 12, 12);
        assertEquals(before + 1, seen.size());
    }

    @Test
    void bindingResolvesThroughTheModLoaderNotTheTestClasspath() throws Throwable {
        bind();
        Class<?> here = Class.forName(API);
        Class<?> there = Class.forName(API, true, loader);
        assertNotSame(here, there);
        assertEquals(0, Handles.intOf(Handles.statik(here, "listenerCount", int.class).invoke(), -1));
        assertEquals(1, Handles.intOf(Handles.statik(there, "listenerCount", int.class).invoke(), -1));
        assertEquals(EventKind.MOD_ABILITY_USED, new ModAbilityUsed(services.header(null, P), "mmo", "x", null, null).kind());
    }
}
