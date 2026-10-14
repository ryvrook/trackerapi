package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.TriggerVolumeFired;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * TRIGGER_VOLUME_FIRED from the world keyed {@code TriggerVolumeEvent}, every key. The entity is resolved to
 * an Actor only when its ref is valid and this is its store's thread, a player by uuid and name, a creature
 * by NPC type id and entity uuid. The header names the player when the entity is one. World thread by
 * inference.
 */
public final class TriggerVolumeListener {
    private final Services s;

    public TriggerVolumeListener(Services services) {
        this.s = services;
    }

    public void register(EventRegistry events) {
        events.registerGlobal(TriggerVolumeEvent.class, this::onTrigger);
    }

    private void onTrigger(TriggerVolumeEvent event) {
        if (!s.publisher.wants(EventKind.TRIGGER_VOLUME_FIRED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            TriggerEventType type = event.getTriggerEventType();
            Actor entity = actor(event.getEntityRef());
            s.publisher.publish(new TriggerVolumeFired(s.header(event.getWorldName(), entity == null ? null : entity.playerUuid()),
                    event.getVolumeId(), type == null ? null : type.name(), entity));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.TRIGGER_VOLUME_FIRED, System.nanoTime() - start);
        }
    }

    private static Actor actor(Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) {
                return null;
            }
            Store<EntityStore> store = ref.getStore();
            if (store == null || !store.isInThread()) {
                return null;
            }
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                return Actor.player(player.getUuid(), player.getUsername());
            }
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null) {
                UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
                return Actor.creature(id == null ? null : id.getUuid(), npc.getNPCTypeId());
            }
            return null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
