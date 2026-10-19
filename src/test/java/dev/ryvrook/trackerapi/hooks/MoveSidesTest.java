package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import dev.ryvrook.trackerapi.TestSupport;
import dev.ryvrook.trackerapi.counters.CounterStore;
import dev.ryvrook.trackerapi.facts.StashBalance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The player's side of a move, built from real engine transactions. The stacks are a small subclass, since a
 * real ItemStack looks its item up in the asset store, which needs a running server.
 */
class MoveSidesTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String SWORD = "Weapon_Sword_Iron";
    private static final String AXE = "Tool_Hatchet_Iron";

    @TempDir
    Path dir;

    /** A stack that needs no asset store. */
    static final class Stack extends ItemStack {
        private final String id;
        private final int quantity;

        Stack(String id, int quantity) {
            this.id = id;
            this.quantity = quantity;
        }

        @Override
        public String getItemId() {
            return id;
        }

        @Override
        public int getQuantity() {
            return quantity;
        }

        @Override
        public boolean isEmpty() {
            return id == null || quantity <= 0;
        }
    }

    private static SlotTransaction slot(ActionType action, int index, ItemStack before, ItemStack after) {
        return new SlotTransaction(true, action, (short) index, before, after, null, false, false, false);
    }

    /** The swap the engine builds when a stack is dropped on a slot holding a different non stackable item. */
    private static MoveTransaction<SlotTransaction> swap(MoveType type, ItemStack mineBefore, ItemStack mineAfter) {
        SlotTransaction mine = slot(ActionType.REPLACE, 3, mineBefore, mineAfter);
        SlotTransaction theirs = slot(ActionType.REPLACE, 7, mineAfter, mineBefore);
        return type == MoveType.MOVE_TO_SELF
                ? new MoveTransaction<>(true, theirs, MoveType.MOVE_TO_SELF, null, mine)
                : new MoveTransaction<>(true, mine, MoveType.MOVE_FROM_SELF, null, theirs);
    }

    @Test
    void aSwapStartedOnTheChestSideRecordsWhatWentIn() {
        // The player drops the chest's axe on their own slot holding a sword. The player's side is the add side.
        MoveSides sides = MoveSides.of(swap(MoveType.MOVE_TO_SELF, new Stack(SWORD, 1), new Stack(AXE, 1)));
        assertEquals(Map.of(SWORD, 1L), sides.left);
        assertEquals(Map.of(AXE, 1L), sides.arrived);
        assertEquals(7, MoveSides.otherSlot(swap(MoveType.MOVE_TO_SELF, new Stack(SWORD, 1), new Stack(AXE, 1))));
    }

    @Test
    void aSwapStartedOnThePlayerSideRecordsWhatCameBack() {
        // The player drops their sword on the chest slot holding the axe. The player's side is the remove side.
        MoveSides sides = MoveSides.of(swap(MoveType.MOVE_FROM_SELF, new Stack(SWORD, 1), new Stack(AXE, 1)));
        assertEquals(Map.of(SWORD, 1L), sides.left);
        assertEquals(Map.of(AXE, 1L), sides.arrived);
    }

    @Test
    void plainMovesAndPartialStacksCountOnlyTheDifference() {
        MoveSides in = MoveSides.of(new MoveTransaction<>(true, slot(ActionType.REMOVE, 0, new Stack("Ore_Iron", 20), null),
                MoveType.MOVE_TO_SELF, null, slot(ActionType.ADD, 4, new Stack("Ore_Iron", 5), new Stack("Ore_Iron", 25))));
        assertEquals(Map.of("Ore_Iron", 20L), in.arrived);
        assertNull(in.left);

        MoveSides out = MoveSides.of(new MoveTransaction<>(true, slot(ActionType.REMOVE, 4, new Stack("Ore_Iron", 25),
                new Stack("Ore_Iron", 10)), MoveType.MOVE_FROM_SELF, null, SlotTransaction.FAILED_ADD));
        assertEquals(Map.of("Ore_Iron", 15L), out.left);
        assertNull(out.arrived);
    }

    @Test
    void aMoveThatChangedNothingIsNull() {
        assertNull(MoveSides.of(new MoveTransaction<>(true, slot(ActionType.REMOVE, 0, new Stack(SWORD, 1), new Stack(SWORD, 1)),
                MoveType.MOVE_FROM_SELF, null, SlotTransaction.FAILED_ADD)));
        assertNull(MoveSides.of(new MoveTransaction<>(false, slot(ActionType.REMOVE, 0, new Stack(SWORD, 1), null),
                MoveType.MOVE_FROM_SELF, null, SlotTransaction.FAILED_ADD)));
    }

    @Test
    void aTakeAllListAddsUpEverySlot() {
        SlotTransaction a = slot(ActionType.ADD, 1, null, new Stack("Ore_Copper", 8));
        SlotTransaction b = slot(ActionType.ADD, 2, new Stack("Ore_Copper", 60), new Stack("Ore_Copper", 64));
        ListTransaction<SlotTransaction> both = new ListTransaction<>(true, List.of(a, b));
        MoveSides sides = MoveSides.of(new MoveTransaction<>(true, slot(ActionType.REMOVE, 0, new Stack("Ore_Copper", 12), null),
                MoveType.MOVE_TO_SELF, null, both));
        assertEquals(Map.of("Ore_Copper", 12L), sides.arrived);
    }

    @Test
    void theSwapLoopNeverCountsAgainInEitherDirection() {
        CounterStore store = TestSupport.store(dir);
        store.join(A, "Alder", 1L);
        StashBalance stash = new StashBalance(store);
        long fresh = 0;
        // The world generated chest held an axe. Swap it for the sword from the chest side, then back from the
        // player side, many times over. Only the first look at the axe is new.
        for (int round = 0; round < 50; round++) {
            MoveTransaction<SlotTransaction> move = round % 2 == 0
                    ? swap(MoveType.MOVE_TO_SELF, new Stack(SWORD, 1), new Stack(AXE, 1))
                    : swap(MoveType.MOVE_FROM_SELF, new Stack(AXE, 1), new Stack(SWORD, 1));
            MoveSides sides = MoveSides.of(move);
            sides.left.forEach((id, n) -> stash.put(A, id, n));
            for (Map.Entry<String, Long> e : sides.arrived.entrySet()) {
                fresh += stash.fresh(A, e.getKey(), e.getValue(), false);
            }
        }
        assertEquals(1, fresh, "the axe once, the sword never");
        // And the other way round, the player starting the swap first.
        CounterStore other = TestSupport.store(dir.resolve("b"));
        other.join(A, "Alder", 1L);
        StashBalance stash2 = new StashBalance(other);
        long fresh2 = 0;
        for (int round = 0; round < 50; round++) {
            MoveTransaction<SlotTransaction> move = round % 2 == 0
                    ? swap(MoveType.MOVE_FROM_SELF, new Stack(SWORD, 1), new Stack(AXE, 1))
                    : swap(MoveType.MOVE_TO_SELF, new Stack(AXE, 1), new Stack(SWORD, 1));
            MoveSides sides = MoveSides.of(move);
            sides.left.forEach((id, n) -> stash2.put(A, id, n));
            for (Map.Entry<String, Long> e : sides.arrived.entrySet()) {
                fresh2 += stash2.fresh(A, e.getKey(), e.getValue(), false);
            }
        }
        assertEquals(1, fresh2);
    }
}
