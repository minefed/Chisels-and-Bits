package mod.chiselsandbits.utils;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SimpleMaxSizedCacheTest {

    @Test
    void hashesAnEqualCachedKeyOnceWithoutCallingSupplier() {
        final SimpleMaxSizedCache<CountingKey, Object> cache = new SimpleMaxSizedCache<>(2);
        final Object model = new Object();
        cache.put(new CountingKey("model"), model);
        final CountingKey lookup = new CountingKey("model");

        assertSame(model, cache.get(lookup, () -> fail("Cache hit called supplier")));
        assertEquals(1, lookup.hashCalls);
    }

    @Test
    void fillsOnMissAndReloadsAfterClear() {
        final SimpleMaxSizedCache<String, Integer> cache = new SimpleMaxSizedCache<>(2);
        final AtomicInteger builds = new AtomicInteger();

        assertEquals(1, cache.get("model", builds::incrementAndGet));
        assertEquals(1, cache.get("model", builds::incrementAndGet));
        cache.clear();
        assertEquals(2, cache.get("model", builds::incrementAndGet));
        assertEquals(2, builds.get());
    }

    @Test
    void preservesFifoEvictionAcrossHitsAndValueReplacement() {
        final SimpleMaxSizedCache<String, String> cache = new SimpleMaxSizedCache<>(2);
        cache.put("first", "old");
        cache.put("second", "second-value");
        assertEquals("old", cache.get("first", () -> fail("Unexpected miss")));
        cache.put("first", "replacement");
        assertEquals("replacement", cache.get("first", null));

        cache.put("third", "third-value");

        assertNull(cache.get("first"));
        assertEquals("second-value", cache.get("second"));
        assertEquals("third-value", cache.get("third"));
    }

    @Test
    void observesChangedNbtSnapshotsWithoutReusingAStaleModel() {
        final SimpleMaxSizedCache<CompoundTag, Object> cache = new SimpleMaxSizedCache<>(4);
        final CompoundTag stackData = new CompoundTag();
        final CompoundTag chiseledData = new CompoundTag();
        chiseledData.putIntArray("bits", new int[] {1, 2, 3});
        stackData.put("tag", chiseledData);
        final CompoundTag before = stackData.copy();
        final Object original = new Object();
        assertSame(original, cache.get(before, () -> original));
        assertSame(original, cache.get(stackData.copy(), () -> fail("Equal snapshot missed")));

        chiseledData.putIntArray("bits", new int[] {1, 7, 3});
        final Object updated = new Object();
        assertSame(updated, cache.get(stackData.copy(), () -> updated));
        assertSame(original, cache.get(before.copy(), () -> fail("Original snapshot changed")));
        assertSame(updated, cache.get(stackData.copy(), () -> fail("Updated snapshot missed")));
    }

    @Test
    void keepsCollidingKeysDistinct() {
        final SimpleMaxSizedCache<String, String> cache = new SimpleMaxSizedCache<>(2);
        assertEquals("Aa".hashCode(), "BB".hashCode());
        cache.put("Aa", "first");

        assertEquals("second", cache.get("BB", () -> "second"));
        assertEquals("first", cache.get("Aa", () -> fail("Collision replaced value")));
        assertEquals("second", cache.get("BB", () -> fail("Collision lost value")));
    }

    @Test
    void preservesNullAndFailingSupplierContracts() {
        final SimpleMaxSizedCache<String, String> cache = new SimpleMaxSizedCache<>(4);
        cache.put("present", "value");
        assertEquals("value", cache.get("present", null));
        assertThrows(NullPointerException.class, () -> cache.get(null, () -> fail("Null key reached supplier")));
        assertThrows(NullPointerException.class, () -> cache.get("missing", null));
        assertThrows(NullPointerException.class, () -> cache.get("null-value", () -> null));
        assertFalse(cache.getIfPresent("null-value").isPresent());
        final IllegalStateException failure = new IllegalStateException("supplier failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> cache.get("failed", () -> { throw failure; })));
        assertFalse(cache.getIfPresent("failed").isPresent());
        assertEquals("recovered", cache.get("failed", () -> "recovered"));
    }

    private static final class CountingKey {
        private final String value;
        private int hashCalls;

        private CountingKey(final String value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            hashCalls++;
            return value.hashCode();
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof CountingKey key && value.equals(key.value);
        }
    }
}
