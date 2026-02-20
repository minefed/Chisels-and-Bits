package mod.chiselsandbits.client.model.baked.chiseled;

import mod.chiselsandbits.api.blockinformation.IBlockInformation;
import mod.chiselsandbits.blockinformation.BlockInformation;
import mod.chiselsandbits.api.config.IClientConfiguration;
import mod.chiselsandbits.api.item.multistate.IMultiStateItemStack;
import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.multistate.accessor.identifier.IAreaShapeIdentifier;
import mod.chiselsandbits.api.multistate.accessor.identifier.IRevisionedAreaShapeIdentifierProvider;
import mod.chiselsandbits.api.neighborhood.IBlockNeighborhood;
import mod.chiselsandbits.api.neighborhood.IBlockNeighborhoodBuilder;
import mod.chiselsandbits.api.profiling.IProfilerSection;
import mod.chiselsandbits.profiling.ProfilingManager;
import mod.chiselsandbits.utils.SimpleMaxSizedCache;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class ChiseledBlockBakedModelManager {
    private static final ChiseledBlockBakedModelManager INSTANCE = new ChiseledBlockBakedModelManager();
    private static final long NO_STORAGE_REVISION = -1L;

    private final SimpleMaxSizedCache<Key, ChiseledBlockBakedModel> cache = new SimpleMaxSizedCache<>(
            () -> Math.max(1L, IClientConfiguration.getInstance().getEffectiveModelCacheSize() * RenderType.chunkBufferLayers().size())
    );
    private final AtomicLong cacheRequestCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong cacheMissCount = new AtomicLong();
    private volatile IClientConfiguration.ChiseledRenderingPerformanceMode activePerformanceMode;

    private ChiseledBlockBakedModelManager() {
    }

    public static ChiseledBlockBakedModelManager getInstance() {
        return INSTANCE;
    }

    public void clearCache() {
        cache.clear();
        cacheRequestCount.set(0L);
        cacheHitCount.set(0L);
        cacheMissCount.set(0L);
    }

    public CacheMetrics getCacheMetrics() {
        final long requests = cacheRequestCount.get();
        final long hits = cacheHitCount.get();
        final long misses = cacheMissCount.get();
        final double hitRatePercent = requests == 0L ? 0D : (hits * 100D) / requests;
        return new CacheMetrics(requests, hits, misses, hitRatePercent);
    }

    public void onPerformanceModeChanged(final IClientConfiguration.ChiseledRenderingPerformanceMode mode) {
        if (mode == null) {
            return;
        }

        if (mode == activePerformanceMode) {
            return;
        }

        activePerformanceMode = mode;
        clearCache();
    }

    private void ensurePerformanceModeUpToDate() {
        onPerformanceModeChanged(IClientConfiguration.getInstance().getChiseledRenderingPerformanceMode().get());
    }

    public ChiseledBlockBakedModel get(
            @NotNull final IMultiStateItemStack multiStateItemStack,
            @NotNull final ChiselRenderType chiselRenderType,
            @NotNull final RenderType renderType
    ) {
        ensurePerformanceModeUpToDate();
        try (IProfilerSection ignored = ProfilingManager.getInstance().withSection("Item based chiseled block model")) {
            return get(
                multiStateItemStack,
                multiStateItemStack.getStatistics().getPrimaryState(),
                chiselRenderType,
                null,
                null,
                BlockPos.ZERO,
                renderType
            );
        }
    }

    public ChiseledBlockBakedModel get(
            @NotNull final IAreaAccessor accessor,
            @NotNull final BlockInformation primaryState,
            @NotNull final ChiselRenderType chiselRenderType,
            @NotNull final RenderType renderType
    ) {
        ensurePerformanceModeUpToDate();
        return this.get(accessor, primaryState, chiselRenderType, null, null, BlockPos.ZERO, renderType);
    }

    public ChiseledBlockBakedModel get(
            final IAreaAccessor accessor,
            final IBlockInformation primaryState,
            final ChiselRenderType chiselRenderType,
            @Nullable final Function<Direction, IBlockInformation> neighborhoodBlockInformationProvider,
            @Nullable final Function<Direction, IAreaAccessor> neighborhoodAreaAccessorProvider,
            @NotNull final BlockPos position,
            @NotNull final RenderType renderType
    ) {
        ensurePerformanceModeUpToDate();
        try (IProfilerSection ignored1 = ProfilingManager.getInstance().withSection("Block based chiseled block model")) {
            return get(accessor, primaryState, chiselRenderType, IBlockNeighborhoodBuilder.getInstance().build(
                    neighborhoodBlockInformationProvider,
                    neighborhoodAreaAccessorProvider
            ), position, renderType);
        }
    }

    public ChiseledBlockBakedModel get(
            final IAreaAccessor accessor,
            final IBlockInformation primaryState,
            final ChiselRenderType chiselRenderType,
            @NotNull IBlockNeighborhood blockNeighborhood,
            @NotNull BlockPos position,
            @NotNull final RenderType renderType
    ) {
        ensurePerformanceModeUpToDate();
        try (IProfilerSection ignored1 = ProfilingManager.getInstance().withSection("Block based chiseled block model")) {
            final long primaryStateRenderSeed = primaryState.getBlockState().getSeed(position);
            final Key key = new Key(
                    createShapeIdentifierKey(accessor),
                    primaryState,
                    chiselRenderType,
                    blockNeighborhood,
                    primaryStateRenderSeed,
                    renderType
            );

            cacheRequestCount.incrementAndGet();
            final Optional<ChiseledBlockBakedModel> cachedModel = cache.getIfPresent(key);
            if (cachedModel.isPresent()) {
                cacheHitCount.incrementAndGet();
                return cachedModel.get();
            }

            cacheMissCount.incrementAndGet();
            try (IProfilerSection ignored3 = ProfilingManager.getInstance().withSection("Cache mis")) {
                final ChiseledBlockBakedModel builtModel = new ChiseledBlockBakedModel(
                        primaryState,
                        chiselRenderType,
                        accessor,
                        primaryStateRenderSeed
                );
                cache.put(key, builtModel);
                return builtModel;
            }
        }
    }

    static ShapeIdentifierKey createShapeIdentifierKey(final IAreaAccessor accessor) {
        if (accessor instanceof IRevisionedAreaShapeIdentifierProvider revisionedProvider) {
            return new ShapeIdentifierKey(
                    revisionedProvider.getShapeIdentifierRevision(),
                    revisionedProvider.getCachedShapeIdentifier()
            );
        }

        return new ShapeIdentifierKey(NO_STORAGE_REVISION, accessor.createNewShapeIdentifier());
    }

    void recordCacheLookupResultForTest(final boolean hit) {
        cacheRequestCount.incrementAndGet();
        if (hit) {
            cacheHitCount.incrementAndGet();
            return;
        }

        cacheMissCount.incrementAndGet();
    }

    static record ShapeIdentifierKey(long storageRevision, IAreaShapeIdentifier identifier) {
    }

    private record Key(
            ShapeIdentifierKey shapeIdentifierKey,
            IBlockInformation primaryState,
            ChiselRenderType chiselRenderType,
            IBlockNeighborhood neighborhood,
            long renderSeed,
            RenderType renderType
    ) {
    }

    public record CacheMetrics(long requests, long hits, long misses, double hitRatePercent) {
    }
}
