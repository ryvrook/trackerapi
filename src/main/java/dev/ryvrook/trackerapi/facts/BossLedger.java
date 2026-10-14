package dev.ryvrook.trackerapi.facts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Remembers which players hit a boss recently, so a group fight credits everyone who took part and
 * not only the player who landed the killing blow. A port of the Feats 1.4.0 ledger with the same
 * constants. The combat hook set holds the one instance.
 *
 * <p>Bosses are keyed by the entity's UUID component. Multi phase bosses such as the Gobliterator swap
 * roles by removing the entity and adding it back, which hands out a new {@code Ref} but keeps the
 * UUID, so one fight stays one entry across every phase.
 *
 * <p>Only UUIDs and timestamps are kept, never refs, so a closed world is never held in memory. The map
 * is bounded. Every new boss prunes fights nobody has hit for {@link #FORGET_NANOS}, and past
 * {@link #MAX_BOSSES} the fight hit least recently is dropped. Each fight keeps at most
 * {@link #MAX_PLAYERS} players.
 *
 * <p>The ledger also notes every player who takes damage in the boss's world while a fight is held, so
 * a kill can tell who came through it untouched. A fight opens with its first player hit, and a player
 * counts as hurt from then until the kill, whether or not they had hit the boss yet and however long the
 * fight went quiet in between. A fight nobody has hit for {@link #FORGET_NANOS} is forgotten, and the
 * next hit on that boss opens a new one.
 *
 * <p>Callers use it from the world thread that runs the fight. Several worlds tick on their own threads
 * and share one ledger, so every method is synchronized.
 */
public final class BossLedger {
    /** A hit older than this does not count towards the kill. */
    public static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** A fight nobody has hit for this long is dropped, with its hurt marks. */
    public static final long FORGET_NANOS = TimeUnit.MINUTES.toNanos(10);
    static final int MAX_BOSSES = 64;
    static final int MAX_PLAYERS = 32;
    /**
     * Hurt players kept per fight besides the players who hit the boss, who are always kept. A player
     * whose first hit comes after this filled up may have been hurt unnoticed, so only they lose the
     * untouched kill.
     */
    static final int MAX_HURT = 256;

    /** One player's hits on one boss, the first and the latest. */
    private static final class Hit {
        private final long first;
        private long last;

        Hit(long first) {
            this.first = first;
            this.last = first;
        }
    }

    /** One boss fight. Players in access order, so the eldest is the one who hit least recently. */
    private static final class Fight {
        private final String world;
        private final LinkedHashMap<UUID, Hit> hits = new LinkedHashMap<>(8, 0.75f, true);
        private final Set<UUID> hurt = new HashSet<>(4);
        /** When the hurt set first had no room for a player, or -1 while it never ran out. */
        private long fullSince = -1;
        private long lastHit;

        Fight(String world) {
            this.world = world == null ? "" : world;
        }
    }

    /**
     * How a fight ended. {@code credited} hit the boss within the window. {@code untouched} lists the
     * players who hit the boss and took no damage since the fight opened. {@code clean} is true when the
     * hurt set never ran out of room, so a player who never hit the boss, such as a killer with one
     * blow, can be judged by {@code hurt}. {@code known} is false when the boss was not in the ledger.
     */
    public record Outcome(List<UUID> credited, Set<UUID> untouched, Set<UUID> hurt, boolean clean, boolean known) {
        public static final Outcome NONE = new Outcome(List.of(), Set.of(), Set.of(), false, false);

        /** True when the player took no damage between the first hit of the fight and the kill. */
        public boolean untouched(UUID player) {
            if (!known || player == null) {
                return false;
            }
            return untouched.contains(player) || (clean && !hurt.contains(player));
        }
    }

    private final LinkedHashMap<UUID, Fight> fights = new LinkedHashMap<>(16, 0.75f, true);
    /** Number of fights held, readable without the lock so damage to players skips it when no fight is open. */
    private volatile int open;

    /** Notes that a player hit a boss at {@code now} (from {@link System#nanoTime()}), with no world name. */
    public void record(UUID boss, UUID player, long now) {
        record(boss, player, "", now);
    }

    /** Notes that a player hit a boss in the named world at {@code now} (from {@link System#nanoTime()}). */
    public synchronized void record(UUID boss, UUID player, String world, long now) {
        if (boss == null || player == null) {
            return;
        }
        Fight fight = fights.get(boss);
        if (fight == null) {
            prune(now);
            if (fights.size() >= MAX_BOSSES) {
                Iterator<UUID> eldest = fights.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            fight = new Fight(world);
            fights.put(boss, fight);
            open = fights.size();
        }
        fight.lastHit = now;
        Hit hit = fight.hits.get(player);
        if (hit == null) {
            fight.hits.put(player, new Hit(now));
        } else {
            hit.last = now;
        }
        if (fight.hits.size() > MAX_PLAYERS) {
            Iterator<UUID> eldest = fight.hits.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    /** Notes that a player took damage at {@code now}, with no world name. */
    public void hurt(UUID player, long now) {
        hurt(player, "", now);
    }

    /**
     * Notes that a player took damage in the named world at {@code now}. Marks them hurt in every fight
     * held in that world, quiet or not. One volatile read when no boss is being fought.
     */
    public void hurt(UUID player, String world, long now) {
        if (player == null || open == 0) {
            return;
        }
        String where = world == null ? "" : world;
        synchronized (this) {
            for (Fight fight : fights.values()) {
                if (!fight.world.equals(where) || fight.hurt.contains(player)) {
                    continue;
                }
                if (fight.hurt.size() < MAX_HURT || fight.hits.containsKey(player)) {
                    fight.hurt.add(player);
                } else if (fight.fullSince < 0) {
                    fight.fullSince = now;
                }
            }
        }
    }

    /** Removes a boss from the ledger and returns every player who hit it within the window. */
    public List<UUID> take(UUID boss, long now) {
        return finish(boss, now).credited();
    }

    /** Removes a boss from the ledger and says who hit it within the window and who came through untouched. */
    public synchronized Outcome finish(UUID boss, long now) {
        if (boss == null) {
            return Outcome.NONE;
        }
        Fight fight = fights.remove(boss);
        open = fights.size();
        if (fight == null) {
            return Outcome.NONE;
        }
        List<UUID> players = new ArrayList<>(fight.hits.size());
        Set<UUID> untouched = new HashSet<>();
        for (Map.Entry<UUID, Hit> e : fight.hits.entrySet()) {
            Hit hit = e.getValue();
            if (now - hit.last <= WINDOW_NANOS) {
                players.add(e.getKey());
            }
            // Hurt marks of a player are complete once they have hit, and before that while there was room.
            boolean complete = fight.fullSince < 0 || hit.first < fight.fullSince;
            if (complete && !fight.hurt.contains(e.getKey())) {
                untouched.add(e.getKey());
            }
        }
        return new Outcome(players, Set.copyOf(untouched), Set.copyOf(fight.hurt), fight.fullSince < 0, true);
    }

    /** Drops every fight held in a world that was removed. */
    public synchronized void forgetWorld(String world) {
        String where = world == null ? "" : world;
        fights.values().removeIf(fight -> fight.world.equals(where));
        open = fights.size();
    }

    /** Fights currently held, for tests and diagnostics. */
    public synchronized int size() {
        return fights.size();
    }

    /** Drops every fight, at shutdown. */
    public synchronized void clear() {
        fights.clear();
        open = 0;
    }

    private void prune(long now) {
        fights.values().removeIf(fight -> now - fight.lastHit > FORGET_NANOS);
        open = fights.size();
    }
}
