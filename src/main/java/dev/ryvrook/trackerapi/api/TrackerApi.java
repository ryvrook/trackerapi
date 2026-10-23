package dev.ryvrook.trackerapi.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The TrackerAPI entry point for other plugins.
 *
 * <p>Compile against the TrackerAPI jar ({@code compileOnly}) and list {@code Ryvrook:TrackerAPI} under
 * {@code OptionalDependencies} in your manifest with the lowest version you need, for example
 * {@code ">=1.0.0"}. The engine's plugin class loader resolves the classes of a listed dependency, so your
 * plugin can use these types directly. Keep every use of them in one class that you only load after
 * {@link #isPresent()} answered true, so your plugin still loads on a server without TrackerAPI.
 *
 * <p>Subscribe from {@link #onEnable(Consumer)}, which runs at once when TrackerAPI is already up and again
 * whenever it starts, and drop your state in {@link #onDisable(Runnable)}. Both hooks stay registered across
 * a {@code /plugin reload} of TrackerAPI, and every subscription is closed for you when it stops.
 *
 * <pre>{@code
 * TrackerApi.onEnable(api -> {
 *     api.subscribe("MyPlugin", BlockBroken.class, e -> counts.merge(e.blockId(), 1L, Long::sum));
 * });
 * }</pre>
 *
 * <p>Every method is safe from any thread and never blocks on a world thread.
 *
 * <p>This class is stable. Methods are only ever added, and existing ones keep their meaning. Check
 * {@link #API_VERSION} when a newer method is needed.
 */
public abstract class TrackerApi {
    /** Raised whenever a method or an event field is added. */
    public static final int API_VERSION = 2;

    private static final Logger LOG = Logger.getLogger("TrackerAPI");
    private static volatile TrackerApi current;
    private static final List<Consumer<TrackerApi>> ENABLE = new CopyOnWriteArrayList<>();
    private static final List<Runnable> DISABLE = new CopyOnWriteArrayList<>();

    protected TrackerApi() {
    }

    /** The running API, or null when TrackerAPI is not loaded or not started yet. */
    public static TrackerApi get() {
        return current;
    }

    /** True while TrackerAPI is up. */
    public static boolean isPresent() {
        return current != null;
    }

    /** Runs the hook now when TrackerAPI is up, and every time it starts. */
    public static void onEnable(Consumer<TrackerApi> hook) {
        Objects.requireNonNull(hook, "hook");
        ENABLE.add(hook);
        TrackerApi api = current;
        if (api != null) {
            run(() -> hook.accept(api));
        }
    }

    /** Runs the hook every time TrackerAPI stops. Subscriptions are closed before it runs. */
    public static void onDisable(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        DISABLE.add(hook);
    }

    /** Removes hooks added with {@link #onEnable} and {@link #onDisable}. */
    public static void removeHooks(Consumer<TrackerApi> enable, Runnable disable) {
        if (enable != null) {
            ENABLE.remove(enable);
        }
        if (disable != null) {
            DISABLE.remove(disable);
        }
    }

    // ---- subscriptions ---------------------------------------------------------------------------------

    /**
     * Subscribes to one kind of event with {@link Delivery#ASYNC}. {@code owner} names your plugin in
     * {@code /trackerapi status} and in warnings.
     */
    public <E extends TrackerEvent> Subscription subscribe(String owner, Class<E> type, Consumer<? super E> listener) {
        return subscribe(owner, type, Delivery.ASYNC, listener);
    }

    /** Subscribes to one kind of event. See {@link Delivery} for what each mode asks of the listener. */
    public abstract <E extends TrackerEvent> Subscription subscribe(String owner, Class<E> type, Delivery delivery,
                                                                     Consumer<? super E> listener);

    /** Subscribes to every kind of event. Kinds that are off in the config are never delivered. */
    public abstract Subscription subscribeAll(String owner, Delivery delivery, Consumer<TrackerEvent> listener);

    /** Subscribes to several kinds at once, for a consumer with one switch statement over {@link EventKind}. */
    public abstract Subscription subscribeKinds(String owner, java.util.Set<EventKind> kinds, Delivery delivery,
                                                 Consumer<TrackerEvent> listener);

    /** Every live subscription, for status pages. */
    public abstract List<Subscription> subscriptions();

    // ---- state TrackerAPI keeps for everyone ------------------------------------------------------------

    /** True when the kind is tracked on this server, which the owner can turn off per kind in the config. */
    public abstract boolean isTracked(EventKind kind);

    /**
     * The last position sample TrackerAPI took of an online player, or null when the player is not online
     * or has not been sampled yet. Cheap, answered from memory on any thread.
     */
    public abstract PlayerSnapshot snapshot(UUID player);

    /** Every online player's last sample. */
    public abstract List<PlayerSnapshot> snapshots();

    /** The lifetime counters of every player, see {@link Counters}. */
    public abstract Counters counters();

    /** The journal, or null when the owner turned it off. */
    public abstract Journal journal();

    /** Counters for status pages, see {@link Stats}. */
    public abstract Stats stats();

    /**
     * The mods TrackerAPI knows how to read, bound or not, with the counters each writes. Since API 2. A consumer
     * refreshes it on {@link dev.ryvrook.trackerapi.api.event.ModBound}.
     */
    public abstract List<ModInfo> mods();

    /** The TrackerAPI version, such as {@code 1.0.0}. */
    public abstract String version();

    // ---- internal --------------------------------------------------------------------------------------

    /** TrackerAPI internal. Publishes the API and runs the enable hooks. */
    protected static void enable(TrackerApi api) {
        Objects.requireNonNull(api, "api");
        current = api;
        for (Consumer<TrackerApi> hook : ENABLE) {
            run(() -> hook.accept(api));
        }
    }

    /** TrackerAPI internal. Withdraws the API and runs the disable hooks. */
    protected static void disable(TrackerApi api) {
        if (current != api || api == null) {
            return;
        }
        current = null;
        for (Runnable hook : DISABLE) {
            run(hook);
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "A TrackerAPI hook of another plugin failed", t);
        }
    }
}
