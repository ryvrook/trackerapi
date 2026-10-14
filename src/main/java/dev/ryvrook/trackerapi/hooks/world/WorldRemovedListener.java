package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.WorldRemoved;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * WORLD_REMOVED from {@code RemoveWorldEvent} at {@code EventPriority.LAST}, so every other listener had its
 * chance to cancel, and cancelled removals are skipped (the Chronicle {@code WorldRemovedListener} rule). An
 * exceptional removal goes ahead even when a listener cancelled it, so it is never skipped.
 * After publishing it drops every per world map of every hook set through {@code Forgetting.forgetWorld},
 * which runs whether or not anyone wants the event. Universe code path, not the removed world's thread.
 */
public final class WorldRemovedListener {
    private final Services s;

    public WorldRemovedListener(Services services) {
        this.s = services;
    }

    public void register(EventRegistry events) {
        events.registerGlobal(EventPriority.LAST, RemoveWorldEvent.class, this::onRemove);
    }

    private void onRemove(RemoveWorldEvent event) {
        long start = System.nanoTime();
        try {
            if (event.isCancelled() && event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
                return;
            }
            World world = event.getWorld();
            String name = WorldNames.name(world);
            if (name == null) {
                return;
            }
            try {
                if (s.publisher.wants(EventKind.WORLD_REMOVED)) {
                    s.publisher.publish(new WorldRemoved(s.header(name, null)));
                }
            } finally {
                s.forgetting.forgetWorld(name);
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.WORLD_REMOVED, System.nanoTime() - start);
        }
    }
}
