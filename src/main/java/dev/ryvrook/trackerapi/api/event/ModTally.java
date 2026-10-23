package dev.ryvrook.trackerapi.api.event;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.TrackerEvent;

/**
 * A count or a best of a mod that the other mod kinds do not cover, such as a fish caught by species, a spell's
 * energy or a song's length. The sink adds {@code amount} to {@code <mod>:<stat>} and, with an {@code id}, to
 * {@code <mod>:<stat>:<id>}, and raises {@code <mod>:<stat>_best} to {@code best} when it is above zero. Either may be
 * zero. Since 1.3.0.
 */
public record ModTally(Header header, String mod, String stat, String id, long amount, long best) implements TrackerEvent {

    @Override
    public EventKind kind() {
        return EventKind.MOD_TALLY;
    }
}
