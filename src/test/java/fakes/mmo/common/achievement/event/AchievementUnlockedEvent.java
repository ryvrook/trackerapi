package fakes.mmo.common.achievement.event;

import com.hypixel.hytale.event.IEvent;

import java.util.List;
import java.util.UUID;

/** The achievement unlocked event of the fake common library. */
public final class AchievementUnlockedEvent implements IEvent<Void> {
    private final String achievementId;
    private final UUID playerId;
    private final int points;
    private final boolean awaitingClaim;
    private final List<String> tags;

    public AchievementUnlockedEvent(String achievementId, UUID playerId, int points, boolean awaitingClaim, List<String> tags) {
        this.achievementId = achievementId;
        this.playerId = playerId;
        this.points = points;
        this.awaitingClaim = awaitingClaim;
        this.tags = tags;
    }

    public String achievementId() {
        return achievementId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int points() {
        return points;
    }

    public boolean awaitingClaim() {
        return awaitingClaim;
    }

    public List<String> tags() {
        return tags;
    }
}
