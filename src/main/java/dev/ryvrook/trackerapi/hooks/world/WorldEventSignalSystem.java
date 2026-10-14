package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.builtin.adventure.worldevents.component.WorldEventSignal;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.WorldEventSignalled;
import dev.ryvrook.trackerapi.util.Guard;

/** WORLD_EVENT_SIGNALLED from the world wide {@code WorldEventSignal} of the entity store. World thread. */
public final class WorldEventSignalSystem extends WorldEventSystem<EntityStore, WorldEventSignal> {
    private final Services s;

    public WorldEventSignalSystem(Services services) {
        super(WorldEventSignal.class);
        this.s = services;
    }

    @Override
    public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, WorldEventSignal event) {
        if (!s.publisher.wants(EventKind.WORLD_EVENT_SIGNALLED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            String world = WorldNames.name(WorldNames.ofEntities(store));
            s.publisher.publish(new WorldEventSignalled(s.header(world, null), event.eventId(), event.signalId()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.WORLD_EVENT_SIGNALLED, System.nanoTime() - start);
        }
    }
}
