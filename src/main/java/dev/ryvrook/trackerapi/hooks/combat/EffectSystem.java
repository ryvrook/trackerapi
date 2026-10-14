package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.EffectChanged;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EFFECT_CHANGED, once a second per player, the Chronicle PositionPoller effect read. Status effects change
 * without an event, so the active effect indexes are read and diffed against the last sample of the same
 * player. An index that appears is applied, one that disappears ended. The first sample after a player is
 * forgotten only primes, so joining with an effect already running is not a new one. Nothing is allocated
 * while the set stays the same, beyond what the engine's read itself costs. World thread.
 */
public final class EffectSystem extends DelayedEntitySystem<EntityStore> {
    private static final int[] EMPTY = new int[0];

    private final Services s;
    private final Map<UUID, int[]> last = new ConcurrentHashMap<>();

    public EffectSystem(Services services) {
        super(1.0f);
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    /** Drops a player's last sample, on leave. */
    public void forget(UUID player) {
        if (player != null) {
            last.remove(player);
        }
    }

    public void clear() {
        last.clear();
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> commandBuffer) {
        if (!s.publisher.wants(EventKind.EFFECT_CHANGED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || player.getUuid() == null) {
                return;
            }
            EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
            int[] now = effects != null ? effects.getActiveEffectIndexes() : null;
            if (now == null) {
                now = EMPTY;
            }
            UUID uuid = player.getUuid();
            int[] prev = last.get(uuid);
            if (prev != null && Arrays.equals(prev, now)) {
                return;
            }
            last.put(uuid, now.length == 0 ? EMPTY : now.clone());
            if (prev == null) {
                return; // the first sample only primes
            }
            String world = PlayerEventSystem.worldName(store);
            for (int idx : now) {
                if (!contains(prev, idx)) {
                    publish(world, uuid, idx, true);
                }
            }
            for (int idx : prev) {
                if (!contains(now, idx)) {
                    publish(world, uuid, idx, false);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.EFFECT_CHANGED, System.nanoTime() - start);
        }
    }

    private void publish(String world, UUID uuid, int index, boolean applied) {
        String id = effectName(index);
        if (id != null) {
            s.publisher.publish(new EffectChanged(s.header(world, uuid), id, applied));
        }
    }

    /** Asset id of an entity effect index, or null. */
    private static String effectName(int index) {
        try {
            EntityEffect effect = index < 0 ? null : EntityEffect.getAssetMap().getAsset(index);
            return effect == null ? null : effect.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }
}
