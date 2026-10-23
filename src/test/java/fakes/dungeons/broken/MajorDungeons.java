package fakes.dungeons.broken;

/** The plugin object of the renamed variant. */
public final class MajorDungeons {
    public MajorDungeons() {
        DungeonFrameworkAPIProvider.bind(new DungeonFrameworkAPIImpl());
    }
}
