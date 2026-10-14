package dev.ryvrook.trackerapi.hooks;

import dev.ryvrook.trackerapi.Services;

import java.util.ArrayList;
import java.util.List;

/**
 * The hook sets, in install order. Each lives in its own class under this package, see the package
 * notes. The lifecycle set installs first because the others read the player registry it keeps.
 */
public final class Hooks {
    private Hooks() {
    }

    public static List<HookSet> all(Services services) {
        List<HookSet> sets = new ArrayList<>();
        // Filled in by the hooks packages. Each entry is one class, added as it is written.
        // ---- world ----
        sets.add(new WorldHooks());
        // ---- end world ----
        return sets;
    }
}
