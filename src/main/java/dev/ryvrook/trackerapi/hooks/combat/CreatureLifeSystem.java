package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.CreatureRemoved;
import dev.ryvrook.trackerapi.api.event.CreatureSpawned;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3d;

/**
 * CREATURE_SPAWNED and CREATURE_REMOVED, a {@code RefSystem} over {@code NPCEntity} because no spawn event
 * exists (the Chronicle NpcSpawnSystem). Only {@code AddReason.SPAWN} and {@code RemoveReason.REMOVE}
 * count. {@code LOAD} and {@code UNLOAD} are creatures travelling with their chunk, and a role change (a
 * tame, a boss phase) unloads and loads the entity too, so counting those would make every restart look
 * like a spawn wave. World thread.
 */
public final class CreatureLifeSystem extends RefSystem<EntityStore> {
    private final Services s;

    public CreatureLifeSystem(Services services) {
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store,
                              CommandBuffer<EntityStore> commandBuffer) {
        if (reason != AddReason.SPAWN || !s.publisher.wants(EventKind.CREATURE_SPAWNED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            Vector3d pos = CombatReads.position(store, ref);
            s.publisher.publish(new CreatureSpawned(s.header(PlayerEventSystem.worldName(store), null),
                    npc.getNPCTypeId(), CombatReads.uuidOf(store, ref), pos != null ? pos.x : 0,
                    pos != null ? pos.y : 0, pos != null ? pos.z : 0, reason.name()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CREATURE_SPAWNED, System.nanoTime() - start);
        }
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store,
                               CommandBuffer<EntityStore> commandBuffer) {
        if (reason != RemoveReason.REMOVE || !s.publisher.wants(EventKind.CREATURE_REMOVED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            Vector3d pos = CombatReads.position(store, ref);
            s.publisher.publish(new CreatureRemoved(s.header(PlayerEventSystem.worldName(store), null),
                    npc.getNPCTypeId(), CombatReads.uuidOf(store, ref), pos != null ? pos.x : 0,
                    pos != null ? pos.y : 0, pos != null ? pos.z : 0));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.CREATURE_REMOVED, System.nanoTime() - start);
        }
    }
}
