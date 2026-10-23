package fakes.demo;

import java.util.UUID;

/** An event of the demo mod with getters like a real one. */
public final class DemoLevelEvent {
    private final UUID player;
    private final int oldLevel;
    private final int newLevel;

    public DemoLevelEvent(UUID player, int oldLevel, int newLevel) {
        this.player = player;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public UUID getPlayer() {
        return player;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }
}
