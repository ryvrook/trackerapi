package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.builtin.adventure.worldevents.context.LocationContext;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.WorldEventStarted;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.UUID;

/**
 * WORLD_EVENT_STARTED. There is no start event, a world event is an entity carrying the {@code WorldEvent}
 * component, so a {@code RefSystem} over that component sees it enter the store and publishes
 * {@code AddReason.SPAWN} only (a reload of a running event arrives as LOAD and is not a start). The id is
 * the asset id, the uuid the event's own or else the entity's, the position the entity's transform or else
 * the first location in the event's context, as Atlas reads it. World thread.
 */
public final class WorldEventStartSystem extends RefSystem<EntityStore> {
    private final Services s;

    public WorldEventStartSystem(Services services) {
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return WorldEvent.getComponentType();
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store,
                              CommandBuffer<EntityStore> commandBuffer) {
        if (reason != AddReason.SPAWN || !s.publisher.wants(EventKind.WORLD_EVENT_STARTED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            WorldEvent we = store.getComponent(ref, WorldEvent.getComponentType());
            if (we == null) {
                return;
            }
            UUID uuid = we.uuid;
            if (uuid == null || uuid.equals(WorldEvent.EMPTY_ID)) {
                uuid = entityUuid(store, ref);
            }
            double[] at = position(store, ref, we);
            String world = WorldNames.name(WorldNames.ofEntities(store));
            s.publisher.publish(new WorldEventStarted(s.header(world, null), we.asset, uuid, at[0], at[1], at[2]));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.WORLD_EVENT_STARTED, System.nanoTime() - start);
        }
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
        // ends come from WorldEventEndSystem, which knows the outcome
    }

    private static UUID entityUuid(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
            return id == null ? null : id.getUuid();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static double[] position(Store<EntityStore> store, Ref<EntityStore> ref, WorldEvent we) {
        try {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            Vector3d p = t == null ? null : t.getPosition();
            if (p != null) {
                return new double[]{p.x, p.y, p.z};
            }
        } catch (RuntimeException | LinkageError e) {
            // try the context next
        }
        try {
            double[][] found = {null};
            if (we.context != null) {
                we.context.forEach(LocationContext.class, found, null, (loc, f, unused) -> {
                    Vector3i v = loc == null ? null : loc.location();
                    if (f[0] == null && v != null) {
                        f[0] = new double[]{v.x, v.y, v.z};
                    }
                });
            }
            if (found[0] != null) {
                return found[0];
            }
        } catch (RuntimeException | LinkageError e) {
            // no location in this event
        }
        return new double[]{0, 0, 0};
    }
}
