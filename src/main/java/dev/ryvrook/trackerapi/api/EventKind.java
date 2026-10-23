package dev.ryvrook.trackerapi.api;

import java.util.Locale;

/**
 * One constant per event record. The {@link #id} is the name used in the config, the journal and the
 * commands, such as {@code block_broken}. {@link #highVolume} marks the kinds that can fire many times a
 * second per player and are left out of the journal by default.
 *
 * <p>Constants are only ever added, never renamed or reordered, so {@link #ordinal()} is stable.
 */
public enum EventKind {
    BLOCK_BROKEN(false),
    BLOCK_PLACED(false),
    BLOCK_USED(false),
    BLOCK_BROKEN_BY_WORLD(true),
    ITEM_CRAFTED(false),
    ITEM_PICKED_UP(false),
    ITEMS_GAINED(true),
    ITEMS_LOST(true),
    ITEM_CONSUMED(false),
    ITEM_DROPPED(false),
    DAMAGE_DEALT(true),
    ENTITY_DIED(false),
    CREATURE_SPAWNED(true),
    CREATURE_TAMED(false),
    PROJECTILE_LANDED(true),
    INTERACTION_STARTED(true),
    PLAYER_JOINED(false),
    PLAYER_LEFT(false),
    PLAYER_WORLD_CHANGED(false),
    PLAYER_RESPAWNED(false),
    PLAYER_TELEPORTED(false),
    PLAYER_CHATTED(false),
    PLAYER_COMMAND(false),
    GAME_MODE_CHANGED(false),
    ZONE_DISCOVERED(false),
    PLAYER_MOVED(true),
    MOVEMENT_EDGE(true),
    MOUNT_CHANGED(false),
    MEMORY_RECORDED(false),
    EFFECT_CHANGED(true),
    VITALS_SAMPLED(true),
    WORLD_EVENT_ENDED(false),
    WORLD_ADDED(false),
    WORLD_REMOVED(false),
    CHUNK_SAVED(true),
    CHUNK_GENERATED(true),
    SERVER_LIFECYCLE(false),
    BLOCK_DAMAGED(true),
    ENTITY_USED(false),
    PORTAL_ENTERED(false),
    INSTANCE_DISCOVERED(false),
    TRIGGER_VOLUME_FIRED(true),
    MOON_PHASE_CHANGED(false),
    WORLD_EVENT_STARTED(false),
    WORLD_EVENT_SIGNALLED(false),
    PLAYER_CONNECTED(false),
    PERMISSION_CHANGED(false),
    ACTIVE_SLOT_CHANGED(true),
    CREATURE_REMOVED(true),
    PROJECTILE_LAUNCHED(true),
    ITEM_EQUIPPED(false),
    ITEM_BROKE(false),
    PREFAB_PASTED(false),
    CHUNK_LOADED(true),
    CHUNK_UNLOADED(true),
    WEATHER_CHANGED(false),
    OBJECTIVE_COMPLETED(false),
    RECIPE_LEARNED(false),
    REPUTATION_CHANGED(false),
    SPECTATING_CHANGED(false),
    PLAYER_SLEPT(false),
    GAME_TIME_SAMPLED(false),
    MOD_LEVEL_UP(false),
    MOD_XP_GAINED(true),
    MOD_CHOICE_CHANGED(false),
    MOD_ABILITY_USED(false),
    MOD_ACTION(false),
    MOD_MILESTONE(false),
    DUNGEON_RUN(false),
    MOD_BOSS_DEFEATED(false),
    ITEM_ENCHANTED(false),
    ENCHANTMENT_TRIGGERED(true),
    VEIN_MINED(false),
    LUCKY_DROP(false),
    COMPANION_CHANGED(false),
    MOD_BOUND(false),
    MOD_TALLY(false);

    private final boolean highVolume;
    private final String id;

    EventKind(boolean highVolume) {
        this.highVolume = highVolume;
        this.id = name().toLowerCase(Locale.ROOT);
    }

    /** The lower case name, such as {@code block_broken}. */
    public String id() {
        return id;
    }

    /** True for kinds that can fire many times a second per player. */
    public boolean highVolume() {
        return highVolume;
    }

    /** The kind with this id, ignoring case, or null. */
    public static EventKind byId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (EventKind k : values()) {
            if (k.name().equals(key)) {
                return k;
            }
        }
        return null;
    }

    /** How many kinds there are. Arrays indexed by ordinal use this. */
    public static final int COUNT = values().length;
}
