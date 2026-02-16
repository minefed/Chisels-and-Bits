package mod.chiselsandbits.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelDataUpdateCoalescerTest {

    @Test
    void schedulesImmediatelyWhenIdle() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        final AtomicInteger scheduledCount = new AtomicInteger();

        coalescer.requestUpdate(scheduledCount::incrementAndGet);

        assertEquals(1, scheduledCount.get());
    }

    @Test
    void coalescesMultiplePendingRequestsIntoOneRerun() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        final AtomicInteger scheduledCount = new AtomicInteger();

        coalescer.requestUpdate(scheduledCount::incrementAndGet);
        coalescer.requestUpdate(scheduledCount::incrementAndGet);
        coalescer.requestUpdate(scheduledCount::incrementAndGet);

        assertEquals(1, scheduledCount.get());

        coalescer.markCompleted(scheduledCount::incrementAndGet);
        assertEquals(2, scheduledCount.get());

        coalescer.markCompleted(scheduledCount::incrementAndGet);
        assertEquals(2, scheduledCount.get());
    }

    @Test
    void schedulesFreshAfterFullyCompletedCycle() {
        final ModelDataUpdateCoalescer coalescer = new ModelDataUpdateCoalescer();
        final AtomicInteger scheduledCount = new AtomicInteger();

        coalescer.requestUpdate(scheduledCount::incrementAndGet);
        coalescer.markCompleted(scheduledCount::incrementAndGet);

        coalescer.requestUpdate(scheduledCount::incrementAndGet);

        assertEquals(2, scheduledCount.get());
    }
}
