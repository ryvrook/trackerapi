package fakes.tamework.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ManagedActivityView(ActivityHeader header, String profileId, Set<String> groupIds, List<ActivityParticipantView> participants,
                                  String mappedActivityId, Map<String, Integer> itemQuantities, List<UUID> offspringIds,
                                  CompanionXpOutcomeView companionXpOutcome, CareCreditOutcomeView careCreditOutcome,
                                  HusbandryToolContext tool, UUID actorId) implements ActivityView {
    @Override
    public ActivityDomain domain() {
        return ActivityDomain.MANAGED_CARE_PRODUCTION;
    }

    @Override
    public ManagedActivityView withHeader(ActivityHeader h) {
        return new ManagedActivityView(h, profileId, groupIds, participants, mappedActivityId, itemQuantities, offspringIds,
                companionXpOutcome, careCreditOutcome, tool, actorId);
    }
}
