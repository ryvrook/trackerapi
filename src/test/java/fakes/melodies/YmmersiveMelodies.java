package fakes.melodies;

/** The plugin class of the fake Ymmersive Melodies, with the static accessor the real one has. */
public class YmmersiveMelodies {
    private static YmmersiveMelodies instance;

    public YmmersiveMelodies() {
        instance = this;
    }

    public static YmmersiveMelodies getInstance() {
        return instance;
    }
}
