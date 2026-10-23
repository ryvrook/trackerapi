package fakes.zephyr.api.events.grapple;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The grapple event of a newer mod that renamed the player getter, for the partial bind test. */
public class BrokenGrappleStartEvent implements IEvent<Void> {
    private final Ref<EntityStore> player;

    public BrokenGrappleStartEvent(Ref<EntityStore> player) {
        this.player = player;
    }

    public Ref<EntityStore> getRider() {
        return player;
    }
}
