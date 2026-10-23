package fakes.mmo.common.quest.event;

import com.hypixel.hytale.event.IEvent;

import java.util.List;
import java.util.UUID;

/** The quest completed event of the fake common library. */
public final class QuestCompletedEvent implements IEvent<Void> {
    private final String questId;
    private final UUID playerId;
    private final boolean parked;
    private final List<String> tags;

    public QuestCompletedEvent(String questId, UUID playerId, boolean parked, List<String> tags) {
        this.questId = questId;
        this.playerId = playerId;
        this.parked = parked;
        this.tags = tags;
    }

    public String questId() {
        return questId;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean parked() {
        return parked;
    }

    public List<String> tags() {
        return tags;
    }
}
