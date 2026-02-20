package mod.chiselsandbits.block.entities;

import mod.chiselsandbits.api.multistate.StateEntrySize;
import mod.chiselsandbits.client.model.meshing.DirtyRegion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyRegionTrackingTest {

    @Test
    void tracksSingleBitAsMinimalDirtyRegion() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();

        final List<DirtyRegion> merged = ChiseledBlockEntity.mergeDirtyRegionsForTracking(
                List.of(),
                List.of(DirtyRegion.singleBit(3, 5, 7)),
                bitsPerSide
        );
        final List<DirtyRegion> prepared = ChiseledBlockEntity.finalizeDirtyRegionsForModelDataUpdate(
                List.of(),
                merged,
                bitsPerSide
        );
        final List<DirtyRegion> request = ChiseledBlockEntity.resolveDirtyRegionsForModelDataUpdateRequest(
                prepared,
                bitsPerSide
        );

        assertEquals(List.of(new DirtyRegion(3, 5, 7, 3, 5, 7)), prepared);
        assertEquals(prepared, request);
    }

    @Test
    void mergesAdjacentLineEditsIntoSingleRegion() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();

        final List<DirtyRegion> merged = ChiseledBlockEntity.mergeDirtyRegionsForTracking(
                List.of(),
                List.of(
                        DirtyRegion.singleBit(1, 4, 2),
                        DirtyRegion.singleBit(2, 4, 2),
                        DirtyRegion.singleBit(3, 4, 2)
                ),
                bitsPerSide
        );

        assertEquals(List.of(new DirtyRegion(1, 4, 2, 3, 4, 2)), merged);
    }

    @Test
    void mergesPlaneEditsIntoSingleRegion() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();
        final List<DirtyRegion> planeBits = new ArrayList<>();

        for (int x = 6; x <= 8; x++) {
            for (int z = 1; z <= 2; z++) {
                planeBits.add(DirtyRegion.singleBit(x, 5, z));
            }
        }

        final List<DirtyRegion> merged = ChiseledBlockEntity.mergeDirtyRegionsForTracking(
                List.of(),
                planeBits,
                bitsPerSide
        );

        assertEquals(List.of(new DirtyRegion(6, 5, 1, 8, 5, 2)), merged);
    }

    @Test
    void keepsMultipleRegionsAndFallsBackToFullBlockWhenFragmented() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();
        final int max = bitsPerSide - 1;

        final List<DirtyRegion> separated = ChiseledBlockEntity.mergeDirtyRegionsForTracking(
                List.of(),
                List.of(
                        DirtyRegion.singleBit(0, 0, 0),
                        DirtyRegion.singleBit(max, max, max)
                ),
                bitsPerSide
        );

        final List<DirtyRegion> sortedSeparated = new ArrayList<>(separated);
        sortedSeparated.sort(
                Comparator.comparingInt(DirtyRegion::minX)
                        .thenComparingInt(DirtyRegion::minY)
                        .thenComparingInt(DirtyRegion::minZ)
        );

        assertEquals(
                List.of(
                        new DirtyRegion(0, 0, 0, 0, 0, 0),
                        new DirtyRegion(max, max, max, max, max, max)
                ),
                sortedSeparated
        );

        final List<DirtyRegion> fragmentedBits = new ArrayList<>();
        int recorded = 0;
        for (int x = 0; x < bitsPerSide && recorded <= ChiseledBlockEntity.DIRTY_REGION_FRAGMENTATION_FALLBACK_THRESHOLD; x += 2) {
            for (int y = 0; y < bitsPerSide && recorded <= ChiseledBlockEntity.DIRTY_REGION_FRAGMENTATION_FALLBACK_THRESHOLD; y += 2) {
                for (int z = 0; z < bitsPerSide && recorded <= ChiseledBlockEntity.DIRTY_REGION_FRAGMENTATION_FALLBACK_THRESHOLD; z += 2) {
                    fragmentedBits.add(DirtyRegion.singleBit(x, y, z));
                    recorded++;
                }
            }
        }

        final List<DirtyRegion> fragmentedMerged = ChiseledBlockEntity.mergeDirtyRegionsForTracking(
                List.of(),
                fragmentedBits,
                bitsPerSide
        );
        final List<DirtyRegion> fragmentedRequest = ChiseledBlockEntity.resolveDirtyRegionsForModelDataUpdateRequest(
                fragmentedMerged,
                bitsPerSide
        );

        assertEquals(1, fragmentedRequest.size());
        assertTrue(fragmentedRequest.get(0).isFullBlock(bitsPerSide));
    }
}
