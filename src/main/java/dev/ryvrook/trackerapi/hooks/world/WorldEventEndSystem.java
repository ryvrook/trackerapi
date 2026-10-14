package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.builtin.adventure.worldevents.component.EventEndEcsEvent;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.WorldEventEnded;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WORLD_EVENT_ENDED from the world wide {@code EventEndEcsEvent} (the Chronicle {@code WorldEventEndSystem}
 * read). The event id is the world event's asset id, the outcome the engine's reason name, and the
 * participants are every player in the world when it ended, as Chronicle counts them. World thread.
 */
public final class WorldEventEndSystem extends WorldEventSystem<EntityStore, EventEndEcsEvent> {
    private final Services s;

    public WorldEventEndSystem(Services services) {
        super(EventEndEcsEvent.class);
        this.s = services;
    }

    @Override
    public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, EventEndEcsEvent event) {
        if (!s.publisher.wants(EventKind.WORLD_EVENT_ENDED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            WorldEvent we = event.getWorldEvent();
            if (we == null || event.getReason() == null) {
                return;
            }
            World world = WorldNames.ofEntities(store);
            List<UUID> players = new ArrayList<>();
            if (world != null) {
                for (PlayerRef pr : world.getPlayerRefs()) {
                    UUID uuid = pr == null ? null : pr.getUuid();
                    if (uuid != null && !players.contains(uuid)) {
                        players.add(uuid);
                    }
                }
            }
            s.publisher.publish(new WorldEventEnded(s.header(WorldNames.name(world), null), we.asset,
                    event.getReason().name(), List.copyOf(players)));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.WORLD_EVENT_ENDED, System.nanoTime() - start);
        }
    }
}
