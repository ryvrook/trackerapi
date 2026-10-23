package dev.ryvrook.trackerapi.mods;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.StatSpec;

import java.util.List;
import java.util.Set;

/**
 * One mod TrackerAPI knows how to read. A binding is built once at setup and lives as long as TrackerAPI. The
 * poll in {@link ModBindings} calls {@link #bind} when the mod's plugin is enabled and {@link #unbind} when it is
 * gone, reloaded or switched off. Everything of the mod is resolved through the plugin's own class loader inside
 * {@code bind}, in feature groups, so a missing method disables one feature and the rest works.
 *
 * <p>Thread rules. {@code bind} and {@code unbind} run on the TrackerAPI timer thread, or on the engine thread at
 * TrackerAPI's stop. The mod's callbacks arrive on the mod's threads and publish straight to the publisher, which
 * is safe from any thread. Component reads happen on world threads only.
 */
public interface ModBinding {
    /** The short id and counter prefix, one of {@link dev.ryvrook.trackerapi.api.Keys#MOD_PREFIXES}. */
    String id();

    /** The plugin id the binding looks for, such as {@code Zuxaw:RPGLeveling}. */
    String pluginId();

    /** The mod's name for people. */
    String name();

    /** The kinds this binding publishes. */
    Set<EventKind> kinds();

    /** Every counter this binding writes, with labels. */
    List<StatSpec> stats();

    /**
     * Binds to an enabled instance of the mod, the plugin object itself (a {@code PluginBase} in production, any
     * object in tests) with its version. Returns true when at least one feature resolved. Must not throw, a
     * failure is a false with the reasons in {@link #missing()}.
     */
    boolean bind(Object plugin, String version, Services services);

    /** Lets the mod go, unregistering everything. Safe to call when not bound. */
    void unbind(String why);

    boolean isBound();

    /** The mod's version while bound, else null. */
    String version();

    /** The feature groups that resolved at the last bind. */
    List<String> features();

    /** The feature groups that did not resolve at the last bind, or were switched off since. */
    List<String> missing();

    /** Events published since the binding was built, for the mods command. */
    long published();
}
