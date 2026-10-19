package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;

import java.util.HashMap;
import java.util.Map;

/**
 * What one move between a player's section and another container did to the player's slots, per item id.
 * The player's side is the add transaction of a {@code MOVE_TO_SELF} and the remove transaction of a
 * {@code MOVE_FROM_SELF}. Each of its slots is read before and after, so a plain move, a partial stack and a
 * swap all come out right. A swap is the engine dropping a whole stack on a slot that holds a different item
 * that does not stack with it. Both sides are then REPLACE slots inside one move, and the item that left
 * and the item that arrived both show here. No engine needed, so tests build real transactions.
 */
final class MoveSides {
    /** Item id to amount that left the player's slots. Null when nothing left. */
    Map<String, Long> left;
    /** Item id to amount that arrived in the player's slots. Null when nothing arrived. */
    Map<String, Long> arrived;

    private MoveSides() {
    }

    /** The player's side of a move, or null when it moved nothing countable. */
    static MoveSides of(MoveTransaction<?> move) {
        if (move == null || !move.succeeded()) {
            return null;
        }
        Transaction mine = move.getMoveType() == MoveType.MOVE_TO_SELF ? move.getAddTransaction()
                : move.getRemoveTransaction();
        MoveSides sides = visit(mine, null);
        return sides == null || (sides.left == null && sides.arrived == null) ? null : sides;
    }

    /** The other container's slot of a move, the one items came from or went to, or -1 when unknown. */
    static int otherSlot(MoveTransaction<?> move) {
        Transaction theirs = move.getMoveType() == MoveType.MOVE_TO_SELF ? move.getRemoveTransaction()
                : move.getAddTransaction();
        return theirs instanceof SlotTransaction slot ? slot.getSlot() : -1;
    }

    private static MoveSides visit(Transaction transaction, MoveSides sides) {
        if (transaction == null || !transaction.succeeded()) {
            return sides;
        }
        if (transaction instanceof ListTransaction<?> list) {
            for (Transaction part : list.getList()) {
                sides = visit(part, sides);
            }
            return sides;
        }
        if (transaction instanceof ItemStackTransaction stack) {
            for (SlotTransaction part : stack.getSlotTransactions()) {
                sides = visit(part, sides);
            }
            return sides;
        }
        if (transaction instanceof SlotTransaction slot) {
            sides = slot(slot, sides);
        }
        return sides;
    }

    /** One slot, its old stack less the new one for what left and the other way round for what arrived. */
    private static MoveSides slot(SlotTransaction slot, MoveSides sides) {
        ItemStack before = slot.getSlotBefore();
        ItemStack after = slot.getSlotAfter();
        String beforeId = empty(before) ? null : before.getItemId();
        String afterId = empty(after) ? null : after.getItemId();
        long had = beforeId == null ? 0 : before.getQuantity();
        long has = afterId == null ? 0 : after.getQuantity();
        if (beforeId != null && beforeId.equals(afterId)) {
            if (had > has) {
                sides = note(sides, true, beforeId, had - has);
            } else if (has > had) {
                sides = note(sides, false, afterId, has - had);
            }
            return sides;
        }
        if (beforeId != null && had > 0) {
            sides = note(sides, true, beforeId, had);
        }
        if (afterId != null && has > 0) {
            sides = note(sides, false, afterId, has);
        }
        return sides;
    }

    private static MoveSides note(MoveSides sides, boolean left, String itemId, long amount) {
        if (sides == null) {
            sides = new MoveSides();
        }
        if (left) {
            if (sides.left == null) {
                sides.left = new HashMap<>(4);
            }
            sides.left.merge(itemId, amount, Long::sum);
        } else {
            if (sides.arrived == null) {
                sides.arrived = new HashMap<>(4);
            }
            sides.arrived.merge(itemId, amount, Long::sum);
        }
        return sides;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.isEmpty() || stack.getItemId() == null;
    }
}
