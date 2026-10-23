package fakes.tamework.api;

import java.util.Set;
import java.util.UUID;

public record CompanionXpAwardedEvent(UUID npcUuid, UUID ownerUuid, Set<String> toolIds, String roleId, String levelingConfigId, CompanionXpSource source,
                                      double awardedXp, int previousLevel, int currentLevel, double previousTotalXp, double currentTotalXp,
                                      double previousCurrentXp, double currentXp, double nextLevelXp, int maxLevel, boolean atMaxLevel, boolean leveledUp,
                                      long occurredAtMs, long emittedAtMs) implements TameworkEvent {
}
