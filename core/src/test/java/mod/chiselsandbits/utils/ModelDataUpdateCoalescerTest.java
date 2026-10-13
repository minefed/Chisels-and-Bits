package mod.chiselsandbits.utils;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.Random;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelDataUpdateCoalescerTest {

    @Test
    void enqueuesAndPollsWhenIdle() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.ENQUEUED,
                coalescer.requestUpdate(11L)
        );

        final OptionalLong next = coalescer.pollNext(key -> key);
        assertTrue(next.isPresent());
        assertEquals(11L, next.getAsLong());
        assertEquals(1, coalescer.getInFlightCount());
        assertEquals(0, coalescer.getQueuedCount());
    }

    @Test
    void dedupesPendingRequestsByPosition() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.ENQUEUED,
                coalescer.requestUpdate(7L)
        );

        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.DEDUPED_PENDING,
                coalescer.requestUpdate(7L)
        );
        assertEquals(1, coalescer.getQueuedCount());
    }

    @Test
    void coalescesInFlightRequestsIntoOneRerun() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.ENQUEUED,
                coalescer.requestUpdate(3L)
        );
        assertEquals(3L, coalescer.pollNext(key -> key).orElseThrow());

        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.DEDUPED_IN_FLIGHT,
                coalescer.requestUpdate(3L)
        );
        assertTrue(coalescer.markCompleted(3L));
        assertEquals(1, coalescer.getQueuedCount());

        assertEquals(3L, coalescer.pollNext(key -> key).orElseThrow());
        assertFalse(coalescer.markCompleted(3L));
        assertEquals(0, coalescer.getQueuedCount());
        assertEquals(0, coalescer.getInFlightCount());
    }

    @Test
    void pollsHigherPriorityEntriesFirst() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        coalescer.requestUpdate(10L);
        coalescer.requestUpdate(20L);
        coalescer.requestUpdate(30L);

        final AtomicLong preferred = new AtomicLong(20L);
        assertEquals(
                20L,
                coalescer.pollNext(key -> key == preferred.get() ? 1_000L : 1L).orElseThrow()
        );

        coalescer.markCompleted(20L);
        preferred.set(30L);
        assertEquals(
                30L,
                coalescer.pollNext(key -> key == preferred.get() ? 1_000L : 1L).orElseThrow()
        );
    }

    @Test
    void dropsWhenBoundedQueueIsFull() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer(1);
        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.ENQUEUED,
                coalescer.requestUpdate(1L)
        );
        assertEquals(
                ModelDataUpdateCoalescer.RequestResult.DROPPED_QUEUE_FULL,
                coalescer.requestUpdate(2L)
        );
        assertEquals(1, coalescer.getQueuedCount());
    }

    @Test
    void epochDrainScoresEachQueuedPositionOnceAndKeepsStableOrder() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer(2048);
        for (int i = 0; i < 2048; i++) coalescer.requestUpdate(i);
        final AtomicInteger scores = new AtomicInteger();
        for (int i = 0; i < 2048; i++) {
            assertEquals(i, coalescer.pollNext(1, key -> { scores.incrementAndGet(); return 42; }).orElseThrow());
            coalescer.markCompleted(i);
        }
        assertEquals(2048, scores.get());
        assertFalse(coalescer.hasPending());
    }

    @Test
    void cameraEpochChangeAndPendingPriorityPromotionRescore() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        for (int i = 1; i <= 4; i++) coalescer.requestUpdate(i);
        final Map<Long, Long> priorities = new HashMap<>(Map.of(1L, 10L, 2L, 20L, 3L, 30L, 4L, 40L));
        assertEquals(4, coalescer.pollNext(1, priorities::get).orElseThrow());
        priorities.put(1L, 100L);
        assertEquals(1, coalescer.pollNext(2, priorities::get).orElseThrow());
        priorities.put(2L, 200L);
        coalescer.requestUpdate(2);
        assertEquals(2, coalescer.pollNext(2, priorities::get).orElseThrow());
        coalescer.requestUpdate(5);
        priorities.put(5L, Long.MAX_VALUE);
        assertEquals(5, coalescer.pollNext(2, priorities::get).orElseThrow());
    }

    @Test
    void epochSelectionMatchesDynamicScanForStableScoresIncludingTies() {
        final Random random = new Random(20261013);
        for (int round = 0; round < 20; round++) {
            final ModelDataUpdateCoalescer scan = new ModelDataUpdateCoalescer();
            final ModelDataUpdateCoalescer ranked = new ModelDataUpdateCoalescer();
            final Map<Long, Long> scores = new HashMap<>();
            for (long key = 0; key < 200; key++) {
                scan.requestUpdate(key); ranked.requestUpdate(key);
                scores.put(key, (long) random.nextInt(10));
            }
            while (scan.hasPending()) {
                long expected = scan.pollNext(scores::get).orElseThrow();
                assertEquals(expected, ranked.pollNext(round, scores::get).orElseThrow());
                scan.markCompleted(expected); ranked.markCompleted(expected);
            }
        }
    }

    @Test
    void rerunGetsNewFifoOrderAndFreshScoreWithinEpoch() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        coalescer.requestUpdate(1); coalescer.requestUpdate(2);
        assertEquals(1, coalescer.pollNext(7, key -> 0).orElseThrow());
        coalescer.requestUpdate(1); coalescer.requestUpdate(1);
        assertTrue(coalescer.markCompleted(1));
        assertEquals(2, coalescer.pollNext(7, key -> 0).orElseThrow());
        assertEquals(1, coalescer.pollNext(7, key -> 0).orElseThrow());
        assertFalse(coalescer.markCompleted(1));
    }

    @Test
    void repeatedRescoringDiscardsSupersededRanksAndCanSwitchBackToDynamicApi() {
        final ModelDataUpdateCoalescer scan = new ModelDataUpdateCoalescer(64);
        final ModelDataUpdateCoalescer ranked = new ModelDataUpdateCoalescer(64);
        final Map<Long, Long> scores = new HashMap<>();
        final Random random = new Random(431);
        for (long key = 0; key < 32; key++) {
            scan.requestUpdate(key); ranked.requestUpdate(key); scores.put(key, key);
        }
        for (int round = 0; round < 1000; round++) {
            for (int changed = 0; changed < 10; changed++) {
                long key = random.nextInt(32);
                scores.put(key, (long) random.nextInt(100));
                scan.requestUpdate(key); ranked.requestUpdate(key);
            }
            long expected = scan.pollNext(scores::get).orElseThrow();
            long actual = round % 10 == 0 ? ranked.pollNext(scores::get).orElseThrow()
                    : ranked.pollNext(round / 100, scores::get).orElseThrow();
            assertEquals(expected, actual);
            scan.markCompleted(expected); ranked.markCompleted(actual);
            scan.requestUpdate(expected); ranked.requestUpdate(actual);
        }
    }

    @Test
    void throwingScorerLeavesQueueRetryableAndClearDropsRetiredWorldState() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        coalescer.requestUpdate(1); coalescer.requestUpdate(2);
        assertThrows(IllegalStateException.class, () -> coalescer.pollNext(1, key -> { throw new IllegalStateException(); }));
        assertEquals(2, coalescer.getQueuedCount());
        assertEquals(1, coalescer.pollNext(1, key -> 0).orElseThrow());
        coalescer.requestUpdate(1);
        coalescer.clear();
        assertEquals(0, coalescer.getQueuedCount());
        assertEquals(0, coalescer.getInFlightCount());
        assertFalse(coalescer.markCompleted(1));
        coalescer.requestUpdate(3);
        assertEquals(3, coalescer.pollNext(1, key -> Long.MIN_VALUE).orElseThrow());
    }
}
