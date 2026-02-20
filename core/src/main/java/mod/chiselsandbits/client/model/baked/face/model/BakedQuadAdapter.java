package mod.chiselsandbits.client.model.baked.face.model;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import mod.chiselsandbits.client.model.baked.BakedQuadBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class BakedQuadAdapter extends BakedQuadBuilder {

    private static final int VERTEX_COUNT = 4;

    private final VertexData[] vertexDataByIndex = new VertexData[VERTEX_COUNT];
    private final boolean useColorOverride;
    private final float colorR;
    private final float colorG;
    private final float colorB;
    private final float colorA;

    public BakedQuadAdapter(final Collection<VertexData> adaptationData, final int colorOverride) {
        this(colorOverride);
        adaptationData.forEach(this::registerVertexData);
    }

    public BakedQuadAdapter(final VertexData[] adaptationData, final int colorOverride) {
        this(colorOverride);
        for (final VertexData vertexData : adaptationData) {
            registerVertexData(vertexData);
        }
    }

    private BakedQuadAdapter(final int colorOverride) {
        super();
        this.useColorOverride = colorOverride != -1;
        this.colorR = ((colorOverride >> 16) & 0xFF) / 255.0F;
        this.colorG = ((colorOverride >> 8) & 0xFF) / 255.0F;
        this.colorB = (colorOverride & 0xFF) / 255.0F;
        this.colorA = ((colorOverride >> 24) & 0xFF) / 255.0F;
    }

    private void registerVertexData(final VertexData vertexData) {
        final int vertexIndex = vertexData.vertexIndex();
        if (0 <= vertexIndex && vertexIndex < VERTEX_COUNT) {
            vertexDataByIndex[vertexIndex] = vertexData;
        }
    }

    @Override
    public void put(final int vertexIndex,
                    final int elementIndex,
                    final float @NotNull ... data) {
        if (0 <= vertexIndex && vertexIndex < VERTEX_COUNT) {
            final VertexData vertexData = vertexDataByIndex[vertexIndex];
            if (vertexData != null) {
                final VertexFormat format = getVertexFormat();
                final VertexFormatElement element = format.getElements().get(elementIndex);

                if (element.isPosition()) {
                    super.put(vertexIndex, elementIndex, vertexData.x(), vertexData.y(), vertexData.z(), 0f);
                    return;
                }

                if (element.getUsage() == VertexFormatElement.Usage.UV && element.getIndex() == 0) {
                    super.put(vertexIndex, elementIndex, vertexData.u(), vertexData.v(), 0f, 0f);
                    return;
                }

                if (element.getUsage() == VertexFormatElement.Usage.COLOR && useColorOverride) {
                    super.put(vertexIndex, elementIndex, colorR, colorG, colorB, colorA);
                    return;
                }
            }
        }

        super.put(vertexIndex, elementIndex, data);
    }
}
