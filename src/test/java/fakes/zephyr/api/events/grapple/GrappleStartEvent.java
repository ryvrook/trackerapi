package fakes.zephyr.api.events.grapple;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The grapple kunai was thrown, the player as a ref. */
public class GrappleStartEvent implements IEvent<Void> {
    private final Ref<EntityStore> player;

    public GrappleStartEvent(Ref<EntityStore> player) {
        this.player = player;
    }

    public Ref<EntityStore> getPlayer() {
        return player;
    }
}
