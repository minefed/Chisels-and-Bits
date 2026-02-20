package mod.chiselsandbits.client.model.data;

import com.communi.suggestu.scena.core.client.models.data.IModelDataBuilder;
import com.communi.suggestu.scena.core.client.models.data.IModelDataManager;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.mojang.logging.LogUtils;
import mod.chiselsandbits.ChiselsAndBits;
import mod.chiselsandbits.api.blockinformation.IBlockInformation;
import mod.chiselsandbits.blockinformation.BlockInformation;
import mod.chiselsandbits.api.config.IClientConfiguration;
import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.neighborhood.IBlockNeighborhood;
import mod.chiselsandbits.api.neighborhood.IBlockNeighborhoodBuilder;
import mod.chiselsandbits.api.profiling.IProfilerSection;
import mod.chiselsandbits.api.variant.state.IStateVariant;
import mod.chiselsandbits.api.variant.state.IStateVariantManager;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.client.model.baked.chiseled.ChiselRenderType;
import mod.chiselsandbits.client.model.baked.chiseled.ChiseledBlockBakedModel;
import mod.chiselsandbits.client.model.baked.chiseled.ChiseledBlockBakedModelManager;
import mod.chiselsandbits.client.model.baked.chiseled.FluidRenderingManager;
import mod.chiselsandbits.client.model.baked.simple.CombinedModel;
import mod.chiselsandbits.client.model.baked.simple.NullBakedModel;
import mod.chiselsandbits.client.model.meshing.IncrementalGreedyMeshBuilder;
import mod.chiselsandbits.client.multistate.rendering.RenderingAreaAccessor;
import mod.chiselsandbits.client.util.BlockInformationUtils;
import mod.chiselsandbits.profiling.ProfilingManager;
import mod.chiselsandbits.registrars.ModModelProperties;
import mod.chiselsandbits.utils.ModelDataUpdateCoalescer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ChiseledBlockModelDataExecutor {
    private static ExecutorService recalculationService;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MODEL_UPDATE_QUEUE_LIMIT = 4096;
    private static final int MAX_DRAIN_SUBMISSIONS_PER_PUMP = 8;
    private static final long METRICS_LOG_INTERVAL = 256L;
    private static final double CAMERA_NEAR_DISTANCE_SQ = 24D * 24D;
    private static final double CAMERA_FRONT_FACING_THRESHOLD = 0.15D;
    private static final long CAMERA_DISTANCE_WEIGHT = 256L;
    private static final long CAMERA_FRONT_FACING_WEIGHT = 400_000L;
    private static final long CAMERA_NEAR_BONUS = 750_000L;
    private static final long LOADED_CHUNK_BONUS = 250_000L;
    private static final Runnable NOOP = () -> {};

    private static final ModelDataUpdateCoalescer MODEL_UPDATE_COALESCER =
            new ModelDataUpdateCoalescer(MODEL_UPDATE_QUEUE_LIMIT);
    private static final Map<Long, ScheduledUpdateEntry> SCHEDULED_UPDATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean SCHEDULER_PUMP_SCHEDULED = new AtomicBoolean();
    private static final AtomicInteger ACTIVE_UPDATE_COUNT = new AtomicInteger();
    private static final AtomicInteger MAX_OBSERVED_QUEUE_DEPTH = new AtomicInteger();
    private static final AtomicLong TOTAL_REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong DEDUPED_REQUEST_COUNT = new AtomicLong();
    private static final AtomicLong DROPPED_REQUEST_COUNT = new AtomicLong();
    private static volatile IClientConfiguration.ChiseledRenderingPerformanceMode ACTIVE_PERFORMANCE_MODE;
    private static volatile int ACTIVE_THREAD_COUNT = -1;

    public static void updateModelDataCore(final ChiseledBlockEntity tileEntity, final Runnable onCompleteCallback) {
        scheduleModelDataUpdate(tileEntity, onCompleteCallback, UpdatePriority.STANDARD);
    }

    public static void enqueueChunkLoadModelDataUpdate(final ChiseledBlockEntity tileEntity) {
        scheduleModelDataUpdate(tileEntity, NOOP, UpdatePriority.CHUNK_LOAD);
    }

    public static void enqueueInteractiveModelDataUpdate(final ChiseledBlockEntity tileEntity) {
        scheduleModelDataUpdate(tileEntity, NOOP, UpdatePriority.INTERACTIVE);
    }

    public static SchedulerMetrics getSchedulerMetrics() {
        final ModelDataUpdateCoalescer.Snapshot snapshot = MODEL_UPDATE_COALESCER.snapshot();
        final long total = TOTAL_REQUEST_COUNT.get();
        final long deduped = DEDUPED_REQUEST_COUNT.get();
        final long dropped = DROPPED_REQUEST_COUNT.get();
        final double dedupeRatePercent = total == 0L ? 0D : (deduped * 100D) / total;

        return new SchedulerMetrics(
                total,
                deduped,
                dropped,
                snapshot.queuedCount(),
                snapshot.inFlightCount(),
                MAX_OBSERVED_QUEUE_DEPTH.get(),
                dedupeRatePercent
        );
    }

    private static void scheduleModelDataUpdate(
            final ChiseledBlockEntity tileEntity,
            final Runnable onCompleteCallback,
            final UpdatePriority priority
    ) {
        ensureThreadPoolSetup();

        final Runnable callback = onCompleteCallback == null ? NOOP : onCompleteCallback;
        if (tileEntity == null || !tileEntity.hasLevel() || tileEntity.getLevel() == null || !tileEntity.getLevel().isClientSide()) {
            callback.run();
            return;
        }

        final long positionKey = tileEntity.getBlockPos().asLong();
        final boolean alreadyScheduled = SCHEDULED_UPDATES.containsKey(positionKey);
        final int queueBudget = getModelUpdateQueueBudget();
        if (!alreadyScheduled && MODEL_UPDATE_COALESCER.getQueuedCount() >= queueBudget) {
            DROPPED_REQUEST_COUNT.incrementAndGet();
            callback.run();
            maybeLogSchedulerMetrics();
            return;
        }

        SCHEDULED_UPDATES.compute(positionKey, (ignored, existing) -> {
            if (existing == null) {
                return new ScheduledUpdateEntry(tileEntity, callback, priority);
            }

            existing.merge(tileEntity, callback, priority);
            return existing;
        });

        final ModelDataUpdateCoalescer.RequestResult requestResult = MODEL_UPDATE_COALESCER.requestUpdate(positionKey);
        TOTAL_REQUEST_COUNT.incrementAndGet();

        if (requestResult.isDeduped()) {
            DEDUPED_REQUEST_COUNT.incrementAndGet();
        } else if (requestResult == ModelDataUpdateCoalescer.RequestResult.DROPPED_QUEUE_FULL) {
            DROPPED_REQUEST_COUNT.incrementAndGet();
            final ScheduledUpdateEntry droppedEntry = SCHEDULED_UPDATES.remove(positionKey);
            if (droppedEntry != null) {
                droppedEntry.runCallbacks();
            }

            maybeLogSchedulerMetrics();
            return;
        }

        updateMaxObservedQueueDepth();
        maybeLogSchedulerMetrics();
        scheduleSchedulerPump();
    }

    private static void updateMaxObservedQueueDepth() {
        final int queueDepth = MODEL_UPDATE_COALESCER.getQueuedCount();
        while (true) {
            final int currentlyObserved = MAX_OBSERVED_QUEUE_DEPTH.get();
            if (queueDepth <= currentlyObserved) {
                return;
            }

            if (MAX_OBSERVED_QUEUE_DEPTH.compareAndSet(currentlyObserved, queueDepth)) {
                return;
            }
        }
    }

    private static void scheduleSchedulerPump() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        if (!SCHEDULER_PUMP_SCHEDULED.compareAndSet(false, true)) {
            return;
        }

        minecraft.tell(() -> {
            try {
                drainScheduledModelUpdates();
            } finally {
                SCHEDULER_PUMP_SCHEDULED.set(false);
                if (MODEL_UPDATE_COALESCER.hasPending() && hasAvailableSchedulingCapacity()) {
                    scheduleSchedulerPump();
                }
            }
        });
    }

    private static boolean hasAvailableSchedulingCapacity() {
        return ACTIVE_UPDATE_COUNT.get() < getMaxConcurrentModelBuilds();
    }

    private static void drainScheduledModelUpdates() {
        int submitted = 0;
        while (submitted < MAX_DRAIN_SUBMISSIONS_PER_PUMP && hasAvailableSchedulingCapacity()) {
            final OptionalLong nextPosition = MODEL_UPDATE_COALESCER.pollNext(ChiseledBlockModelDataExecutor::scorePendingUpdate);
            if (nextPosition.isEmpty()) {
                break;
            }

            final long positionKey = nextPosition.getAsLong();
            final ScheduledUpdateEntry entry = SCHEDULED_UPDATES.get(positionKey);
            if (entry == null) {
                MODEL_UPDATE_COALESCER.markCompleted(positionKey);
                continue;
            }

            ACTIVE_UPDATE_COUNT.incrementAndGet();
            submitted++;
            submitModelDataUpdate(entry.getTileEntity(), () -> onScheduledUpdateCompleted(positionKey));
        }
    }

    private static int getMaxConcurrentModelBuilds() {
        return Math.max(1, IClientConfiguration.getInstance().getEffectiveModelBuildingThreadCount());
    }

    private static int getModelUpdateQueueBudget() {
        return Math.max(32, IClientConfiguration.getInstance().getModelUpdateQueueBudget());
    }

    private static long scorePendingUpdate(final long positionKey) {
        final ScheduledUpdateEntry entry = SCHEDULED_UPDATES.get(positionKey);
        if (entry == null) {
            return Long.MIN_VALUE;
        }

        long score = entry.getPriority().baseScore();
        score += Math.min(entry.waitMillis(), 5_000L) * 1_000L;

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return score;
        }

        final Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            return score;
        }

        final ChiseledBlockEntity tileEntity = entry.getTileEntity();
        final double distanceSq = tileEntity.getBlockPos().distToCenterSqr(
                cameraEntity.getX(),
                cameraEntity.getY(),
                cameraEntity.getZ()
        );

        final long distanceScore = Math.max(0L, 1_500_000L - (long) (distanceSq * CAMERA_DISTANCE_WEIGHT));
        score += distanceScore;

        if (distanceSq <= CAMERA_NEAR_DISTANCE_SQ) {
            score += CAMERA_NEAR_BONUS;
        }

        final Vec3 toTarget = Vec3.atCenterOf(tileEntity.getBlockPos()).subtract(cameraEntity.position());
        if (toTarget.lengthSqr() > 1.0E-6D) {
            final double facingDot = cameraEntity.getLookAngle().normalize().dot(toTarget.normalize());
            if (facingDot > CAMERA_FRONT_FACING_THRESHOLD) {
                score += (long) ((facingDot - CAMERA_FRONT_FACING_THRESHOLD) * CAMERA_FRONT_FACING_WEIGHT);
            }
        }

        if (minecraft.level == tileEntity.getLevel() && minecraft.level != null && minecraft.level.isLoaded(tileEntity.getBlockPos())) {
            score += LOADED_CHUNK_BONUS;
        }

        return score;
    }

    private static void maybeLogSchedulerMetrics() {
        final long total = TOTAL_REQUEST_COUNT.get();
        if (total == 0L || total % METRICS_LOG_INTERVAL != 0L) {
            return;
        }

        final SchedulerMetrics metrics = getSchedulerMetrics();
        final ChiseledBlockBakedModelManager.CacheMetrics cacheMetrics =
                ChiseledBlockBakedModelManager.getInstance().getCacheMetrics();
        final IClientConfiguration configuration = IClientConfiguration.getInstance();
        LOGGER.info(
                "Chiseled model scheduler metrics: mode={}, dedupeRate={}%, queue={}/{}, inFlight={}, dropped={}, maxQueue={}, cacheHitRate={}%, cacheMisses={}",
                configuration.getChiseledRenderingPerformanceMode().get().name().toLowerCase(Locale.ROOT),
                Math.round(metrics.dedupeRatePercent() * 100D) / 100D,
                metrics.queueDepth(),
                configuration.getModelUpdateQueueBudget(),
                metrics.inFlightCount(),
                metrics.droppedRequests(),
                metrics.maxObservedQueueDepth(),
                Math.round(cacheMetrics.hitRatePercent() * 100D) / 100D,
                cacheMetrics.misses()
        );
    }

    private static void onScheduledUpdateCompleted(final long positionKey) {
        final ScheduledUpdateEntry completedEntry = SCHEDULED_UPDATES.get(positionKey);
        if (completedEntry != null) {
            completedEntry.runCallbacks();
        }

        final boolean rerunQueued = MODEL_UPDATE_COALESCER.markCompleted(positionKey);
        if (!rerunQueued && completedEntry != null) {
            SCHEDULED_UPDATES.remove(positionKey, completedEntry);
        }

        ACTIVE_UPDATE_COUNT.updateAndGet(current -> Math.max(0, current - 1));
        scheduleSchedulerPump();
    }

    private static void submitModelDataUpdate(final ChiseledBlockEntity tileEntity, final Runnable onCompleteCallback) {
        ensureThreadPoolSetup();

        if (tileEntity == null || tileEntity.getLevel() == null || !tileEntity.getLevel().isClientSide()) {
            onCompleteCallback.run();
            return;
        }

        final IBlockNeighborhood neighborhood = IBlockNeighborhoodBuilder.getInstance().build(
                direction -> {
                    final BlockState state = Objects.requireNonNull(tileEntity.getLevel()).getBlockState(tileEntity.getBlockPos().offset(direction.getNormal()));
                    final Optional<IStateVariant> additionalStateInfo = IStateVariantManager.getInstance().getStateVariant(
                            state,
                            Optional.ofNullable(tileEntity.getLevel().getBlockEntity(tileEntity.getBlockPos().offset(direction.getNormal())))
                    );

                    return new BlockInformation(state, additionalStateInfo);
                },
                direction -> {
                    final BlockEntity otherTileEntity = Objects.requireNonNull(tileEntity.getLevel()).getBlockEntity(tileEntity.getBlockPos().offset(direction.getNormal()));
                    if (otherTileEntity instanceof IAreaAccessor) {
                        return (IAreaAccessor) otherTileEntity;
                    }

                    return null;
                }
        );
        CompletableFuture.supplyAsync(() -> {
                    BakedModel unknownRenderTypeModel;
                    Map<RenderType, BakedModel> renderTypedModels = Maps.newLinkedHashMap();

                    final Set<RenderType> renderTypes = BlockInformationUtils.extractRenderTypes(tileEntity.getStatistics().getStateCounts().keySet());

                    try (IProfilerSection ignored1 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_DATA_EXTRACT)) {

                        try (IProfilerSection ignored2 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_KNOWN_LAYER)) {
                            for (final RenderType chunkBufferLayer : renderTypes) {
                                final ChiselRenderType solidType =
                                        ChiselRenderType.fromLayer(chunkBufferLayer, false);
                                final ChiselRenderType fluidType =
                                        ChiselRenderType.fromLayer(chunkBufferLayer, true);

                                if (tileEntity.getStatistics().getStateCounts().isEmpty() ||
                                        (tileEntity.getStatistics().getStateCounts().size() == 1 && tileEntity.getStatistics().getStateCounts().containsKey(BlockInformation.AIR))) {
                                    continue;
                                }

                                BakedModel baked;

                                try (IProfilerSection ignored3 = ProfilingManager.getInstance()
                                        .withSection(ProfilingManager.SECTION_MODEL_BUILD_LAYER)) {

                                    if (FluidRenderingManager.getInstance().isFluidRenderType(chunkBufferLayer)) {
                                        try (IProfilerSection ignored4 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_COMBINED)) {

                                            final ChiseledBlockBakedModel solidModel;
                                            try (IProfilerSection ignored5 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_SOLID)) {
                                                solidModel = ChiseledBlockBakedModelManager.getInstance().get(
                                                        tileEntity,
                                                        tileEntity.getStatistics().getPrimaryState(),
                                                        solidType,
                                                        neighborhood,
                                                        tileEntity.getBlockPos(),
                                                        chunkBufferLayer
                                                );
                                            }

                                            final ChiseledBlockBakedModel fluidModel;
                                            try (IProfilerSection ignored5 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_FLUID)) {
                                                fluidModel = ChiseledBlockBakedModelManager.getInstance().get(
                                                        tileEntity,
                                                        tileEntity.getStatistics().getPrimaryState(),
                                                        fluidType,
                                                        neighborhood,
                                                        tileEntity.getBlockPos(),
                                                        chunkBufferLayer
                                                );
                                            }

                                            try (IProfilerSection ignored5 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_COMBINE)) {
                                                if (solidModel.isEmpty()) {
                                                    baked = fluidModel;
                                                } else if (fluidModel.isEmpty()) {
                                                    baked = solidModel;
                                                } else {
                                                    baked = new CombinedModel(solidModel, fluidModel);
                                                }
                                            }
                                        }
                                    } else {
                                        try (IProfilerSection ignored4 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_SINGLE)) {
                                            baked = ChiseledBlockBakedModelManager.getInstance().get(
                                                    tileEntity,
                                                    tileEntity.getStatistics().getPrimaryState(),
                                                    ChiselRenderType.fromLayer(chunkBufferLayer, false),
                                                    neighborhood,
                                                    tileEntity.getBlockPos(),
                                                    chunkBufferLayer
                                            );
                                        }
                                    }
                                }

                                renderTypedModels.put(chunkBufferLayer, baked);
                            }
                        }
                    }

                    unknownRenderTypeModel = new CombinedModel(renderTypedModels.values().stream()
                            .filter(model -> model != NullBakedModel.instance)
                            .toArray(BakedModel[]::new));

                    return IModelDataBuilder.create()
                            .withInitial(
                                    ModModelProperties.UNKNOWN_LAYER_MODEL_PROPERTY, unknownRenderTypeModel
                            )
                            .withInitial(
                                    ModModelProperties.KNOWN_LAYER_MODEL_PROPERTY, renderTypedModels
                            )
                            .build();
                }, recalculationService)
                .thenAcceptAsync(tileEntity::setModelData, recalculationService)
                .handleAsync((unused, throwable) -> {
                    onCompleteCallback.run();
                    if (throwable != null) {
                        LOGGER.error("Failed to update model data for chiseled block entity", throwable);
                        return false;
                    }

                    return true;
                }, recalculationService)
                .thenAcceptAsync(updateSucceeded -> {
                    if (updateSucceeded && Minecraft.getInstance().level == tileEntity.getLevel()) {
                        try (IProfilerSection ignored = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_REFRESH_REQUEST)) {
                            IModelDataManager.getInstance().requestModelDataRefresh(tileEntity);
                            Objects.requireNonNull(Minecraft.getInstance().level).sendBlockUpdated(
                                    tileEntity.getBlockPos(),
                                tileEntity.getBlockState(),
                                tileEntity.getBlockState(),
                                8
                            );
                        }
                    }
                }, Minecraft.getInstance());
    }

    public record SchedulerMetrics(
            long totalRequests,
            long dedupedRequests,
            long droppedRequests,
            int queueDepth,
            int inFlightCount,
            int maxObservedQueueDepth,
            double dedupeRatePercent
    ) {
    }

    private enum UpdatePriority {
        INTERACTIVE(3_000_000L),
        STANDARD(2_000_000L),
        CHUNK_LOAD(1_000_000L);

        private final long baseScore;

        UpdatePriority(final long baseScore) {
            this.baseScore = baseScore;
        }

        private long baseScore() {
            return baseScore;
        }

        private static UpdatePriority merge(final UpdatePriority first, final UpdatePriority second) {
            return first.baseScore >= second.baseScore ? first : second;
        }
    }

    private static final class ScheduledUpdateEntry {
        private final Queue<Runnable> completionCallbacks = new ConcurrentLinkedQueue<>();
        private volatile ChiseledBlockEntity tileEntity;
        private volatile UpdatePriority priority;
        private final long firstEnqueuedNanos;

        private ScheduledUpdateEntry(
                final ChiseledBlockEntity tileEntity,
                final Runnable completionCallback,
                final UpdatePriority priority
        ) {
            this.tileEntity = tileEntity;
            this.priority = priority;
            this.firstEnqueuedNanos = System.nanoTime();
            if (completionCallback != NOOP) {
                this.completionCallbacks.add(completionCallback);
            }
        }

        private void merge(
                final ChiseledBlockEntity tileEntity,
                final Runnable completionCallback,
                final UpdatePriority requestedPriority
        ) {
            this.tileEntity = tileEntity;
            this.priority = UpdatePriority.merge(this.priority, requestedPriority);
            if (completionCallback != NOOP) {
                this.completionCallbacks.add(completionCallback);
            }
        }

        private ChiseledBlockEntity getTileEntity() {
            return tileEntity;
        }

        private UpdatePriority getPriority() {
            return priority;
        }

        private long waitMillis() {
            final long elapsedNanos = Math.max(0L, System.nanoTime() - firstEnqueuedNanos);
            return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        }

        private void runCallbacks() {
            Runnable callback;
            while ((callback = completionCallbacks.poll()) != null) {
                try {
                    callback.run();
                } catch (Exception exception) {
                    LOGGER.error("Failed to execute model update callback", exception);
                }
            }
        }
    }

    public static void updateModelDataPerContainedState(final ChiseledBlockEntity tileEntity, final Consumer<Table<RenderType, IBlockInformation, BakedModel>> resultConsumer) {
        ensureThreadPoolSetup();

        final IBlockNeighborhood neighborhood = IBlockNeighborhoodBuilder.getInstance().build(
                direction -> {
                    final BlockState state = Objects.requireNonNull(tileEntity.getLevel()).getBlockState(tileEntity.getBlockPos().offset(direction.getNormal()));
                    final Optional<IStateVariant> additionalStateInfo = IStateVariantManager.getInstance().getStateVariant(
                            state,
                            Optional.ofNullable(tileEntity.getLevel().getBlockEntity(tileEntity.getBlockPos().offset(direction.getNormal())))
                    );

                    return new BlockInformation(state, additionalStateInfo);
                },
                direction -> {
                    final BlockEntity otherTileEntity = Objects.requireNonNull(tileEntity.getLevel()).getBlockEntity(tileEntity.getBlockPos().offset(direction.getNormal()));
                    if (otherTileEntity instanceof IAreaAccessor) {
                        return (IAreaAccessor) otherTileEntity;
                    }

                    return null;
                }
        );

        CompletableFuture.supplyAsync(new Supplier<Table<RenderType, IBlockInformation, BakedModel>>() {
            @Override
            public Table<RenderType, IBlockInformation, BakedModel> get() {
                final HashBasedTable<RenderType, IBlockInformation, BakedModel> result = HashBasedTable.create();

                for (IBlockInformation blockInformation : tileEntity.getStatistics().getContainedStates()) {
                    final Set<RenderType> renderTypes = BlockInformationUtils.extractRenderTypes(blockInformation);

                    final IAreaAccessor filtered = new RenderingAreaAccessor(blockInformation, tileEntity);

                    for (RenderType renderType : renderTypes) {
                        final ChiselRenderType solidType =
                                ChiselRenderType.fromLayer(renderType, false);
                        final ChiselRenderType fluidType =
                                ChiselRenderType.fromLayer(renderType, true);

                        if (tileEntity.getStatistics().getStateCounts().isEmpty() ||
                                (tileEntity.getStatistics().getStateCounts().size() == 1 && tileEntity.getStatistics().getStateCounts().containsKey(BlockInformation.AIR))) {
                            continue;
                        }

                        BakedModel baked;

                        try (IProfilerSection ignored3 = ProfilingManager.getInstance()
                                .withSection(ProfilingManager.SECTION_MODEL_BUILD_LAYER)) {

                            if (FluidRenderingManager.getInstance().isFluidRenderType(renderType)) {
                                try (IProfilerSection ignored4 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_COMBINED)) {

                                    final ChiseledBlockBakedModel solidModel;
                                    try (IProfilerSection ignored5 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_SOLID)) {
                                        solidModel = ChiseledBlockBakedModelManager.getInstance().get(
                                                filtered,
                                                blockInformation,
                                                solidType,
                                                neighborhood,
                                                tileEntity.getBlockPos(),
                                                renderType
                                        );
                                    }

                                    final ChiseledBlockBakedModel fluidModel;
                                    try (IProfilerSection ignored5 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_FLUID)) {
                                        fluidModel = ChiseledBlockBakedModelManager.getInstance().get(
                                                filtered,
                                                blockInformation,
                                                fluidType,
                                                neighborhood,
                                                tileEntity.getBlockPos(),
                                                renderType
                                        );
                                    }

                                    try (IProfilerSection ignored5 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_COMBINE)) {
                                        if (solidModel.isEmpty()) {
                                            baked = fluidModel;
                                        } else if (fluidModel.isEmpty()) {
                                            baked = solidModel;
                                        } else {
                                            baked = new CombinedModel(solidModel, fluidModel);
                                        }
                                    }
                                }
                            } else {
                                try (IProfilerSection ignored4 = ProfilingManager.getInstance().withSection(ProfilingManager.SECTION_MODEL_BUILD_SINGLE)) {
                                    baked = ChiseledBlockBakedModelManager.getInstance().get(
                                            filtered,
                                            blockInformation,
                                            ChiselRenderType.fromLayer(renderType, false),
                                            neighborhood,
                                            tileEntity.getBlockPos(),
                                            renderType
                                    );
                                }
                            }
                        }

                        result.put(renderType, blockInformation, baked);
                    }
                }

                return result;
            }
        }, recalculationService)
        .thenAcceptAsync(resultConsumer, recalculationService);
    }

    private static synchronized void ensureThreadPoolSetup() {
        final IClientConfiguration configuration = IClientConfiguration.getInstance();
        final IClientConfiguration.ChiseledRenderingPerformanceMode mode = configuration.getChiseledRenderingPerformanceMode().get();
        final int desiredThreadCount = Math.max(1, configuration.getEffectiveModelBuildingThreadCount());
        final boolean modeChanged = mode != ACTIVE_PERFORMANCE_MODE;
        final boolean threadCountChanged = desiredThreadCount != ACTIVE_THREAD_COUNT;

        if (modeChanged) {
            ACTIVE_PERFORMANCE_MODE = mode;
            IncrementalGreedyMeshBuilder.setDirtyVolumeFallbackRatio(configuration.getIncrementalRebuildThresholdRatio());
            ChiseledBlockBakedModelManager.getInstance().onPerformanceModeChanged(mode);
        }

        if (recalculationService == null || threadCountChanged) {
            recreateRecalculationService(desiredThreadCount);
        }
    }

    private static void recreateRecalculationService(final int threadCount) {
        if (recalculationService != null) {
            recalculationService.shutdown();
        }

        final ClassLoader classLoader = ChiselsAndBits.class.getClassLoader();
        final AtomicInteger genericThreadCounter = new AtomicInteger();
        recalculationService = Executors.newFixedThreadPool(
                threadCount,
                runnable -> {
                    final Thread thread = new Thread(runnable);
                    thread.setContextClassLoader(classLoader);
                    thread.setName(String.format("Chisels and Bits Model builder #%s", genericThreadCounter.incrementAndGet()));
                    thread.setDaemon(true);
                    return thread;
                }
        );
        ACTIVE_THREAD_COUNT = threadCount;
    }
}
