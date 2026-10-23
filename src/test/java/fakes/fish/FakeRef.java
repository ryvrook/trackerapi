package fakes.fish;

import java.util.UUID;

/**
 * Stands in for an engine entity {@code Ref}. The test engine reads {@link #getUuid()}, which is null for an entity
 * that is not a player.
 */
public final class FakeRef {
    private final UUID uuid;

    public FakeRef(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
