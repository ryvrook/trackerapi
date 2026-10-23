package dev.ryvrook.trackerapi.api;

import java.util.List;

/**
 * One mod TrackerAPI knows how to read. {@code id} is the short binding id and the counter prefix, such as
 * {@code rpg}, {@code pluginId} the plugin it binds to, such as {@code Zuxaw:RPGLeveling}. {@code bound} is true
 * while the mod is enabled and the binding holds it, {@code version} is then the mod's version, else null.
 * {@code features} are the groups of the mod's API that resolved and {@code missing} the ones that did not, so a
 * consumer can tell a partial bind. {@code stats} declares every counter the binding writes.
 */
public record ModInfo(String id, String pluginId, String name, String version, boolean bound, List<String> features,
                      List<String> missing, List<StatSpec> stats) {
    public ModInfo {
        features = features == null ? List.of() : List.copyOf(features);
        missing = missing == null ? List.of() : List.copyOf(missing);
        stats = stats == null ? List.of() : List.copyOf(stats);
    }
}
