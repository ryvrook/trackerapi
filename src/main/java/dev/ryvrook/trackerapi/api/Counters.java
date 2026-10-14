package dev.ryvrook.trackerapi.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The lifetime counters TrackerAPI keeps for every player, built from the events, one JSON file per
 * player under {@code mods/Ryvrook_TrackerAPI/players/}. Keys follow one scheme, see {@link Keys}. Totals
 * such as {@code block_broken} are always kept, per id keys such as {@code block_broken:Rock_Stone} too,
 * and per world copies of the totals under {@code world:<name>:<key>}.
 *
 * <p>Online players are answered from memory on any thread. Offline players are read from disk on the
 * {@code TrackerAPI-io} thread through {@link #load}. A player TrackerAPI has never seen answers zero or
 * empty.
 *
 * <p>Other plugins may add counters of their own under their own prefix with {@link #add}, for example
 * {@code feats:points}. They are saved with everything else and never touched by TrackerAPI.
 */
public interface Counters {

    /** A counter of an online player, zero when unknown or offline. */
    long get(UUID player, String key);

    /** Every counter of an online player, a copy. Empty when offline. */
    Map<String, Long> all(UUID player);

    /** True while the player's counters are in memory. */
    boolean isLoaded(UUID player);

    /** The counters of any player, read from disk when they are not in memory. Empty when never seen. */
    CompletableFuture<Map<String, Long>> load(UUID player);

    /** The names TrackerAPI has seen for a player, latest first. */
    CompletableFuture<Optional<String>> nameOf(UUID player);

    /** The uuid last seen under a name, ignoring case. */
    CompletableFuture<Optional<UUID>> uuidOf(String name);

    /** Every player with a file, from the names index. */
    Set<UUID> known();

    /** A server wide counter, summed over every player as events arrive. */
    long server(String key);

    /** Every server wide counter, a copy. */
    Map<String, Long> serverAll();

    /**
     * Adds to a counter of another plugin for an online player. The key must start with the plugin's
     * own prefix and a colon, such as {@code feats:}, and never with a TrackerAPI prefix. Returns false
     * when the player is not loaded or the key is reserved.
     */
    boolean add(UUID player, String key, long amount);

    /** Sets such a counter to the larger of its value and {@code value}. Same rules as {@link #add}. */
    boolean raiseTo(UUID player, String key, long value);

    /**
     * The top players for a key, from the leaderboard index TrackerAPI rebuilds every {@code boardSeconds}
     * from every player file. Empty until the first build. {@code n} is capped at 1000.
     */
    List<Map.Entry<UUID, Long>> top(String key, int n);

    /** The keys the leaderboard index holds, every total plus the ones other plugins asked for. */
    Set<String> boardKeys();

    /** Asks the index to keep a leaderboard for a per id or plugin key from the next build on. */
    void watchBoard(String key);
}
