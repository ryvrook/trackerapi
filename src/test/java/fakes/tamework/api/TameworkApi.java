package fakes.tamework.api;

import java.util.EnumSet;

/** The API root, the members the binding resolves with the real names and return types. */
public interface TameworkApi {
    String getApiVersion();

    EnumSet<TameworkApiCapability> getCapabilities();

    ProgressionApi progression();

    TameworkEventsApi events();

    default PopulationGroupApi populationGroups() {
        return PopulationGroupApi.unavailable();
    }

    default ActivityFeedApi activities() {
        return ActivityFeedApi.unavailable();
    }
}
