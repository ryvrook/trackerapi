package dev.ryvrook.trackerapi.mods.arcanum;

/**
 * The package root of the mod and the engine seam of the Arcanum binding. {@link #LIVE} names the real mod and the
 * real engine, a test gives the fake package and a stand in engine, so the same binding code runs against both.
 */
public record ArcanumNames(String mod, ArcanumEngine engine) {
    public static final ArcanumNames LIVE = new ArcanumNames("com.zenkuro.arcanum", LiveArcanumEngine.LIVE);
}
