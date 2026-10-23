package fakes.zephyr.api.events.dodge;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** A dodge or dash started, the player as a ref. */
public class DodgeIFrameStartEvent implements IEvent<Void> {
    private final Ref<EntityStore> player;

    public DodgeIFrameStartEvent(Ref<EntityStore> player) {
        this.player = player;
    }

    public Ref<EntityStore> getPlayer() {
        return player;
    }
}
