package fakes.tamework.api;

import java.util.UUID;

public record ActivityParticipantView(UUID companionId, UUID ownerId, String profileId, String roleId) {
}
