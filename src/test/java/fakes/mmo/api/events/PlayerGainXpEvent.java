package fakes.mmo.api.events;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/** The XP event of the fake mod. A negative final amount makes the getter throw, for the guard test. */
public class PlayerGainXpEvent {
    private final PlayerRef playerRef;
    private final String skillId;
    private final long baseAmount;
    private final long finalAmount;
    private final long xpBefore;
    private final long xpAfter;
    private final int levelBefore;
    private final int levelAfter;

    public PlayerGainXpEvent(PlayerRef playerRef, String skillId, long baseAmount, long finalAmount, long xpBefore,
                             long xpAfter, int levelBefore, int levelAfter) {
        this.playerRef = playerRef;
        this.skillId = skillId;
        this.baseAmount = baseAmount;
        this.finalAmount = finalAmount;
        this.xpBefore = xpBefore;
        this.xpAfter = xpAfter;
        this.levelBefore = levelBefore;
        this.levelAfter = levelAfter;
    }

    public PlayerRef getPlayerRef() {
        return playerRef;
    }

    public String getSkillId() {
        return skillId;
    }

    public long getBaseAmount() {
        return baseAmount;
    }

    public long getFinalAmount() {
        if (finalAmount < 0) {
            throw new IllegalStateException("broken event");
        }
        return finalAmount;
    }

    public long getXpBefore() {
        return xpBefore;
    }

    public long getXpAfter() {
        return xpAfter;
    }

    public int getLevelBefore() {
        return levelBefore;
    }

    public int getLevelAfter() {
        return levelAfter;
    }

    public boolean isLevelUp() {
        return levelAfter > levelBefore;
    }
}
