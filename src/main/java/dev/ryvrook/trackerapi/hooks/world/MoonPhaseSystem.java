package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.universe.world.events.ecs.MoonPhaseChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.MoonPhaseChanged;
import dev.ryvrook.trackerapi.util.Guard;

/** MOON_PHASE_CHANGED from the world wide {@code MoonPhaseChangeEvent} of the entity store. World thread. */
public final class MoonPhaseSystem extends WorldEventSystem<EntityStore, MoonPhaseChangeEvent> {
    private final Services s;

    public MoonPhaseSystem(Services services) {
        super(MoonPhaseChangeEvent.class);
        this.s = services;
    }

    @Override
    public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, MoonPhaseChangeEvent event) {
        if (!s.publisher.wants(EventKind.MOON_PHASE_CHANGED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            String world = WorldNames.name(WorldNames.ofEntities(store));
            s.publisher.publish(new MoonPhaseChanged(s.header(world, null), event.getNewMoonPhase()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.MOON_PHASE_CHANGED, System.nanoTime() - start);
        }
    }
}
