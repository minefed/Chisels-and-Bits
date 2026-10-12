package mod.chiselsandbits.client.model.baked.cache;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.nbt.CompoundTag;

/** Conservative insertion-time estimates, not an exact heap/VRAM measurement. */
public final class BakedModelCacheWeight {
    private BakedModelCacheWeight() { }

    public static long estimate(final CompoundTag key, final BakedModel model) {
        if (!(model instanceof EstimatedBakedModel estimated)) {
            return Long.MAX_VALUE;
        }
        // Minecraft already supplies an NBT heap-size estimate; unsigned conversion avoids overflow undercounting.
        return add(256, add(Integer.toUnsignedLong(key.sizeInBytes()), estimated.getEstimatedCacheBytes()));
    }

    public static long referenceArray(final int length) {
        return add(24, length * 8L);
    }

    public static long quadArray(final BakedQuad[] quads) {
        if (quads == null) {
            return 0;
        }
        long bytes = referenceArray(quads.length);
        for (final BakedQuad quad : quads) {
            if (quad != null) {
                bytes = add(bytes, add(64, add(24, quad.getVertices().length * 4L)));
            }
        }
        return bytes;
    }

    public static long quadListStorage(final int size) {
        // ArrayList's backing capacity can exceed its logical size; this deliberately overestimates it.
        return size == 0 ? 32 : add(32, add(24, Math.max(10L, size * 3L / 2 + 1) * 8L));
    }

    public static long model(final BakedModel model) {
        return model instanceof EstimatedBakedModel estimated ? estimated.getEstimatedCacheBytes() : Long.MAX_VALUE;
    }

    public static long add(final long left, final long right) {
        return left < 0 || right < 0 || right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
