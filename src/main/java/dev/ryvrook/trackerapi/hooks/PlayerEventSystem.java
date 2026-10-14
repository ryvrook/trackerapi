package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.UUID;

/**
 * Base class for ECS event systems that only care about player entities. Resolves the player, checks
 * that someone wants the kind before anything else, times the body into the Perf slot and swallows
 * failures with one warning a minute. World thread.
 */
public abstract class PlayerEventSystem<E extends EcsEvent> extends EntityEventSystem<EntityStore, E> {
    protected final Services s;
    protected final EventKind kind;

    protected PlayerEventSystem(Class<E> eventType, EventKind kind, Services services) {
        super(eventType);
        this.kind = kind;
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, E event) {
        if (!s.publisher.wants(kind)) {
            return;
        }
        long start = System.nanoTime();
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            handlePlayer(playerRef.getUuid(), playerRef, ref, store, event);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(kind, System.nanoTime() - start);
        }
    }

    protected abstract void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, E event);

    /** Name of the world this store belongs to, or an empty string when it cannot be read. */
    public static String worldName(Store<EntityStore> store) {
        try {
            EntityStore entities = store.getExternalData();
            World world = entities != null ? entities.getWorld() : null;
            String name = world != null ? world.getName() : null;
            return name != null ? name : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The world of this store, or null. */
    public static World worldOf(Store<EntityStore> store) {
        try {
            EntityStore entities = store.getExternalData();
            return entities != null ? entities.getWorld() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
