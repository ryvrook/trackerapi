package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.EnvironmentBreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.prefab.event.PrefabPasteEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.BlockBrokenByWorld;
import dev.ryvrook.trackerapi.api.event.BlockDamaged;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.BlockUsed;
import dev.ryvrook.trackerapi.api.event.PrefabPasted;
import dev.ryvrook.trackerapi.facts.PlacedBlocks;
import dev.ryvrook.trackerapi.facts.RecentContainers;
import dev.ryvrook.trackerapi.facts.RecentPlacements;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3i;

import java.util.UUID;

/**
 * The {@code blocks} hook set. Breaks, placements, uses and hits by players, blocks the world broke by
 * itself and prefab pastes. Keeps the placed spots, the last placement and the last used block, which the
 * item hooks read, so it installs before them. Each system is its own class because the engine removes
 * systems by class. Every system runs on the world thread of the store it fired for.
 */
public final class BlockHooks implements HookSet {
    /** The block id the engine reports when a placement replaces air, which is not a real break. */
    static final String EMPTY_BLOCK = "Empty";

    private final Services s;
    private final PlacedBlocks placed = new PlacedBlocks();
    /** Spots where the player broke an unripe crop, which makes the next planting there a repeat. */
    private final PlacedBlocks unripe = new PlacedBlocks();
    private final RecentPlacements placements = new RecentPlacements();
    private final RecentContainers containers = new RecentContainers();

    public BlockHooks(Services services) {
        this.s = services;
    }

    @Override
    public String name() {
        return "blocks";
    }

    /** The last item each player placed, read by the item hooks for the consumed rule. */
    public RecentPlacements placements() {
        return placements;
    }

    /** The last block each player used, read by the item hooks for container moves. */
    public RecentContainers containers() {
        return containers;
    }

    public PlacedBlocks placed() {
        return placed;
    }

    @Override
    public void install(PluginBase plugin, Services services, Registrar registrar) {
        placed.registerWith(services.forgetting);
        unripe.registerWith(services.forgetting);
        placements.registerWith(services.forgetting);
        containers.registerWith(services.forgetting);
        registrar.entitySystem("block broken", () -> new BreakSystem(this));
        registrar.entitySystem("block placed", () -> new PlaceSystem(this));
        registrar.entitySystem("block used", () -> new UseSystem(this));
        registrar.entitySystem("block damaged", () -> new DamageSystem(this));
        registrar.entitySystem("block broken by world", () -> new EnvironmentBreakSystem(this));
        // PREFAB_PASTED is not hooked. Worldgen pastes prefabs from its generator threads, and a registered
        // system for the event would make the engine fork a command buffer off the world thread, as the chunk
        // unload event did on a live server. The kind stays in the API for a later safe source.
    }

    // ---- helpers shared with the item hooks -------------------------------------------------------------

    /** True when the player entity is in Creative mode right now. */
    static boolean creative(Store<EntityStore> store, Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getGameMode() == GameMode.Creative;
    }

    /** The item id of a stack, or null for an empty hand. */
    static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.getItemId();
    }

    static String blockId(BlockType type) {
        return type != null ? type.getId() : null;
    }

    /**
     * Notes a crop broken before it was ripe, whose seed drops back and could be planted again on the spot.
     * A ripe crop and any other block leave the spot alone.
     */
    static void cropBroken(PlacedBlocks unripe, UUID uuid, String world, int x, int y, int z, boolean farming,
                           boolean ripe) {
        if (farming && !ripe) {
            unripe.place(uuid, world, x, y, z);
        }
    }

    /**
     * The repeat flag of a placement. A planting is a repeat when the player broke an unripe crop on the spot
     * since, and takes the note away. Any other block is a repeat when the spot was already in the player's
     * ring, which {@code fresh} answers.
     */
    static boolean repeatSpot(PlacedBlocks unripe, UUID uuid, String world, int x, int y, int z, boolean planted,
                              boolean fresh) {
        return planted ? unripe.release(uuid, world, x, y, z) : !fresh;
    }

    // ---- BLOCK_BROKEN -----------------------------------------------------------------------------------

    /**
     * {@code BreakBlockEvent} on players. The Empty id fires on placement and is skipped. The F key harvest
     * of a ripe crop comes through here too, from {@code BlockHarvestUtils.performPickupByInteraction} with
     * no item in hand [javap 0.7.1]. Breaking an unripe crop drops its seed, so the spot is noted and the
     * next planting there is a repeat.
     */
    static final class BreakSystem extends AnyKindPlayerSystem<BreakBlockEvent> {
        private final BlockHooks h;

        BreakSystem(BlockHooks hooks) {
            super(BreakBlockEvent.class, hooks.s, EventKind.BLOCK_BROKEN, EventKind.BLOCK_PLACED);
            this.h = hooks;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    BreakBlockEvent event) {
            if (event.isCancelled()) {
                return;
            }
            String blockId = blockId(event.getBlockType());
            if (blockId == null || EMPTY_BLOCK.equals(blockId)) {
                return;
            }
            Vector3i pos = event.getTargetBlock();
            if (pos == null) {
                return;
            }
            String world = worldName(store);
            BlockType type = event.getBlockType();
            boolean farming = FarmBlocks.farming(type);
            boolean ripe = farming && FarmBlocks.ripe(type);
            cropBroken(h.unripe, uuid, world, pos.x, pos.y, pos.z, farming, ripe);
            if (!s.publisher.wants(EventKind.BLOCK_BROKEN)) {
                return;
            }
            boolean own = h.placed.placedHere(uuid, world, pos.x, pos.y, pos.z);
            s.publisher.publish(new BlockBroken(s.header(world, uuid), pos.x, pos.y, pos.z, blockId,
                    itemId(event.getItemInHand()), own, creative(store, ref), ripe));
        }
    }

    // ---- BLOCK_PLACED -----------------------------------------------------------------------------------

    /**
     * {@code PlaceBlockEvent} on players. Notes the spot and the item even when nobody wants the placement
     * itself, since the break flag and the consumed rule read them. Planting a seed fires this same event
     * with the seed bag in hand, see {@link FarmBlocks#placedBy}. A planting is a repeat only when the player
     * broke an unripe crop on the spot since, so replanting a harvested plot counts while planting and
     * breaking the same seed does not.
     */
    static final class PlaceSystem extends AnyKindPlayerSystem<PlaceBlockEvent> {
        private final BlockHooks h;

        PlaceSystem(BlockHooks hooks) {
            super(PlaceBlockEvent.class, hooks.s, EventKind.BLOCK_PLACED, EventKind.BLOCK_BROKEN,
                    EventKind.ITEM_CONSUMED, EventKind.ITEMS_LOST);
            this.h = hooks;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    PlaceBlockEvent event) {
            if (event.isCancelled()) {
                return;
            }
            ItemStack item = event.getItemInHand();
            String itemId = itemId(item);
            h.placements.record(uuid, itemId);
            Vector3i pos = event.getTargetBlock();
            if (pos == null) {
                return;
            }
            String world = worldName(store);
            boolean fresh = h.placed.place(uuid, world, pos.x, pos.y, pos.z);
            String blockKey = item != null ? item.getBlockKey() : null;
            boolean planted = false;
            if (blockKey == null) {
                blockKey = FarmBlocks.placedBy(itemId);
                planted = FarmBlocks.farming(blockKey);
            }
            boolean repeat = repeatSpot(h.unripe, uuid, world, pos.x, pos.y, pos.z, planted, fresh);
            if (!s.publisher.wants(EventKind.BLOCK_PLACED)) {
                return;
            }
            s.publisher.publish(new BlockPlaced(s.header(world, uuid), pos.x, pos.y, pos.z, itemId, blockKey,
                    repeat, creative(store, ref), planted));
        }
    }

    // ---- BLOCK_USED -------------------------------------------------------------------------------------

    /** {@code UseBlockEvent.Post} on players. Also remembers the block for the container moves after it. */
    static final class UseSystem extends AnyKindPlayerSystem<UseBlockEvent.Post> {
        private final BlockHooks h;

        UseSystem(BlockHooks hooks) {
            super(UseBlockEvent.Post.class, hooks.s, EventKind.BLOCK_USED, EventKind.ITEMS_GAINED,
                    EventKind.ITEMS_LOST);
            this.h = hooks;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    UseBlockEvent.Post event) {
            Vector3i pos = event.getTargetBlock();
            String world = worldName(store);
            if (pos != null) {
                h.containers.remember(uuid, world, pos.x, pos.y, pos.z);
            }
            if (!s.publisher.wants(EventKind.BLOCK_USED)) {
                return;
            }
            String blockId = blockId(event.getBlockType());
            if (blockId == null) {
                return;
            }
            s.publisher.publish(new BlockUsed(s.header(world, uuid), pos != null ? pos.x : 0, pos != null ? pos.y : 0,
                    pos != null ? pos.z : 0, blockId));
        }
    }

    // ---- BLOCK_DAMAGED ----------------------------------------------------------------------------------

    /** {@code DamageBlockEvent} on players, every swing that hits a block. */
    static final class DamageSystem extends PlayerEventSystem<DamageBlockEvent> {
        DamageSystem(BlockHooks hooks) {
            super(DamageBlockEvent.class, EventKind.BLOCK_DAMAGED, hooks.s);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    DamageBlockEvent event) {
            if (event.isCancelled()) {
                return;
            }
            Vector3i pos = event.getTargetBlock();
            if (pos == null) {
                return;
            }
            s.publisher.publish(new BlockDamaged(s.header(worldName(store), uuid), pos.x, pos.y, pos.z,
                    blockId(event.getBlockType()), itemId(event.getItemInHand()), event.getDamage(),
                    event.getCurrentDamage()));
        }
    }

    // ---- BLOCK_BROKEN_BY_WORLD --------------------------------------------------------------------------

    /** {@code EnvironmentBreakBlockEvent}, a world event with no actor, fire, fluids and physics. */
    static final class EnvironmentBreakSystem extends WorldEventSystem<EntityStore, EnvironmentBreakBlockEvent> {
        private final Services s;

        EnvironmentBreakSystem(BlockHooks hooks) {
            super(EnvironmentBreakBlockEvent.class);
            this.s = hooks.s;
        }

        @Override
        public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                           EnvironmentBreakBlockEvent event) {
            if (!s.publisher.wants(EventKind.BLOCK_BROKEN_BY_WORLD)) {
                return;
            }
            long start = System.nanoTime();
            try {
                Vector3i pos = event.getTargetBlock();
                String blockId = blockId(event.getBlockType());
                if (pos == null || blockId == null || EMPTY_BLOCK.equals(blockId)) {
                    return;
                }
                s.publisher.publish(new BlockBrokenByWorld(s.header(PlayerEventSystem.worldName(store), null),
                        pos.x, pos.y, pos.z, blockId, null));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            } finally {
                s.perf.record(EventKind.BLOCK_BROKEN_BY_WORLD, System.nanoTime() - start);
            }
        }
    }

    // ---- PREFAB_PASTED ----------------------------------------------------------------------------------

    /** {@code PrefabPasteEvent}, a world event, published once per paste at its start. */
    static final class PrefabPasteSystem extends WorldEventSystem<EntityStore, PrefabPasteEvent> {
        private final Services s;

        PrefabPasteSystem(BlockHooks hooks) {
            super(PrefabPasteEvent.class);
            this.s = hooks.s;
        }

        @Override
        public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, PrefabPasteEvent event) {
            if (!s.publisher.wants(EventKind.PREFAB_PASTED)) {
                return;
            }
            long start = System.nanoTime();
            try {
                if (event.isCancelled() || !event.isPasteStart()) {
                    return;
                }
                s.publisher.publish(new PrefabPasted(s.header(PlayerEventSystem.worldName(store), null),
                        event.getPrefabId(), 0, 0, 0));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            } finally {
                s.perf.record(EventKind.PREFAB_PASTED, System.nanoTime() - start);
            }
        }
    }
}
