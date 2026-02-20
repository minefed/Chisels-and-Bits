package mod.chiselsandbits.client.util;

import com.sun.management.ThreadMXBean;
import mod.chiselsandbits.client.model.baked.face.model.BakedQuadAdapter;
import mod.chiselsandbits.client.model.baked.face.model.ModelQuadLayer;
import mod.chiselsandbits.client.model.baked.face.model.VertexData;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VertexDataUtilsAllocationTest {

    private static final float EPSILON = 1.0e-5f;

    @Test
    void reusableBufferPathMatchesLegacyUvAndVertexOrdering() {
        final VertexData[] input = createFaceVertices();
        final Vector3f from = new Vector3f(0.2f, 0.0f, 0.3f);
        final Vector3f to = new Vector3f(0.9f, 1.0f, 0.8f);

        final VertexData[] legacy = VertexDataUtils.adaptVerticesLegacy(input, Direction.UP, from, to);
        final VertexData[] reusableBuffer = new VertexData[4];
        final VertexData[] optimized = VertexDataUtils.adaptVerticesWithReusableBuffer(
                input,
                Direction.UP,
                from,
                to,
                reusableBuffer
        );

        assertSame(reusableBuffer, optimized);
        assertArrayEquals(
                new int[] {0, 1, 2, 3},
                new int[] {
                        optimized[0].vertexIndex(),
                        optimized[1].vertexIndex(),
                        optimized[2].vertexIndex(),
                        optimized[3].vertexIndex()
                }
        );

        for (int i = 0; i < 4; i++) {
            assertEquals(legacy[i].u(), optimized[i].u(), EPSILON, "u mismatch at vertex " + i);
            assertEquals(legacy[i].v(), optimized[i].v(), EPSILON, "v mismatch at vertex " + i);
            assertEquals(legacy[i].x(), optimized[i].x(), EPSILON, "x mismatch at vertex " + i);
            assertEquals(legacy[i].y(), optimized[i].y(), EPSILON, "y mismatch at vertex " + i);
            assertEquals(legacy[i].z(), optimized[i].z(), EPSILON, "z mismatch at vertex " + i);
        }
    }

    @Test
    void quadConfigurationFastPathPreservesOrientationAndTint() {
        final RecordingBakedQuadAdapter adapter = new RecordingBakedQuadAdapter();
        final ModelQuadLayer layer = new ModelQuadLayer(
                createFaceVertices(),
                null,
                0,
                0xFF00AA11,
                37,
                true,
                Direction.NORTH,
                null
        );

        QuadGenerationUtils.applyLayerProperties(adapter, layer, Direction.SOUTH);

        assertEquals(37, adapter.recordedTint);
        assertEquals(Direction.SOUTH, adapter.recordedDirection);
    }

    @Test
    void optimizedAdaptationAllocatesLessThanLegacyPathInSampleScenario() {
        final ThreadMXBean threadMxBean = threadMxBean();
        assumeTrue(threadMxBean != null && threadMxBean.isThreadAllocatedMemorySupported());

        if (!threadMxBean.isThreadAllocatedMemoryEnabled()) {
            threadMxBean.setThreadAllocatedMemoryEnabled(true);
        }

        final VertexData[] input = createFaceVertices();
        final Vector3f from = new Vector3f(0.1f, 0.0f, 0.2f);
        final Vector3f to = new Vector3f(0.95f, 1.0f, 0.85f);
        final long threadId = Thread.currentThread().getId();

        final VertexData[] reusableBuffer = new VertexData[4];
        warmup(() -> VertexDataUtils.adaptVerticesLegacy(input, Direction.UP, from, to), 20_000);
        warmup(() -> VertexDataUtils.adaptVerticesWithReusableBuffer(input, Direction.UP, from, to, reusableBuffer), 20_000);

        final int iterations = 100_000;
        final long legacyBytes = measureAllocatedBytes(
                threadMxBean,
                threadId,
                () -> VertexDataUtils.adaptVerticesLegacy(input, Direction.UP, from, to),
                iterations
        );
        final long optimizedBytes = measureAllocatedBytes(
                threadMxBean,
                threadId,
                () -> VertexDataUtils.adaptVerticesWithReusableBuffer(input, Direction.UP, from, to, reusableBuffer),
                iterations
        );

        final double legacyPerOperation = (double) legacyBytes / iterations;
        final double optimizedPerOperation = (double) optimizedBytes / iterations;
        System.out.println(
                "VertexDataUtils allocation sample (bytes/op): legacy="
                        + legacyPerOperation
                        + ", optimized="
                        + optimizedPerOperation
        );

        assumeTrue(legacyPerOperation > 0.0d, "legacy allocation must be measurable");
        assertTrue(
                optimizedPerOperation <= legacyPerOperation * 0.8d,
                "Expected optimized allocation <= 80% of legacy allocation. legacy="
                        + legacyPerOperation + ", optimized=" + optimizedPerOperation
        );
    }

    private static void warmup(final Runnable runnable, final int iterations) {
        for (int i = 0; i < iterations; i++) {
            runnable.run();
        }
    }

    private static long measureAllocatedBytes(
            final ThreadMXBean threadMxBean,
            final long threadId,
            final Runnable runnable,
            final int iterations
    ) {
        final long before = threadMxBean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < iterations; i++) {
            runnable.run();
        }
        return threadMxBean.getThreadAllocatedBytes(threadId) - before;
    }

    private static ThreadMXBean threadMxBean() {
        if (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threadMxBean) {
            return threadMxBean;
        }

        return null;
    }

    private static VertexData[] createFaceVertices() {
        return new VertexData[] {
                new VertexData(0f, 1f, 0f, 0f, 0f, 0),
                new VertexData(0f, 1f, 1f, 0f, 1f, 1),
                new VertexData(1f, 1f, 1f, 1f, 1f, 2),
                new VertexData(1f, 1f, 0f, 1f, 0f, 3)
        };
    }

    private static final class RecordingBakedQuadAdapter extends BakedQuadAdapter {
        private int recordedTint = -1;
        private Direction recordedDirection;

        private RecordingBakedQuadAdapter() {
            super(new VertexData[] {
                    new VertexData(0f, 0f, 0f, 0f, 0f, 0),
                    new VertexData(0f, 0f, 0f, 0f, 0f, 1),
                    new VertexData(0f, 0f, 0f, 0f, 0f, 2),
                    new VertexData(0f, 0f, 0f, 0f, 0f, 3)
            },
            -1);
        }

        @Override
        public void setQuadTint(final int tint) {
            this.recordedTint = tint;
        }

        @Override
        public void setQuadOrientation(final Direction orientation) {
            this.recordedDirection = orientation;
        }
    }
}
