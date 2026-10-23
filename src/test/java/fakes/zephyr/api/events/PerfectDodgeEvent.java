package fakes.zephyr.api.events;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** A hit dodged inside the perfect window, the defender and the attacker as refs. */
public class PerfectDodgeEvent implements IEvent<Void> {
    private final Ref<EntityStore> defender;
    private final Ref<EntityStore> attacker;

    public PerfectDodgeEvent(Ref<EntityStore> defender, Ref<EntityStore> attacker) {
        this.defender = defender;
        this.attacker = attacker;
    }

    public Ref<EntityStore> getDefender() {
        return defender;
    }

    public Ref<EntityStore> getAttacker() {
        return attacker;
    }
}
