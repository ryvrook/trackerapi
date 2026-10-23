package fakes.dungeons;

import fakes.dungeons.internal.DungeonFrameworkAPIImpl;

/** The plugin object. Its setup binds the framework API into the provider, as the real plugin's setup does. */
public final class MajorDungeons {
    public MajorDungeons() {
        DungeonFrameworkAPIProvider.bind(new DungeonFrameworkAPIImpl());
    }
}
