package mod.chiselsandbits.utils;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * Coalesces model-data update requests into a position-deduplicated queue.
 * Per position: one in-flight update, plus one queued rerun when requests arrive during execution.
 */
public final class ModelDataUpdateCoalescer {

    public static final int DEFAULT_MAX_QUEUED_UPDATES = 1024;

    private final Object stateLock = new Object();
    private final int maxQueuedUpdates;

    private final ArrayDeque<Long> pendingQueue = new ArrayDeque<>();
    private final Set<Long> pendingSet = new HashSet<>();
    private final Set<Long> inFlightSet = new HashSet<>();
    private final Set<Long> rerunSet = new HashSet<>();

    private long dedupedPendingCount;
    private long dedupedInFlightCount;
    private long droppedQueueFullCount;

    public ModelDataUpdateCoalescer() {
        this(DEFAULT_MAX_QUEUED_UPDATES);
    }

    public ModelDataUpdateCoalescer(final int maxQueuedUpdates) {
        if (maxQueuedUpdates <= 0) {
            throw new IllegalArgumentException("Maximum queued updates must be greater than zero.");
        }

        this.maxQueuedUpdates = maxQueuedUpdates;
    }

    public RequestResult requestUpdate(final long positionKey) {
        synchronized (stateLock) {
            if (inFlightSet.contains(positionKey)) {
                rerunSet.add(positionKey);
                dedupedInFlightCount++;
                return RequestResult.DEDUPED_IN_FLIGHT;
            }

            if (pendingSet.contains(positionKey)) {
                dedupedPendingCount++;
                return RequestResult.DEDUPED_PENDING;
            }

            if (pendingQueue.size() >= maxQueuedUpdates) {
                droppedQueueFullCount++;
                return RequestResult.DROPPED_QUEUE_FULL;
            }

            pendingQueue.addLast(positionKey);
            pendingSet.add(positionKey);
            return RequestResult.ENQUEUED;
        }
    }

    public OptionalLong pollNext(final LongUnaryOperator priorityScorer) {
        synchronized (stateLock) {
            if (pendingQueue.isEmpty()) {
                return OptionalLong.empty();
            }

            Long selectedKey = null;
            long selectedScore = Long.MIN_VALUE;
            for (Long candidate : pendingQueue) {
                final long score = priorityScorer.applyAsLong(candidate);
                if (selectedKey == null || score > selectedScore) {
                    selectedKey = candidate;
                    selectedScore = score;
                }
            }

            if (selectedKey == null) {
                return OptionalLong.empty();
            }

            pendingQueue.remove(selectedKey);
            pendingSet.remove(selectedKey);
            inFlightSet.add(selectedKey);
            return OptionalLong.of(selectedKey);
        }
    }

    /**
     * Marks an in-flight update as completed.
     *
     * @return true when a rerun was enqueued for the same position, false otherwise.
     */
    public boolean markCompleted(final long positionKey) {
        synchronized (stateLock) {
            inFlightSet.remove(positionKey);
            if (!rerunSet.remove(positionKey)) {
                return false;
            }

            if (pendingSet.contains(positionKey)) {
                return true;
            }

            if (pendingQueue.size() >= maxQueuedUpdates) {
                droppedQueueFullCount++;
                return false;
            }

            pendingQueue.addLast(positionKey);
            pendingSet.add(positionKey);
            return true;
        }
    }

    public boolean hasPending() {
        synchronized (stateLock) {
            return !pendingQueue.isEmpty();
        }
    }

    public int getQueuedCount() {
        synchronized (stateLock) {
            return pendingQueue.size();
        }
    }

    public int getInFlightCount() {
        synchronized (stateLock) {
            return inFlightSet.size();
        }
    }

    public Snapshot snapshot() {
        synchronized (stateLock) {
            return new Snapshot(
                    pendingQueue.size(),
                    inFlightSet.size(),
                    dedupedPendingCount,
                    dedupedInFlightCount,
                    droppedQueueFullCount
            );
        }
    }

    public enum RequestResult {
        ENQUEUED,
        DEDUPED_PENDING,
        DEDUPED_IN_FLIGHT,
        DROPPED_QUEUE_FULL;

        public boolean isDeduped() {
            return this == DEDUPED_PENDING || this == DEDUPED_IN_FLIGHT;
        }
    }

    public record Snapshot(
            int queuedCount,
            int inFlightCount,
            long dedupedPendingCount,
            long dedupedInFlightCount,
            long droppedQueueFullCount
    ) {
    }
}
