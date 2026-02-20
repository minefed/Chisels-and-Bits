package mod.chiselsandbits.client.model.meshing;

import mod.chiselsandbits.api.blockinformation.IBlockInformation;
import mod.chiselsandbits.api.multistate.StateEntrySize;
import mod.chiselsandbits.api.variant.state.IStateVariant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalGreedyMeshBuilderTest {

    private static final IBlockInformation AIR = new TestBlockInformation("air", true);
    private static final IBlockInformation STONE = new TestBlockInformation("stone", false);
    private static final IBlockInformation OAK_PLANKS = new TestBlockInformation("oak_planks", false);

    @Test
    void incrementalRebuildMatchesFullBuildForLocalizedDirtyRegion() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();
        final MaterialGrid base = new MaterialGrid(bitsPerSide, AIR);
        base.fillCuboid(2, 2, 2, bitsPerSide - 3, bitsPerSide - 3, bitsPerSide - 3, STONE);

        final GreedyMeshFace[] previousMesh = GreedyMeshBuilder.buildMesh(base::get);

        final MaterialGrid updated = base.copy();
        final int center = bitsPerSide / 2;
        updated.set(center, center, center, OAK_PLANKS);

        final GreedyMeshFace[] expectedFullMesh = GreedyMeshBuilder.buildMesh(updated::get);
        final GreedyMeshFace[] incrementalMesh = IncrementalGreedyMeshBuilder.rebuildMesh(
                updated::get,
                previousMesh,
                List.of(DirtyRegion.singleBit(center, center, center))
        );

        assertEquals(signatures(expectedFullMesh, bitsPerSide), signatures(incrementalMesh, bitsPerSide));
    }

    @Test
    void incrementalRebuildUsesFewerMaterialLookupsForSmallDirtyRegion() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();
        final MaterialGrid base = new MaterialGrid(bitsPerSide, STONE);

        final GreedyMeshFace[] previousMesh = GreedyMeshBuilder.buildMesh(base::get);

        final MaterialGrid updated = base.copy();
        final int center = bitsPerSide / 2;
        updated.set(center, center, center, AIR);

        final CountingMaterialProvider fullProvider = new CountingMaterialProvider(updated);
        GreedyMeshBuilder.buildMesh(fullProvider);

        final CountingMaterialProvider incrementalProvider = new CountingMaterialProvider(updated);
        IncrementalGreedyMeshBuilder.rebuildMesh(
                incrementalProvider,
                previousMesh,
                List.of(DirtyRegion.singleBit(center, center, center))
        );

        assertTrue(
                incrementalProvider.lookupCount() < fullProvider.lookupCount(),
                "Expected incremental path to perform fewer material lookups than full rebuild."
        );
    }

    @Test
    void fallsBackToFullRebuildWhenDirtyRegionIsFullBlock() {
        final int bitsPerSide = StateEntrySize.current().getBitsPerBlockSide();
        final MaterialGrid base = new MaterialGrid(bitsPerSide, STONE);

        final GreedyMeshFace[] previousMesh = GreedyMeshBuilder.buildMesh(base::get);

        final MaterialGrid updated = base.copy();
        updated.set(bitsPerSide / 2, bitsPerSide / 2, bitsPerSide / 2, AIR);

        final CountingMaterialProvider fullProvider = new CountingMaterialProvider(updated);
        final GreedyMeshFace[] expected = GreedyMeshBuilder.buildMesh(fullProvider);

        final CountingMaterialProvider fallbackProvider = new CountingMaterialProvider(updated);
        final GreedyMeshFace[] actual = IncrementalGreedyMeshBuilder.rebuildMesh(
                fallbackProvider,
                previousMesh,
                List.of(DirtyRegion.fullBlock(bitsPerSide))
        );

        assertEquals(fullProvider.lookupCount(), fallbackProvider.lookupCount());
        assertEquals(signatures(expected, bitsPerSide), signatures(actual, bitsPerSide));
    }

    private static List<FaceSignature> signatures(final GreedyMeshFace[] faces, final int bitsPerSide) {
        final List<FaceSignature> signatures = new ArrayList<>(faces.length);
        for (final GreedyMeshFace face : faces) {
            signatures.add(FaceSignature.from(face, bitsPerSide));
        }
        signatures.sort(FaceSignature.SORTER);
        return signatures;
    }

    private record FaceSignature(
            String material,
            Direction direction,
            boolean outerFace,
            int lowerLeftX,
            int lowerLeftY,
            int lowerLeftZ,
            int upperLeftX,
            int upperLeftY,
            int upperLeftZ,
            int lowerRightX,
            int lowerRightY,
            int lowerRightZ,
            int upperRightX,
            int upperRightY,
            int upperRightZ
    ) {

        private static final Comparator<FaceSignature> SORTER =
                Comparator.comparing(FaceSignature::material)
                        .thenComparing(FaceSignature::direction)
                        .thenComparing(FaceSignature::outerFace)
                        .thenComparingInt(FaceSignature::lowerLeftX)
                        .thenComparingInt(FaceSignature::lowerLeftY)
                        .thenComparingInt(FaceSignature::lowerLeftZ)
                        .thenComparingInt(FaceSignature::upperLeftX)
                        .thenComparingInt(FaceSignature::upperLeftY)
                        .thenComparingInt(FaceSignature::upperLeftZ)
                        .thenComparingInt(FaceSignature::lowerRightX)
                        .thenComparingInt(FaceSignature::lowerRightY)
                        .thenComparingInt(FaceSignature::lowerRightZ)
                        .thenComparingInt(FaceSignature::upperRightX)
                        .thenComparingInt(FaceSignature::upperRightY)
                        .thenComparingInt(FaceSignature::upperRightZ);

        private static FaceSignature from(final GreedyMeshFace face, final int bitsPerSide) {
            return new FaceSignature(
                    face.faceValue().toString(),
                    face.normalDirection(),
                    face.isOnOuterFace(),
                    scale(face.lowerLeft().x, bitsPerSide),
                    scale(face.lowerLeft().y, bitsPerSide),
                    scale(face.lowerLeft().z, bitsPerSide),
                    scale(face.upperLeft().x, bitsPerSide),
                    scale(face.upperLeft().y, bitsPerSide),
                    scale(face.upperLeft().z, bitsPerSide),
                    scale(face.lowerRight().x, bitsPerSide),
                    scale(face.lowerRight().y, bitsPerSide),
                    scale(face.lowerRight().z, bitsPerSide),
                    scale(face.upperRight().x, bitsPerSide),
                    scale(face.upperRight().y, bitsPerSide),
                    scale(face.upperRight().z, bitsPerSide)
            );
        }

        private static int scale(final float value, final int bitsPerSide) {
            return Math.round(value * bitsPerSide);
        }
    }

    private static final class MaterialGrid {
        private final int bitsPerSide;
        private final IBlockInformation[] materials;

        private MaterialGrid(final int bitsPerSide, final IBlockInformation fillMaterial) {
            this.bitsPerSide = bitsPerSide;
            this.materials = new IBlockInformation[bitsPerSide * bitsPerSide * bitsPerSide];
            Arrays.fill(this.materials, fillMaterial);
        }

        private void set(final int x, final int y, final int z, final IBlockInformation material) {
            materials[index(x, y, z)] = material;
        }

        private IBlockInformation get(final int x, final int y, final int z) {
            return materials[index(x, y, z)];
        }

        private void fillCuboid(
                final int minX,
                final int minY,
                final int minZ,
                final int maxX,
                final int maxY,
                final int maxZ,
                final IBlockInformation material
        ) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        set(x, y, z, material);
                    }
                }
            }
        }

        private MaterialGrid copy() {
            final MaterialGrid copy = new MaterialGrid(bitsPerSide, AIR);
            System.arraycopy(this.materials, 0, copy.materials, 0, this.materials.length);
            return copy;
        }

        private int index(final int x, final int y, final int z) {
            return ((x * bitsPerSide) + y) * bitsPerSide + z;
        }
    }

    private static final class CountingMaterialProvider implements GreedyMeshBuilder.MaterialProvider {
        private final MaterialGrid grid;
        private int lookupCount;

        private CountingMaterialProvider(final MaterialGrid grid) {
            this.grid = grid;
        }

        @Override
        public IBlockInformation getMaterial(final int x, final int y, final int z) {
            lookupCount++;
            return grid.get(x, y, z);
        }

        private int lookupCount() {
            return lookupCount;
        }
    }

    private record TestBlockInformation(String id, boolean air) implements IBlockInformation {
        @Override
        public CompoundTag serializeNBT() {
            return new CompoundTag();
        }

        @Override
        public void deserializeNBT(final CompoundTag nbt) {
        }

        @Override
        public void serializeInto(@NotNull final FriendlyByteBuf packetBuffer) {
        }

        @Override
        public void deserializeFrom(@NotNull final FriendlyByteBuf packetBuffer) {
        }

        @Override
        public IBlockInformation createSnapshot() {
            return this;
        }

        @Override
        public int compareTo(@NotNull final IBlockInformation other) {
            if (other instanceof TestBlockInformation testBlockInformation) {
                return this.id.compareTo(testBlockInformation.id);
            }

            return this.id.compareTo(other.toString());
        }

        @Override
        public BlockState getBlockState() {
            return null;
        }

        @Override
        public Optional<IStateVariant> getVariant() {
            return Optional.empty();
        }

        @Override
        public boolean isFluid() {
            return false;
        }

        @Override
        public boolean isAir() {
            return air;
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
