package fakes.tamework.api;

import java.util.UUID;

public record PopulationGroupCountsView(UUID ownerUuid, String groupId, PopulationGroupScope scope, String ownershipWorldName, long committedOwned,
                                        long pendingOwned, long committedActive, long pendingActive, long maxOwned, long maxActive,
                                        boolean overOwnedLimit, boolean overActiveLimit, long classificationRevision) {
}
