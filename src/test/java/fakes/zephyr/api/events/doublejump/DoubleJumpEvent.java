package fakes.zephyr.api.events.doublejump;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** A double jump, the player as a ref. */
public class DoubleJumpEvent implements IEvent<Void> {
    private final Ref<EntityStore> player;

    public DoubleJumpEvent(Ref<EntityStore> player) {
        this.player = player;
    }

    public Ref<EntityStore> getPlayer() {
        return player;
    }
}
