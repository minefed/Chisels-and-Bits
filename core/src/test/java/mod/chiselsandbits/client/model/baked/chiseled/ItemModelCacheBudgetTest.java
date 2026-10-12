package mod.chiselsandbits.client.model.baked.chiseled;

import mod.chiselsandbits.api.IChiselsAndBitsAPI;
import mod.chiselsandbits.api.block.state.id.IBlockStateIdManager;
import mod.chiselsandbits.api.blockinformation.IBlockInformationFactory;
import mod.chiselsandbits.api.config.IChiselsAndBitsConfiguration;
import mod.chiselsandbits.api.config.IClientConfiguration;
import mod.chiselsandbits.api.config.ICommonConfiguration;
import mod.chiselsandbits.api.config.IServerConfiguration;
import mod.chiselsandbits.blockinformation.BlockInformation;
import mod.chiselsandbits.client.model.baked.cache.BakedModelCacheWeight;
import mod.chiselsandbits.client.model.baked.simple.CombinedModel;
import mod.chiselsandbits.client.model.baked.simple.NullBakedModel;
import mod.chiselsandbits.client.reloading.ClientResourceReloadingManager;
import mod.chiselsandbits.utils.SimpleMaxWeightedCache;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real cache, DataAware factory, CombinedModel and ChiseledBlockBakedModel; no Minecraft client/OpenGL. */
class ItemModelCacheBudgetTest {
    private static IClientConfiguration clientConfiguration;

    @BeforeAll
    static void initializeCoreRegistriesAndApiBoundary() {
        // Only registry/configuration boundaries are fixtures. The model and quad implementations are production classes.
        clientConfiguration = (IClientConfiguration) Proxy.newProxyInstance(IClientConfiguration.class.getClassLoader(), new Class<?>[]{IClientConfiguration.class}, (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            if (method.getName().equals("getStackModelCacheSize")) return (Supplier<Long>) () -> 100L;
            throw new UnsupportedOperationException(method.getName());
        });
        if (IChiselsAndBitsAPI.getInstance() == null) {
            final IBlockStateIdManager ids = new IBlockStateIdManager() { };
            final IBlockInformationFactory factory = BlockInformation::new;
            final IChiselsAndBitsConfiguration configuration = new IChiselsAndBitsConfiguration() {
                @Override public IClientConfiguration getClient() { return clientConfiguration; }
                @Override public ICommonConfiguration getCommon() { throw new UnsupportedOperationException(); }
                @Override public IServerConfiguration getServer() { throw new UnsupportedOperationException(); }
            };
            IChiselsAndBitsAPI.Holder.setInstance((IChiselsAndBitsAPI) Proxy.newProxyInstance(IChiselsAndBitsAPI.class.getClassLoader(), new Class<?>[]{IChiselsAndBitsAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getBlockStateIdManager" -> ids;
                case "getBlockInformationFactory" -> factory;
                case "getConfiguration" -> configuration;
                default -> throw new UnsupportedOperationException(method.getName());
            }));
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actualModelEstimationNeverQueriesQuadsOrChangesPackedVertexUvAndLightingData() throws Exception {
        final TrackingModel solid = model(13, false);
        final TrackingModel fluid = model(37, true);
        final CombinedModel combined = new CombinedModel(fluid, solid);
        final int callsBeforeWeight = fluid.quadQueries + solid.quadQueries;
        final CompoundTag key = key(1);
        final long estimated = BakedModelCacheWeight.estimate(key, combined);
        assertTrue(estimated > key.sizeInBytes());
        assertEquals(callsBeforeWeight, fluid.quadQueries + solid.quadQueries, "Weighing may not consume random/model queries");
        final var cache = DataAwareChiseledBlockBakedModel.createStackModelCache(() -> 100, () -> estimated * 2);
        assertSame(combined, cache.get(key, () -> combined));
        assertSame(combined, cache.get(key.copy(), () -> fail("Equal key missed")));
        assertEquals(estimated, cache.getRetainedWeight());
        assertEquals(callsBeforeWeight, fluid.quadQueries + solid.quadQueries);
        assertCombinedGeometry(combined, fluid, solid);
        assertTrue(combined.useAmbientOcclusion());
        assertTrue(combined.usesBlockLight());
        assertTrue(combined.isGui3d());
        assertFalse(combined.isCustomRenderer());
    }

    @Test
    void largerStoredVertexArraysAndNbtDataHaveLargerEstimates() throws Exception {
        final TrackingModel model = model(7, false);
        final long original = model.getEstimatedCacheBytes();
        final Field generic = ChiseledBlockBakedModel.class.getDeclaredField("generic");
        generic.setAccessible(true);
        generic.set(model, new BakedQuad[]{quad(99, 1_024, Direction.UP, true)});
        assertTrue(model.getEstimatedCacheBytes() > original);
        assertEquals(0, model.quadQueries);
        final CompoundTag small = key(1);
        final CompoundTag large = small.copy();
        large.putLongArray("storage", new long[4_096]);
        assertTrue(BakedModelCacheWeight.estimate(large, model) > BakedModelCacheWeight.estimate(small, model));
        assertEquals(Long.MAX_VALUE, BakedModelCacheWeight.add(Long.MAX_VALUE - 1, 2));
    }

    @Test
    void actualFactoryPreservesNestedNbtInvalidationAndOriginalSnapshot() throws Exception {
        final var cache = DataAwareChiseledBlockBakedModel.createStackModelCache(() -> 100, () -> 64L * 1024 * 1024);
        final ItemStack stack = new ItemStack(Items.STONE);
        stack.setTag(key(1).getCompound("tag"));
        final CompoundTag before = stack.save(new CompoundTag());
        final CombinedModel first = combined(1);
        final CombinedModel changed = combined(2);
        assertSame(first, cache.get(before, () -> first));
        assertSame(first, cache.get(stack.save(new CompoundTag()), () -> fail("Unchanged NBT missed")));
        stack.getOrCreateTag().putIntArray("bits", new int[]{1, 9, 3});
        assertSame(changed, cache.get(stack.save(new CompoundTag()), () -> changed));
        assertSame(first, cache.get(before.copy(), () -> fail("Nested mutation changed saved snapshot")));
        assertSame(changed, cache.get(stack.save(new CompoundTag()), () -> fail("Updated NBT missed")));
    }

    @Test
    void bypassAndFifoEvictionReturnEquivalentProductionModelGeometry() throws Exception {
        final CombinedModel baseline = combined(41);
        final long oneWeight = BakedModelCacheWeight.estimate(key(1), baseline);
        final var bounded = DataAwareChiseledBlockBakedModel.createStackModelCache(() -> 100, () -> oneWeight * 2);
        bounded.put(key(1), baseline);
        bounded.put(key(2), combined(41));
        assertSame(baseline, bounded.get(key(1), null));
        bounded.put(key(3), combined(41));
        assertNull(bounded.get(key(1)), "Hits must not turn FIFO into LRU");
        assertEquals(2, bounded.size());
        assertTrue(bounded.getRetainedWeight() <= oneWeight * 2);
        final CombinedModel rebuilt = combined(41);
        assertSame(rebuilt, bounded.get(key(1), () -> rebuilt));
        assertEquivalentGeometry(baseline, rebuilt);
        final var bypass = DataAwareChiseledBlockBakedModel.createStackModelCache(() -> 100, () -> oneWeight - 1);
        final AtomicInteger builds = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            final CombinedModel candidate = combined(41);
            assertSame(candidate, bypass.get(key(1), () -> { builds.incrementAndGet(); return candidate; }));
            assertEquivalentGeometry(baseline, candidate);
        }
        assertEquals(3, builds.get());
        assertEquals(0, bypass.size());
        assertEquals(0, bypass.getRetainedWeight());
    }

    @Test
    void unknownModelsBypassEvenAnUnlimitedConfiguredBudget() {
        final var cache = DataAwareChiseledBlockBakedModel.createStackModelCache(() -> 100, () -> Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, BakedModelCacheWeight.estimate(key(1), NullBakedModel.instance));
        assertSame(NullBakedModel.instance, cache.get(key(1), () -> NullBakedModel.instance));
        assertEquals(0, cache.size());
        final CombinedModel unknownChild = new CombinedModel(NullBakedModel.instance);
        assertSame(unknownChild, cache.get(key(1), () -> unknownChild));
        assertEquals(0, cache.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualClientReloadClearsTheStaticItemCacheAndAllowsFreshModels() throws Exception {
        final Field field = DataAwareChiseledBlockBakedModel.class.getDeclaredField("STACK_MODEL_CACHE");
        field.setAccessible(true);
        final var cache = (SimpleMaxWeightedCache<CompoundTag, BakedModel>) field.get(null);
        DataAwareChiseledBlockBakedModel.clearCache();
        final CombinedModel before = combined(3);
        cache.put(key(1), before);
        assertSame(before, cache.get(key(1)));
        ClientResourceReloadingManager.getInstance().registerCacheClearer(DataAwareChiseledBlockBakedModel::clearCache);
        ClientResourceReloadingManager.getInstance().onResourceManagerReload(null);
        assertEquals(0, cache.size());
        assertEquals(0, cache.getRetainedWeight());
        final CombinedModel after = combined(3);
        assertSame(after, cache.get(key(1), () -> after));
        assertNotSame(before, after);
        assertEquivalentGeometry(before, after);
        DataAwareChiseledBlockBakedModel.clearCache();
    }

    @Test
    void defaultBudgetAndZeroCountAreExplicitAndModelBuildMethodIsUnchanged() throws Exception {
        assertEquals(64L * 1024 * 1024, clientConfiguration.getStackModelCacheMemoryBudget().get());
        final var disabled = DataAwareChiseledBlockBakedModel.createStackModelCache(() -> 0, () -> 64L * 1024 * 1024);
        final CombinedModel model = combined(5);
        assertSame(model, disabled.get(key(1), () -> model));
        assertEquals(0, disabled.size());
        final String source = Files.readString(projectRoot().resolve("core/src/main/java/mod/chiselsandbits/client/model/baked/chiseled/DataAwareChiseledBlockBakedModel.java"));
        final String frozen = new String(getClass().getResourceAsStream("/model-cache/handleItemStack.java.txt").readAllBytes(), StandardCharsets.UTF_8).strip();
        final int start = source.indexOf("    public BakedModel handleItemStack(");
        final int opening = source.indexOf('{', start);
        int end = opening + 1;
        int depth = 1;
        while (depth > 0) { depth += (source.charAt(end) == '{' ? 1 : 0) - (source.charAt(end) == '}' ? 1 : 0); end++; }
        assertEquals(frozen.replace("\r\n", "\n"), source.substring(start, end).strip().replace("\r\n", "\n"), "NBT save and complete production build body must be unchanged");
        final String reload = Files.readString(projectRoot().resolve("core/src/main/java/mod/chiselsandbits/client/reloading/ClientResourceReloadingManager.java"));
        assertTrue(reload.contains(".registerCacheClearer(DataAwareChiseledBlockBakedModel::clearCache)"));
    }

    private static CombinedModel combined(int seed) throws Exception {
        return new CombinedModel(model(seed, true), model(seed + 1, false));
    }

    private static TrackingModel model(int seed, boolean shade) throws Exception {
        final TrackingModel model = new TrackingModel();
        final String[] fields = {"up", "down", "north", "south", "east", "west", "generic"};
        for (int i = 0; i < fields.length; i++) {
            final Field field = ChiseledBlockBakedModel.class.getDeclaredField(fields[i]);
            field.setAccessible(true);
            field.set(model, new BakedQuad[]{quad(seed + i, 32, Direction.values()[i % 6], shade)});
        }
        return model;
    }

    private static BakedQuad quad(int seed, int size, Direction direction, boolean shade) {
        final int[] vertices = new int[size];
        for (int i = 0; i < size; i++) vertices[i] = (seed * 31 + i) ^ 0xA51100FF;
        return new BakedQuad(vertices, seed, direction, null, shade);
    }

    private static CompoundTag key(int id) {
        final CompoundTag key = new CompoundTag();
        key.putString("id", "chiselsandbits:chiseled-block");
        key.putByte("Count", (byte) 1);
        final CompoundTag tag = new CompoundTag();
        tag.putInt("fixture", id);
        tag.putIntArray("bits", new int[]{1, 2, 3});
        key.put("tag", tag);
        return key;
    }

    private static void assertCombinedGeometry(CombinedModel combined, ChiseledBlockBakedModel first, ChiseledBlockBakedModel second) {
        for (final Direction side : sides()) {
            final List<BakedQuad> expected = new java.util.ArrayList<>(first.getQuads(null, side, RandomSource.create(42)));
            expected.addAll(second.getQuads(null, side, RandomSource.create(42)));
            final List<BakedQuad> actual = combined.getQuads(null, side, RandomSource.create(42));
            assertEquals(expected, actual, "Model/face order changed");
            for (int i = 0; i < actual.size(); i++) assertQuad(expected.get(i), actual.get(i));
        }
    }

    private static void assertEquivalentGeometry(CombinedModel before, CombinedModel after) {
        for (final Direction side : sides()) {
            final var expected = before.getQuads(null, side, RandomSource.create(42));
            final var actual = after.getQuads(null, side, RandomSource.create(42));
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < actual.size(); i++) assertQuad(expected.get(i), actual.get(i));
        }
        assertEquals(before.usesBlockLight(), after.usesBlockLight());
        assertEquals(before.useAmbientOcclusion(), after.useAmbientOcclusion());
    }

    private static void assertQuad(BakedQuad expected, BakedQuad actual) {
        assertArrayEquals(expected.getVertices(), actual.getVertices(), "Packed position/UV/color/light bytes changed");
        assertEquals(expected.getTintIndex(), actual.getTintIndex());
        assertEquals(expected.getDirection(), actual.getDirection());
        assertEquals(expected.isShade(), actual.isShade());
        assertSame(expected.getSprite(), actual.getSprite());
    }

    private static Direction[] sides() {
        final Direction[] sides = Arrays.copyOf(Direction.values(), 7);
        sides[6] = null;
        return sides;
    }

    private static Path projectRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/src/main/java"))) root = root.getParent();
        return java.util.Objects.requireNonNull(root);
    }

    private static final class TrackingModel extends ChiseledBlockBakedModel {
        private int quadQueries;
        private TrackingModel() { super(null, ChiselRenderType.SOLID, null, 0); }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            quadQueries++;
            return super.getQuads(state, side, random);
        }
    }
}
