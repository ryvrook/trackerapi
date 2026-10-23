package fakes.tamework.api;

import java.util.UUID;

public record NpcLostRecordedEvent(NpcProfileView profile, UUID npcUuid, Vector3View lastKnownPosition, Vector3View homePosition,
                                   long lastRelocationQueuedAtMs, long lostAtMs, int relocationRetryAttempts, long emittedAtMs) implements TameworkEvent {
}
