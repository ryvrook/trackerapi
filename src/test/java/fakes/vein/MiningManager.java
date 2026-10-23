package fakes.vein;

/** The seam class of Vein Mining, with the one public static flag the binding reads, shaped like the real one. */
public final class MiningManager {
    public static final ThreadLocal<Boolean> IS_VEIN_MINING = ThreadLocal.withInitial(() -> false);

    private MiningManager() {
    }
}
