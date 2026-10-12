package mod.chiselsandbits.utils;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SimpleMaxWeightedCacheTest {
    @Test
    void hitsHashOnceAndPreserveFifoAcrossReplacement() {
        final var cache = new SimpleMaxWeightedCache<CountingKey, String>(() -> 2, () -> 20, (k, v) -> v.length());
        final CountingKey first = new CountingKey("first");
        final CountingKey second = new CountingKey("second");
        cache.put(first, "old");
        cache.put(second, "second");
        final CountingKey equal = new CountingKey("first");
        assertEquals("old", cache.get(equal, () -> fail("Unexpected build")));
        assertEquals(1, equal.hashes);
        cache.put(equal, "replacement");
        cache.put(new CountingKey("third"), "third");
        assertNull(cache.get(first));
        assertEquals("second", cache.get(second));
        assertEquals(11, cache.getRetainedWeight());
    }

    @Test
    void evictsByAggregateWeightBeforeTheCountCap() {
        final var cache = strings(10, 10);
        cache.put("a", "1234");
        cache.put("b", "5678");
        cache.put("c", "abcd");
        assertNull(cache.get("a"));
        assertEquals(2, cache.size());
        assertEquals(8, cache.getRetainedWeight());
        assertEquals("5678", cache.get("b", null));
    }

    @Test
    void bypassesOversizedValuesWithoutEvictingOtherEntries() {
        final var cache = strings(2, 5);
        cache.put("small", "123");
        final AtomicInteger builds = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertEquals("oversized", cache.get("large", () -> { builds.incrementAndGet(); return "oversized"; }));
        }
        assertEquals(3, builds.get());
        assertNull(cache.get("large"));
        assertEquals("123", cache.get("small"));
        assertEquals(3, cache.getRetainedWeight());
        cache.put("small", "too-large");
        assertNull(cache.get("small"), "An oversized replacement must not leave the old value cached");
        assertEquals(0, cache.getRetainedWeight());
    }

    @Test
    void observesDynamicBudgetsOnInsertionAndTreatsZeroAsDisabled() {
        final AtomicLong count = new AtomicLong(4);
        final AtomicLong weight = new AtomicLong(20);
        final var cache = new SimpleMaxWeightedCache<String, String>(count::get, weight::get, (k, v) -> v.length());
        cache.put("a", "1234");
        cache.put("b", "5678");
        count.set(1);
        cache.put("b", "xy");
        assertNull(cache.get("a"));
        assertEquals(1, cache.size());
        assertEquals(2, cache.getRetainedWeight());
        weight.set(0);
        assertEquals("uncached", cache.get("c", () -> "uncached"));
        assertEquals(0, cache.size());
        assertEquals(0, cache.getRetainedWeight());
        weight.set(10);
        count.set(0);
        assertEquals("uncached", cache.get("d", () -> "uncached"));
        assertEquals(0, cache.size());
    }

    @Test
    void preservesNestedNbtSnapshotsAndHashCollisions() {
        final var cache = new SimpleMaxWeightedCache<CompoundTag, Object>(() -> 4, () -> 10_000, (k, v) -> 100);
        final CompoundTag data = new CompoundTag();
        final CompoundTag nested = new CompoundTag();
        nested.putIntArray("bits", new int[]{1, 2, 3});
        data.put("tag", nested);
        final CompoundTag snapshot = data.copy();
        final Object first = cache.get(snapshot, Object::new);
        assertSame(first, cache.get(data.copy(), () -> fail("Equal snapshot missed")));
        nested.putIntArray("bits", new int[]{1, 9, 3});
        final Object changed = cache.get(data.copy(), Object::new);
        assertNotSame(first, changed);
        assertSame(first, cache.get(snapshot.copy(), null));
        assertSame(changed, cache.get(data.copy(), null));
        final var collisions = strings(4, 20);
        assertEquals("Aa".hashCode(), "BB".hashCode());
        collisions.put("Aa", "one");
        collisions.put("BB", "two");
        assertEquals("one", collisions.get("Aa"));
        assertEquals("two", collisions.get("BB"));
    }

    @Test
    void keepsNullAndExceptionContractsTransactional() {
        final var cache = strings(2, 10);
        cache.put("present", "value");
        assertEquals("value", cache.get("present", null));
        assertThrows(NullPointerException.class, () -> cache.get(null, () -> fail("Null key reached supplier")));
        assertThrows(NullPointerException.class, () -> cache.get("missing", null));
        assertThrows(NullPointerException.class, () -> cache.get("missing", () -> null));
        assertThrows(NullPointerException.class, () -> cache.put("present", null));
        final IllegalStateException failure = new IllegalStateException("failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> cache.get("failed", () -> { throw failure; })));
        assertEquals(1, cache.size());
        assertEquals("value", cache.get("present"));
        final var negative = new SimpleMaxWeightedCache<String, String>(() -> 2, () -> 10, (k, v) -> -1);
        assertThrows(IllegalArgumentException.class, () -> negative.put("key", "value"));
        assertEquals(0, negative.size());
        final var throwing = new SimpleMaxWeightedCache<String, String>(() -> 2, () -> 10, (k, v) -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> throwing.get("key", () -> "value")));
        assertEquals(0, throwing.size());
    }

    @Test
    void clearResetsAllAccountingAndPreventsInFlightRepopulation() throws Exception {
        final var cache = strings(4, 20);
        cache.put("old", "old");
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final Future<String> built = executor.submit(() -> cache.get("model", () -> {
                started.countDown();
                await(finish);
                return "before-clear";
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cache.clear();
            finish.countDown();
            assertEquals("before-clear", built.get(5, TimeUnit.SECONDS));
            assertNull(cache.get("model"));
            assertEquals(0, cache.size());
            assertEquals(0, cache.getRetainedWeight());
            assertEquals("after-clear", cache.get("model", () -> "after-clear"));
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void unrelatedBuildsRunConcurrentlyWhileInsertsAndClearRemainBounded() throws Exception {
        final var cache = strings(8, 32);
        final var executor = Executors.newFixedThreadPool(4);
        final CountDownLatch started = new CountDownLatch(4);
        final CountDownLatch finish = new CountDownLatch(1);
        try {
            final List<Future<?>> work = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                final int worker = thread;
                work.add(executor.submit(() -> {
                    cache.get("parallel-" + worker, () -> { started.countDown(); await(finish); return "1234"; });
                    for (int i = 0; i < 1_000; i++) {
                        cache.put("key-" + (i % 13), "1234");
                        if (i % 31 == 0) cache.clear();
                        assertTrue(cache.size() <= 8);
                        assertTrue(cache.getRetainedWeight() <= 32);
                    }
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "Model builds must not share a lock");
            finish.countDown();
            for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            cache.clear();
            for (int key = 0; key < 8; key++) cache.put("final-" + key, "1234");
            assertEquals(8, cache.size());
            assertEquals(32, cache.getRetainedWeight());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void avoidsOverflowAtLongMaxWeightAndHandlesAReplacementEvictingItself() {
        final var cache = new SimpleMaxWeightedCache<String, Long>(() -> 2, () -> Long.MAX_VALUE, (k, v) -> v);
        cache.put("first", Long.MAX_VALUE - 1);
        cache.put("second", 1L);
        cache.put("third", 2L);
        assertNull(cache.get("first"));
        assertEquals(3, cache.getRetainedWeight());
        cache.put("second", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, cache.getRetainedWeight());
        assertEquals(1, cache.size());
        assertEquals(Long.MAX_VALUE, cache.get("second"));
    }

    private static SimpleMaxWeightedCache<String, String> strings(long size, long weight) {
        return new SimpleMaxWeightedCache<>(() -> size, () -> weight, (k, v) -> v.length());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static final class CountingKey {
        private final String text;
        private int hashes;
        private CountingKey(String text) { this.text = text; }
        @Override public int hashCode() { hashes++; return text.hashCode(); }
        @Override public boolean equals(Object other) { return other instanceof CountingKey key && text.equals(key.text); }
    }
}
