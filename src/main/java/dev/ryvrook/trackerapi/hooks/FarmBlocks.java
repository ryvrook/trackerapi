package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceBlockInteraction;
import dev.ryvrook.trackerapi.facts.Crops;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine reads for seeds and crops, by id, memoised so the block hooks pay for them once per id.
 *
 * <p>A seed bag has no block of its own. Its Secondary chain checks for tilled soil and then runs the
 * interaction in its {@code SeedId} var, a {@code PlaceBlock} whose {@code BlockTypeToPlace} names the crop,
 * for example {@code Plant_Crop_Carrot_Block}. {@code PlaceBlockInteraction} passes that key to
 * {@code BlockPlaceUtils.placeBlock}, which fires {@code PlaceBlockEvent} with the seed bag as the item in
 * hand and no word of the block [javap 0.7.1]. {@link #placedBy} walks the item's vars to the same
 * interaction and reads the block from its packet, whose {@code blockId} is the index of that key.
 *
 * <p>A crop is a block whose farming data has growth stages, which takes in saplings and leaves out tilled
 * soil, whose farming data only says when it dries out. The growth stages are state variants, which may not
 * carry the farming data themselves, so a state falls back to its base block. A crop is ripe when its stage has a
 * {@code Harvest} gathering, which for the vegetables is only {@code StageFinal}.
 *
 * <p>The asset maps read under their own lock and the fields read here are fixed once the asset loaded, so
 * any thread may call this. An asset reload can change a seed, so the memo is cleared when it grows past
 * its cap and a stale entry lives until then at most.
 */
final class FarmBlocks {
    private static final int MEMO_CAP = 4_096;
    /** The memo's word for an item that places no block through its vars. */
    private static final String NONE = "";

    private static final Map<String, String> PLACED_BY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FARMING = new ConcurrentHashMap<>();

    private FarmBlocks() {
    }

    /**
     * The block a block-less item places through a {@code PlaceBlock} in its interaction vars, such as the
     * crop of a seed bag, or null.
     */
    static String placedBy(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        String hit = PLACED_BY.get(itemId);
        if (hit == null) {
            hit = resolve(itemId);
            if (PLACED_BY.size() >= MEMO_CAP) {
                PLACED_BY.clear();
            }
            PLACED_BY.put(itemId, hit);
        }
        return hit.isEmpty() ? null : hit;
    }

    private static String resolve(String itemId) {
        Item item = Item.getAssetMap().getAsset(itemId);
        Map<String, String> vars = item == null ? null : item.getInteractionVars();
        if (vars == null || vars.isEmpty()) {
            return NONE;
        }
        for (String rootId : vars.values()) {
            RootInteraction root = rootId == null ? null : RootInteraction.getAssetMap().getAsset(rootId);
            String[] ids = root == null ? null : root.getInteractionIds();
            if (ids == null) {
                continue;
            }
            for (String id : ids) {
                Interaction step = id == null ? null : Interaction.getAssetMap().getAsset(id);
                if (step instanceof PlaceBlockInteraction
                        && step.toPacket() instanceof com.hypixel.hytale.protocol.PlaceBlockInteraction packet
                        && packet.blockId >= 0) {
                    BlockType type = BlockType.getAssetMap().getAsset(packet.blockId);
                    if (type != null && !type.isUnknown() && type.getId() != null) {
                        return type.getId();
                    }
                }
            }
        }
        return NONE;
    }

    /** True when the block, or the block it is a state of, grows in stages. */
    static boolean farming(String blockId) {
        return blockId != null && farming(BlockType.getAssetMap().getAsset(blockId));
    }

    /** True when the block, or the block it is a state of, grows in stages. */
    static boolean farming(BlockType type) {
        if (type == null) {
            return false;
        }
        if (grows(type.getFarming())) {
            return true;
        }
        if (!type.isState()) {
            return false;
        }
        String id = type.getId();
        if (id == null) {
            return false;
        }
        Boolean known = FARMING.get(id);
        if (known == null) {
            BlockType base = BlockType.getAssetMap().getAsset(Crops.baseId(id));
            known = base != null && base != type && grows(base.getFarming());
            if (FARMING.size() >= MEMO_CAP) {
                FARMING.clear();
            }
            FARMING.put(id, known);
        }
        return known;
    }

    private static boolean grows(FarmingData farming) {
        if (farming == null) {
            return false;
        }
        Map<String, ?> stages = farming.getStages();
        return stages != null && !stages.isEmpty();
    }

    /** True when the block is a crop in a stage that can be harvested. */
    static boolean ripe(BlockType type) {
        if (!farming(type)) {
            return false;
        }
        BlockGathering gathering = type.getGathering();
        return gathering != null && gathering.isHarvestable();
    }
}
