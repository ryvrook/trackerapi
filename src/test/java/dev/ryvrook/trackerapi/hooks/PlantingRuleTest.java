package dev.ryvrook.trackerapi.hooks;

import dev.ryvrook.trackerapi.facts.PlacedBlocks;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repeat rule of plantings, driven the way the block hooks drive it. The placement ring is the one every
 * block uses, the unripe ring holds the spots where an unripe crop was broken.
 */
class PlantingRuleTest {
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String W = "default";

    private final PlacedBlocks placed = new PlacedBlocks();
    private final PlacedBlocks unripe = new PlacedBlocks();

    /** One placement as PlaceSystem makes it, returning the repeat flag. */
    private boolean place(int x, boolean planted) {
        boolean fresh = placed.place(P, W, x, 64, 0);
        return BlockHooks.repeatSpot(unripe, P, W, x, 64, 0, planted, fresh);
    }

    private void breakCrop(int x, boolean ripe) {
        BlockHooks.cropBroken(unripe, P, W, x, 64, 0, true, ripe);
    }

    @Test
    void replantingAHarvestedPlotCountsEveryTime() {
        for (int season = 0; season < 5; season++) {
            assertFalse(place(1, true), "season " + season);
            breakCrop(1, true);
        }
    }

    @Test
    void plantingAndBreakingTheSameSeedIsARepeat() {
        assertFalse(place(1, true));
        breakCrop(1, false);
        assertTrue(place(1, true), "the seed came back from the unripe crop");
        assertFalse(place(2, true), "another plot is fine");
    }

    @Test
    void theNoteGoesOnceTheRepeatIsSeen() {
        place(1, true);
        breakCrop(1, false);
        assertTrue(place(1, true));
        breakCrop(1, true);
        assertFalse(place(1, true), "it grew this time, so the next planting counts");
    }

    @Test
    void aSickleHarvestLeavesNoBreakAndTheReplantStillCounts() {
        assertFalse(place(1, true));
        // FarmingUtil.harvest sets the block to Empty without a BreakBlockEvent, so nothing is noted here
        assertFalse(place(1, true));
    }

    @Test
    void otherBlocksKeepTheRingRule() {
        assertFalse(place(5, false));
        assertTrue(place(5, false), "a plank on a spot just used is a repeat as before");
        BlockHooks.cropBroken(unripe, P, W, 5, 64, 0, false, false);
        assertTrue(place(5, false), "breaking a block that is no crop notes nothing");
    }

    @Test
    void aBlockOnARecentPlotDoesNotEatTheCropNote() {
        place(1, true);
        breakCrop(1, false);
        assertTrue(place(1, false), "a plank there is a ring repeat");
        assertTrue(place(1, true), "and the seed after it is still the repeat it was");
    }
}
