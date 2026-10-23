package dev.ryvrook.trackerapi.mods.tamework;

/**
 * Where the Tamework classes live. {@code plugin} is the plugin main class, {@code api} the package of the public
 * API. Production reads the real mod, a test points both at a fake with the same member names, so one binding
 * runs against either.
 */
public record Names(String plugin, String api) {
    public static final Names PRODUCTION = new Names("com.alechilles.alecstamework.Tamework", "com.alechilles.alecstamework.api");

    /** The full name of a class of the API package. */
    public String type(String simple) {
        return api + '.' + simple;
    }
}
