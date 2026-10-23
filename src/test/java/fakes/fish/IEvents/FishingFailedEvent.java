package fakes.fish.IEvents;

import com.hypixel.hytale.event.IEvent;
import fakes.fish.FakePlayer;
import fakes.fish.Models.FishLoot;

/** The shape of the mod's {@code FishingFailedEvent}. */
public class FishingFailedEvent implements IEvent<Void> {
    private final FishLoot missed;
    private final FakePlayer player;

    public FishingFailedEvent(FishLoot missed, FakePlayer player) {
        this.missed = missed;
        this.player = player;
    }

    public FishLoot getMissedLoot() {
        return missed;
    }

    public String getLootID() {
        return missed.getItemID();
    }

    public FakePlayer getPlayer() {
        return player;
    }
}
