package dev.ryvrook.trackerapi.hooks.combat;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.modules.projectile.event.ProjectileLaunchEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.ProjectileLaunched;
import dev.ryvrook.trackerapi.hooks.PlayerEventSystem;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * PROJECTILE_LAUNCHED. The engine dispatches {@code ProjectileLaunchEvent} on the entity that ran the launch
 * interaction, the shooter, with the new projectile's ref. The query is every entity so creatures that shoot
 * are seen too. The projectile was only just queued for adding, so its id is read in a guard and may be
 * null. World thread.
 */
public final class ProjectileLaunchSystem extends EntityEventSystem<EntityStore, ProjectileLaunchEvent> {
    private final Services s;

    public ProjectileLaunchSystem(Services services) {
        super(ProjectileLaunchEvent.class);
        this.s = services;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, ProjectileLaunchEvent event) {
        if (!s.publisher.wants(EventKind.PROJECTILE_LAUNCHED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            Actor shooter = CombatReads.actor(store, ref);
            String projectileId = ProjectileLandSystem.projectileId(store, event.getProjectileRef());
            s.publisher.publish(new ProjectileLaunched(s.header(PlayerEventSystem.worldName(store), shooter.playerUuid()),
                    shooter, projectileId, event.isLaunchedByWeapon()));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PROJECTILE_LAUNCHED, System.nanoTime() - start);
        }
    }
}
