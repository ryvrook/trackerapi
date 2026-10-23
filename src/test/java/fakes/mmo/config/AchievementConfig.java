package fakes.mmo.config;

import fakes.mmo.achievement.AchievementDefinition;

/** The achievement definitions of the fake mod. Ids starting with "mmo" belong to it. */
public final class AchievementConfig {
    private static final AchievementConfig INSTANCE = new AchievementConfig();

    public static AchievementConfig getInstance() {
        return INSTANCE;
    }

    public AchievementDefinition getAchievement(String id) {
        return id != null && id.startsWith("mmo") ? new AchievementDefinition(id) : null;
    }
}
