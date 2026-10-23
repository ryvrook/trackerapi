package fakes.zephyr.api;

/** The API holder of the fake Zephyr, a static accessor that is null between shutdown and setup. */
public final class ZephyrAPI {
    private static ZephyrAPI instance = new ZephyrAPI();

    public static ZephyrAPI get() {
        return instance;
    }

    public static void clearInstance() {
        instance = null;
    }
}
