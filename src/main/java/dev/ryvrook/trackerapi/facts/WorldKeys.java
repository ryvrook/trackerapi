package dev.ryvrook.trackerapi.facts;

import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * The stable key a world name becomes inside a counter key, the Chronicle Stats world key rules. Portal
 * fragments and instance runs get a fresh world name per run, such as {@code instance-<asset>-<uuid>}, so
 * counters never use the raw name. Event records keep the raw name.
 *
 * <p>The order is portal, then instance, then persistent, because a portal world is also an instance world.
 * A portal world becomes {@code portal.<type>}, an instance world {@code instance.<template>} and any other
 * world its own name, lower cased, with every character outside {@code [a-z0-9_-]} turned into an underscore
 * and cut to 64 characters, so the default world stays {@code default}. At most 64 distinct keys are admitted
 * after boot, any further one folds into {@link #OTHER} with one warning.
 *
 * <p>{@link #learn(World)} reads the portal and instance facts on the world's own thread and remembers the
 * key for the world's name. {@link #keyOf(String)} answers from that memory on any thread, and for a name
 * nobody learned yet it guesses from the name alone, which gives the same key for instance runs. Thread safe.
 */
public final class WorldKeys {
    public static final String OTHER = "other";
    public static final String PORTAL = "portal.";
    public static final String INSTANCE = "instance.";
    public static final int MAX_KEYS = 64;

    private static final int SLUG_MAX = 64;
    private static final String RUN_PREFIX = "instance-";
    private static final Pattern UUID_SUFFIX =
            Pattern.compile("[-_][0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");
    private static final Pattern DIGIT_SUFFIX = Pattern.compile("[-_]\\d+$");
    private static final Pattern CONTAINS_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final HytaleLogger logger;
    private final int maxKeys;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Set<String> learned = ConcurrentHashMap.newKeySet();
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();
    private volatile boolean capWarned;

    public WorldKeys(HytaleLogger logger) {
        this(logger, MAX_KEYS);
    }

    public WorldKeys(HytaleLogger logger, int maxKeys) {
        this.logger = logger;
        this.maxKeys = Math.max(1, maxKeys);
    }

    /** The key of a world name, any thread. Null stays null. */
    public String keyOf(String worldName) {
        if (worldName == null) {
            return null;
        }
        String hit = cache.get(worldName);
        if (hit != null) {
            return hit;
        }
        String key = admit(guess(worldName));
        String raced = cache.putIfAbsent(worldName, key);
        return raced != null ? raced : key;
    }

    /** Remembers the key of a world from its facts, replacing a guess. Returns the key. */
    public String learn(String worldName, String portalTypeId, String instanceKey) {
        String key = admit(compute(worldName, portalTypeId, instanceKey));
        if (worldName != null) {
            cache.put(worldName, key);
            learned.add(worldName);
        }
        return key;
    }

    /**
     * Reads the facts of a world and remembers its key, once per world name. World thread, each read in its
     * own guard so a reshaped engine class only loses that step.
     */
    public String learn(World world) {
        if (world == null) {
            return null;
        }
        String name;
        try {
            name = world.getName();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (name == null) {
            return null;
        }
        if (learned.contains(name)) {
            return cache.get(name);
        }
        String portalTypeId = null;
        try {
            PortalWorld pw = world.getEntityStore().getStore().getResource(PortalWorld.getResourceType());
            if (pw != null && pw.exists()) {
                PortalType type = pw.getPortalType();
                portalTypeId = type == null ? null : type.getId();
            }
        } catch (RuntimeException | LinkageError e) {
            portalTypeId = null;
        }
        String instanceKey = null;
        try {
            InstanceWorldConfig ic = InstanceWorldConfig.get(world.getWorldConfig());
            if (ic != null) {
                String key = ic.getInstanceKey();
                if (key == null || key.isBlank()) {
                    key = ic.getInstanceName();
                }
                instanceKey = key == null ? "" : key;
            }
        } catch (RuntimeException | LinkageError e) {
            instanceKey = null;
        }
        return learn(name, portalTypeId, instanceKey);
    }

    /** True once the facts of this world name were read. */
    public boolean isLearned(String worldName) {
        return worldName != null && learned.contains(worldName);
    }

    /** Forgets a removed world name. Its key stays admitted until restart. */
    public void forget(String worldName) {
        if (worldName != null) {
            cache.remove(worldName);
            learned.remove(worldName);
        }
    }

    /** Distinct keys admitted since boot. */
    public int admittedCount() {
        return admitted.size();
    }

    /**
     * The key from the facts, uncapped. {@code instanceKey} is null for a world without an instance config
     * and empty for an instance whose config names neither a fixed key nor a template.
     */
    public static String compute(String name, String portalTypeId, String instanceKey) {
        if (portalTypeId != null && !portalTypeId.isBlank()) {
            String s = slug(portalTypeId);
            if (s != null) {
                return PORTAL + s;
            }
        }
        if (instanceKey != null) {
            String source = instanceKey.isBlank() || looksLikeRun(instanceKey) ? stripRunSuffix(name) : instanceKey;
            String s = slug(source);
            if (s != null) {
                return INSTANCE + s;
            }
        }
        String s = slug(name);
        return s == null ? OTHER : s;
    }

    /**
     * The key from the name alone, for a world whose facts were not read yet. A world named the way the
     * instances plugin names its runs, {@code instance-<template>-<uuid>}, becomes {@code instance.<template>},
     * which is what its facts give.
     */
    public static String guess(String name) {
        if (name != null && name.regionMatches(true, 0, RUN_PREFIX, 0, RUN_PREFIX.length())
                && name.length() > RUN_PREFIX.length()) {
            String s = slug(stripRunSuffix(name.substring(RUN_PREFIX.length())));
            if (s != null) {
                return INSTANCE + s;
            }
        }
        return compute(name, null, null);
    }

    /** Lower cases, replaces every character outside [a-z0-9_-] with an underscore, cuts to 64. Null for blank. */
    public static String slug(String s) {
        if (s == null) {
            return null;
        }
        String lower = s.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return null;
        }
        int n = Math.min(lower.length(), SLUG_MAX);
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char ch = lower.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
            out.append(ok ? ch : '_');
        }
        return out.toString();
    }

    /** Removes a trailing uuid, then a trailing run number after an underscore or a dash. */
    public static String stripRunSuffix(String name) {
        if (name == null) {
            return null;
        }
        String s = UUID_SUFFIX.matcher(name).replaceFirst("");
        return DIGIT_SUFFIX.matcher(s).replaceFirst("");
    }

    /** True when an instance key looks like a per run id, so the world name is used instead. */
    public static boolean looksLikeRun(String key) {
        return key != null && (CONTAINS_UUID.matcher(key).find() || DIGIT_SUFFIX.matcher(key).find());
    }

    private String admit(String key) {
        if (OTHER.equals(key) || admitted.contains(key)) {
            return key;
        }
        synchronized (admitted) {
            if (admitted.contains(key)) {
                return key;
            }
            if (admitted.size() >= maxKeys) {
                if (!capWarned) {
                    capWarned = true;
                    if (logger != null) {
                        logger.at(Level.WARNING).log("TrackerAPI has seen %d world keys since boot and counts further worlds as %s, starting with %s",
                                admitted.size(), OTHER, key);
                    }
                }
                return OTHER;
            }
            admitted.add(key);
            return key;
        }
    }
}
