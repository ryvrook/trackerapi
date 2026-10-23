package dev.ryvrook.trackerapi.mods;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.ModInfo;
import dev.ryvrook.trackerapi.hooks.HookSet;
import dev.ryvrook.trackerapi.hooks.Registrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The hook set that reads other mods. Every {@link ModBinding} is built once here. A poll on the timer thread
 * looks each mod up by plugin id, binds when it is enabled and not yet bound, binds again when the instance
 * changed (the mod was reloaded), and lets it go when it is gone or switched off in the config. A bind that found
 * nothing of the mod's API is remembered for that instance and logged once, so the poll does not retry it every
 * few seconds.
 */
public final class ModBindings implements HookSet {
    /** The seam to the engine's plugin manager, a fake in tests. */
    public interface Plugins {
        /** The enabled instance of the plugin, or null when it is missing or not enabled. */
        Object enabled(String pluginId);

        /** The version of an instance from {@link #enabled}. */
        String version(Object plugin);
    }

    /** The engine's plugin manager. */
    static final Plugins ENGINE = new Plugins() {
        @Override
        public Object enabled(String pluginId) {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            PluginBase p = manager.getPlugin(PluginIdentifier.fromString(pluginId));
            return p != null && p.getState() == PluginState.ENABLED ? p : null;
        }

        @Override
        public String version(Object plugin) {
            try {
                return String.valueOf(((PluginBase) plugin).getManifest().getVersion());
            } catch (RuntimeException | LinkageError e) {
                return "unknown";
            }
        }
    };

    /** Seconds before the first poll after start, so the other plugins have enabled. */
    static final long FIRST_POLL_SECONDS = 3;

    private final List<ModBinding> bindings;
    private final Plugins plugins;
    private final Map<String, Object> boundTo = new ConcurrentHashMap<>();
    private final Map<String, Object> failedFor = new ConcurrentHashMap<>();
    private Services services;
    private PluginBase owner;
    private volatile ScheduledFuture<?> poll;
    private static volatile EventRegistry events;

    public ModBindings(List<ModBinding> bindings, Plugins plugins) {
        this.bindings = List.copyOf(bindings);
        this.plugins = plugins;
    }

    /** The production set, every binding against the engine's plugin manager. */
    public static ModBindings create() {
        return new ModBindings(all(), ENGINE);
    }

    /** Every binding TrackerAPI ships, in the order the status lists them. */
    public static List<ModBinding> all() {
        List<ModBinding> list = new ArrayList<>();
        // ---- bindings, begin ----
        // ---- bindings, end ----
        return list;
    }

    /** TrackerAPI's own event registry, for bindings that listen to a mod's events on the engine bus. */
    public static EventRegistry events() {
        return events;
    }

    public List<ModBinding> bindings() {
        return bindings;
    }

    @Override
    public String name() {
        return "mods";
    }

    @Override
    public void install(PluginBase plugin, Services services, Registrar registrar) {
        attach(services);
        this.owner = plugin;
        events = plugin.getEventRegistry();
    }

    /** Gives the set its services without an engine, what install does in production. */
    public void attach(Services services) {
        this.services = services;
        services.setMods(this);
    }

    @Override
    public void start() {
        if (services == null || bindings.isEmpty()) {
            return;
        }
        int seconds = Math.max(2, services.config().modsPollSeconds);
        poll = services.timer.scheduleAtFixedRate(this::pollSafely, FIRST_POLL_SECONDS, seconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> p = poll;
        if (p != null) {
            p.cancel(false);
            poll = null;
        }
        for (ModBinding b : bindings) {
            try {
                b.unbind("TrackerAPI stopped");
            } catch (RuntimeException | LinkageError e) {
                if (services != null) {
                    services.logger.at(Level.FINE).withCause(e).log("TrackerAPI %s binding failed to stop", b.name());
                }
            }
        }
        boundTo.clear();
        failedFor.clear();
        events = null;
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException | LinkageError e) {
            services.logger.at(Level.WARNING).withCause(e).log("TrackerAPI's look for other mods failed");
        }
    }

    /** One pass over the bindings. Package private for tests, which call it in place of the timer. */
    void poll() {
        for (ModBinding b : bindings) {
            String id = b.id();
            if (!services.config().modEnabled(id)) {
                if (b.isBound()) {
                    b.unbind("mods." + id + " is never");
                    boundTo.remove(id);
                }
                failedFor.remove(id);
                continue;
            }
            Object p;
            try {
                p = plugins.enabled(b.pluginId());
            } catch (RuntimeException | LinkageError e) {
                p = null;
            }
            if (p == null) {
                if (b.isBound()) {
                    b.unbind(b.name() + " is not loaded");
                    boundTo.remove(id);
                }
                failedFor.remove(id);
                continue;
            }
            if (b.isBound()) {
                if (boundTo.get(id) == p) {
                    continue;
                }
                b.unbind(b.name() + " was reloaded");
                boundTo.remove(id);
            }
            if (failedFor.get(id) == p) {
                continue;
            }
            boolean ok;
            try {
                ok = b.bind(p, plugins.version(p), services);
            } catch (RuntimeException | LinkageError e) {
                services.logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not bind to %s", b.name());
                ok = false;
            }
            if (ok) {
                boundTo.put(id, p);
                failedFor.remove(id);
            } else {
                failedFor.put(id, p);
                services.logger.at(Level.WARNING).log("TrackerAPI could not bind to %s %s because none of its API matched, and leaves it alone until it is reloaded",
                        b.name(), plugins.version(p));
            }
        }
    }

    // ---- answers -----------------------------------------------------------------------------------------

    public List<ModInfo> infos() {
        List<ModInfo> out = new ArrayList<>(bindings.size());
        for (ModBinding b : bindings) {
            out.add(new ModInfo(b.id(), b.pluginId(), b.name(), b.version(), b.isBound(), b.features(), b.missing(), b.stats()));
        }
        return out;
    }

    /** The one line of the status command. */
    public String statusLine() {
        if (bindings.isEmpty()) {
            return "No mod bindings.";
        }
        StringBuilder sb = new StringBuilder("Mods: ");
        boolean first = true;
        for (ModBinding b : bindings) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(b.name());
            if (b.isBound()) {
                List<String> miss = b.missing();
                sb.append(' ').append(b.version()).append(" bound (");
                if (miss.isEmpty()) {
                    sb.append(b.features().size()).append(" features)");
                } else {
                    sb.append(b.features().size()).append(" of ").append(b.features().size() + miss.size())
                            .append(", missing ").append(String.join(", ", miss)).append(')');
                }
            } else if (!services.config().modEnabled(b.id())) {
                sb.append(" off");
            } else if (failedFor.containsKey(b.id())) {
                sb.append(" not matched");
            } else {
                sb.append(" not installed");
            }
        }
        return sb.append('.').toString();
    }

    /** The block per mod of the mods command. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        if (bindings.isEmpty()) {
            lines.add("TrackerAPI ships no mod bindings.");
            return lines;
        }
        for (ModBinding b : bindings) {
            String state;
            if (b.isBound()) {
                state = "bound to " + b.version();
            } else if (!services.config().modEnabled(b.id())) {
                state = "off (mods." + b.id() + " is never)";
            } else if (failedFor.containsKey(b.id())) {
                state = "installed, none of its API matched";
            } else {
                state = "not installed";
            }
            lines.add(b.name() + " (" + b.pluginId() + ", prefix " + b.id() + "), " + state + ", " + b.published() + " events.");
            if (b.isBound()) {
                lines.add("  features " + String.join(", ", b.features())
                        + (b.missing().isEmpty() ? "" : ", missing " + String.join(", ", b.missing())));
            }
            StringBuilder keys = new StringBuilder("  counters");
            for (var spec : b.stats()) {
                keys.append(' ').append(spec.key()).append(spec.perId() ? ":<" + (spec.idLabel() == null ? "id" : spec.idLabel().toLowerCase(java.util.Locale.ROOT)) + ">" : "");
            }
            lines.add(keys.toString());
        }
        return lines;
    }
}
