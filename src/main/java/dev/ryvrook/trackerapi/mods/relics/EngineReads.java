package dev.ryvrook.trackerapi.mods.relics;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;

import java.util.ArrayList;
import java.util.UUID;

/**
 * The engine behind {@link RelicsBinding.Engine}. The held item is read the way the item hooks read it,
 * {@code InventoryComponent.getItemInHand} on the world thread. The chain scan posts one task per world with
 * players through a {@link WorldTasks.Gate}, as the sampler does, and on the world thread walks the players,
 * reads each one's hand and, for a relic holder, the chains of the engine's {@code InteractionManager} component.
 */
final class EngineReads implements RelicsBinding.Engine {
    private final WorldTasks.Gate gate = new WorldTasks.Gate(5_000);
    /** The chain list handed to the visitor, one per world thread, cleared and refilled per relic holder. */
    private final ThreadLocal<ArrayList<RelicsBinding.Chain>> buffer = ThreadLocal.withInitial(() -> new ArrayList<>(8));

    @Override
    public String heldItem(UUID player) {
        Universe universe = Universe.get();
        if (universe == null || player == null) {
            return null;
        }
        PlayerRef pr = universe.getPlayer(player);
        if (pr == null) {
            return null;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        EntityStore entities = store == null ? null : store.getExternalData();
        World world = entities == null ? null : entities.getWorld();
        if (world == null || !world.isInThread()) {
            return null;
        }
        return itemId(InventoryComponent.getItemInHand(store, ref));
    }

    @Override
    public void checkChains() {
        InteractionModule module = InteractionModule.get();
        if (module == null) {
            throw new IllegalStateException("the interaction module is not loaded");
        }
        if (module.getInteractionManagerComponent() == null) {
            throw new IllegalStateException("the interaction manager component is not registered");
        }
    }

    @Override
    public void scanChains(RelicsBinding.ChainVisitor visitor) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        for (World world : universe.getWorlds().values()) {
            try {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                gate.post(world, () -> scanWorld(world, visitor));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            }
        }
    }

    private void scanWorld(World world, RelicsBinding.ChainVisitor visitor) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            ComponentType<EntityStore, InteractionManager> type = InteractionModule.get().getInteractionManagerComponent();
            String name = world.getName();
            ArrayList<RelicsBinding.Chain> list = buffer.get();
            for (PlayerRef pr : world.getPlayerRefs()) {
                try {
                    scanPlayer(pr, store, type, name, visitor, list);
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            }
            list.clear();
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    /** The visitor reads {@code list} during the call only, it is reused for the next player. */
    private static void scanPlayer(PlayerRef pr, Store<EntityStore> store, ComponentType<EntityStore, InteractionManager> type,
                                   String world, RelicsBinding.ChainVisitor visitor, ArrayList<RelicsBinding.Chain> list) {
        if (pr == null) {
            return;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return;
        }
        String held = itemId(InventoryComponent.getItemInHand(store, ref));
        if (!Relics.isRelic(held)) {
            return;
        }
        InteractionManager manager = store.getComponent(ref, type);
        if (manager == null) {
            return;
        }
        var chains = manager.getChains();
        list.clear();
        if (chains != null) {
            for (InteractionChain chain : chains.values()) {
                if (chain == null) {
                    continue;
                }
                RootInteraction root = chain.getRootInteraction();
                list.add(new RelicsBinding.Chain(chain.getChainId(), root == null ? null : root.getId()));
            }
        }
        visitor.visit(pr.getUuid(), world, held, list);
    }

    private static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.getItemId();
    }
}
