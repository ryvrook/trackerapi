package dev.ryvrook.trackerapi.facts;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One place that drops per player state when a player leaves and per world state when a world is
 * removed. Every hook set and fact registers what it keeps, so nothing outlives the player or the world
 * it belongs to. Callbacks run on the thread that observed the leave or the removal and must be cheap
 * and thread safe, which a {@code ConcurrentHashMap.remove} is.
 *
 * <p>Two lists for players. {@link #onPlayer} is the anti farm memory, forgotten an hour after the player
 * left unless they came back. {@link #onLeave} is session state such as the last sample of a poll, which
 * goes the moment the player disconnects so a relog starts clean. {@link #onSweep} is called once a minute
 * from the timer thread to drop entries of worlds that went away without a removal event.
 */
public final class Forgetting {
    private final List<Consumer<UUID>> players = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> worlds = new CopyOnWriteArrayList<>();
    private final List<Consumer<UUID>> leaves = new CopyOnWriteArrayList<>();
    private final List<Runnable> sweeps = new CopyOnWriteArrayList<>();

    /** Registers a callback for when a player is forgotten. */
    public void onPlayer(Consumer<UUID> callback) {
        players.add(callback);
    }

    /** Registers a callback for the moment a player disconnects. */
    public void onLeave(Consumer<UUID> callback) {
        leaves.add(callback);
    }

    /** Registers a once a minute sweep, run on the timer thread. */
    public void onSweep(Runnable sweep) {
        sweeps.add(sweep);
    }

    /** The player disconnected. Drops their session state now. */
    public void leave(UUID player) {
        if (player == null) {
            return;
        }
        for (Consumer<UUID> c : leaves) {
            try {
                c.accept(player);
            } catch (RuntimeException | LinkageError ignored) {
                // one fact failing to forget never stops the others
            }
        }
    }

    /** Runs every registered sweep. */
    public void sweep() {
        for (Runnable r : sweeps) {
            try {
                r.run();
            } catch (RuntimeException | LinkageError ignored) {
                // as above
            }
        }
    }

    /** Registers a callback for when a world is removed, with the world name. */
    public void onWorld(Consumer<String> callback) {
        worlds.add(callback);
    }

    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        for (Consumer<UUID> c : players) {
            try {
                c.accept(player);
            } catch (RuntimeException | LinkageError ignored) {
                // one fact failing to forget never stops the others
            }
        }
    }

    public void forgetWorld(String world) {
        if (world == null) {
            return;
        }
        for (Consumer<String> c : worlds) {
            try {
                c.accept(world);
            } catch (RuntimeException | LinkageError ignored) {
                // as above
            }
        }
    }
}
