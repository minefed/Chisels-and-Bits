package mod.chiselsandbits.utils;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
