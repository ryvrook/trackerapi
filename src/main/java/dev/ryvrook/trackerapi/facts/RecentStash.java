package dev.ryvrook.trackerapi.facts;

/**
 * Items each player put away in the last ten minutes, into a container or onto the ground. Taking the
 * same items back takes from here first, so moving a stack into a chest and out again is not a gain.
 * The shape of {@link RecentDrops} with a longer window and more room.
 */
public final class RecentStash extends ItemLedger {
    public static final long WINDOW_NANOS = 10L * 60 * 1_000_000_000L;
    public static final int MAX_ENTRIES = 512;

    public RecentStash() {
        super(WINDOW_NANOS, MAX_ENTRIES);
    }

    public RecentStash registerWith(Forgetting forgetting) {
        forgetting.onPlayer(this::forget);
        return this;
    }
}
