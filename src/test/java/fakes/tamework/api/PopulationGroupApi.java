package fakes.tamework.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** The population reads. As in Tamework 5.5.1 the loaded count is a default that answers empty. */
public interface PopulationGroupApi {
    Optional<PopulationGroupDefinitionView> getDefinition(String groupId);

    List<PopulationGroupDefinitionView> resolveForRole(String roleId);

    Optional<PopulationGroupCountsView> getCounts(UUID ownerUuid, String groupId, String worldName);

    default OptionalLong getLoadedOwnedCount(UUID ownerUuid, Set<String> groupIds) {
        return OptionalLong.empty();
    }

    default OptionalLong getDurableOwnedCount(UUID ownerUuid, Set<String> groupIds) {
        return OptionalLong.empty();
    }

    default OptionalLong getDurableDeployableCount(UUID ownerUuid, Set<String> groupIds) {
        return OptionalLong.empty();
    }

    static PopulationGroupApi unavailable() {
        return new PopulationGroupApi() {
            @Override
            public Optional<PopulationGroupDefinitionView> getDefinition(String groupId) {
                return Optional.empty();
            }

            @Override
            public List<PopulationGroupDefinitionView> resolveForRole(String roleId) {
                return List.of();
            }

            @Override
            public Optional<PopulationGroupCountsView> getCounts(UUID ownerUuid, String groupId, String worldName) {
                return Optional.empty();
            }
        };
    }
}
