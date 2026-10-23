package dev.ryvrook.trackerapi.mods.rpg;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.UUID;

/**
 * The engine walk behind the RPG Leveling reads. Posts one task per world through {@link WorldTasks#post} and
 * visits each player whose reference lives in that world's store, on the world thread, as the sampler does. A
 * single player is found through the universe and visited on the world their reference names. Timer or dispatch
 * thread in, world threads out.
 */
final class EngineRpgPlayers implements RpgPlayers {
    @Override
    public void forEachOnline(Visitor visitor) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        for (World world : universe.getWorlds().values()) {
            try {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                WorldTasks.post(world, () -> visitWorld(world, visitor));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            }
        }
    }

    @Override
    public void one(UUID uuid, Visitor visitor) {
        if (uuid == null) {
            return;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            PlayerRef pr = universe.getPlayer(uuid);
            if (pr == null) {
                return;
            }
            UUID worldId = pr.getWorldUuid();
            World world = worldId == null ? null : universe.getWorld(worldId);
            if (world == null) {
                return;
            }
            WorldTasks.post(world, () -> visitOne(world, pr, visitor));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private static void visitWorld(World world, Visitor visitor) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (PlayerRef pr : world.getPlayerRefs()) {
                visitPlayer(pr, store, visitor);
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private static void visitOne(World world, PlayerRef pr, Visitor visitor) {
        try {
            visitPlayer(pr, world.getEntityStore().getStore(), visitor);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private static void visitPlayer(PlayerRef pr, Store<EntityStore> store, Visitor visitor) {
        if (pr == null) {
            return;
        }
        try {
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) {
                return; // between worlds, read where they land
            }
            visitor.visit(pr.getUuid(), pr, ref, store);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }
}
