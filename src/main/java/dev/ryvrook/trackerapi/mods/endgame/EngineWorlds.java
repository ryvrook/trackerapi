package dev.ryvrook.trackerapi.mods.endgame;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.facts.WorldTasks;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The engine side of {@link EndgameBinding.Worlds}. A seed of everyone posts one task per world with players
 * through a {@link WorldTasks.Gate}, so a world thread that fell behind holds at most one waiting seed, and a
 * seed of one player posts to the world the player's reference names without the gate, since a join must not be
 * dropped behind a bind time seed. On the world thread a player whose reference is not in that world's store is
 * skipped, they are between worlds and the retry or the next join reads them where they land. Never throws.
 */
final class EngineWorlds implements EndgameBinding.Worlds {
    static final EngineWorlds LIVE = new EngineWorlds();

    private final WorldTasks.Gate gate = new WorldTasks.Gate(10_000);

    private EngineWorlds() {
    }

    @Override
    public void seedAll(Consumer<UUID> seed) {
        Universe universe;
        try {
            universe = Universe.get();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (universe == null) {
            return;
        }
        gate.sweep();
        for (World world : universe.getWorlds().values()) {
            try {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                gate.post(world, () -> {
                    try {
                        Store<EntityStore> store = world.getEntityStore().getStore();
                        for (PlayerRef pr : world.getPlayerRefs()) {
                            seedOne(pr, store, seed);
                        }
                    } catch (RuntimeException | LinkageError ignored) {
                        // a world closing under the seed
                    }
                });
            } catch (RuntimeException | LinkageError ignored) {
                // the next world
            }
        }
    }

    @Override
    public void seedOne(UUID uuid, Consumer<UUID> seed) {
        try {
            Universe universe = Universe.get();
            if (universe == null || uuid == null) {
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
            WorldTasks.post(world, () -> {
                try {
                    seedOne(pr, world.getEntityStore().getStore(), seed);
                } catch (RuntimeException | LinkageError ignored) {
                    // the player left while the task waited
                }
            });
        } catch (RuntimeException | LinkageError ignored) {
            // no engine
        }
    }

    private static void seedOne(PlayerRef pr, Store<EntityStore> store, Consumer<UUID> seed) {
        if (pr == null) {
            return;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return;
        }
        try {
            seed.accept(pr.getUuid());
        } catch (RuntimeException | LinkageError ignored) {
            // the seed guards itself
        }
    }
}
