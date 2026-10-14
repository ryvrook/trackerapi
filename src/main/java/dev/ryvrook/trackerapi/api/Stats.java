package dev.ryvrook.trackerapi.api;

import java.util.Map;

/**
 * What TrackerAPI has done since it started or since the counters were last reset.
 *
 * @param published      events published per kind
 * @param dropped        events the async queue had to drop per kind because it was full
 * @param queued         events waiting on the dispatch thread right now
 * @param queueCapacity  the size of the async queue
 * @param journalLines   lines the journal wrote
 * @param journalPending lines waiting for the journal thread
 * @param journalBytes   bytes the journal holds on disk, or -1 when the journal is off
 * @param hookNanos      total nanoseconds spent inside TrackerAPI's engine hooks per kind, on engine threads
 * @param hookCalls      how many times each hook ran, including runs that published nothing
 * @param sinceMillis    when the counters started
 */
public record Stats(Map<EventKind, Long> published, Map<EventKind, Long> dropped, int queued, int queueCapacity,
                    long journalLines, int journalPending, long journalBytes, Map<EventKind, Long> hookNanos,
                    Map<EventKind, Long> hookCalls, long sinceMillis) {
}
