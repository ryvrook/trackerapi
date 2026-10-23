package dev.ryvrook.trackerapi.api;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The counter key scheme. A total is a bare name, a per id counter is {@code <prefix>:<id>} and a per
 * world copy of a total is {@code world:<world>:<total>}. The world part, here and in
 * {@code world_entered:<world>}, is the world's stable key rather than its raw name, by the Chronicle Stats
 * rule. That is an ordinary world's name lower cased, such as {@code default}, {@code portal.<type>} for a
 * portal world, {@code instance.<template>} for an instance run, and {@code other} past 64 distinct keys. Ids are the engine's asset ids as they are, such
 * as {@code Rock_Stone}, {@code Goblin_Scrapper} or {@code Food_Apple}.
 *
 * <p>Per id keys are built once per id and kept, so the hot paths allocate nothing.
 */
public final class Keys {
    private Keys() {
    }

    // Totals, one per event kind or per side of it.
    public static final String BLOCK_BROKEN = "block_broken";
    public static final String BLOCK_PLACED = "block_placed";
    public static final String BLOCK_USED = "block_used";
    /** Seeds and saplings planted, a placement whose block has farming data. Replanting a harvested plot counts. */
    public static final String CROPS_PLANTED = "crops_planted";
    /** Ripe crops broken by hand, the F key harvest. Harvests through a sickle or a berry bush are not in it. */
    public static final String CROPS_HARVESTED = "crops_harvested";
    public static final String ITEM_CRAFTED = "item_crafted";
    public static final String ITEM_PICKED_UP = "item_picked_up";
    public static final String ITEMS_GAINED = "items_gained";
    public static final String ITEMS_LOST = "items_lost";
    public static final String ITEM_CONSUMED = "item_consumed";
    public static final String ITEM_DROPPED = "item_dropped";
    public static final String DAMAGE_DEALT = "damage_dealt";
    public static final String DAMAGE_TAKEN = "damage_taken";
    public static final String BIGGEST_HIT = "biggest_hit";
    public static final String KILLS = "kills";
    public static final String KILLS_PLAYER = "kills_player";
    public static final String KILLS_RANGED = "kills_ranged";
    public static final String KILLS_BOSS = "kills_boss";
    public static final String BOSS_FLAWLESS = "boss_flawless";
    public static final String DEATHS = "deaths";
    public static final String KILL_STREAK_BEST = "kill_streak_best";
    public static final String CREATURE_TAMED = "creature_tamed";
    public static final String SHOTS_HIT = "shots_hit";
    public static final String SHOTS_FIRED = "shots_fired";
    public static final String INTERACTIONS = "interactions";
    public static final String ABILITY_CASTS = "ability_casts";
    public static final String JOINS = "joins";
    public static final String PLAYTIME = "playtime";
    public static final String SESSION_LONGEST = "session_longest";
    public static final String WORLD_CHANGES = "world_changes";
    public static final String PORTALS_ENTERED = "portals_entered";
    public static final String RESPAWNS = "respawns";
    public static final String TELEPORTS = "teleports";
    public static final String CHAT = "chat";
    public static final String COMMANDS = "commands";
    public static final String ZONES_DISCOVERED = "zones_discovered";
    public static final String DISTANCE = "distance";
    public static final String DISTANCE_SWIM = "distance_swim";
    public static final String DISTANCE_GLIDE = "distance_glide";
    public static final String DISTANCE_RIDE = "distance_ride";
    public static final String JUMPS = "jumps";
    public static final String FALLS = "falls";
    public static final String FALL_DISTANCE = "fall_distance";
    public static final String LONGEST_FALL = "longest_fall";
    public static final String GLIDES = "glides";
    public static final String HIGHEST_Y = "highest_y";
    public static final String LOWEST_Y = "lowest_y";
    public static final String MOUNTS = "mounts";
    public static final String MEMORIES = "memories";
    public static final String EFFECTS_RECEIVED = "effects_received";
    public static final String WORLD_EVENTS = "world_events";
    public static final String OBJECTIVES_COMPLETED = "objectives_completed";
    public static final String RECIPES_KNOWN = "recipes_known";
    public static final String ITEMS_BROKEN = "items_broken";
    public static final String SLEEPS = "sleeps";
    public static final String ENTITIES_USED = "entities_used";
    public static final String INSTANCES_DISCOVERED = "instances_discovered";

    // Per id prefixes, without the colon.
    public static final String P_BLOCK_BROKEN = "block_broken";
    public static final String P_BLOCK_PLACED = "block_placed";
    public static final String P_BLOCK_USED = "block_used";
    /** Per crop block, such as {@code crops_planted:Plant_Crop_Carrot_Block}. */
    public static final String P_CROPS_PLANTED = "crops_planted";
    /** Per crop block without its stage, such as {@code crops_harvested:Plant_Crop_Carrot_Block}. */
    public static final String P_CROPS_HARVESTED = "crops_harvested";
    public static final String P_ITEM_CRAFTED = "item_crafted";
    public static final String P_ITEM_PICKED_UP = "item_picked_up";
    public static final String P_ITEMS_GAINED = "items_gained";
    public static final String P_ITEMS_LOST = "items_lost";
    public static final String P_ITEM_CONSUMED = "item_consumed";
    public static final String P_ITEM_DROPPED = "item_dropped";
    public static final String P_KILL = "kill";
    public static final String P_KILLED_BY = "killed_by";
    public static final String P_DEATH = "death";
    public static final String P_DAMAGE_CAUSE = "damage_cause";
    public static final String P_TAMED = "tamed";
    public static final String P_INTERACTION = "interaction";
    public static final String P_ZONE = "zone";
    public static final String P_WORLD_ENTERED = "world_entered";
    public static final String P_PORTAL = "portal";
    public static final String P_MEMORY = "memory";
    public static final String P_EFFECT = "effect";
    public static final String P_MOUNT = "mount";
    public static final String P_WORLD_EVENT = "world_event";
    public static final String P_COMMAND = "command";
    public static final String P_OBJECTIVE = "objective";
    public static final String P_RECIPE = "recipe";
    public static final String P_ITEM_BROKEN = "item_broken";
    public static final String P_ENTITY_USED = "entity_used";
    public static final String P_INSTANCE = "instance";
    public static final String P_REPUTATION = "reputation";
    /** Per world copies of totals live under {@code world:<name>:<total>}. */
    public static final String P_WORLD = "world";
    /**
     * The hidden per item balance of what a player put into containers or dropped and has not taken back,
     * {@code stash:<item id>}. It is kept in the player's file only. It is never a stat, never on a board, never
     * in an API read and never in a server total.
     */
    public static final String P_STASH = "stash";

    /** Every prefix TrackerAPI writes, so other plugins cannot claim them. */
    public static final List<String> RESERVED_PREFIXES = List.of(P_BLOCK_BROKEN, P_BLOCK_PLACED, P_BLOCK_USED,
            P_CROPS_PLANTED, P_CROPS_HARVESTED, P_ITEM_CRAFTED, P_ITEM_PICKED_UP, P_ITEMS_GAINED, P_ITEMS_LOST,
            P_ITEM_CONSUMED, P_ITEM_DROPPED, P_KILL,
            P_KILLED_BY, P_DEATH, P_DAMAGE_CAUSE, P_TAMED, P_INTERACTION, P_ZONE, P_WORLD_ENTERED, P_PORTAL, P_MEMORY,
            P_EFFECT, P_MOUNT, P_WORLD_EVENT, P_COMMAND, P_OBJECTIVE, P_RECIPE, P_ITEM_BROKEN, P_ENTITY_USED,
            P_INSTANCE, P_REPUTATION, P_WORLD, P_STASH);

    /** Totals that get a per world copy. */
    /**
     * The counter prefixes of the mods TrackerAPI reads itself (the mod bindings), one per binding id. Their keys
     * are {@code <prefix>:<stat>} and {@code <prefix>:<stat>:<id>}, written by TrackerAPI and not reserved, so other
     * plugins read them like the {@code coinage:} and {@code dominion:} counters.
     */
    public static final List<String> MOD_PREFIXES = List.of("rpg", "dungeons", "parries", "relics", "zephyr", "enchant",
            "vein", "lucky", "mmo", "endless", "endgame", "tamework");

    /** True for a key under one of {@link #MOD_PREFIXES}. */
    public static boolean isMod(String key) {
        return key != null && MOD_PREFIXES.contains(prefixOf(key)) && key.indexOf(':') > 0;
    }

    public static final List<String> PER_WORLD_TOTALS = List.of(BLOCK_BROKEN, BLOCK_PLACED, BLOCK_USED, ITEM_CRAFTED,
            ITEM_PICKED_UP, ITEMS_GAINED, DAMAGE_DEALT, DAMAGE_TAKEN, KILLS, KILLS_PLAYER, KILLS_BOSS, DEATHS,
            PLAYTIME, DISTANCE, CHAT);

    private static final int MEMO_LIMIT = 16_384;
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> MEMOS = new ConcurrentHashMap<>();

    /** The per id key for a prefix and an id, such as {@code kill:Goblin_Scrapper}. Memoised per prefix. */
    public static String of(String prefix, String id) {
        if (id == null) {
            return prefix;
        }
        ConcurrentHashMap<String, String> memo = MEMOS.computeIfAbsent(prefix, p -> new ConcurrentHashMap<>());
        String hit = memo.get(id);
        if (hit != null) {
            return hit;
        }
        String key = prefix + ':' + id;
        if (memo.size() < MEMO_LIMIT) {
            String raced = memo.putIfAbsent(id, key);
            if (raced != null) {
                return raced;
            }
        }
        return key;
    }

    /** The per world copy of a total, such as {@code world:default:block_broken}, for a world key. */
    public static String world(String world, String total) {
        if (world == null) {
            return total;
        }
        return of(of(P_WORLD, world), total);
    }

    /** The prefix of a key, the part before the first colon, or the key itself for a total. */
    public static String prefixOf(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(0, i);
    }

    /** True when the key starts with a prefix TrackerAPI owns. */
    public static boolean isReserved(String key) {
        String p = prefixOf(key);
        if (p.equals(key)) {
            return true; // every bare total is TrackerAPI's
        }
        return RESERVED_PREFIXES.contains(p);
    }

    /** True for a key TrackerAPI keeps for itself and never shows, the {@code stash:} balance. */
    public static boolean isHidden(String key) {
        return key != null && key.length() > P_STASH.length() && key.charAt(P_STASH.length()) == ':'
                && key.startsWith(P_STASH);
    }

    /** True for a key another plugin may write, {@code <plugin>:<name>} with a prefix TrackerAPI does not own. */
    public static boolean isExternal(String key) {
        return key != null && key.indexOf(':') > 0 && !isReserved(key) && key.length() <= 128;
    }
}
