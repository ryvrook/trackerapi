package fakes.fish;

import java.util.UUID;

/** Stands in for the engine's {@code PlayerRef}, with the one method the binding reads. */
public final class FakePlayerRef {
    private final UUID uuid;

    public FakePlayerRef(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
