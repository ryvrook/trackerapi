package fakes.tamework.api;

import java.util.EnumSet;
import java.util.Set;

public record NpcProfileChangedEvent(String profileId, EnumSet<ProfileChangeType> changeTypes, NpcProfileView before, NpcProfileView after,
                                     long emittedAtMs, String oldLocationKind, String newLocationKind, Set<String> groupIds,
                                     Set<PopulationDomainClaim> domainClaims) implements TameworkEvent {
}
