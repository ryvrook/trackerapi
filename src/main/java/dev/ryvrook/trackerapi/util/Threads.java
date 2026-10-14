package dev.ryvrook.trackerapi.util;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * TrackerAPI's own threads. Every one is a daemon, owned by the plugin instance, so a {@code /plugin reload}
 * stops the old ones in shutdown and the new instance starts its own. Nothing of TrackerAPI runs on the
 * engine's {@code SCHEDULED_EXECUTOR}.
 *
 * <ul>
 *   <li>{@code TrackerAPI-dispatch}, delivers async events, see the bus package.</li>
 *   <li>{@code TrackerAPI-io}, the counters saves, the import and the journal writes and queries.</li>
 *   <li>{@code TrackerAPI-timer}, the once a second sampler post and every other timer.</li>
 * </ul>
 */
public final class Threads {
    private Threads() {
    }

    /**
     * A single thread scheduled executor named {@code name}. Tasks given to {@code execute} keep their
     * exception in a future, so afterExecute digs it out and logs it.
     */
    public static ScheduledExecutorService single(String name, HytaleLogger logger) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                    logger.at(Level.SEVERE).withCause(e).log("%s failed", name));
            return thread;
        }) {
            @Override
            protected void afterExecute(Runnable task, Throwable failure) {
                super.afterExecute(task, failure);
                if (failure == null && task instanceof Future<?> future && future.isDone() && !future.isCancelled()) {
                    try {
                        future.get();
                    } catch (ExecutionException e) {
                        failure = e.getCause();
                    } catch (CancellationException | InterruptedException ignored) {
                        // nothing to report
                    }
                }
                if (failure != null) {
                    logger.at(Level.WARNING).withCause(failure).log("%s task failed", name);
                }
            }
        };
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /** Shuts an executor down and waits up to {@code seconds} for queued work. */
    public static void drain(ScheduledExecutorService executor, String name, long seconds, HytaleLogger logger) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                logger.at(Level.WARNING).log("%s did not finish within %d seconds", name, seconds);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
