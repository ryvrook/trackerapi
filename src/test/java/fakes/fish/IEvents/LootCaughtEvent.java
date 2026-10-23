package fakes.fish.IEvents;

import com.hypixel.hytale.event.IEvent;
import fakes.fish.FakePlayer;
import fakes.fish.Models.FishLoot;

/** The shape of the mod's {@code LootCaughtEvent} record. */
public record LootCaughtEvent(FishLoot loot, boolean newDiscovery, boolean isLegendary, FakePlayer player, float performance)
        implements IEvent<Void> {

    public FishLoot getLoot() {
        return loot;
    }

    public String getLootID() {
        return loot.getItemID();
    }

    public FakePlayer getPlayer() {
        return player;
    }

    public boolean isNewDiscovery() {
        return newDiscovery;
    }

    public float getPerformance() {
        return performance;
    }

    public String getPRating() {
        if (performance == -1f) {
            return "nil";
        }
        if (performance >= 95f) {
            return "perfect";
        }
        if (performance >= 80f) {
            return "great";
        }
        if (performance >= 40f) {
            return "good";
        }
        return "fail";
    }
}
