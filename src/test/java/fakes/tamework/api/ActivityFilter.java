package fakes.tamework.api;

import java.util.Set;

/** The filter of a feed subscription, the real matching rule: the domain must match, the action ids only when given. */
public record ActivityFilter(Set<ActivityDomain> domains, Set<String> actionIds) {
    public static ActivityFilter forDomain(ActivityDomain domain) {
        return new ActivityFilter(Set.of(domain), Set.of());
    }

    public static ActivityFilter all() {
        return new ActivityFilter(Set.of(ActivityDomain.values()), Set.of());
    }

    public boolean matches(ActivityDomain domain, String actionId) {
        return domains.contains(domain) && (actionIds.isEmpty() || actionIds.contains(actionId));
    }
}
