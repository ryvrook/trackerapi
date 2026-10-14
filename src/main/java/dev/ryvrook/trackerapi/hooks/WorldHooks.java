package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.hooks.world.ChunkGeneratedListener;
import dev.ryvrook.trackerapi.hooks.world.ChunkLoadSystem;
import dev.ryvrook.trackerapi.hooks.world.ChunkSaveSystem;
import dev.ryvrook.trackerapi.hooks.world.ChunkUnloadSystem;
import dev.ryvrook.trackerapi.hooks.world.MoonPhaseSystem;
import dev.ryvrook.trackerapi.hooks.world.TriggerVolumeListener;
import dev.ryvrook.trackerapi.hooks.world.WorldAddedListener;
import dev.ryvrook.trackerapi.hooks.world.WorldClockPoller;
import dev.ryvrook.trackerapi.hooks.world.WorldEventEndSystem;
import dev.ryvrook.trackerapi.hooks.world.WorldEventSignalSystem;
import dev.ryvrook.trackerapi.hooks.world.WorldEventStartSystem;
import dev.ryvrook.trackerapi.hooks.world.WorldRemovedListener;

/**
 * The world hook set. Worlds added and removed, chunks saved, generated, loaded and unloaded, the moon
 * phase, weather and game time, world events and trigger volumes. Each row registers in its own guard, the
 * systems and listeners live in the {@code hooks.world} package, one class each because the engine
 * registers systems by class. The weather and game time poll is the only timer of this set.
 */
public final class WorldHooks implements HookSet {
    private WorldClockPoller clock;

    @Override
    public String name() {
        return "world";
    }

    @Override
    public void install(PluginBase plugin, Services s, Registrar r) {
        r.listener("world added", events -> new WorldAddedListener(s).register(events));
        r.listener("world removed", events -> new WorldRemovedListener(s).register(events));
        r.chunkSystem("chunk saved", () -> new ChunkSaveSystem(s));
        r.listener("chunk generated", events -> new ChunkGeneratedListener(s).register(events));
        r.chunkSystem("chunk loaded", () -> new ChunkLoadSystem(s));
        r.chunkSystem("chunk unloaded", () -> new ChunkUnloadSystem(s));
        r.entitySystem("moon phase", () -> new MoonPhaseSystem(s));
        WorldClockPoller poller = new WorldClockPoller(s);
        r.guard("weather", poller::enableWeather);
        r.guard("game time", poller::enableTime);
        clock = poller;
        r.entitySystem("world event started", () -> new WorldEventStartSystem(s));
        r.entitySystem("world event signalled", () -> new WorldEventSignalSystem(s));
        r.entitySystem("world event ended", () -> new WorldEventEndSystem(s));
        r.listener("trigger volumes", events -> new TriggerVolumeListener(s).register(events));
    }

    @Override
    public void start() {
        if (clock != null) {
            clock.start();
        }
    }

    @Override
    public void stop() {
        if (clock != null) {
            clock.stop();
        }
    }
}
