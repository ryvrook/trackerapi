package fakes.fish.IEvents;

import com.hypixel.hytale.event.IEvent;
import fakes.fish.FakeRef;

/** The shape of the mod's {@code FishingRodEntityPullEvent}, without the launch velocity. */
public class FishingRodEntityPullEvent implements IEvent<Void> {
    private final FakeRef player;
    private final FakeRef target;
    private boolean cancelled;

    public FishingRodEntityPullEvent(FakeRef player, FakeRef target) {
        this.player = player;
        this.target = target;
    }

    public FakeRef getPlayer() {
        return player;
    }

    public FakeRef getTarget() {
        return target;
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    public final void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
