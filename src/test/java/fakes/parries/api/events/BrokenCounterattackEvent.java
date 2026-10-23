package fakes.parries.api.events;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The counterattack event of a newer mod that renamed the multiplier getter, for the partial bind test. */
public class BrokenCounterattackEvent implements IEvent<Void> {
    private final Ref<EntityStore> attacker;
    private final Ref<EntityStore> target;
    private final float damageMultiplier;

    public BrokenCounterattackEvent(Ref<EntityStore> attacker, Ref<EntityStore> target, float damageMultiplier) {
        this.attacker = attacker;
        this.target = target;
        this.damageMultiplier = damageMultiplier;
    }

    public Ref<EntityStore> getAttacker() {
        return attacker;
    }

    public Ref<EntityStore> getTarget() {
        return target;
    }

    public float getMultiplier() {
        return damageMultiplier;
    }
}
