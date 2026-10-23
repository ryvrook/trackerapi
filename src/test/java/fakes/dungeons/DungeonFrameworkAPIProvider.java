package fakes.dungeons;

/** The static holder of the framework API, set by the plugin's setup and never cleared by the mod. */
public final class DungeonFrameworkAPIProvider {
    private static volatile DungeonFrameworkAPI INSTANCE;

    private DungeonFrameworkAPIProvider() {
    }

    public static DungeonFrameworkAPI get() {
        return INSTANCE;
    }

    public static void bind(DungeonFrameworkAPI api) {
        INSTANCE = api;
    }

    public static void unbind() {
        INSTANCE = null;
    }
}
