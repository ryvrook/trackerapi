package fakes.mmo.ability.event;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The resolved cast event of the fake mod, on the engine bus. */
public final class MmoAbilityResolvedEvent implements IEvent<Void> {
    private final String abilityId;
    private final Ref<EntityStore> casterRef;
    private final boolean success;
    private final String failureGateId;
    private final long resolvedAtMs;

    public MmoAbilityResolvedEvent(String abilityId, Ref<EntityStore> casterRef, boolean success, String failureGateId, long resolvedAtMs) {
        this.abilityId = abilityId;
        this.casterRef = casterRef;
        this.success = success;
        this.failureGateId = failureGateId;
        this.resolvedAtMs = resolvedAtMs;
    }

    public String abilityId() {
        return abilityId;
    }

    public Ref<EntityStore> casterRef() {
        return casterRef;
    }

    public boolean success() {
        return success;
    }

    public String failureGateId() {
        return failureGateId;
    }

    public long resolvedAtMs() {
        return resolvedAtMs;
    }
}
