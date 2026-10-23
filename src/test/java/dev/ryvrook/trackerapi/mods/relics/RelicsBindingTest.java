package dev.ryvrook.trackerapi.mods.relics;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.mods.FakeMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Wonder Weapons binding against a fake engine. Nothing of the mod is called, so the fake mod is a plugin
 * class alone, loaded through the isolating loader as a live plugin is. Kills arrive as TrackerAPI's own
 * {@code EntityDied} and are read against the fake hand, the chain scan is driven by the test.
 */
class RelicsBindingTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID MOB = UUID.fromString("00000000-0000-0000-0000-000000000399");
    /** The failures the guard allows one feature before it switches it off, AbstractBinding.FAILURES_TO_OFF. */
    private static final int FAILURES_TO_OFF = 20;

    /** The fake engine, a hand per player and a list of chains per player, visited when the test scans. */
    static final class FakeEngine implements RelicsBinding.Engine {
        final Map<UUID, String> hands = new HashMap<>();
        final Map<UUID, List<RelicsBinding.Chain>> chains = new HashMap<>();
        boolean noChains;
        boolean throwOnHand;
        int scans;

        @Override
        public String heldItem(UUID player) {
            if (throwOnHand) {
                throw new IllegalStateException("no hand");
            }
            return hands.get(player);
        }

        @Override
        public void checkChains() {
            if (noChains) {
                throw new IllegalStateException("no interaction module");
            }
        }

        @Override
        public void scanChains(RelicsBinding.ChainVisitor visitor) {
            scans++;
            for (Map.Entry<UUID, String> e : hands.entrySet()) {
                if (Relics.isRelic(e.getValue())) {
                    visitor.visit(e.getKey(), "default", e.getValue(), chains.getOrDefault(e.getKey(), List.of()));
                }
            }
        }
    }

    @TempDir
    Path dir;
    private Services services;
    private FakeEngine engine;
    private RelicsBinding binding;
    private ClassLoader loader;
    private Object plugin;
    private final List<TrackerEvent> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        services = Services.forTests(TestSupport.LOGGER, dir, TestSupport.config(), TestSupport.DIRECT);
        services.counters.loadServer();
        for (UUID u : List.of(P, Q)) {
            services.counters.join(u, u == P ? "Alder" : "Birch", 1L);
            services.players.join(u, u == P ? "Alder" : "Birch", 1L).world = "default";
        }
        services.publisher.subscribe("test", null, Delivery.SYNC, seen::add);
        engine = new FakeEngine();
        binding = new RelicsBinding(engine, 0);
        loader = FakeMods.loader();
        plugin = Class.forName("fakes.relics.WonderWeaponPlugin", true, loader).getConstructor().newInstance();
    }

    @AfterEach
    void tearDown() {
        binding.unbind("test over");
        services.publisher.close();
        services.timer.shutdownNow();
    }

    private void bind() {
        assertTrue(binding.bind(plugin, "1.1.1", services));
        seen.clear();
    }

    private void kill(UUID killer, Actor victim) {
        Actor k = killer == null ? Actor.creature(MOB, "Goblin_Scrapper") : Actor.player(killer, "Alder");
        UUID about = victim.isPlayer() ? victim.uuid() : killer;
        services.publisher.publish(new EntityDied(TestSupport.header("default", about), victim, k, "Physical", null,
                false, false, 0, 0, 0, false, List.of(), Set.of(), false, null, 0));
    }

    private void kill(UUID killer) {
        kill(killer, Actor.creature(MOB, "Goblin_Scrapper"));
    }

    private <T> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (TrackerEvent e : seen) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private long count(UUID player, String key) {
        return services.counters.get(player, key);
    }

    @Test
    void fullBindListsBothFeaturesAndItsStats() {
        assertEquals("relics", binding.id());
        assertEquals("wanmine:WansWonderWeapon", binding.pluginId());
        assertEquals("Wan's Wonder Weapons", binding.name());
        assertTrue(binding.bind(plugin, "1.1.1", services));
        assertEquals(List.of(RelicsBinding.F_KILLS, RelicsBinding.F_ABILITIES), binding.features());
        assertTrue(binding.missing().isEmpty());
        assertEquals("1.1.1", binding.version());
        assertSame(loader, plugin.getClass().getClassLoader());
        assertEquals(Set.of(EventKind.MOD_ABILITY_USED, EventKind.MOD_ACTION), binding.kinds());

        List<String> keys = new ArrayList<>();
        for (StatSpec s : binding.stats()) {
            keys.add(s.key() + (s.perId() ? ":<" + s.idLabel() + ">" : ""));
            assertTrue(Keys.isMod(s.key()), s.key());
        }
        assertEquals(List.of("relics:kills", "relics:kills:<Relic>", "relics:abilities", "relics:abilities:<Ability>",
                "relics:form_switch"), keys);

        ModBound bound = of(ModBound.class).get(0);
        assertEquals("relics", bound.mod());
        assertTrue(bound.bound());
        assertEquals(2, bound.features().size());
        assertTrue(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/relics")));
    }

    @Test
    void killsWithARelicCountPerRelicAndInTotal() {
        bind();
        engine.hands.put(P, Relics.VOID_REQUIEM);
        kill(P);
        kill(P);
        assertEquals(2, count(P, "relics:kills"));
        assertEquals(2, count(P, "relics:kills:" + Relics.VOID_REQUIEM));
        assertEquals(2, services.counters.server("relics:kills"));

        engine.hands.put(P, Relics.ECLIPSEBOUND);
        kill(P);
        assertEquals(3, count(P, "relics:kills"));
        assertEquals(1, count(P, "relics:kills:Wanmine_Weapon_Eclipsebound_Dagger"), "the lower case m id counts");

        // a relic the table does not know yet, by the mod's prefix
        engine.hands.put(P, "WanMine_Future_Blade");
        kill(P);
        assertEquals(1, count(P, "relics:kills:WanMine_Future_Blade"));

        // a player kill with a relic counts too
        engine.hands.put(P, Relics.GOD_SLAYER);
        kill(P, Actor.player(Q, "Birch"));
        assertEquals(5, count(P, "relics:kills"));
        assertEquals(1, count(P, "relics:kills:" + Relics.GOD_SLAYER));
        assertEquals(0, count(Q, "relics:kills"));
        assertTrue(of(ModAbilityUsed.class).isEmpty(), "kills publish no generic event");
        assertEquals(1, binding.published(), "the bound event alone");
    }

    @Test
    void killsWithoutARelicAndByCreaturesCountNothing() {
        bind();
        engine.hands.put(P, "Weapon_Sword_Iron");
        kill(P);
        engine.hands.put(P, null);
        kill(P);
        kill(null);
        // a death of the killer's own
        engine.hands.put(P, Relics.MJOLLNIR);
        kill(P, Actor.player(P, "Alder"));
        assertEquals(0, count(P, "relics:kills"));
        assertEquals(0, services.counters.server("relics:kills"));
    }

    @Test
    void newSignatureChainsPublishOneAbilityEach() {
        bind();
        engine.hands.put(P, Relics.VOID_REQUIEM);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Void_Requiem_Signature_Airblades"),
                new RelicsBinding.Chain(2, "Root_Weapon_Void_Requiem_Primary")));
        binding.scan();
        List<ModAbilityUsed> used = of(ModAbilityUsed.class);
        assertEquals(1, used.size());
        assertEquals("relics", used.get(0).mod());
        assertEquals("airblades", used.get(0).abilityId());
        assertEquals(Relics.VOID_REQUIEM, used.get(0).itemId());
        assertNull(used.get(0).targetTypeId());
        assertEquals(P, used.get(0).player());
        assertEquals("default", used.get(0).world());
        assertEquals(1, count(P, "relics:abilities"));
        assertEquals(1, count(P, "relics:abilities:airblades"));
        assertEquals(1, binding.remembered());

        // the same chains again are not new, and cost no new set
        java.util.Set<Integer> remembered = binding.remembered(P);
        assertEquals(java.util.Set.of(1, 2), remembered);
        binding.scan();
        binding.scan();
        assertEquals(1, of(ModAbilityUsed.class).size());
        assertSame(remembered, binding.remembered(P), "the remembered set is compared in place");
        // a chain that ended is a change, so the set is rebuilt
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Void_Requiem_Signature_Airblades")));
        binding.scan();
        assertEquals(java.util.Set.of(1), binding.remembered(P));
        assertEquals(1, of(ModAbilityUsed.class).size());

        // a new chain beside the old one
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Void_Requiem_Signature_Airblades"),
                new RelicsBinding.Chain(3, "Root_Weapon_Void_Requiem_Ability4_Teleport")));
        binding.scan();
        used = of(ModAbilityUsed.class);
        assertEquals(2, used.size());
        assertEquals("teleport", used.get(1).abilityId());
        assertEquals(2, count(P, "relics:abilities"));
        assertEquals(1, count(P, "relics:abilities:teleport"));

        // the chains end, then a chain id comes back as a fresh chain
        engine.chains.put(P, List.of());
        binding.scan();
        assertEquals(0, binding.remembered(), "no live chains, nothing remembered");
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Void_Requiem_Signature_Airblades")));
        binding.scan();
        assertEquals(3, of(ModAbilityUsed.class).size());
        assertEquals(2, count(P, "relics:abilities:airblades"));
        assertEquals(4, binding.published(), "three abilities and the bound event");
    }

    @Test
    void vanillaSignatureRootsAreAttributedToTheRelicInHand() {
        bind();
        engine.hands.put(P, Relics.ASHTHORN);
        engine.hands.put(Q, Relics.MAELSTROM);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(7, "Root_Weapon_Daggers_Signature_Razorstrike")));
        engine.chains.put(Q, List.of(new RelicsBinding.Chain(7, "Root_Weapon_Mace_Signature_Groundslam")));
        binding.scan();
        List<ModAbilityUsed> used = of(ModAbilityUsed.class);
        assertEquals(2, used.size());
        Map<UUID, ModAbilityUsed> by = new HashMap<>();
        for (ModAbilityUsed u : used) {
            by.put(u.player(), u);
        }
        assertEquals("razorstrike", by.get(P).abilityId());
        assertEquals(Relics.ASHTHORN, by.get(P).itemId());
        assertEquals("groundslam", by.get(Q).abilityId());
        assertEquals(Relics.MAELSTROM, by.get(Q).itemId());
        assertEquals(1, count(P, "relics:abilities:razorstrike"));
        assertEquals(1, count(Q, "relics:abilities:groundslam"));
        assertEquals(2, binding.remembered(), "one entry per player, the same chain id apart");
    }

    @Test
    void formSwitchRootsPublishAnActionWithoutAnAbility() {
        bind();
        engine.hands.put(P, Relics.ECLIPSEBOUND);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Eclipsebound_DayToNight_Switch")));
        binding.scan();
        engine.hands.put(P, Relics.CHROMATIC_CLEAVER);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Eclipsebound_DayToNight_Switch"),
                new RelicsBinding.Chain(2, "Root_Weapon_Chromatic_Cleaver_Telekinetic_Stance_Change")));
        binding.scan();
        List<ModAction> actions = of(ModAction.class);
        assertEquals(2, actions.size());
        for (ModAction a : actions) {
            assertEquals("relics", a.mod());
            assertEquals("form_switch", a.action());
            assertFalse(a.pvp());
            assertNull(a.targetTypeId());
            assertEquals(P, a.player());
        }
        assertEquals(2, count(P, "relics:form_switch"));
        assertEquals(0, count(P, "relics:abilities"));
        assertTrue(of(ModAbilityUsed.class).isEmpty());
    }

    @Test
    void playersWhoDropTheRelicOrLeaveAreForgotten() {
        bind();
        engine.hands.put(P, Relics.SOULBLIGHT);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Soulblight_Signature_Summon")));
        binding.scan();
        assertEquals(1, binding.remembered());
        assertEquals(1, count(P, "relics:abilities:summon"));
        // the relic goes back into the bag, the fake engine stops visiting
        engine.hands.put(P, "Weapon_Sword_Iron");
        binding.scan();
        assertEquals(1, binding.remembered(), "not visited, still remembered until the player leaves");
        services.players.leave(P);
        binding.scan();
        assertEquals(0, binding.remembered());
    }

    @Test
    void theRememberedChainsAreBounded() {
        bind();
        engine.hands.put(P, Relics.HEARTROOT);
        List<RelicsBinding.Chain> many = new ArrayList<>();
        for (int i = 0; i < RelicsBinding.SEEN_LIMIT * 3; i++) {
            many.add(new RelicsBinding.Chain(i, "Root_Weapon_Daggers_Primary"));
        }
        engine.chains.put(P, many);
        binding.scan();
        assertEquals(1, binding.remembered());
        assertTrue(of(ModAbilityUsed.class).isEmpty());
    }

    @Test
    void unbindClosesTheSubscriptionAndForgetsEverything() {
        bind();
        engine.hands.put(P, Relics.QUASAR_COSMIC);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Quasar_Cosmic_Signature_Vortexstrike")));
        binding.scan();
        kill(P);
        assertEquals(1, count(P, "relics:kills"));
        assertEquals(1, count(P, "relics:abilities:black_hole"));
        assertEquals(1, binding.remembered());

        binding.unbind("the mod is gone");
        assertFalse(binding.isBound());
        assertNull(binding.version());
        assertEquals(0, binding.remembered());
        assertFalse(services.publisher.subscriptions().stream().anyMatch(s -> s.owner().equals("mods/relics")));
        ModBound last = of(ModBound.class).get(of(ModBound.class).size() - 1);
        assertFalse(last.bound());

        seen.clear();
        kill(P);
        engine.chains.put(P, List.of(new RelicsBinding.Chain(2, "Root_Weapon_Quasar_Cosmic_Signature_Vortexstrike")));
        int scans = engine.scans;
        binding.scan();
        assertEquals(scans, engine.scans, "no scan while unbound");
        assertEquals(1, count(P, "relics:kills"));
        assertEquals(1, count(P, "relics:abilities"));
        assertTrue(of(ModAbilityUsed.class).isEmpty());
        assertTrue(of(ModAction.class).isEmpty());
        assertTrue(of(ModBound.class).isEmpty());
    }

    @Test
    void anEngineWithoutChainsGivesAPartialBindWithKillsWorking() {
        engine.noChains = true;
        assertTrue(binding.bind(plugin, "1.1.1", services));
        assertEquals(List.of(RelicsBinding.F_KILLS), binding.features());
        assertEquals(List.of(RelicsBinding.F_ABILITIES), binding.missing());
        engine.hands.put(P, Relics.FROSTBURN);
        kill(P);
        assertEquals(1, count(P, "relics:kills:" + Relics.FROSTBURN));
        engine.chains.put(P, List.of(new RelicsBinding.Chain(1, "Root_Weapon_Daggers_Signature_Razorstrike")));
        binding.scan();
        assertEquals(0, engine.scans);
        assertEquals(0, count(P, "relics:abilities"));
    }

    @Test
    void aFailingHandReadIsGuardedAndSwitchesKillsOffAtTheLimit() {
        bind();
        engine.throwOnHand = true;
        for (int i = 0; i < FAILURES_TO_OFF - 1; i++) {
            kill(P);
        }
        assertTrue(binding.features().contains(RelicsBinding.F_KILLS));
        kill(P);
        assertEquals(List.of(RelicsBinding.F_KILLS), binding.missing());
        assertEquals(List.of(RelicsBinding.F_ABILITIES), binding.features());
        engine.throwOnHand = false;
        engine.hands.put(P, Relics.LETHAL_LEFTOVERS);
        kill(P);
        assertEquals(0, count(P, "relics:kills"), "off until the next bind");
    }

    @Test
    void aSecondBindReplacesTheFirst() {
        bind();
        Object again = plugin;
        assertTrue(binding.bind(again, "1.1.1", services));
        assertEquals(1, services.publisher.subscriptions().stream().filter(s -> s.owner().equals("mods/relics")).count());
        engine.hands.put(P, Relics.HELIORAM);
        kill(P);
        assertEquals(1, count(P, "relics:kills"), "counted once, not once per bind");
    }

    @Test
    void isolatingLoaderGivesTheBindingAForeignPluginClass() throws Exception {
        Class<?> here = Class.forName("fakes.relics.WonderWeaponPlugin");
        assertNotSame(here, plugin.getClass());
        assertSame(loader, plugin.getClass().getClassLoader());
    }

    @Test
    void theTableHasSixteenRelicsAndEveryRootNamesOne() {
        assertEquals(16, Relics.ITEMS.size());
        assertEquals(16, new HashSet<>(Relics.ITEMS).size());
        assertEquals("Wanmine_Weapon_Eclipsebound_Dagger", Relics.ECLIPSEBOUND);
        for (String id : Relics.ITEMS) {
            assertEquals(id, Relics.relicOf(id));
            assertTrue(id.startsWith("WanMine_") || id.startsWith("Wanmine_"), id);
        }
        assertNull(Relics.relicOf(null));
        assertNull(Relics.relicOf(""));
        assertNull(Relics.relicOf("Weapon_Sword_Iron"));
        assertNull(Relics.relicOf("wanmine_lower"));
        assertEquals("Wanmine_New_Relic", Relics.relicOf("Wanmine_New_Relic"));

        assertEquals(13, Relics.SIGNATURES.size());
        Set<String> abilities = new HashSet<>();
        for (Relics.Signature s : Relics.SIGNATURES.values()) {
            assertSame(s, Relics.signatureOf(s.rootId()));
            assertTrue(abilities.add(s.ability()), "ability names are distinct, " + s.ability());
            assertTrue(s.ability().matches("[a-z_]+"), s.ability());
            if (s.relic() != null) {
                assertTrue(Relics.ITEMS.contains(s.relic()), s.relic());
            } else {
                assertTrue(s.rootId().startsWith("Root_Weapon_") && !s.rootId().contains("WanMine"), "vanilla root " + s.rootId());
            }
            assertFalse(Relics.isFormSwitch(s.rootId()));
        }
        assertEquals(Relics.GOD_SLAYER, Relics.signatureOf("Root_WanMine_GodSlayer_Signature").relic());
        assertEquals("mortal_verdict", Relics.signatureOf("Root_WanMine_GodSlayer_Signature").ability());
        assertEquals(4, Relics.FORM_SWITCHES.size());
        assertTrue(Relics.isFormSwitch("Root_Weapon_Chromatic_Cleaver_Stance_Change"));
        assertFalse(Relics.isFormSwitch(null));
        assertNull(Relics.signatureOf("Root_WanMine_Gaias_Wrath_Primary"), "primaries are not counted");
    }
}
