package dev.ryvrook.trackerapi.journal;

import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Placeholder until the journal package is written. Records nothing and answers empty queries, so the
 * plugin boots with the journal setting on. See docs/CONTRACT.md section 7 for what replaces it.
 */
public final class FileJournal implements JournalService {
    private final Path folder;
    private volatile Set<EventKind> kinds;

    private FileJournal(Path folder, Set<EventKind> kinds) {
        this.folder = folder;
        this.kinds = EnumSet.copyOf(kinds);
    }

    public static JournalService create(Services services, Path folder, Set<EventKind> kinds, int retentionDays, int maxMegabytes) {
        return new FileJournal(folder, kinds);
    }

    @Override
    public boolean wants(EventKind kind) {
        return false;
    }

    @Override
    public void accept(TrackerEvent event) {
    }

    @Override
    public long linesWritten() {
        return 0;
    }

    @Override
    public int pending() {
        return 0;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void reconfigure(Set<EventKind> kinds, int retentionDays, int maxMegabytes) {
        this.kinds = EnumSet.copyOf(kinds);
    }

    @Override
    public CompletableFuture<List<Entry>> find(Query query) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public Set<EventKind> kinds() {
        return kinds;
    }

    @Override
    public Path folder() {
        return folder;
    }

    @Override
    public long bytesOnDisk() {
        return 0;
    }
}
