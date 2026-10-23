package fakes.fish.IEvents;

import com.hypixel.hytale.event.IEvent;
import fakes.fish.FakeRef;

/** The shape of the mod's {@code FishingRodCastEvent}, the cancel flag as the engine's cancellable event has it. */
public class FishingRodCastEvent implements IEvent<Void> {
    private final FakeRef player;
    private boolean cancelled;

    public FishingRodCastEvent(FakeRef player) {
        this.player = player;
    }

    public FakeRef getPlayer() {
        return player;
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    public final void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
