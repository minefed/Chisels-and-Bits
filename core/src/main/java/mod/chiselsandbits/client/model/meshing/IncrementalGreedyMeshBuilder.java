package mod.chiselsandbits.client.model.meshing;

import mod.chiselsandbits.api.multistate.StateEntrySize;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IncrementalGreedyMeshBuilder {

    private static final int DIMENSIONS = Direction.Axis.values().length;
    private static final double DEFAULT_DIRTY_VOLUME_FALLBACK_RATIO = 0.35D;
    private static final double DEFAULT_AFFECTED_SLICE_FALLBACK_RATIO = 0.6D;
    private static volatile double dirtyVolumeFallbackRatio = DEFAULT_DIRTY_VOLUME_FALLBACK_RATIO;
    private static volatile double affectedSliceFallbackRatio = DEFAULT_AFFECTED_SLICE_FALLBACK_RATIO;

    private IncrementalGreedyMeshBuilder() {
        throw new IllegalStateException("Cannot instantiate utility class");
    }

    public static void setDirtyVolumeFallbackRatio(final double fallbackRatio) {
        dirtyVolumeFallbackRatio = sanitizeFallbackRatio(fallbackRatio, DEFAULT_DIRTY_VOLUME_FALLBACK_RATIO);
    }

    public static double getDirtyVolumeFallbackRatio() {
        return dirtyVolumeFallbackRatio;
    }

    private static double sanitizeFallbackRatio(final double fallbackRatio, final double defaultValue) {
        if (Double.isNaN(fallbackRatio) || Double.isInfinite(fallbackRatio)) {
            return defaultValue;
        }

        return Math.max(0.0D, Math.min(1.0D, fallbackRatio));
    }

    public static GreedyMeshFace[] rebuildMesh(
            final GreedyMeshBuilder.MaterialProvider materialProvider,
            final GreedyMeshFace[] previousMesh,
            final List<DirtyRegion> dirtyRegions
    ) {
        Objects.requireNonNull(materialProvider, "materialProvider");

        if (previousMesh == null || previousMesh.length == 0 || dirtyRegions == null || dirtyRegions.isEmpty()) {
            return GreedyMeshBuilder.buildMesh(materialProvider);
        }

        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();
        final List<DirtyRegion> sanitizedDirtyRegions = sanitizeDirtyRegions(dirtyRegions);
        if (sanitizedDirtyRegions.isEmpty() || shouldFallbackToFullRebuild(sanitizedDirtyRegions, bitsPerSide)) {
            return GreedyMeshBuilder.buildMesh(materialProvider);
        }

        final boolean[][] affectedSlices = collectAffectedSlices(sanitizedDirtyRegions, bitsPerSide);
        final int affectedSliceCount = countAffectedSlices(affectedSlices);
        if (affectedSliceCount == 0) {
            return previousMesh.clone();
        }

        final int totalSlices = DIMENSIONS * (bitsPerSide + 1);
        if ((double) affectedSliceCount / totalSlices >= affectedSliceFallbackRatio) {
            return GreedyMeshBuilder.buildMesh(materialProvider);
        }

        final GreedyMeshFace[] rebuiltSlices = GreedyMeshBuilder.buildMesh(
                materialProvider,
                (dimension, sliceIndex) -> isSliceAffected(affectedSlices, dimension, sliceIndex)
        );

        final Map<SliceKey, List<GreedyMeshFace>> previousFacesBySlice = groupFacesBySlice(previousMesh, bitsPerSide);
        final Map<SliceKey, List<GreedyMeshFace>> rebuiltFacesBySlice = groupFacesBySlice(rebuiltSlices, bitsPerSide);

        final List<GreedyMeshFace> mergedFaces = new ArrayList<>(Math.max(previousMesh.length, rebuiltSlices.length));
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
            for (int sliceIndex = -1; sliceIndex < bitsPerSide; sliceIndex++) {
                final SliceKey key = new SliceKey(dimension, sliceIndex);
                final List<GreedyMeshFace> sourceFaces = isSliceAffected(affectedSlices, dimension, sliceIndex)
                        ? rebuiltFacesBySlice.get(key)
                        : previousFacesBySlice.get(key);

                if (sourceFaces != null) {
                    mergedFaces.addAll(sourceFaces);
                }
            }
        }

        return mergedFaces.toArray(new GreedyMeshFace[0]);
    }

    private static List<DirtyRegion> sanitizeDirtyRegions(final List<DirtyRegion> dirtyRegions) {
        final List<DirtyRegion> sanitized = new ArrayList<>(dirtyRegions.size());
        for (final DirtyRegion dirtyRegion : dirtyRegions) {
            if (dirtyRegion != null) {
                sanitized.add(dirtyRegion);
            }
        }
        return sanitized;
    }

    private static boolean shouldFallbackToFullRebuild(final List<DirtyRegion> dirtyRegions, final int bitsPerSide) {
        final long totalVolume = (long) bitsPerSide * bitsPerSide * bitsPerSide;
        final long dirtyVolumeFallbackThreshold = Math.max(1L, Math.round(totalVolume * dirtyVolumeFallbackRatio));
        long dirtyVolume = 0L;

        for (final DirtyRegion dirtyRegion : dirtyRegions) {
            if (dirtyRegion.isFullBlock(bitsPerSide) || isOutOfBounds(dirtyRegion, bitsPerSide)) {
                return true;
            }

            dirtyVolume += regionVolume(dirtyRegion);
            if (dirtyVolume >= dirtyVolumeFallbackThreshold) {
                return true;
            }
        }

        return false;
    }

    private static boolean isOutOfBounds(final DirtyRegion dirtyRegion, final int bitsPerSide) {
        return dirtyRegion.minX() < 0 || dirtyRegion.minY() < 0 || dirtyRegion.minZ() < 0 ||
                dirtyRegion.maxX() >= bitsPerSide || dirtyRegion.maxY() >= bitsPerSide || dirtyRegion.maxZ() >= bitsPerSide;
    }

    private static long regionVolume(final DirtyRegion dirtyRegion) {
        return (long) (dirtyRegion.maxX() - dirtyRegion.minX() + 1) *
                (dirtyRegion.maxY() - dirtyRegion.minY() + 1) *
                (dirtyRegion.maxZ() - dirtyRegion.minZ() + 1);
    }

    private static boolean[][] collectAffectedSlices(final List<DirtyRegion> dirtyRegions, final int bitsPerSide) {
        final boolean[][] affectedSlices = new boolean[DIMENSIONS][bitsPerSide + 1];

        for (final DirtyRegion dirtyRegion : dirtyRegions) {
            markSliceRange(affectedSlices[0], dirtyRegion.minX() - 1, dirtyRegion.maxX(), bitsPerSide);
            markSliceRange(affectedSlices[1], dirtyRegion.minY() - 1, dirtyRegion.maxY(), bitsPerSide);
            markSliceRange(affectedSlices[2], dirtyRegion.minZ() - 1, dirtyRegion.maxZ(), bitsPerSide);
        }

        return affectedSlices;
    }

    private static void markSliceRange(
            final boolean[] affectedSlicesForAxis,
            final int minSliceIndex,
            final int maxSliceIndex,
            final int bitsPerSide
    ) {
        final int from = Math.max(-1, minSliceIndex);
        final int to = Math.min(bitsPerSide - 1, maxSliceIndex);

        if (from > to) {
            return;
        }

        for (int sliceIndex = from; sliceIndex <= to; sliceIndex++) {
            affectedSlicesForAxis[sliceIndex + 1] = true;
        }
    }

    private static int countAffectedSlices(final boolean[][] affectedSlices) {
        int count = 0;
        for (int dimension = 0; dimension < affectedSlices.length; dimension++) {
            for (final boolean affected : affectedSlices[dimension]) {
                if (affected) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isSliceAffected(final boolean[][] affectedSlices, final int dimension, final int sliceIndex) {
        if (dimension < 0 || dimension >= affectedSlices.length) {
            return false;
        }
        if (sliceIndex < -1 || sliceIndex >= affectedSlices[dimension].length - 1) {
            return false;
        }

        return affectedSlices[dimension][sliceIndex + 1];
    }

    private static Map<SliceKey, List<GreedyMeshFace>> groupFacesBySlice(
            final GreedyMeshFace[] faces,
            final int bitsPerSide
    ) {
        final Map<SliceKey, List<GreedyMeshFace>> facesBySlice = new HashMap<>();

        for (final GreedyMeshFace face : faces) {
            final int dimension = face.normalDirection().getAxis().ordinal();
            final int sliceIndex = getSliceIndex(face, bitsPerSide);
            final SliceKey key = new SliceKey(dimension, sliceIndex);
            facesBySlice.computeIfAbsent(key, ignored -> new ArrayList<>()).add(face);
        }

        return facesBySlice;
    }

    private static int getSliceIndex(final GreedyMeshFace face, final int bitsPerSide) {
        final Direction.Axis axis = face.normalDirection().getAxis();
        final double axisValue = axis.choose(face.lowerLeft().x, face.lowerLeft().y, face.lowerLeft().z);
        final int planeCoordinate = (int) Math.round(axisValue * bitsPerSide);
        final int sliceIndex = planeCoordinate - 1;
        return Math.max(-1, Math.min(bitsPerSide - 1, sliceIndex));
    }

    private record SliceKey(int dimension, int sliceIndex) {
    }
}
