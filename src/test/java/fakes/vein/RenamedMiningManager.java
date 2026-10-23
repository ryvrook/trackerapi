package fakes.vein;

/** A Vein Mining whose flag was renamed, for the partial bind test. */
public final class RenamedMiningManager {
    public static final ThreadLocal<Boolean> VEIN_FLAG = ThreadLocal.withInitial(() -> false);

    private RenamedMiningManager() {
    }
}
