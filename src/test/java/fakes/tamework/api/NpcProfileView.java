package fakes.tamework.api;

import java.util.Set;
import java.util.UUID;

public record NpcProfileView(String profileId, UUID currentNpcUuid, UUID ownerUuid, String ownerName, String roleId, String displayName,
                             String customName, boolean tamed, String coopId, Integer coopSlot, Set<String> toolIds,
                             Set<String> activeSnapshotTypes, long lastUpdatedAtMs) {
}
