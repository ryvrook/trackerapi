package fakes.tamework.api;

import java.util.Optional;
import java.util.UUID;

/** The progression reads. The real one also has the mutations, which the binding never names. */
public interface ProgressionApi {
    Optional<ProgressionView> getByProfileId(String profileId);

    Optional<ProgressionView> getByNpcUuid(UUID npcUuid);
}
