package fakes.zephyr.api.events.walljump;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** A wall jump, the player as a ref. */
public class WallJumpEvent implements IEvent<Void> {
    private final Ref<EntityStore> player;

    public WallJumpEvent(Ref<EntityStore> player) {
        this.player = player;
    }

    public Ref<EntityStore> getPlayer() {
        return player;
    }
}
