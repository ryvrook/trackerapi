package dev.ryvrook.trackerapi.counters;

import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Keys;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.api.event.ModTally;
import dev.ryvrook.trackerapi.api.event.ModChoiceChanged;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.DungeonRun;
import dev.ryvrook.trackerapi.api.event.ModBossDefeated;
import dev.ryvrook.trackerapi.api.event.ItemEnchanted;
import dev.ryvrook.trackerapi.api.event.EnchantmentTriggered;
import dev.ryvrook.trackerapi.api.event.VeinMined;
import dev.ryvrook.trackerapi.api.event.LuckyDrop;
import dev.ryvrook.trackerapi.api.event.CompanionChanged;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.BlockUsed;
import dev.ryvrook.trackerapi.api.event.CreatureTamed;
import dev.ryvrook.trackerapi.api.event.DamageDealt;
import dev.ryvrook.trackerapi.api.event.EffectChanged;
import dev.ryvrook.trackerapi.api.event.EntityUsed;
import dev.ryvrook.trackerapi.api.event.InstanceDiscovered;
import dev.ryvrook.trackerapi.api.event.ItemBroke;
import dev.ryvrook.trackerapi.api.event.ObjectiveCompleted;
import dev.ryvrook.trackerapi.api.event.PlayerSlept;
import dev.ryvrook.trackerapi.api.event.RecipeLearned;
import dev.ryvrook.trackerapi.api.event.ReputationChanged;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import dev.ryvrook.trackerapi.api.event.InteractionStarted;
import dev.ryvrook.trackerapi.api.event.ItemConsumed;
import dev.ryvrook.trackerapi.api.event.ItemCrafted;
import dev.ryvrook.trackerapi.api.event.ItemDropped;
import dev.ryvrook.trackerapi.api.event.ItemPickedUp;
import dev.ryvrook.trackerapi.api.event.ItemsGained;
import dev.ryvrook.trackerapi.api.event.ItemsLost;
import dev.ryvrook.trackerapi.api.event.MemoryRecorded;
import dev.ryvrook.trackerapi.api.event.MountChanged;
import dev.ryvrook.trackerapi.api.event.MovementEdge;
import dev.ryvrook.trackerapi.api.event.PlayerChatted;
import dev.ryvrook.trackerapi.api.event.PlayerCommand;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.api.event.PlayerMoved;
import dev.ryvrook.trackerapi.api.event.PlayerRespawned;
import dev.ryvrook.trackerapi.api.event.PlayerTeleported;
import dev.ryvrook.trackerapi.api.event.PlayerWorldChanged;
import dev.ryvrook.trackerapi.api.event.ProjectileLanded;
import dev.ryvrook.trackerapi.api.event.ProjectileLaunched;
import dev.ryvrook.trackerapi.api.event.WorldEventEnded;
import dev.ryvrook.trackerapi.api.event.ZoneDiscovered;
import dev.ryvrook.trackerapi.bus.Publisher;
import dev.ryvrook.trackerapi.facts.Crops;
import dev.ryvrook.trackerapi.facts.Forgetting;
import dev.ryvrook.trackerapi.facts.KillStreaks;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns events into counter writes, on the publishing thread. One switch over the kind, a handful of
 * map writes under the player's monitor, no allocation on the busy kinds. The rules here are the
 * canonical ones every consumer can rely on, see {@link Keys}.
 *
 * <p>Creative mode counts here as it does in the engine, the {@code creative} flag on the block events
 * lets a consumer skip it. The anti farm rules are the Feats ones. Breaking a block the player placed
 * recently and placing a block on a spot used recently count nothing, neither the total nor the per id key.
 * A ripe crop broken by hand counts as a harvest even on a spot the player planted, since it had to grow.
 * Chat counts at most one message per player every five seconds, and a cancelled message never counts.
 * Kill streaks, the chat timer and the fractions of mod XP under one point outlive a relog and are forgotten an
 * hour after the player left, through {@link Forgetting}.
 */
public final class CounterSink implements Publisher.Sink {
    private static final Set<EventKind> WANTED = EnumSet.of(EventKind.BLOCK_BROKEN, EventKind.BLOCK_PLACED,
            EventKind.BLOCK_USED, EventKind.ITEM_CRAFTED, EventKind.ITEM_PICKED_UP, EventKind.ITEMS_GAINED,
            EventKind.ITEMS_LOST, EventKind.ITEM_CONSUMED, EventKind.ITEM_DROPPED, EventKind.DAMAGE_DEALT,
            EventKind.ENTITY_DIED, EventKind.CREATURE_TAMED, EventKind.PROJECTILE_LANDED, EventKind.PROJECTILE_LAUNCHED, EventKind.INTERACTION_STARTED,
            EventKind.PLAYER_JOINED, EventKind.PLAYER_LEFT, EventKind.PLAYER_WORLD_CHANGED, EventKind.PLAYER_RESPAWNED,
            EventKind.PLAYER_TELEPORTED, EventKind.PLAYER_CHATTED, EventKind.PLAYER_COMMAND, EventKind.ZONE_DISCOVERED,
            EventKind.PLAYER_MOVED, EventKind.MOVEMENT_EDGE, EventKind.MOUNT_CHANGED, EventKind.MEMORY_RECORDED,
            EventKind.EFFECT_CHANGED, EventKind.WORLD_EVENT_ENDED, EventKind.ENTITY_USED, EventKind.INSTANCE_DISCOVERED,
            EventKind.ITEM_BROKE, EventKind.OBJECTIVE_COMPLETED, EventKind.PLAYER_SLEPT, EventKind.RECIPE_LEARNED,
            EventKind.REPUTATION_CHANGED,
            EventKind.MOD_LEVEL_UP, EventKind.MOD_XP_GAINED, EventKind.MOD_CHOICE_CHANGED, EventKind.MOD_ABILITY_USED,
            EventKind.MOD_ACTION, EventKind.MOD_MILESTONE, EventKind.DUNGEON_RUN, EventKind.MOD_BOSS_DEFEATED,
            EventKind.ITEM_ENCHANTED, EventKind.ENCHANTMENT_TRIGGERED, EventKind.VEIN_MINED, EventKind.LUCKY_DROP,
            EventKind.COMPANION_CHANGED, EventKind.MOD_TALLY);

    private final CounterStore store;
    /** At most one counted chat message per player in this window, the Feats rule. */
    public static final long CHAT_GAP_MILLIS = 5_000L;

    private final KillStreaks streaks = new KillStreaks();
    private final Map<UUID, Long> lastChatAt = new ConcurrentHashMap<>();
    /** The fraction of mod XP under one point per player and key, carried until it adds up to a whole point. */
    private final Map<UUID, Map<String, Double>> xpRemainders = new ConcurrentHashMap<>();
    private volatile boolean countCreative = true;

    public CounterSink(CounterStore store) {
        this.store = store;
    }

    public void setCountCreative(boolean on) {
        countCreative = on;
    }

    public KillStreaks streaks() {
        return streaks;
    }

    /** Forgets the streak, the chat timer and the XP fractions of a player once they stayed away for the hour of grace. */
    public void registerWith(Forgetting forgetting) {
        forgetting.onPlayer(streaks::forget);
        forgetting.onPlayer(lastChatAt::remove);
        forgetting.onPlayer(xpRemainders::remove);
    }

    /**
     * The whole points of an XP amount once the fraction carried for this player and key is added. An award under a
     * point counts nothing now and a point later, so four awards of 0.25 count as one.
     */
    private long wholeXp(UUID player, String key, double amount) {
        Map<String, Double> carried = xpRemainders.computeIfAbsent(player, u -> new ConcurrentHashMap<>(4));
        synchronized (carried) {
            double total = amount + carried.getOrDefault(key, 0.0);
            long whole = (long) Math.floor(total + 1e-9);
            double rest = total - whole;
            if (rest > 1e-9) {
                carried.put(key, rest);
            } else {
                carried.remove(key);
            }
            return whole;
        }
    }

    @Override
    public boolean wants(EventKind kind) {
        return WANTED.contains(kind);
    }

    @Override
    public void accept(TrackerEvent event) {
        UUID p = event.player();
        String w = event.world();
        switch (event) {
            case BlockBroken e -> {
                if (p == null || (e.creative() && !countCreative)) {
                    return;
                }
                if (e.ripeCrop()) {
                    store.addId(p, Keys.P_CROPS_HARVESTED, Crops.baseId(e.blockId()), Keys.CROPS_HARVESTED, 1, w);
                }
                if (!e.playerPlaced()) {
                    store.addId(p, Keys.P_BLOCK_BROKEN, e.blockId(), Keys.BLOCK_BROKEN, 1, w);
                }
            }
            case BlockPlaced e -> {
                if (p == null || e.repeatSpot() || (e.creative() && !countCreative)) {
                    return;
                }
                store.addId(p, Keys.P_BLOCK_PLACED, e.itemId(), Keys.BLOCK_PLACED, 1, w);
                if (e.planted()) {
                    store.addId(p, Keys.P_CROPS_PLANTED, e.blockId(), Keys.CROPS_PLANTED, 1, w);
                }
            }
            case BlockUsed e -> store.addId(p, Keys.P_BLOCK_USED, e.blockId(), Keys.BLOCK_USED, 1, w);
            case ItemCrafted e -> store.addId(p, Keys.P_ITEM_CRAFTED, e.itemId(), Keys.ITEM_CRAFTED, Math.max(1, e.quantity()), w);
            case ItemPickedUp e -> {
                if (e.fresh() > 0) {
                    store.addId(p, Keys.P_ITEM_PICKED_UP, e.itemId(), Keys.ITEM_PICKED_UP, e.fresh(), w);
                }
            }
            case ItemsGained e -> {
                if (e.fresh() > 0) {
                    store.addId(p, Keys.P_ITEMS_GAINED, e.itemId(), Keys.ITEMS_GAINED, e.fresh(), w);
                }
            }
            case ItemsLost e -> store.addId(p, Keys.P_ITEMS_LOST, e.itemId(), Keys.ITEMS_LOST, e.quantity(), w);
            case ItemConsumed e -> store.addId(p, Keys.P_ITEM_CONSUMED, e.itemId(), Keys.ITEM_CONSUMED, 1, w);
            case ItemDropped e -> store.addId(p, Keys.P_ITEM_DROPPED, e.itemId(), Keys.ITEM_DROPPED, e.quantity(), w);
            case DamageDealt e -> damage(e, w);
            case EntityDied e -> death(e, w);
            case CreatureTamed e -> store.addId(p, Keys.P_TAMED, e.npcTypeId(), Keys.CREATURE_TAMED, 1, w);
            case ProjectileLaunched e -> {
                UUID shooter = e.shooter() == null ? null : e.shooter().playerUuid();
                if (shooter != null) {
                    store.add(shooter, Keys.SHOTS_FIRED, 1, w);
                }
            }
            case ProjectileLanded e -> {
                UUID shooter = e.shooter() == null ? null : e.shooter().playerUuid();
                if (shooter != null && e.hit() != null) {
                    store.add(shooter, Keys.SHOTS_HIT, 1, w);
                }
            }
            case InteractionStarted e -> {
                store.addId(p, Keys.P_INTERACTION, e.rootId(), Keys.INTERACTIONS, 1, w);
                if (e.affordable() && e.type() != null && e.type().startsWith("Ability")) {
                    store.add(p, Keys.ABILITY_CASTS, 1, w);
                }
            }
            case PlayerJoined e -> store.add(p, Keys.JOINS, 1, w);
            case PlayerLeft e -> {
                long seconds = e.sessionMillis() / 1000;
                if (seconds > 0) {
                    store.raise(p, Keys.SESSION_LONGEST, seconds);
                }
            }
            case PlayerWorldChanged e -> {
                store.addId(p, Keys.P_WORLD_ENTERED, store.worldKeys().keyOf(w),
                        e.fromWorld() == null ? null : Keys.WORLD_CHANGES, 1, null);
                if (e.portalTypeId() != null) {
                    store.addId(p, Keys.P_PORTAL, e.portalTypeId(), Keys.PORTALS_ENTERED, 1, w);
                }
            }
            case PlayerRespawned e -> store.add(p, Keys.RESPAWNS, 1, w);
            case PlayerTeleported e -> store.add(p, Keys.TELEPORTS, 1, w);
            case PlayerChatted e -> {
                if (p != null && !e.cancelled() && chatCounts(p, e.header().at())) {
                    store.add(p, Keys.CHAT, 1, w);
                }
            }
            case PlayerCommand e -> store.addId(p, Keys.P_COMMAND, e.command(), Keys.COMMANDS, 1, w);
            case ZoneDiscovered e -> store.discover(p, Keys.P_ZONE, e.zone(), Keys.ZONES_DISCOVERED);
            case PlayerMoved e -> {
                if (e.metres() <= 0) {
                    return;
                }
                store.add(p, Keys.DISTANCE, e.metres(), w);
                switch (e.mode()) {
                    case SWIM -> store.add(p, Keys.DISTANCE_SWIM, e.metres(), w);
                    case GLIDE -> store.add(p, Keys.DISTANCE_GLIDE, e.metres(), w);
                    case RIDE -> store.add(p, Keys.DISTANCE_RIDE, e.metres(), w);
                    default -> { }
                }
                long y = Math.round(e.y());
                store.raise(p, Keys.HIGHEST_Y, y);
                store.lower(p, Keys.LOWEST_Y, y);
            }
            case MovementEdge e -> {
                switch (e.edge()) {
                    case JUMP -> store.add(p, Keys.JUMPS, 1, w);
                    case GLIDE_START -> store.add(p, Keys.GLIDES, 1, w);
                    case LANDED -> {
                        long fell = Math.round(e.fallDistance());
                        if (fell >= 3) {
                            store.add(p, Keys.FALLS, 1, w);
                            store.add(p, Keys.FALL_DISTANCE, fell, w);
                            store.raise(p, Keys.LONGEST_FALL, fell);
                        }
                    }
                    default -> { }
                }
            }
            case MountChanged e -> {
                if (e.mounted()) {
                    store.addId(p, Keys.P_MOUNT, e.mountTypeId(), Keys.MOUNTS, 1, w);
                }
            }
            case MemoryRecorded e -> store.discover(p, Keys.P_MEMORY, e.memoryId(), Keys.MEMORIES);
            case EffectChanged e -> {
                if (e.applied()) {
                    store.addId(p, Keys.P_EFFECT, e.effectId(), Keys.EFFECTS_RECEIVED, 1, w);
                }
            }
            case WorldEventEnded e -> {
                for (UUID u : e.participants()) {
                    store.addId(u, Keys.P_WORLD_EVENT, e.eventId(), Keys.WORLD_EVENTS, 1, w);
                }
            }
            case EntityUsed e -> store.addId(p, Keys.P_ENTITY_USED, e.target() == null ? null : e.target().typeId(), Keys.ENTITIES_USED, 1, w);
            case InstanceDiscovered e -> store.discover(p, Keys.P_INSTANCE, e.instanceId() != null ? e.instanceId() : String.valueOf(e.instanceWorldUuid()), Keys.INSTANCES_DISCOVERED);
            case ItemBroke e -> store.addId(p, Keys.P_ITEM_BROKEN, e.itemId(), Keys.ITEMS_BROKEN, 1, w);
            case ObjectiveCompleted e -> store.discover(p, Keys.P_OBJECTIVE, e.objectiveId(), Keys.OBJECTIVES_COMPLETED);
            case PlayerSlept e -> {
                if (e.asleep()) {
                    store.add(p, Keys.SLEEPS, 1, w);
                }
            }
            case RecipeLearned e -> store.discover(p, Keys.P_RECIPE, e.recipeId(), Keys.RECIPES_KNOWN);
            case ReputationChanged e -> {
                PlayerCounters pc = store.live(p);
                if (pc != null && e.groupId() != null) {
                    pc.set(Keys.of(Keys.P_REPUTATION, e.groupId()), e.to());
                }
            }
            case ModLevelUp e -> {
                if (p == null || e.mod() == null) {
                    return;
                }
                boolean main = e.track() == null || e.track().isEmpty() || e.track().equals(ModKeys.PLAYER_TRACK);
                if (main) {
                    store.raise(p, Keys.of(e.mod(), ModKeys.LEVEL), e.newLevel());
                    store.add(p, Keys.of(e.mod(), ModKeys.LEVEL_UPS), Math.max(1, e.newLevel() - e.oldLevel()), w);
                    if (e.maxLevel() > 0 && e.newLevel() >= e.maxLevel()) {
                        store.discover(p, Keys.of(e.mod(), ModKeys.MAX_LEVEL_REACHED), ModKeys.PLAYER_TRACK, null);
                    }
                } else {
                    store.raise(p, Keys.of(Keys.of(e.mod(), ModKeys.SKILL_LEVEL), e.track()), e.newLevel());
                    store.add(p, Keys.of(e.mod(), ModKeys.SKILL_LEVEL_UPS), Math.max(1, e.newLevel() - e.oldLevel()), w);
                }
            }
            case ModXpGained e -> {
                if (p == null || e.mod() == null || !(e.amount() > 0)) {
                    return;
                }
                boolean main = e.track() == null || e.track().isEmpty() || e.track().equals(ModKeys.PLAYER_TRACK);
                String id = main ? e.source() : e.track();
                String key = Keys.of(e.mod(), ModKeys.XP);
                long amount = wholeXp(p, id == null ? key : Keys.of(key, id), e.amount());
                if (amount <= 0) {
                    return;
                }
                store.addId(p, key, id, key, amount, w);
            }
            case ModChoiceChanged e -> {
                if (p == null || e.mod() == null || e.slot() == null || e.id() == null) {
                    return;
                }
                store.discover(p, Keys.of(e.mod(), e.slot()), e.id(), Keys.of(e.mod(), ModKeys.plural(e.slot())));
            }
            case ModAbilityUsed e -> {
                if (p == null || e.mod() == null) {
                    return;
                }
                store.addId(p, Keys.of(e.mod(), ModKeys.ABILITIES), e.abilityId(), Keys.of(e.mod(), ModKeys.ABILITIES), 1, w);
            }
            case ModAction e -> {
                if (p == null || e.mod() == null || e.action() == null) {
                    return;
                }
                String key = Keys.of(e.mod(), e.action());
                store.add(p, key, 1, w);
                if (e.pvp()) {
                    store.add(p, Keys.of(key, ModKeys.PVP), 1, w);
                }
            }
            case ModMilestone e -> {
                if (p == null || e.mod() == null || e.category() == null || e.id() == null) {
                    return;
                }
                store.discover(p, Keys.of(e.mod(), e.category()), e.id(), Keys.of(e.mod(), ModKeys.plural(e.category())));
            }
            case DungeonRun e -> {
                if (p == null || e.mod() == null || e.phase() == null) {
                    return;
                }
                switch (e.phase()) {
                    case ENTERED -> store.addId(p, Keys.of(e.mod(), ModKeys.RUNS), e.dungeonId(), Keys.of(e.mod(), ModKeys.RUNS), 1, w);
                    case COMPLETED -> {
                        store.addId(p, Keys.of(e.mod(), ModKeys.CLEARED), e.dungeonId(), Keys.of(e.mod(), ModKeys.CLEARED), 1, w);
                        if (e.mode() != null && !e.mode().isEmpty()) {
                            store.add(p, Keys.of(Keys.of(e.mod(), ModKeys.CLEARED_MODE), e.mode()), 1, w);
                        }
                        if (e.durationMillis() > 0) {
                            store.lower(p, Keys.of(e.mod(), ModKeys.FASTEST), e.durationMillis());
                            if (e.dungeonId() != null) {
                                store.lower(p, Keys.of(Keys.of(e.mod(), ModKeys.FASTEST), e.dungeonId()), e.durationMillis());
                            }
                        }
                    }
                    case FAILED -> store.addId(p, Keys.of(e.mod(), ModKeys.FAILED), e.dungeonId(), Keys.of(e.mod(), ModKeys.FAILED), 1, w);
                    case LEFT -> { }
                }
            }
            case ModBossDefeated e -> {
                if (p == null || e.mod() == null) {
                    return;
                }
                store.addId(p, Keys.of(e.mod(), ModKeys.BOSSES), e.bossId(), Keys.of(e.mod(), ModKeys.BOSSES), 1, w);
            }
            case ItemEnchanted e -> {
                if (p == null || e.mod() == null) {
                    return;
                }
                store.addId(p, Keys.of(e.mod(), ModKeys.ITEMS), e.enchantId(), Keys.of(e.mod(), ModKeys.ITEMS), 1, w);
                if (e.level() > 0) {
                    store.raise(p, Keys.of(e.mod(), ModKeys.HIGHEST_LEVEL), e.level());
                }
            }
            case EnchantmentTriggered e -> {
                if (p == null || e.mod() == null) {
                    return;
                }
                store.addId(p, Keys.of(e.mod(), ModKeys.PROCS), e.enchantId(), Keys.of(e.mod(), ModKeys.PROCS), 1, w);
            }
            case VeinMined e -> {
                if (p == null || e.mod() == null || e.blocks() <= 0 || (e.creative() && !countCreative)) {
                    return;
                }
                store.add(p, Keys.of(e.mod(), ModKeys.VEINS), 1, w);
                store.addId(p, Keys.of(e.mod(), ModKeys.BLOCKS), e.blockId(), Keys.of(e.mod(), ModKeys.BLOCKS), e.blocks(), w);
                store.raise(p, Keys.of(e.mod(), ModKeys.LARGEST), e.blocks());
            }
            case LuckyDrop e -> {
                if (p == null || e.mod() == null || (e.creative() && !countCreative)) {
                    return;
                }
                store.addId(p, Keys.of(e.mod(), ModKeys.DROPS), e.blockId(), Keys.of(e.mod(), ModKeys.DROPS), 1, w);
                if (e.streak() > 0) {
                    store.raise(p, Keys.of(e.mod(), ModKeys.STREAK_BEST), e.streak());
                }
            }
            case CompanionChanged e -> {
                if (p == null || e.mod() == null || e.change() == null) {
                    return;
                }
                switch (e.change()) {
                    case TAMED -> store.addId(p, Keys.of(e.mod(), ModKeys.TAMED), e.npcTypeId(), Keys.of(e.mod(), ModKeys.TAMED), 1, w);
                    case BRED -> store.addId(p, Keys.of(e.mod(), ModKeys.BRED), e.npcTypeId(), Keys.of(e.mod(), ModKeys.BRED), 1, w);
                    case LEVEL_UP -> {
                        store.add(p, Keys.of(e.mod(), ModKeys.COMPANION_LEVEL_UPS), 1, w);
                        if (e.level() > 0) {
                            store.raise(p, Keys.of(e.mod(), ModKeys.COMPANION_LEVEL_BEST), e.level());
                        }
                    }
                    case DIED -> store.add(p, Keys.of(e.mod(), ModKeys.COMPANIONS_LOST), 1, w);
                    case RENAMED, MOUNTED, RELEASED -> { }
                }
            }
            case ModTally e -> {
                if (p == null || e.mod() == null || e.stat() == null || e.stat().isEmpty()) {
                    return;
                }
                String key = Keys.of(e.mod(), e.stat());
                if (e.amount() > 0) {
                    store.addId(p, key, e.id() == null || e.id().isEmpty() ? null : e.id(), key, e.amount(), w);
                }
                if (e.best() > 0) {
                    store.raise(p, Keys.of(e.mod(), e.stat() + "_best"), e.best());
                }
            }
            default -> { }
        }
    }

    /** True when this message is the first of the player's five second window, which it then opens. */
    private boolean chatCounts(UUID player, long now) {
        boolean[] counts = {false};
        lastChatAt.compute(player, (id, last) -> {
            if (last == null || now - last >= CHAT_GAP_MILLIS || now < last) {
                counts[0] = true;
                return now;
            }
            return last;
        });
        return counts[0];
    }

    private void damage(DamageDealt e, String w) {
        long amount = Math.round(e.amount());
        if (amount <= 0) {
            return;
        }
        UUID attacker = e.attacker() == null ? null : e.attacker().playerUuid();
        UUID victim = e.victim() == null ? null : e.victim().playerUuid();
        if (attacker != null) {
            store.add(attacker, Keys.DAMAGE_DEALT, amount, w);
            store.raise(attacker, Keys.BIGGEST_HIT, amount);
        }
        if (victim != null) {
            store.add(victim, Keys.DAMAGE_TAKEN, amount, w);
            store.addId(victim, Keys.P_DAMAGE_CAUSE, e.causeId(), null, amount, w);
        }
    }

    private void death(EntityDied e, String w) {
        Actor victim = e.victim();
        Actor killer = e.killer();
        UUID victimPlayer = victim == null ? null : victim.playerUuid();
        UUID killerPlayer = killer == null ? null : killer.playerUuid();
        if (victimPlayer != null) {
            store.add(victimPlayer, Keys.DEATHS, 1, w);
            store.addId(victimPlayer, Keys.P_DEATH, e.causeId(), null, 1, w);
            if (killer != null && killer.kind() != Actor.Kind.WORLD) {
                store.addId(victimPlayer, Keys.P_KILLED_BY, killer.typeId(), null, 1, w);
            }
            streaks.died(victimPlayer);
        }
        if (e.boss() && !e.participants().isEmpty()) {
            for (UUID u : e.participants()) {
                credit(u, victim, u.equals(killerPlayer) && e.ranged(), w);
                store.add(u, Keys.KILLS_BOSS, 1, w);
                if (e.untouched().contains(u)) {
                    store.add(u, Keys.BOSS_FLAWLESS, 1, w);
                }
            }
            return;
        }
        if (killerPlayer != null && victim != null) {
            credit(killerPlayer, victim, e.ranged(), w);
            if (e.boss()) {
                store.add(killerPlayer, Keys.KILLS_BOSS, 1, w);
            }
        }
    }

    private void credit(UUID killer, Actor victim, boolean ranged, String w) {
        store.addId(killer, Keys.P_KILL, victim.typeId(), Keys.KILLS, 1, w);
        if (victim.isPlayer()) {
            store.add(killer, Keys.KILLS_PLAYER, 1, w);
        }
        if (ranged) {
            store.add(killer, Keys.KILLS_RANGED, 1, w);
        }
        int streak = streaks.killed(killer);
        store.raise(killer, Keys.KILL_STREAK_BEST, streak);
    }
}
