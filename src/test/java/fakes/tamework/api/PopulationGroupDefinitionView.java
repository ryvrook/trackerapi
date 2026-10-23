package fakes.tamework.api;

import java.util.Set;

public record PopulationGroupDefinitionView(String configId, long configRevision, String groupId, Set<String> roleIds, long maxOwnedPerOwner,
                                            long maxActivePerOwner, PopulationGroupScope scope) {
}
