package mod.chiselsandbits.utils;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongBiFunction;

/** A count- and weight-bounded FIFO cache. Hits do not change insertion order. */
public final class SimpleMaxWeightedCache<K, V> {
    private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();
    private final Queue<K> keyQueue = new ArrayDeque<>();
    private final Object mutationLock = new Object();
    private final LongSupplier maxSize;
    private final LongSupplier maxWeight;
    private final ToLongBiFunction<K, V> weigher;
    private long retainedWeight;
    private volatile long generation;

    public SimpleMaxWeightedCache(final LongSupplier maxSize, final LongSupplier maxWeight, final ToLongBiFunction<K, V> weigher) {
        this.maxSize = Objects.requireNonNull(maxSize);
        this.maxWeight = Objects.requireNonNull(maxWeight);
        this.weigher = Objects.requireNonNull(weigher);
    }

    public V get(final K key) {
        final Entry<V> entry = cache.get(key);
        return entry == null ? null : entry.value;
    }

    public V get(final K key, final Supplier<V> valueSupplier) {
        final long buildGeneration = generation;
        // Preserve the single key hash/lookup on hits, including large detached NBT keys.
        final Entry<V> cached = cache.get(key);
        if (cached != null) {
            return cached.value;
        }

        // Builds and weighing never hold the mutation lock, so unrelated models can build in parallel.
        final V value = Objects.requireNonNull(valueSupplier.get());
        final long weight = weigh(key, value);
        synchronized (mutationLock) {
            // A reload/clear must not retain a model whose build started before that clear.
            if (generation == buildGeneration) {
                putLocked(key, value, weight);
            }
        }
        return value;
    }

    public void put(final K key, final V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        final long weight = weigh(key, value);
        synchronized (mutationLock) {
            putLocked(key, value, weight);
        }
    }

    private long weigh(final K key, final V value) {
        final long weight = weigher.applyAsLong(key, value);
        if (weight < 0) {
            throw new IllegalArgumentException("Cache weights must not be negative");
        }
        return weight;
    }

    private void putLocked(final K key, final V value, final long weight) {
        final long sizeLimit = maxSize.getAsLong();
        final long weightLimit = maxWeight.getAsLong();
        Entry<V> previous = cache.get(key);
        if (sizeLimit <= 0 || weightLimit <= 0 || weight > weightLimit) {
            // Bypass oversized entries without displacing other valid models.
            if (previous != null) {
                cache.remove(key);
                keyQueue.remove(key);
                retainedWeight -= previous.weight;
            }
            trimLocked(sizeLimit, weightLimit);
            return;
        }

        long remainingWeight = retainedWeight - (previous == null ? 0 : previous.weight);
        while (cache.size() + (previous == null ? 1L : 0L) > sizeLimit || weight > weightLimit - remainingWeight) {
            final Entry<V> evicted = cache.remove(keyQueue.remove());
            if (evicted == previous) {
                previous = null;
            } else {
                remainingWeight -= evicted.weight;
            }
        }
        if (previous == null) {
            keyQueue.add(key);
        }
        cache.put(key, new Entry<>(value, weight));
        retainedWeight = remainingWeight + weight;
    }

    private void trimLocked(final long sizeLimit, final long weightLimit) {
        while (!keyQueue.isEmpty() && (cache.size() > Math.max(0, sizeLimit) || retainedWeight > Math.max(0, weightLimit))) {
            retainedWeight -= cache.remove(keyQueue.remove()).weight;
        }
    }

    public void clear() {
        synchronized (mutationLock) {
            generation++;
            cache.clear();
            keyQueue.clear();
            retainedWeight = 0;
        }
    }

    public int size() {
        synchronized (mutationLock) {
            return cache.size();
        }
    }

    public long getRetainedWeight() {
        synchronized (mutationLock) {
            return retainedWeight;
        }
    }

    private record Entry<V>(V value, long weight) { }
}
