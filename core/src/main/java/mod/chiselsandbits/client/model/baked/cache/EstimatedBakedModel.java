package mod.chiselsandbits.client.model.baked.cache;

/** Model-owned arrays/quads only; shared atlas textures and exact JVM heap sizes are not measured. */
public interface EstimatedBakedModel {
    long getEstimatedCacheBytes();
}
