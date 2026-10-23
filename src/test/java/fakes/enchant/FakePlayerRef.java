package fakes.enchant;

import java.util.UUID;

/**
 * Stands in for the engine's {@code PlayerRef}, which cannot be built without a server. It has the one method the
 * binding reads, {@code getUuid()}.
 */
public final class FakePlayerRef {
    private final UUID uuid;

    public FakePlayerRef(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
