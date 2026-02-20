package mod.chiselsandbits.client.util;

import mod.chiselsandbits.api.blockinformation.IBlockInformation;
import mod.chiselsandbits.client.model.baked.face.FaceManager;
import mod.chiselsandbits.client.model.baked.face.model.BakedQuadAdapter;
import mod.chiselsandbits.client.model.baked.face.model.ModelQuadLayer;
import mod.chiselsandbits.client.model.baked.face.model.VertexData;
import mod.chiselsandbits.utils.LightUtil;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.List;

public final class QuadGenerationUtils {

    private static final ThreadLocal<VertexData[]> ADAPTED_VERTEX_BUFFER =
            ThreadLocal.withInitial(() -> new VertexData[4]);

    private QuadGenerationUtils() {
        throw new IllegalStateException("Tried to instantiate: 'QuadGenerationUtils', but this is a utility class.");
    }

    public static void generateQuads(
            final List<BakedQuad> target,
            final long primaryStateRenderSeed,
            @NotNull final RenderType renderType,
            final IBlockInformation blockInformation,
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to
    ) {
        final List<ModelQuadLayer> quadLayers = FaceManager.getInstance()
                .getCachedLayersFor(blockInformation, cullDirection, renderType, primaryStateRenderSeed, renderType);

        if (quadLayers.isEmpty()) {
            return;
        }

        final VertexData[] adaptedVertices = ADAPTED_VERTEX_BUFFER.get();
        for (final ModelQuadLayer layer : quadLayers) {
            try {
                VertexDataUtils.adaptVerticesWithReusableBuffer(
                        layer.vertexData(),
                        cullDirection,
                        from,
                        to,
                        adaptedVertices
                );
            } catch (final IllegalStateException exception) {
                continue;
            }

            final BakedQuadAdapter adapter = new BakedQuadAdapter(adaptedVertices, layer.color());
            LightUtil.put(adapter, layer.sourceQuad());
            applyLayerProperties(adapter, layer, cullDirection);
            target.add(adapter.build());
        }
    }

    static void applyLayerProperties(
            final BakedQuadAdapter adapter,
            final ModelQuadLayer layer,
            final Direction cullDirection
    ) {
        adapter.setQuadTint(layer.tint());
        adapter.setApplyDiffuseLighting(layer.shade());
        adapter.setTexture(layer.sprite());
        adapter.setQuadOrientation(cullDirection);
    }
}
