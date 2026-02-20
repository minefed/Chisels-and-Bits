package mod.chiselsandbits.client.model.baked.chiseled;

import mod.chiselsandbits.api.multistate.accessor.IAreaAccessor;
import mod.chiselsandbits.api.multistate.accessor.IStateEntryInfo;
import mod.chiselsandbits.api.multistate.accessor.identifier.IAreaShapeIdentifier;
import mod.chiselsandbits.api.multistate.accessor.identifier.IRevisionedAreaShapeIdentifierProvider;
import mod.chiselsandbits.api.multistate.accessor.sortable.IPositionMutator;
import mod.chiselsandbits.api.multistate.snapshot.IMultiStateSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ModelCacheKeyStabilityTest {

    private final ChiseledBlockBakedModelManager manager = ChiseledBlockBakedModelManager.getInstance();

    @BeforeEach
    void setUp() {
        manager.clearCache();
    }

    @AfterEach
    void tearDown() {
        manager.clearCache();
    }

    @Test
    void reusesCachedIdentifierWhenRevisionIsStable() {
        final TestAccessor accessor = new TestAccessor(new IdentifierFactory(false, "stable"));

        final ChiseledBlockBakedModelManager.ShapeIdentifierKey first =
                ChiseledBlockBakedModelManager.createShapeIdentifierKey(accessor);
        final ChiseledBlockBakedModelManager.ShapeIdentifierKey second =
                ChiseledBlockBakedModelManager.createShapeIdentifierKey(accessor);

        assertEquals(first, second);
        assertEquals(1, accessor.identifierBuildCount);
        assertEquals(0, accessor.createNewIdentifierCalls);
    }

    @Test
    void rebuildsIdentifierKeyWhenRevisionChanges() {
        final TestAccessor accessor = new TestAccessor(new IdentifierFactory(false, "revision"));

        final ChiseledBlockBakedModelManager.ShapeIdentifierKey before =
                ChiseledBlockBakedModelManager.createShapeIdentifierKey(accessor);
        accessor.bumpRevision();
        final ChiseledBlockBakedModelManager.ShapeIdentifierKey after =
                ChiseledBlockBakedModelManager.createShapeIdentifierKey(accessor);

        assertNotEquals(before, after);
        assertEquals(2, accessor.identifierBuildCount);
        assertEquals(0, accessor.createNewIdentifierCalls);
    }

    @Test
    void keepsHashCollisionIdentifiersDistinct() {
        final TestAccessor firstAccessor = new TestAccessor(new IdentifierFactory(true, "first"));
        final TestAccessor secondAccessor = new TestAccessor(new IdentifierFactory(true, "second"));

        final ChiseledBlockBakedModelManager.ShapeIdentifierKey first =
                ChiseledBlockBakedModelManager.createShapeIdentifierKey(firstAccessor);
        final ChiseledBlockBakedModelManager.ShapeIdentifierKey second =
                ChiseledBlockBakedModelManager.createShapeIdentifierKey(secondAccessor);

        assertNotEquals(first, second);
    }

    @Test
    void reportsCacheHitRateFromLookupCounters() {
        manager.recordCacheLookupResultForTest(false);
        manager.recordCacheLookupResultForTest(true);
        manager.recordCacheLookupResultForTest(true);
        manager.recordCacheLookupResultForTest(false);

        final ChiseledBlockBakedModelManager.CacheMetrics metrics = manager.getCacheMetrics();
        assertEquals(4L, metrics.requests());
        assertEquals(2L, metrics.hits());
        assertEquals(2L, metrics.misses());
        assertEquals(50.0D, metrics.hitRatePercent(), 0.0001D);
    }

    private static final class TestAccessor implements IAreaAccessor, IRevisionedAreaShapeIdentifierProvider {
        private final IdentifierFactory identifierFactory;
        private long revision;
        private long cachedRevision = Long.MIN_VALUE;
        private IAreaShapeIdentifier cachedIdentifier;
        private int identifierBuildCount;
        private int createNewIdentifierCalls;

        private TestAccessor(final IdentifierFactory identifierFactory) {
            this.identifierFactory = identifierFactory;
        }

        @Override
        public long getShapeIdentifierRevision() {
            return revision;
        }

        @Override
        public IAreaShapeIdentifier getCachedShapeIdentifier() {
            if (cachedIdentifier != null && cachedRevision == revision) {
                return cachedIdentifier;
            }

            cachedRevision = revision;
            identifierBuildCount++;
            cachedIdentifier = identifierFactory.build(revision);
            return cachedIdentifier;
        }

        void bumpRevision() {
            this.revision += 1L;
        }

        @Override
        public IAreaShapeIdentifier createNewShapeIdentifier() {
            createNewIdentifierCalls++;
            return identifierFactory.build(revision);
        }

        @Override
        public Stream<IStateEntryInfo> stream() {
            return Stream.empty();
        }

        @Override
        public boolean isInside(final Vec3 inAreaTarget) {
            return true;
        }

        @Override
        public boolean isInside(final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget) {
            return true;
        }

        @Override
        public IMultiStateSnapshot createSnapshot() {
            throw new UnsupportedOperationException("Not required for this test.");
        }

        @Override
        public Stream<IStateEntryInfo> streamWithPositionMutator(final IPositionMutator positionMutator) {
            return Stream.empty();
        }

        @Override
        public void forEachWithPositionMutator(final IPositionMutator positionMutator, final Consumer<IStateEntryInfo> consumer) {
            // no-op
        }

        @Override
        public Optional<IStateEntryInfo> getInAreaTarget(final Vec3 inAreaTarget) {
            return Optional.empty();
        }

        @Override
        public Optional<IStateEntryInfo> getInBlockTarget(final BlockPos inAreaBlockPosOffset, final Vec3 inBlockTarget) {
            return Optional.empty();
        }

        @Override
        public AABB getBoundingBox() {
            return new AABB(0, 0, 0, 1, 1, 1);
        }
    }

    private static final class IdentifierFactory {
        private final boolean forceHashCollision;
        private final String prefix;

        private IdentifierFactory(final boolean forceHashCollision, final String prefix) {
            this.forceHashCollision = forceHashCollision;
            this.prefix = prefix;
        }

        private IAreaShapeIdentifier build(final long revision) {
            return new TestIdentifier(prefix + "-" + revision, forceHashCollision ? 7 : (31 + prefix.hashCode()));
        }
    }

    private static final class TestIdentifier implements IAreaShapeIdentifier {
        private final String id;
        private final int hashCode;

        private TestIdentifier(final String id, final int hashCode) {
            this.id = id;
            this.hashCode = hashCode;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TestIdentifier that)) {
                return false;
            }
            return this.id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
