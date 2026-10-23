package fakes.endgame.plugin;

import fakes.endgame.plugin.components.PlayerEndgameComponent;
import fakes.endgame.plugin.events.domain.GameEventBus;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The plugin object of the fake Endgame mod, with its event bus and the per player component map. */
public final class EndgameQoL {
    private static volatile EndgameQoL instance;
    private final GameEventBus gameEventBus = new GameEventBus();
    private final ConcurrentHashMap<UUID, PlayerEndgameComponent> playerComponents = new ConcurrentHashMap<>();

    public EndgameQoL() {
        instance = this;
    }

    public static EndgameQoL getInstance() {
        return instance;
    }

    public GameEventBus getGameEventBus() {
        return gameEventBus;
    }

    public PlayerEndgameComponent getPlayerComponent(UUID uuid) {
        return playerComponents.get(uuid);
    }

    /** Test side, what the mod's data ensure system does on entity add. */
    public void onPlayerComponentReady(UUID uuid, PlayerEndgameComponent component) {
        playerComponents.put(uuid, component);
    }

    /** Test side, what the mod does on disconnect. */
    public void onPlayerComponentRemoved(UUID uuid) {
        playerComponents.remove(uuid);
    }
}
