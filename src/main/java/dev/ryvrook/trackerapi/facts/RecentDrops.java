package dev.ryvrook.trackerapi.facts;

/**
 * Items each player dropped on the ground in the last five minutes, ported from Feats. Picking the same
 * items back up takes from here first, so a stack dropped and picked up again is not a fresh pickup.
 */
public final class RecentDrops extends ItemLedger {
    public static final long WINDOW_NANOS = 5L * 60 * 1_000_000_000L;
    public static final int MAX_ENTRIES = 256;

    public RecentDrops() {
        super(WINDOW_NANOS, MAX_ENTRIES);
    }

    public RecentDrops registerWith(Forgetting forgetting) {
        forgetting.onPlayer(this::forget);
        return this;
    }
}
