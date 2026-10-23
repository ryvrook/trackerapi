package fakes.tamework.api;

import java.util.UUID;

public record CompanionXpOutcomeView(UUID companionId, UUID ownerId, CompanionXpSource source, double awardedXp, int previousLevel, int currentLevel,
                                     double previousTotalXp, double currentTotalXp, boolean leveledUp) {
}
