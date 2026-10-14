package dev.ryvrook.trackerapi.facts;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Pairs the two halves of a tame. The engine tames an animal by changing its NPC role, for example Cow to
 * Tamed_Cow, which takes the entity out of the store and adds it back under the new role with the same
 * UUID. The animal remembers the player who fed it in its InteractionTarget slot, but only under the old
 * role. So the removal notes who that player was, and the add that follows under a Tamed_ role credits
 * them. Growing up, such as Tamed_Cow_Calf to Tamed_Cow, starts from a Tamed_ role and is not a tame.
 * A port of the Feats class with the same constants, which also keeps the old role for the event.
 *
 * <p>Holds at most {@link #MAX_PENDING} notes, each only for {@link #WINDOW_MILLIS}. The two halves run in
 * the same world tick, so the window is generous. Shared by world threads, so every method is
 * synchronized.
 */
public final class TameWatch {
    /** Prefix of every tamed animal role. */
    public static final String TAMED_PREFIX = "Tamed_";
    static final long WINDOW_MILLIS = 5_000L;
    static final int MAX_PENDING = 64;

    /** A completed tame, the player who fed the animal and the wild role it had before. */
    public record Tame(UUID player, String fromRole) {
    }

    private record Note(UUID player, String role, long at) {
    }

    private final LinkedHashMap<UUID, Note> pending = new LinkedHashMap<>();
    /** Size of {@code pending}, readable without the lock. */
    private volatile int count;

    /**
     * An animal is leaving the store because its role is about to change. Notes the player who last
     * interacted with it when the old role is a wild one.
     */
    public synchronized void leaving(UUID animal, String oldRole, UUID player, long nowMillis) {
        if (animal == null || player == null || oldRole == null || oldRole.startsWith(TAMED_PREFIX)) {
            return;
        }
        pending.put(animal, new Note(player, oldRole, nowMillis));
        if (pending.size() > MAX_PENDING) {
            Iterator<UUID> eldest = pending.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        count = pending.size();
    }

    /**
     * An animal was added to the store. Returns the player who tamed it when it arrives under a Tamed_
     * role straight after leaving a wild one, otherwise null.
     */
    public UUID arrived(UUID animal, String newRole, long nowMillis) {
        Tame tame = arrive(animal, newRole, nowMillis);
        return tame == null ? null : tame.player();
    }

    /** As {@link #arrived}, with the wild role the animal left. */
    public synchronized Tame arrive(UUID animal, String newRole, long nowMillis) {
        if (animal == null || pending.isEmpty()) {
            return null;
        }
        Note note = pending.remove(animal);
        count = pending.size();
        if (note == null || newRole == null || !newRole.startsWith(TAMED_PREFIX)
                || nowMillis - note.at() > WINDOW_MILLIS) {
            return null;
        }
        return new Tame(note.player(), note.role());
    }

    /** True when nothing is waiting, so an add can skip the lock. Only a hint. */
    public boolean idle() {
        return count == 0;
    }

    synchronized int size() {
        return pending.size();
    }

    /** Drops every note, at shutdown. */
    public synchronized void clear() {
        pending.clear();
        count = 0;
    }
}
