package fakes.parries.api;

/** The API holder of the fake Perfect Parries, a static accessor that is null between shutdown and setup. */
public final class PerfectParriesAPI {
    private static PerfectParriesAPI instance = new PerfectParriesAPI();

    public static PerfectParriesAPI get() {
        return instance;
    }

    public static void clearInstance() {
        instance = null;
    }
}
