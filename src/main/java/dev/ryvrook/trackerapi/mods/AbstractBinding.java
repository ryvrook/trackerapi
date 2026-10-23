package dev.ryvrook.trackerapi.mods;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.ModBound;
import dev.ryvrook.trackerapi.facts.PlayerRegistry;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

/**
 * The shared part of every mod binding. A subclass gives the ids and the stat specs and fills {@link #resolve}
 * with {@link #feature} blocks, each resolving one group of the mod's API and registering what it needs with
 * {@link #onUnbind} for the undo. Callbacks from the mod go through {@link #guard}, which switches a feature off
 * after repeated failures instead of filling the log.
 *
 * <p>The bound flag is read by every callback first, so a listener the mod kept after an unbind does nothing.
 */
public abstract class AbstractBinding implements ModBinding {
    /** Failures of one feature before it is switched off for this bind. */
    static final int FAILURES_TO_OFF = 20;

    private final String id;
    private final String pluginId;
    private final String name;

    protected Services services;
    protected HytaleLogger log;
    protected ClassLoader loader;
    private volatile boolean bound;
    private volatile String version;
    private final Set<String> features = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> missing = Collections.synchronizedSet(new LinkedHashSet<>());
    private final List<Runnable> undo = new ArrayList<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final LongAdder published = new LongAdder();

    protected AbstractBinding(String id, String pluginId, String name) {
        this.id = id;
        this.pluginId = pluginId;
        this.name = name;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String pluginId() {
        return pluginId;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final boolean isBound() {
        return bound;
    }

    @Override
    public final String version() {
        return bound ? version : null;
    }

    @Override
    public final List<String> features() {
        synchronized (features) {
            return List.copyOf(features);
        }
    }

    @Override
    public final List<String> missing() {
        synchronized (missing) {
            return List.copyOf(missing);
        }
    }

    @Override
    public final long published() {
        return published.sum();
    }

    // ---- bind and unbind ---------------------------------------------------------------------------------

    @Override
    public final synchronized boolean bind(Object plugin, String version, Services services) {
        if (bound) {
            unbind("binding again");
        }
        this.services = services;
        this.log = services.logger;
        this.loader = plugin.getClass().getClassLoader();
        this.version = version == null ? "unknown" : version;
        features.clear();
        missing.clear();
        failures.clear();
        warned.clear();
        synchronized (undo) {
            undo.clear();
        }
        bound = true;
        try {
            resolve(plugin);
        } catch (Throwable t) {
            log.at(Level.WARNING).withCause(t).log("TrackerAPI could not bind to %s %s", name, version);
        }
        if (features.isEmpty()) {
            bound = false;
            runUndo();
            return false;
        }
        for (StatSpec spec : stats()) {
            if (spec.board()) {
                try {
                    services.counters.watchBoard(spec.key());
                } catch (RuntimeException e) {
                    log.at(Level.FINE).withCause(e).log("TrackerAPI could not watch the %s board", spec.key());
                }
            }
        }
        List<String> miss = missing();
        if (miss.isEmpty()) {
            log.at(Level.INFO).log("TrackerAPI bound %s %s, %d features", name, version, features.size());
        } else {
            log.at(Level.INFO).log("TrackerAPI bound %s %s, %d of %d features, without %s", name, version,
                    features.size(), features.size() + miss.size(), String.join(", ", miss));
        }
        if (services.publisher.wants(EventKind.MOD_BOUND)) {
            publish(new ModBound(services.header(null, null), id, pluginId, version, true, features()));
        }
        return true;
    }

    /**
     * Resolves the mod's API in {@link #feature} groups and registers the listeners. Runs once per bind on the
     * timer thread. A throw here ends the bind with whatever features resolved before it.
     */
    protected abstract void resolve(Object plugin) throws Throwable;

    @Override
    public final synchronized void unbind(String why) {
        if (!bound) {
            return;
        }
        bound = false;
        runUndo();
        if (services != null) {
            try {
                beforeFactsCleared();
            } catch (RuntimeException e) {
                log.at(Level.FINE).withCause(e).log("TrackerAPI %s binding failed to clean up", name);
            }
            for (PlayerRegistry.Online o : services.players.all()) {
                o.facts.keySet().removeIf(k -> k.startsWith(id + '.'));
            }
            if (log != null) {
                log.at(Level.INFO).log("TrackerAPI let %s go, %s", name, why);
            }
            if (services.publisher.wants(EventKind.MOD_BOUND)) {
                publish(new ModBound(services.header(null, null), id, pluginId, version, false, List.of()));
            }
        }
    }

    /** A hook for a subclass to drop its own per player state at unbind. */
    protected void beforeFactsCleared() {
    }

    private void runUndo() {
        List<Runnable> steps;
        synchronized (undo) {
            steps = new ArrayList<>(undo);
            undo.clear();
        }
        Collections.reverse(steps);
        for (Runnable r : steps) {
            try {
                r.run();
            } catch (Throwable t) {
                if (log != null) {
                    log.at(Level.FINE).withCause(t).log("TrackerAPI %s binding failed an undo step", name);
                }
            }
        }
    }

    // ---- features ----------------------------------------------------------------------------------------

    /** A block of {@link #resolve} that may throw. */
    @FunctionalInterface
    protected interface Resolver {
        void resolve() throws Throwable;
    }

    /** A callback body that may throw. */
    @FunctionalInterface
    protected interface Body {
        void run() throws Throwable;
    }

    /**
     * Resolves one feature group. A throw lists the feature under {@link #missing()} and logs it once at FINE,
     * the bind goes on with the other groups.
     */
    protected final boolean feature(String feature, Resolver resolver) {
        try {
            resolver.resolve();
            features.add(feature);
            missing.remove(feature);
            return true;
        } catch (Throwable t) {
            missing.add(feature);
            features.remove(feature);
            if (log != null) {
                log.at(Level.FINE).withCause(t).log("TrackerAPI %s binding is without %s", name, feature);
            }
            return false;
        }
    }

    /** True while the feature resolved and was not switched off. */
    protected final boolean has(String feature) {
        return bound && features.contains(feature);
    }

    /** Registers an undo step, run in reverse order at unbind. */
    protected final void onUnbind(Runnable step) {
        synchronized (undo) {
            undo.add(step);
        }
    }

    /**
     * Runs a callback body for a feature. Returns at once while unbound or the feature is off. A failure is
     * counted, logged once, and after {@link #FAILURES_TO_OFF} the feature is switched off for this bind.
     */
    protected final void guard(String feature, Body body) {
        if (!has(feature)) {
            return;
        }
        try {
            body.run();
        } catch (Throwable t) {
            int n = failures.computeIfAbsent(feature, f -> new AtomicInteger()).incrementAndGet();
            if (warned.add(feature) && log != null) {
                log.at(Level.WARNING).withCause(t).log("TrackerAPI %s binding failed in %s, it is switched off after %d failures",
                        name, feature, FAILURES_TO_OFF);
            }
            if (n >= FAILURES_TO_OFF) {
                off(feature);
            }
        }
    }

    /** Switches a feature off for this bind. */
    protected final void off(String feature) {
        if (features.remove(feature)) {
            missing.add(feature);
            if (log != null) {
                log.at(Level.WARNING).log("TrackerAPI %s binding switched %s off until the mod or TrackerAPI is reloaded", name, feature);
            }
        }
    }

    // ---- publishing --------------------------------------------------------------------------------------

    /** True when bound and someone wants the kind. Check it before reading the mod's event. */
    protected final boolean wants(EventKind kind) {
        return bound && services != null && services.publisher.wants(kind);
    }

    /** A header for a player event, with the player's world when known. */
    protected final Header header(UUID player) {
        return services.header(player == null ? null : services.players.worldOf(player), player);
    }

    /** A header for a player event in a named world. */
    protected final Header header(String world, UUID player) {
        return services.header(world, player);
    }

    protected final void publish(TrackerEvent event) {
        published.increment();
        services.publisher.publish(event);
    }

    /** Sets or, with a null value, removes a fact on the online player, under this binding's id. */
    protected final void fact(UUID player, String key, String value) {
        PlayerRegistry.Online o = services.players.get(player);
        if (o == null) {
            return;
        }
        String full = id + '.' + key;
        if (value == null) {
            o.facts.remove(full);
        } else {
            o.facts.put(full, value);
        }
    }

    /** True when the player is online, so a counter write lands in memory. */
    protected final boolean online(UUID player) {
        return services.players.isOnline(player);
    }

    // ---- engine bus --------------------------------------------------------------------------------------

    /**
     * Listens to an event class of the mod on the engine's event bus through TrackerAPI's own registry, undone at
     * unbind. For an {@code IEvent} the mod fires with {@code EventRegistry}. The consumer receives the raw event.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected final void engineEvent(Class<?> eventClass, java.util.function.Consumer<Object> consumer) {
        com.hypixel.hytale.event.EventRegistry registry = ModBindings.events();
        if (registry == null) {
            throw new IllegalStateException("TrackerAPI's event registry is not available");
        }
        com.hypixel.hytale.registry.Registration reg = registry.register((Class) eventClass, (java.util.function.Consumer) consumer);
        onUnbind(reg::unregister);
    }

    // ---- mod objects -------------------------------------------------------------------------------------

    /** The uuid of a player given as a {@link PlayerRef}, a {@link UUID} or an object with {@code getUuid()}. */
    protected final UUID uuidOf(Object player) {
        if (player == null) {
            return null;
        }
        if (player instanceof UUID u) {
            return u;
        }
        if (player instanceof PlayerRef pr) {
            return pr.getUuid();
        }
        MethodHandle mh = uuidGetter(player.getClass());
        if (mh != null) {
            try {
                Object o = mh.invoke(player);
                if (o instanceof UUID u) {
                    return u;
                }
                if (o instanceof PlayerRef pr) {
                    return pr.getUuid();
                }
            } catch (Throwable ignored) {
                // no uuid from this object
            }
        }
        return null;
    }

    private final Map<Class<?>, MethodHandle> uuidGetters = new ConcurrentHashMap<>();

    private MethodHandle uuidGetter(Class<?> type) {
        return uuidGetters.computeIfAbsent(type, t -> {
            for (String n : new String[]{"getUuid", "getUUID", "uuid", "getPlayerUuid", "getPlayer", "player"}) {
                try {
                    return Handles.virtualByName(t, n, 0);
                } catch (ReflectiveOperationException ignored) {
                    // try the next name
                }
            }
            return null;
        });
    }

}
