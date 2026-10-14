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
 */
public final class Forgetting {
    private final List<Consumer<UUID>> players = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> worlds = new CopyOnWriteArrayList<>();

    /** Registers a callback for when a player is forgotten. */
    public void onPlayer(Consumer<UUID> callback) {
        players.add(callback);
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
