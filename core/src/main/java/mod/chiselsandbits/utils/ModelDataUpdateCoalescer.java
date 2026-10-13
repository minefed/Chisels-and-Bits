package mod.chiselsandbits.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.function.LongUnaryOperator;

/**
 * Coalesces model-data update requests into a position-deduplicated queue.
 * Per position: one in-flight update, plus one queued rerun when requests arrive during execution.
 */
public final class ModelDataUpdateCoalescer {

    public static final int DEFAULT_MAX_QUEUED_UPDATES = 1024;

    private final Object stateLock = new Object();
    private final int maxQueuedUpdates;

    // Insertion order is the tie breaker, including when an in-flight request becomes a rerun.
    private final Map<Long, Long> pending = new LinkedHashMap<>();
    private final Set<Long> inFlightSet = new HashSet<>();
    private final Set<Long> rerunSet = new HashSet<>();
    private final Set<Long> dirtyPriorities = new HashSet<>();
    private final Map<Long, RankedUpdate> ranksByKey = new HashMap<>();
    private PriorityQueue<RankedUpdate> ranked = new PriorityQueue<>();
    private long nextOrder;
    private long rankedEpoch;
    private boolean ranksValid;

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

            if (pending.containsKey(positionKey)) {
                dirtyPriorities.add(positionKey);
                dedupedPendingCount++;
                return RequestResult.DEDUPED_PENDING;
            }

            if (pending.size() >= maxQueuedUpdates) {
                droppedQueueFullCount++;
                return RequestResult.DROPPED_QUEUE_FULL;
            }

            enqueue(positionKey);
            return RequestResult.ENQUEUED;
        }
    }

    public OptionalLong pollNext(final LongUnaryOperator priorityScorer) {
        synchronized (stateLock) {
            // This API deliberately keeps its fully dynamic scorer contract.
            invalidateRanks();
            if (pending.isEmpty()) {
                return OptionalLong.empty();
            }

            Long selectedKey = null;
            long selectedScore = Long.MIN_VALUE;
            for (Long candidate : pending.keySet()) {
                final long score = priorityScorer.applyAsLong(candidate);
                if (selectedKey == null || score > selectedScore) {
                    selectedKey = candidate;
                    selectedScore = score;
                }
            }

            if (selectedKey == null) {
                return OptionalLong.empty();
            }

            return start(selectedKey);
        }
    }

    /**
     * Reuses rankings within an explicitly stable priority epoch (for example one client tick).
     * New or coalesced requests and reruns are rescored even inside that epoch. Changing camera,
     * age or other global scoring inputs requires a new epoch. Equal scores keep FIFO order.
     */
    public OptionalLong pollNext(final long priorityEpoch, final LongUnaryOperator priorityScorer) {
        synchronized (stateLock) {
            if (pending.isEmpty()) return OptionalLong.empty();
            if (!ranksValid || rankedEpoch != priorityEpoch) {
                invalidateRanks();
                for (final var entry : pending.entrySet()) {
                    ranksByKey.put(entry.getKey(), new RankedUpdate(entry.getKey(),
                            priorityScorer.applyAsLong(entry.getKey()), entry.getValue()));
                }
                // The collection constructor heapifies in linear time on each new tick.
                ranked = new PriorityQueue<>(ranksByKey.values());
                rankedEpoch = priorityEpoch;
                ranksValid = true;
            }
            // Compute before removing the dirty marker: a throwing scorer can safely be retried.
            final var dirtyIterator = dirtyPriorities.iterator();
            while (dirtyIterator.hasNext()) {
                final long key = dirtyIterator.next();
                final Long order = pending.get(key);
                if (order != null) {
                    final RankedUpdate update = new RankedUpdate(key, priorityScorer.applyAsLong(key), order);
                    ranksByKey.put(key, update);
                    ranked.add(update);
                }
                dirtyIterator.remove();
            }
            // Superseded ranks are lazy-invalidated. Bound that metadata under repeated promotions.
            if (ranked.size() > Math.max(16, pending.size()) * 2) {
                ranked = new PriorityQueue<>(ranksByKey.values());
            }
            RankedUpdate selected;
            do {
                selected = ranked.remove();
            } while (ranksByKey.get(selected.key()) != selected);
            return start(selected.key());
        }
    }

    private void enqueue(final long key) {
        pending.put(key, nextOrder++);
        dirtyPriorities.add(key);
    }

    private OptionalLong start(final long key) {
        pending.remove(key);
        dirtyPriorities.remove(key);
        ranksByKey.remove(key);
        inFlightSet.add(key);
        return OptionalLong.of(key);
    }

    private void invalidateRanks() {
        ranksValid = false;
        ranked.clear();
        ranksByKey.clear();
        dirtyPriorities.clear();
    }

    /** Forget a retired world's queue. Late completions must be filtered by the caller's world/entry identity. */
    public void clear() {
        synchronized (stateLock) {
            pending.clear();
            inFlightSet.clear();
            rerunSet.clear();
            invalidateRanks();
        }
    }

    private record RankedUpdate(long key, long score, long order) implements Comparable<RankedUpdate> {
        @Override
        public int compareTo(final RankedUpdate other) {
            final int byScore = Long.compare(other.score, score);
            return byScore == 0 ? Long.compare(order, other.order) : byScore;
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

            if (pending.containsKey(positionKey)) {
                return true;
            }

            if (pending.size() >= maxQueuedUpdates) {
                droppedQueueFullCount++;
                return false;
            }

            enqueue(positionKey);
            return true;
        }
    }

    public boolean hasPending() {
        synchronized (stateLock) {
            return !pending.isEmpty();
        }
    }

    public int getQueuedCount() {
        synchronized (stateLock) {
            return pending.size();
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
                    pending.size(),
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
