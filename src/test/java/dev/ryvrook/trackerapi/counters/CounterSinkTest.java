package dev.ryvrook.trackerapi.counters;

import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.BlockUsed;
import dev.ryvrook.trackerapi.api.event.ChunkLoaded;
import dev.ryvrook.trackerapi.api.event.CreatureTamed;
import dev.ryvrook.trackerapi.api.event.DamageDealt;
import dev.ryvrook.trackerapi.api.event.EffectChanged;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.EntityUsed;
import dev.ryvrook.trackerapi.api.event.InstanceDiscovered;
import dev.ryvrook.trackerapi.api.event.InteractionStarted;
import dev.ryvrook.trackerapi.api.event.ItemBroke;
import dev.ryvrook.trackerapi.api.event.ItemConsumed;
import dev.ryvrook.trackerapi.api.event.ItemCrafted;
import dev.ryvrook.trackerapi.api.event.ItemDropped;
import dev.ryvrook.trackerapi.api.event.ItemPickedUp;
import dev.ryvrook.trackerapi.api.event.ItemsGained;
import dev.ryvrook.trackerapi.api.event.ItemsLost;
import dev.ryvrook.trackerapi.api.event.MemoryRecorded;
import dev.ryvrook.trackerapi.api.event.MountChanged;
import dev.ryvrook.trackerapi.api.event.MovementEdge;
import dev.ryvrook.trackerapi.api.event.ObjectiveCompleted;
import dev.ryvrook.trackerapi.api.event.PlayerChatted;
import dev.ryvrook.trackerapi.api.event.PlayerCommand;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.api.event.PlayerMoved;
import dev.ryvrook.trackerapi.api.event.PlayerRespawned;
import dev.ryvrook.trackerapi.api.event.PlayerSlept;
import dev.ryvrook.trackerapi.api.event.PlayerTeleported;
import dev.ryvrook.trackerapi.api.event.PlayerWorldChanged;
import dev.ryvrook.trackerapi.api.event.ProjectileLanded;
import dev.ryvrook.trackerapi.api.event.ProjectileLaunched;
import dev.ryvrook.trackerapi.api.event.RecipeLearned;
import dev.ryvrook.trackerapi.api.event.ReputationChanged;
import dev.ryvrook.trackerapi.api.event.WorldEventEnded;
import dev.ryvrook.trackerapi.api.event.ZoneDiscovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every kind the sink counts, through a real store whose file work runs inline. */
class CounterSinkTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID R = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000199");
    private static final String W = "default";

    @TempDir
    Path dir;
    private CounterStore store;
    private CounterSink sink;

    @BeforeEach
    void setUp() {
        store = TestSupport.store(dir);
        store.loadServer();
        store.join(P, "Alder", 1L);
        store.join(Q, "Birch", 1L);
        store.join(R, "Cedar", 1L);
        sink = new CounterSink(store);
    }

    private static Header h(UUID player) {
        return TestSupport.header(W, player);
    }

    private long c(String key) {
        return store.get(P, key);
    }

    private long c(UUID who, String key) {
        return store.get(who, key);
    }

    private static Actor player(UUID u) {
        return Actor.player(u, "p" + u.getLeastSignificantBits());
    }

    private static Actor goblin() {
        return Actor.creature(UUID.randomUUID(), "Goblin_Scrapper");
    }

    private void accept(dev.ryvrook.trackerapi.api.TrackerEvent e) {
        assertTrue(sink.wants(e.kind()), e.kind().id());
        sink.accept(e);
    }

    @Test
    void wantsOnlyTheKindsItCounts() {
        assertTrue(sink.wants(EventKind.BLOCK_BROKEN));
        assertTrue(sink.wants(EventKind.REPUTATION_CHANGED));
        assertFalse(sink.wants(EventKind.CHUNK_LOADED));
        assertFalse(sink.wants(EventKind.VITALS_SAMPLED));
        assertFalse(sink.wants(EventKind.SERVER_LIFECYCLE));
        sink.accept(new ChunkLoaded(h(null), 1, 2)); // ignored without harm
    }

    @Test
    void blockBrokenOnOwnPlacementCountsNothing() {
        accept(new BlockBroken(h(P), 0, 0, 0, "Rock_Stone", null, false, false));
        accept(new BlockBroken(h(P), 0, 0, 0, "Rock_Stone", null, true, false));
        assertEquals(1, c(Keys.BLOCK_BROKEN));
        assertEquals(1, c("block_broken:Rock_Stone"), "the Feats rule, not even the per id key");
        assertEquals(1, c("world:default:block_broken"), "per world copy follows the total");
        assertEquals(1, store.server(Keys.BLOCK_BROKEN));
        assertEquals(1, store.server("block_broken:Rock_Stone"));
    }

    @Test
    void repeatPlacementCountsNothing() {
        accept(new BlockPlaced(h(P), 0, 0, 0, "Wood_Oak_Planks", "Wood_Oak_Planks", false, false));
        accept(new BlockPlaced(h(P), 0, 0, 0, "Wood_Oak_Planks", "Wood_Oak_Planks", true, false));
        assertEquals(1, c(Keys.BLOCK_PLACED));
        assertEquals(1, c("block_placed:Wood_Oak_Planks"));
    }

    @Test
    void instanceRunsShareOnePerWorldKey() {
        String run1 = "instance-Dungeon_Goblin-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
        String run2 = "instance-Dungeon_Goblin-11111111-2222-3333-4444-555555555555";
        accept(new BlockBroken(TestSupport.header(run1, P), 0, 0, 0, "Rock_Stone", null, false, false));
        accept(new BlockBroken(TestSupport.header(run2, P), 0, 0, 0, "Rock_Stone", null, false, false));
        accept(new PlayerWorldChanged(TestSupport.header(run1, P), "default", null, true));
        accept(new PlayerWorldChanged(TestSupport.header(run2, P), "default", null, true));
        assertEquals(2, c("world:instance.dungeon_goblin:block_broken"));
        assertEquals(2, c("world_entered:instance.dungeon_goblin"));
        assertTrue(store.all(P).keySet().stream().noneMatch(k -> k.contains("0f1e2d3c")), "no run uuid reaches a key");

        store.worldKeys().learn("portal-fragment-7", "Portal_Void", "");
        accept(new BlockBroken(TestSupport.header("portal-fragment-7", P), 0, 0, 0, "Rock_Stone", null, false, false));
        assertEquals(1, c("world:portal.portal_void:block_broken"), "a learned portal world keys by its type");
    }

    @Test
    void chatCountsOncePerFiveSecondsAndNeverWhenCancelled() {
        long t = 1_000_000L;
        accept(new PlayerChatted(new Header(t, 1, W, P), "Alder", 5, true, null));
        assertEquals(0, c(Keys.CHAT), "a cancelled message never counts");
        accept(new PlayerChatted(new Header(t + 100, 2, W, P), "Alder", 5, false, null));
        accept(new PlayerChatted(new Header(t + 4_000, 3, W, P), "Alder", 5, false, null));
        assertEquals(1, c(Keys.CHAT), "the window opened at the counted message");
        accept(new PlayerChatted(new Header(t + 5_100, 4, W, P), "Alder", 5, false, null));
        assertEquals(2, c(Keys.CHAT));
        accept(new PlayerChatted(new Header(t + 5_200, 5, W, Q), "Birch", 5, false, null));
        assertEquals(1, c(Q, Keys.CHAT), "the window is per player");
    }

    @Test
    void chatTimerAndStreakAreForgottenAfterTheGrace() {
        dev.ryvrook.trackerapi.facts.Forgetting forgetting = new dev.ryvrook.trackerapi.facts.Forgetting();
        sink.registerWith(forgetting);
        long t = 1_000_000L;
        accept(new PlayerChatted(new Header(t, 1, W, P), "Alder", 5, false, null));
        accept(died(goblin(), player(P), false, false, List.of(), Set.of()));
        forgetting.leave(P);
        assertEquals(1, sink.streaks().current(P), "a disconnect alone keeps both");
        accept(new PlayerChatted(new Header(t + 1_000, 2, W, P), "Alder", 5, false, null));
        assertEquals(1, c(Keys.CHAT));
        forgetting.forget(P);
        assertEquals(0, sink.streaks().current(P));
        accept(new PlayerChatted(new Header(t + 2_000, 3, W, P), "Alder", 5, false, null));
        assertEquals(2, c(Keys.CHAT), "a forgotten timer counts the next message");
    }

    @Test
    void creativeCountsOnlyWhenAllowed() {
        sink.setCountCreative(false);
        accept(new BlockBroken(h(P), 0, 0, 0, "Rock_Stone", null, false, true));
        accept(new BlockPlaced(h(P), 0, 0, 0, "Rock_Stone", null, false, true));
        assertEquals(0, c(Keys.BLOCK_BROKEN));
        assertEquals(0, c(Keys.BLOCK_PLACED));
        sink.setCountCreative(true);
        accept(new BlockBroken(h(P), 0, 0, 0, "Rock_Stone", null, false, true));
        assertEquals(1, c(Keys.BLOCK_BROKEN));
    }

    @Test
    void perWorldCopiesCanBeTurnedOff() {
        store.setPerWorld(false);
        accept(new BlockBroken(h(P), 0, 0, 0, "Rock_Stone", null, false, false));
        assertEquals(1, c(Keys.BLOCK_BROKEN));
        assertEquals(0, c("world:default:block_broken"));
    }

    @Test
    void offlinePlayersAndNullIdsAreIgnored() {
        accept(new BlockBroken(h(OFFLINE), 0, 0, 0, "Rock_Stone", null, false, false));
        accept(new BlockBroken(h(null), 0, 0, 0, "Rock_Stone", null, false, false));
        assertEquals(0, store.server(Keys.BLOCK_BROKEN));
        accept(new BlockUsed(h(P), 0, 0, 0, null));
        assertEquals(1, c(Keys.BLOCK_USED), "a missing id still counts the total");
        assertEquals(Set.of(Keys.BLOCK_USED, "world:default:block_used"), store.all(P).keySet());
    }

    @Test
    void itemKinds() {
        accept(new BlockUsed(h(P), 0, 0, 0, "Bench_Workbench"));
        accept(new ItemCrafted(h(P), "Recipe_Pickaxe", "Tool_Pickaxe_Iron", 0));
        accept(new ItemCrafted(h(P), "Recipe_Stick", "Ingredient_Stick", 4));
        accept(new ItemPickedUp(h(P), "Ingredient_Stick", 5, 3));
        accept(new ItemPickedUp(h(P), "Ingredient_Stick", 5, 0));
        accept(new ItemsGained(h(P), "Ore_Copper", 10, 6, ItemsGained.Source.CONTAINER, true, 1, 2, 3));
        accept(new ItemsGained(h(P), "Ore_Copper", 10, 0, ItemsGained.Source.CONTAINER, true, 1, 2, 3));
        accept(new ItemsLost(h(P), "Rock_Stone", 7, ItemsLost.How.STASHED, false, 0, 0, 0));
        accept(new ItemConsumed(h(P), "Food_Apple"));
        accept(new ItemDropped(h(P), "Rock_Stone", 3, false));
        accept(new ItemBroke(h(P), "Tool_Pickaxe_Iron"));

        assertEquals(1, c("block_used:Bench_Workbench"));
        assertEquals(5, c(Keys.ITEM_CRAFTED), "quantity at least one");
        assertEquals(1, c("item_crafted:Tool_Pickaxe_Iron"));
        assertEquals(4, c("item_crafted:Ingredient_Stick"));
        assertEquals(3, c(Keys.ITEM_PICKED_UP), "only the fresh part counts");
        assertEquals(3, c("item_picked_up:Ingredient_Stick"));
        assertEquals(6, c(Keys.ITEMS_GAINED));
        assertEquals(6, c("items_gained:Ore_Copper"));
        assertEquals(7, c(Keys.ITEMS_LOST));
        assertEquals(7, c("items_lost:Rock_Stone"));
        assertEquals(1, c(Keys.ITEM_CONSUMED));
        assertEquals(1, c("item_consumed:Food_Apple"));
        assertEquals(3, c(Keys.ITEM_DROPPED));
        assertEquals(3, c("item_dropped:Rock_Stone"));
        assertEquals(1, c(Keys.ITEMS_BROKEN));
        assertEquals(1, c("item_broken:Tool_Pickaxe_Iron"));
    }

    @Test
    void damageBothWays() {
        accept(new DamageDealt(h(P), player(P), goblin(), 12.6, "Physical", false, null, 0, 0, 0, false));
        accept(new DamageDealt(h(P), player(P), goblin(), 4.2, "Physical", false, null, 0, 0, 0, false));
        accept(new DamageDealt(h(P), goblin(), player(P), 7, "Physical", false, null, 0, 0, 0, false));
        accept(new DamageDealt(h(P), goblin(), player(P), 0.2, "Fall", false, null, 0, 0, 0, false));
        assertEquals(17, c(Keys.DAMAGE_DEALT));
        assertEquals(13, c(Keys.BIGGEST_HIT));
        assertEquals(7, c(Keys.DAMAGE_TAKEN));
        assertEquals(7, c("damage_cause:Physical"));
        assertEquals(0, c("damage_cause:Fall"), "rounds to zero, not counted");
        assertEquals(13, store.server(Keys.BIGGEST_HIT));
    }

    private EntityDied died(Actor victim, Actor killer, boolean ranged, boolean boss, List<UUID> participants, Set<UUID> untouched) {
        UUID about = victim.isPlayer() ? victim.uuid() : killer == null ? null : killer.playerUuid();
        return new EntityDied(h(about), victim, killer, "Physical", null, ranged, false, 0, 0, 0, boss, participants,
                untouched, false, null, 0);
    }

    @Test
    void killsDeathsAndPvp() {
        accept(died(goblin(), player(P), true, false, List.of(), Set.of()));
        accept(died(player(Q), player(P), false, false, List.of(), Set.of()));
        accept(died(player(P), Actor.WORLD, false, false, List.of(), Set.of()));
        accept(died(player(P), goblin(), false, false, List.of(), Set.of()));

        assertEquals(2, c(Keys.KILLS));
        assertEquals(1, c("kill:Goblin_Scrapper"));
        assertEquals(1, c("kill:Player"));
        assertEquals(1, c(Keys.KILLS_PLAYER));
        assertEquals(1, c(Keys.KILLS_RANGED));
        assertEquals(2, c(Keys.DEATHS));
        assertEquals(2, c("death:Physical"));
        assertEquals(1, c("killed_by:Goblin_Scrapper"), "a world death names no killer");
        assertEquals(1, c(Q, Keys.DEATHS));
        assertEquals(1, c(Q, "killed_by:Player"));
        assertEquals(2, c("world:default:kills"));
    }

    @Test
    void bossKillCreditsEveryParticipant() {
        Actor boss = Actor.creature(UUID.randomUUID(), "Goblin_Gobliterator");
        accept(died(boss, player(P), true, true, List.of(P, Q, R), Set.of(Q)));
        for (UUID u : List.of(P, Q, R)) {
            assertEquals(1, c(u, Keys.KILLS), u.toString());
            assertEquals(1, c(u, Keys.KILLS_BOSS));
            assertEquals(1, c(u, "kill:Goblin_Gobliterator"));
            assertEquals(1, c(u, Keys.KILL_STREAK_BEST));
        }
        assertEquals(1, c(P, Keys.KILLS_RANGED), "ranged goes to the killer only");
        assertEquals(0, c(Q, Keys.KILLS_RANGED));
        assertEquals(1, c(Q, Keys.BOSS_FLAWLESS), "only the untouched participant is flawless");
        assertEquals(0, c(P, Keys.BOSS_FLAWLESS));
        assertEquals(0, c(R, Keys.BOSS_FLAWLESS));
        assertEquals(3, store.server(Keys.KILLS_BOSS));

        accept(died(boss, player(R), false, true, List.of(), Set.of()));
        assertEquals(2, c(R, Keys.KILLS_BOSS), "a boss with no ledger credits the killer");
    }

    @Test
    void killStreakBestSurvivesADeathAndARelog() {
        for (int i = 0; i < 3; i++) {
            accept(died(goblin(), player(P), false, false, List.of(), Set.of()));
        }
        assertEquals(3, c(Keys.KILL_STREAK_BEST));
        assertEquals(3, sink.streaks().current(P));
        accept(died(player(P), goblin(), false, false, List.of(), Set.of()));
        assertEquals(0, sink.streaks().current(P));
        accept(died(goblin(), player(P), false, false, List.of(), Set.of()));
        accept(died(goblin(), player(P), false, false, List.of(), Set.of()));
        assertEquals(3, c(Keys.KILL_STREAK_BEST), "a shorter run does not lower the best");
        accept(new PlayerLeft(h(P), "Alder", 0));
        assertEquals(2, sink.streaks().current(P), "a relog within the hour of grace keeps the streak, as Feats does");
        sink.streaks().forget(P);
        for (int i = 0; i < 4; i++) {
            accept(died(goblin(), player(P), false, false, List.of(), Set.of()));
        }
        assertEquals(4, c(Keys.KILL_STREAK_BEST));
    }

    @Test
    void creaturesProjectilesAndInteractions() {
        accept(new CreatureTamed(h(P), "Cow", UUID.randomUUID(), "Cow_Tamed"));
        accept(new ProjectileLaunched(h(P), player(P), "Arrow", true));
        accept(new ProjectileLaunched(h(P), player(P), "Arrow", true));
        accept(new ProjectileLaunched(h(P), goblin(), "Arrow", true));
        accept(new ProjectileLanded(h(P), player(P), "Arrow", goblin(), 0, 0, 0));
        accept(new ProjectileLanded(h(P), player(P), "Arrow", null, 0, 0, 0));
        accept(new ProjectileLanded(h(P), goblin(), "Arrow", player(P), 0, 0, 0));
        accept(new InteractionStarted(h(P), "Root_Dash", "Ability1", true));
        accept(new InteractionStarted(h(P), "Root_Dash", "Ability1", false));
        accept(new InteractionStarted(h(P), "Root_Swing", "Primary", true));
        accept(new EntityUsed(h(P), Actor.creature(null, "Kweebec_Merchant"), "Use"));

        assertEquals(1, c(Keys.CREATURE_TAMED));
        assertEquals(1, c("tamed:Cow"));
        assertEquals(2, c(Keys.SHOTS_FIRED), "shots are counted when launched by a player");
        assertEquals(1, c(Keys.SHOTS_HIT));
        assertEquals(3, c(Keys.INTERACTIONS));
        assertEquals(2, c("interaction:Root_Dash"));
        assertEquals(1, c(Keys.ABILITY_CASTS), "only an affordable ability cast counts");
        assertEquals(1, c(Keys.ENTITIES_USED));
        assertEquals(1, c("entity_used:Kweebec_Merchant"));
    }

    @Test
    void sessionKinds() {
        accept(new PlayerJoined(h(P), "Alder", true));
        accept(new PlayerLeft(h(P), "Alder", 3_600_500));
        accept(new PlayerLeft(h(P), "Alder", 60_000));
        accept(new PlayerWorldChanged(TestSupport.header("Instance_1", P), null, null, false));
        accept(new PlayerWorldChanged(TestSupport.header("Instance_1", P), "default", "Portal_Device", true));
        accept(new PlayerRespawned(h(P), 0, 0, 0));
        accept(new PlayerTeleported(h(P), 0, 0, 0, 1, 1, 1));
        accept(new PlayerChatted(h(P), "Alder", 5, true, null));
        accept(new PlayerChatted(h(P), "Alder", 5, false, null));
        accept(new PlayerCommand(h(P), "Alder", "spawn"));

        assertEquals(1, c(Keys.JOINS));
        assertEquals(3600, c(Keys.SESSION_LONGEST));
        assertEquals(2, c("world_entered:instance_1"), "world keys are lower cased");
        assertEquals(1, c(Keys.WORLD_CHANGES), "the first world of a session is not a change");
        assertEquals(1, c(Keys.PORTALS_ENTERED));
        assertEquals(1, c("portal:Portal_Device"));
        assertEquals(1, c(Keys.RESPAWNS));
        assertEquals(1, c(Keys.TELEPORTS));
        assertEquals(1, c(Keys.CHAT), "a cancelled message does not count");
        assertEquals(1, c(Keys.COMMANDS));
        assertEquals(1, c("command:spawn"));
    }

    @Test
    void firstsAreCountedOnce() {
        for (int i = 0; i < 3; i++) {
            accept(new ZoneDiscovered(h(P), "Emerald_Wilds", "Zone1"));
            accept(new MemoryRecorded(h(P), "Memory_Goblin"));
            accept(new RecipeLearned(h(P), "Recipe_Pickaxe"));
            accept(new ObjectiveCompleted(h(P), "Objective_Tutorial"));
            accept(new InstanceDiscovered(h(P), UUID.fromString("00000000-0000-0000-0000-00000000beef"), null));
            accept(new InstanceDiscovered(h(P), null, "Dungeon_Goblin"));
        }
        assertEquals(1, c(Keys.ZONES_DISCOVERED));
        assertEquals(1, c("zone:Emerald_Wilds"));
        assertEquals(1, c(Keys.MEMORIES));
        assertEquals(1, c(Keys.RECIPES_KNOWN));
        assertEquals(1, c(Keys.OBJECTIVES_COMPLETED));
        assertEquals(2, c(Keys.INSTANCES_DISCOVERED));
        assertEquals(1, c("instance:00000000-0000-0000-0000-00000000beef"));
        assertEquals(1, c("instance:Dungeon_Goblin"));
        assertEquals(1, store.server(Keys.ZONES_DISCOVERED));
    }

    @Test
    void movement() {
        accept(new PlayerMoved(h(P), 0, 70.4, 0, 10, PlayerMoved.Mode.WALK, null, null));
        accept(new PlayerMoved(h(P), 0, 12, 0, 4, PlayerMoved.Mode.SWIM, null, null));
        accept(new PlayerMoved(h(P), 0, 140.6, 0, 6, PlayerMoved.Mode.GLIDE, null, null));
        accept(new PlayerMoved(h(P), 0, 90, 0, 20, PlayerMoved.Mode.RIDE, null, null));
        accept(new PlayerMoved(h(P), 0, -50, 0, 0, PlayerMoved.Mode.WALK, null, null));
        accept(new MovementEdge(h(P), MovementEdge.Edge.JUMP, 70, 0));
        accept(new MovementEdge(h(P), MovementEdge.Edge.GLIDE_START, 70, 0));
        accept(new MovementEdge(h(P), MovementEdge.Edge.LANDED, 70, 2.4));
        accept(new MovementEdge(h(P), MovementEdge.Edge.LANDED, 70, 8.6));
        accept(new MovementEdge(h(P), MovementEdge.Edge.LANDED, 70, 5));
        accept(new MovementEdge(h(P), MovementEdge.Edge.SPRINT_START, 70, 0));
        accept(new MountChanged(h(P), true, "Horse", UUID.randomUUID()));
        accept(new MountChanged(h(P), false, "Horse", UUID.randomUUID()));

        assertEquals(40, c(Keys.DISTANCE));
        assertEquals(4, c(Keys.DISTANCE_SWIM));
        assertEquals(6, c(Keys.DISTANCE_GLIDE));
        assertEquals(20, c(Keys.DISTANCE_RIDE));
        assertEquals(141, c(Keys.HIGHEST_Y));
        assertEquals(12, c(Keys.LOWEST_Y), "a sample with no distance is skipped entirely");
        assertEquals(1, c(Keys.JUMPS));
        assertEquals(1, c(Keys.GLIDES));
        assertEquals(2, c(Keys.FALLS), "falls under three blocks do not count");
        assertEquals(14, c(Keys.FALL_DISTANCE));
        assertEquals(9, c(Keys.LONGEST_FALL));
        assertEquals(1, c(Keys.MOUNTS));
        assertEquals(1, c("mount:Horse"));
    }

    @Test
    void effectsSleepReputationAndWorldEvents() {
        accept(new EffectChanged(h(P), "Burn", true));
        accept(new EffectChanged(h(P), "Burn", false));
        accept(new PlayerSlept(h(P), true, 0, 0, 0));
        accept(new PlayerSlept(h(P), false, 0, 0, 0));
        accept(new ReputationChanged(h(P), "Kweebec", 0, 20));
        accept(new ReputationChanged(h(P), "Kweebec", 20, -5));
        accept(new WorldEventEnded(h(null), "Blood_Moon", "COMPLETED", List.of(P, Q, OFFLINE)));

        assertEquals(1, c(Keys.EFFECTS_RECEIVED));
        assertEquals(1, c("effect:Burn"));
        assertEquals(1, c(Keys.SLEEPS));
        assertEquals(-5, c("reputation:Kweebec"), "reputation is a level, set rather than added");
        assertEquals(1, c(Keys.WORLD_EVENTS));
        assertEquals(1, c(Q, "world_event:Blood_Moon"));
        assertEquals(0, c(R, Keys.WORLD_EVENTS));
    }
}
