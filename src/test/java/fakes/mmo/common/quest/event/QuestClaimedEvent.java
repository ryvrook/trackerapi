package fakes.mmo.common.quest.event;

import com.hypixel.hytale.event.IEvent;

import java.util.List;
import java.util.UUID;

/** The quest claimed event of the fake common library. */
public final class QuestClaimedEvent implements IEvent<Void> {
    private final String questId;
    private final UUID playerId;
    private final int rewardsGranted;
    private final int rewardsQueued;
    private final int rewardsFailed;
    private final List<String> tags;

    public QuestClaimedEvent(String questId, UUID playerId, int rewardsGranted, int rewardsQueued, int rewardsFailed, List<String> tags) {
        this.questId = questId;
        this.playerId = playerId;
        this.rewardsGranted = rewardsGranted;
        this.rewardsQueued = rewardsQueued;
        this.rewardsFailed = rewardsFailed;
        this.tags = tags;
    }

    public String questId() {
        return questId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int rewardsGranted() {
        return rewardsGranted;
    }

    public int rewardsQueued() {
        return rewardsQueued;
    }

    public int rewardsFailed() {
        return rewardsFailed;
    }

    public List<String> tags() {
        return tags;
    }
}
