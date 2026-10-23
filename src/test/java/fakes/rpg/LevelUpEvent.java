package fakes.rpg;

/** The immutable level up event. */
public final class LevelUpEvent {
    private final FakePlayerRef player;
    private final int oldLevel;
    private final int newLevel;
    private final int levelsGained;

    public LevelUpEvent(FakePlayerRef player, int oldLevel, int newLevel, int levelsGained) {
        this.player = player;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
        this.levelsGained = levelsGained;
    }

    public FakePlayerRef getPlayer() {
        return player;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }

    public int getLevelsGained() {
        return levelsGained;
    }
}
