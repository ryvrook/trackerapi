package fakes.tamework.api;

import java.util.Set;
import java.util.UUID;

public record NpcDeathRecordedEvent(NpcProfileView profile, UUID npcUuid, UUID ownerUuid, String ownerName, Set<String> toolIds, String roleId,
                                    String displayName, String customName, boolean tamed, Vector3View lastKnownPosition, Vector3View homePosition,
                                    long diedAtMs, long respawnAvailableAtMs, long emittedAtMs) implements TameworkEvent {
}
