package dev.ryvrook.trackerapi.journal;

import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.bus.Publisher;

/**
 * The journal as the plugin and the API see it. The implementation lives in this package and is a
 * {@link Publisher.Sink} that appends every event of the configured kinds to hourly files on the IO
 * thread, plus the query side of {@link Journal}.
 */
public interface JournalService extends Journal, Publisher.Sink {

    /** Lines written since start. */
    long linesWritten();

    /** Lines waiting for the IO thread. */
    int pending();

    /** Plugin start. Opens the current hour and schedules the flush and the prune. */
    void start();

    /** Plugin shutdown. Flushes what is pending and closes the files. */
    void stop();

    /** The config changed. */
    void reconfigure(java.util.Set<dev.ryvrook.trackerapi.api.EventKind> kinds, int retentionDays, int maxMegabytes);
}
