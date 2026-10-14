package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.VitalsSampled;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * VITALS_SAMPLED, health, stamina and mana of every player every {@code vitalsSeconds}, the Chronicle
 * EntityStatsSystem read. There is no stat change event, so sampling is the only reliable read. Registered
 * only when the setting is above zero. A stat the player does not have reads as -1. World thread.
 */
public final class VitalsSystem extends DelayedEntitySystem<EntityStore> {
    private final Services s;

    public VitalsSystem(Services services, float intervalSeconds) {
        super(intervalSeconds);
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> commandBuffer) {
        if (!s.publisher.wants(EventKind.VITALS_SAMPLED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
            if (player == null || stats == null) {
                return;
            }
            EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
            if (health == null) {
                return;
            }
            EntityStatValue stamina = stats.get(DefaultEntityStatTypes.getStamina());
            EntityStatValue mana = stats.get(DefaultEntityStatTypes.getMana());
            s.publisher.publish(new VitalsSampled(s.header(PlayerEventSystem.worldName(store), player.getUuid()),
                    health.get(), health.getMax(),
                    stamina != null ? stamina.get() : -1, stamina != null ? stamina.getMax() : -1,
                    mana != null ? mana.get() : -1, mana != null ? mana.getMax() : -1));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.VITALS_SAMPLED, System.nanoTime() - start);
        }
    }
}
