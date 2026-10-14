package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import dev.ryvrook.trackerapi.Services;

/**
 * One group of engine hooks. Each implementation registers its ECS systems and bus listeners in
 * {@link #install}, every registration in its own guard so one hook the engine refuses only disables that
 * feature with one warning. {@link #start} runs after the plugin started, for samplers and timers, and
 * {@link #stop} at shutdown, before the publisher closes.
 */
public interface HookSet {
    /** A short name for the log, such as {@code blocks}. */
    String name();

    /** Plugin setup. Register systems and listeners through the plugin's registries. */
    void install(PluginBase plugin, Services services, Registrar registrar);

    /** Plugin start. Timers and samplers begin here. */
    default void start() {
    }

    /** Plugin shutdown. Cancel timers, drop per world state. */
    default void stop() {
    }
}
