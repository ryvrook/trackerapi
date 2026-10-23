package fakes.fish;

/** The plugin object of the fake Angler's Almanac, its class loader is the isolating loader. */
public final class AnglersAlmanac {
    private static AnglersAlmanac instance;

    public AnglersAlmanac() {
        instance = this;
    }

    public static AnglersAlmanac getInstance() {
        return instance;
    }
}
