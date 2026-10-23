package fakes.fish;

import java.util.UUID;

/** Stands in for the engine's {@code Player} component, which the catch and escape events carry. */
public final class FakePlayer {
    private final FakePlayerRef ref;

    public FakePlayer(UUID uuid) {
        this.ref = uuid == null ? null : new FakePlayerRef(uuid);
    }

    public FakePlayerRef getPlayerRef() {
        return ref;
    }
}
