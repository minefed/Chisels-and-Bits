package mod.chiselsandbits.client.model.meshing;

/**
 * Inclusive bit-space axis-aligned dirty bounds inside a single chiseled block.
 */
public record DirtyRegion(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {

    public DirtyRegion {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("DirtyRegion min bounds must be <= max bounds.");
        }

        if (minX < 0 || minY < 0 || minZ < 0) {
            throw new IllegalArgumentException("DirtyRegion coordinates must be non-negative.");
        }
    }

    public static DirtyRegion singleBit(final int x, final int y, final int z) {
        return new DirtyRegion(x, y, z, x, y, z);
    }

    public static DirtyRegion fullBlock(final int bitsPerSide) {
        if (bitsPerSide <= 0) {
            throw new IllegalArgumentException("bitsPerSide must be greater than zero.");
        }

        final int max = bitsPerSide - 1;
        return new DirtyRegion(0, 0, 0, max, max, max);
    }

    public boolean intersectsOrAdjacent(final DirtyRegion other) {
        return this.minX <= other.maxX + 1 && this.maxX + 1 >= other.minX &&
                this.minY <= other.maxY + 1 && this.maxY + 1 >= other.minY &&
                this.minZ <= other.maxZ + 1 && this.maxZ + 1 >= other.minZ;
    }

    public DirtyRegion merge(final DirtyRegion other) {
        return new DirtyRegion(
                Math.min(this.minX, other.minX),
                Math.min(this.minY, other.minY),
                Math.min(this.minZ, other.minZ),
                Math.max(this.maxX, other.maxX),
                Math.max(this.maxY, other.maxY),
                Math.max(this.maxZ, other.maxZ)
        );
    }

    public boolean isFullBlock(final int bitsPerSide) {
        if (bitsPerSide <= 0) {
            return false;
        }

        final int max = bitsPerSide - 1;
        return this.minX == 0 && this.minY == 0 && this.minZ == 0 &&
                this.maxX == max && this.maxY == max && this.maxZ == max;
    }
}
