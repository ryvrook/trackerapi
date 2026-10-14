package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * How many recipe runs one {@code CraftRecipeEvent.Post} stands for.
 *
 * <p>The engine has two craft paths. An instant craft fires one event carrying the number of runs it
 * performed. A queued craft, which is every recipe with a craft time and every craft from a diagram or
 * structural bench, fires one event per finished unit, and stamps each of those events with the quantity
 * of the whole batch. Counting the quantity of every event then adds up a batch of a hundred as ten
 * thousand. A queued craft is told apart by the recipe's craft time, or by the player's crafting manager
 * still holding a job when the event fires, and counts as one run per event.
 */
public final class CraftCounts {
    private CraftCounts() {
    }

    public static long of(Store<EntityStore> store, Ref<EntityStore> ref, CraftingRecipe recipe, int quantity) {
        return queued(store, ref, recipe) ? 1L : Math.max(1L, quantity);
    }

    /** True when the event came from the bench queue rather than an instant craft. */
    public static boolean queued(Store<EntityStore> store, Ref<EntityStore> ref, CraftingRecipe recipe) {
        try {
            if (recipe != null && recipe.getTimeSeconds() > 0f) {
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            // fall through to the queue check
        }
        try {
            if (store == null || ref == null || !ref.isValid() || ref.getStore() != store) {
                return false;
            }
            CraftingManager manager = store.getComponent(ref, CraftingManager.getComponentType());
            return manager != null && manager.getRemainingQueueSize() > 0;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
