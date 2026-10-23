package fakes.parries.api.events;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** A successful parry, the defender and the attacker as refs, as the real event has them. */
public class PerfectParryEvent implements IEvent<Void> {
    private final Ref<EntityStore> defender;
    private final Ref<EntityStore> attacker;

    public PerfectParryEvent(Ref<EntityStore> defender, Ref<EntityStore> attacker) {
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
