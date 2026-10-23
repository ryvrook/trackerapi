package fakes.zephyr.api.events.vanish;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The player vanished, the player as a ref. */
public class VanishStartEvent implements IEvent<Void> {
    private final Ref<EntityStore> player;

    public VanishStartEvent(Ref<EntityStore> player) {
        this.player = player;
    }

    public Ref<EntityStore> getPlayer() {
        return player;
    }
}
