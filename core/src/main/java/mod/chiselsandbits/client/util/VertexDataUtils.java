package mod.chiselsandbits.client.util;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import mod.chiselsandbits.client.model.baked.chiseled.InterpolationHelper;
import mod.chiselsandbits.client.model.baked.face.model.VertexData;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VertexDataUtils {

    private static final int VERTEX_COUNT = 4;

    private static final ThreadLocal<AdaptationScratch> ADAPTATION_SCRATCH = ThreadLocal.withInitial(AdaptationScratch::new);

    private VertexDataUtils() {
        throw new IllegalStateException("Tried to instantiate: 'VertexDataUtils', but this is a utility class.");
    }

    public static Collection<VertexData> adaptVertices(
            final VertexData[] vertexData,
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to
    ) {
        final VertexData[] adapted = adaptVerticesWithReusableBuffer(
                vertexData,
                cullDirection,
                from,
                to,
                new VertexData[VERTEX_COUNT]
        );

        return Arrays.asList(adapted);
    }

    @SuppressWarnings("ConstantConditions")
    static VertexData[] adaptVerticesLegacy(
            final VertexData[] vertexData,
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to
    ) {
        final BiMap<VertexData, Vector2f> projectedVertexPositions = HashBiMap.create();
        Arrays.stream(vertexData).forEach(vertex -> projectedVertexPositions.put(vertex, vertex.projectOntoPlaneOf(cullDirection)));
        final Collection<Vector2f> boxCorners = buildCornersLegacy(cullDirection, from, to);
        final Map<Vector2f, VertexData> closestCorners = buildClosestVerticesLegacy(vertexData, boxCorners, cullDirection);

        final InterpolationHelper interpolationHelper = new InterpolationHelper(
                projectedVertexPositions.get(vertexData[0]).x(),
                projectedVertexPositions.get(vertexData[0]).y(),
                projectedVertexPositions.get(vertexData[1]).x(),
                projectedVertexPositions.get(vertexData[1]).y(),
                projectedVertexPositions.get(vertexData[2]).x(),
                projectedVertexPositions.get(vertexData[2]).y(),
                projectedVertexPositions.get(vertexData[3]).x(),
                projectedVertexPositions.get(vertexData[3]).y()
        );

        final List<VertexData> adaptedVertices = new ArrayList<>();
        for (final Vector2f newPosition : boxCorners) {
            interpolationHelper.locate(newPosition.x(), newPosition.y());
            final float u = interpolationHelper.interpolate(
                    vertexData[0].u(),
                    vertexData[1].u(),
                    vertexData[2].u(),
                    vertexData[3].u()
            );
            final float v = interpolationHelper.interpolate(
                    vertexData[0].v(),
                    vertexData[1].v(),
                    vertexData[2].v(),
                    vertexData[3].v()
            );

            final Vector3f position = VectorUtils.unprojectFromPlaneOf(newPosition, from, cullDirection);
            if (!closestCorners.containsKey(newPosition) || closestCorners.get(newPosition) == null) {
                throw new IllegalStateException("Could not find a vertex for the given position: %s".formatted(newPosition));
            }
            final VertexData vertex = new VertexData(position, new Vector2f(u, v), closestCorners.get(newPosition).vertexIndex());
            adaptedVertices.add(vertex);
        }

        adaptedVertices.sort(Comparator.comparing(VertexData::vertexIndex));

        return adaptedVertices.toArray(VertexData[]::new);
    }

    public static VertexData[] adaptVerticesWithReusableBuffer(
            final VertexData[] vertexData,
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to,
            final VertexData[] outputBuffer
    ) {
        if (vertexData.length != VERTEX_COUNT) {
            throw new IllegalArgumentException("Expected 4 vertices, got: " + vertexData.length);
        }
        if (outputBuffer.length < VERTEX_COUNT) {
            throw new IllegalArgumentException("Output buffer must have at least 4 slots, got: " + outputBuffer.length);
        }
        if (!hasBufferCompatibleVertexIndices(vertexData)) {
            return copyLegacyIntoBuffer(vertexData, cullDirection, from, to, outputBuffer);
        }

        final AdaptationScratch scratch = ADAPTATION_SCRATCH.get();
        fillCorners(cullDirection, from, to, scratch.corners);

        float cornerCenterX = 0f;
        float cornerCenterY = 0f;
        for (int i = 0; i < VERTEX_COUNT; i++) {
            cornerCenterX += scratch.corners[i].x();
            cornerCenterY += scratch.corners[i].y();
        }
        cornerCenterX /= VERTEX_COUNT;
        cornerCenterY /= VERTEX_COUNT;

        float vertexCenterX = 0f;
        float vertexCenterY = 0f;
        for (int i = 0; i < VERTEX_COUNT; i++) {
            final VertexData current = vertexData[i];
            final Vector2f projected = scratch.projectedVertices[i];
            projectOntoPlane(current, cullDirection, projected);

            vertexCenterX += projected.x();
            vertexCenterY += projected.y();
        }
        vertexCenterX /= VERTEX_COUNT;
        vertexCenterY /= VERTEX_COUNT;

        scratch.interpolationHelper.reset(
                scratch.projectedVertices[0].x(), scratch.projectedVertices[0].y(),
                scratch.projectedVertices[1].x(), scratch.projectedVertices[1].y(),
                scratch.projectedVertices[2].x(), scratch.projectedVertices[2].y(),
                scratch.projectedVertices[3].x(), scratch.projectedVertices[3].y()
        );
        scratch.interpolationHelper.setup();

        Arrays.fill(scratch.vertexByCorner, null);
        for (final VertexData current : vertexData) {
            final int corner = classifyCorner(current, cullDirection, vertexCenterX, vertexCenterY);
            scratch.vertexByCorner[corner] = current;
        }

        final float baseY = from.y();
        final float baseZ = from.z();
        final float baseX = from.x();

        for (int i = 0; i < VERTEX_COUNT; i++) {
            final Vector2f cornerPosition = scratch.corners[i];
            final int corner = classifyCorner(cornerPosition, cornerCenterX, cornerCenterY);
            final VertexData sourceVertex = scratch.vertexByCorner[corner];
            if (sourceVertex == null) {
                throw new IllegalStateException("Could not map corner to source vertex for corner index: " + corner);
            }

            scratch.interpolationHelper.locate(cornerPosition.x(), cornerPosition.y());
            final float u = scratch.interpolationHelper.interpolate(
                    vertexData[0].u(),
                    vertexData[1].u(),
                    vertexData[2].u(),
                    vertexData[3].u()
            );
            final float v = scratch.interpolationHelper.interpolate(
                    vertexData[0].v(),
                    vertexData[1].v(),
                    vertexData[2].v(),
                    vertexData[3].v()
            );

            float x = 0f;
            float y = 0f;
            float z = 0f;

            switch (cullDirection) {
                case DOWN, UP -> {
                    x = cornerPosition.x();
                    y = baseY;
                    z = cornerPosition.y();
                }
                case NORTH, SOUTH -> {
                    x = cornerPosition.x();
                    y = cornerPosition.y();
                    z = baseZ;
                }
                case WEST, EAST -> {
                    x = baseX;
                    y = cornerPosition.y();
                    z = cornerPosition.x();
                }
                default -> throw new IllegalStateException("Unsupported cull direction: " + cullDirection);
            }

            final int vertexIndex = sourceVertex.vertexIndex();
            outputBuffer[vertexIndex] = new VertexData(x, y, z, u, v, vertexIndex);
        }

        return outputBuffer;
    }

    private static boolean hasBufferCompatibleVertexIndices(final VertexData[] vertexData) {
        final boolean[] seen = new boolean[VERTEX_COUNT];
        for (final VertexData vertex : vertexData) {
            final int index = vertex.vertexIndex();
            if (index < 0 || index >= VERTEX_COUNT || seen[index]) {
                return false;
            }
            seen[index] = true;
        }

        return true;
    }

    private static VertexData[] copyLegacyIntoBuffer(
            final VertexData[] vertexData,
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to,
            final VertexData[] outputBuffer
    ) {
        final VertexData[] legacy = adaptVerticesLegacy(vertexData, cullDirection, from, to);
        System.arraycopy(legacy, 0, outputBuffer, 0, VERTEX_COUNT);
        return outputBuffer;
    }

    private static void projectOntoPlane(final VertexData vertexData, final Direction cullDirection, final Vector2f out) {
        switch (cullDirection) {
            case DOWN, UP -> out.set(vertexData.x(), vertexData.z());
            case NORTH, SOUTH -> out.set(vertexData.x(), vertexData.y());
            case WEST, EAST -> out.set(vertexData.z(), vertexData.y());
        }
    }

    private static void fillCorners(
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to,
            final Vector2f[] corners
    ) {
        final float x1 = from.x();
        final float y1 = from.y();
        final float z1 = from.z();

        final float x2 = to.x();
        final float y2 = to.y();
        final float z2 = to.z();

        switch (cullDirection) {
            case DOWN -> {
                corners[0].set(x1, z1);
                corners[1].set(x1, z2);
                corners[2].set(x2, z2);
                corners[3].set(x2, z1);
            }
            case UP -> {
                corners[0].set(x1, z1);
                corners[1].set(x2, z1);
                corners[2].set(x2, z2);
                corners[3].set(x1, z2);
            }
            case NORTH -> {
                corners[0].set(x1, y1);
                corners[1].set(x2, y1);
                corners[2].set(x2, y2);
                corners[3].set(x1, y2);
            }
            case SOUTH -> {
                corners[0].set(x1, y1);
                corners[1].set(x1, y2);
                corners[2].set(x2, y2);
                corners[3].set(x2, y1);
            }
            case WEST -> {
                corners[0].set(z1, y1);
                corners[1].set(z1, y2);
                corners[2].set(z2, y2);
                corners[3].set(z2, y1);
            }
            case EAST -> {
                corners[0].set(z1, y1);
                corners[1].set(z2, y1);
                corners[2].set(z2, y2);
                corners[3].set(z1, y2);
            }
        }
    }

    private static int classifyCorner(
            final VertexData vertexData,
            final Direction cullDirection,
            final float centerX,
            final float centerY
    ) {
        return switch (cullDirection) {
            case DOWN, UP -> classifyCorner(vertexData.x(), vertexData.z(), centerX, centerY);
            case NORTH, SOUTH -> classifyCorner(vertexData.x(), vertexData.y(), centerX, centerY);
            case WEST, EAST -> classifyCorner(vertexData.z(), vertexData.y(), centerX, centerY);
        };
    }

    private static int classifyCorner(final Vector2f value, final float centerX, final float centerY) {
        return classifyCorner(value.x(), value.y(), centerX, centerY);
    }

    private static int classifyCorner(final float x, final float y, final float centerX, final float centerY) {
        final boolean left = x < centerX;
        final boolean bottom = y < centerY;

        if (left) {
            return bottom ? 2 : 0;
        }

        return bottom ? 3 : 1;
    }

    private static Map<Vector2f, VertexData> buildClosestVerticesLegacy(
            final VertexData[] vertexData,
            final Collection<Vector2f> boxCorners,
            final Direction cullDirection
    ) {
        final Vector2f center = new Vector2f();
        for (final Vector2f corner : boxCorners) {
            center.add(corner);
        }
        center.mul(1f / boxCorners.size());

        final Vector2f centerOfVertices = new Vector2f();
        for (final VertexData vertex : vertexData) {
            centerOfVertices.add(vertex.projectOntoPlaneOf(cullDirection));
        }
        centerOfVertices.mul(1f / vertexData.length);

        enum Corner {
            TOP_LEFT,
            TOP_RIGHT,
            BOTTOM_LEFT,
            BOTTOM_RIGHT;

            private static Corner closest(final Vector2f vector2f, final Vector2f center) {
                if (vector2f.x() < center.x()) {
                    if (vector2f.y() < center.y()) {
                        return BOTTOM_LEFT;
                    } else {
                        return TOP_LEFT;
                    }
                } else {
                    if (vector2f.y() < center.y()) {
                        return BOTTOM_RIGHT;
                    } else {
                        return TOP_RIGHT;
                    }
                }
            }
        }

        final BiMap<Corner, Vector2f> boxCornersByCorner = HashBiMap.create();
        boxCorners.forEach(corner -> boxCornersByCorner.put(Corner.closest(corner, center), corner));

        final BiMap<Corner, VertexData> vertexByCorner = HashBiMap.create();
        for (final VertexData vertexDatum : vertexData) {
            vertexByCorner.put(Corner.closest(vertexDatum.projectOntoPlaneOf(cullDirection), centerOfVertices), vertexDatum);
        }

        final Map<Vector2f, VertexData> closestVertices = new HashMap<>();
        boxCornersByCorner.forEach((corner, vector2f) -> closestVertices.put(vector2f, vertexByCorner.get(corner)));
        return closestVertices;
    }

    private static Collection<Vector2f> buildCornersLegacy(
            final Direction cullDirection,
            final Vector3f from,
            final Vector3f to
    ) {
        final Collection<Vector2f> corners = new ArrayList<>();

        final float x1 = from.x();
        final float y1 = from.y();
        final float z1 = from.z();

        final float x2 = to.x();
        final float y2 = to.y();
        final float z2 = to.z();

        switch (cullDirection) {
            case DOWN -> {
                corners.add(new Vector2f(x1, z1));
                corners.add(new Vector2f(x1, z2));
                corners.add(new Vector2f(x2, z2));
                corners.add(new Vector2f(x2, z1));
            }
            case UP -> {
                corners.add(new Vector2f(x1, z1));
                corners.add(new Vector2f(x2, z1));
                corners.add(new Vector2f(x2, z2));
                corners.add(new Vector2f(x1, z2));
            }
            case NORTH -> {
                corners.add(new Vector2f(x1, y1));
                corners.add(new Vector2f(x2, y1));
                corners.add(new Vector2f(x2, y2));
                corners.add(new Vector2f(x1, y2));
            }
            case SOUTH -> {
                corners.add(new Vector2f(x1, y1));
                corners.add(new Vector2f(x1, y2));
                corners.add(new Vector2f(x2, y2));
                corners.add(new Vector2f(x2, y1));
            }
            case WEST -> {
                corners.add(new Vector2f(z1, y1));
                corners.add(new Vector2f(z1, y2));
                corners.add(new Vector2f(z2, y2));
                corners.add(new Vector2f(z2, y1));
            }
            case EAST -> {
                corners.add(new Vector2f(z1, y1));
                corners.add(new Vector2f(z2, y1));
                corners.add(new Vector2f(z2, y2));
                corners.add(new Vector2f(z1, y2));
            }
        }

        return corners;
    }

    private static final class AdaptationScratch {
        private final Vector2f[] corners = {
                new Vector2f(),
                new Vector2f(),
                new Vector2f(),
                new Vector2f()
        };
        private final Vector2f[] projectedVertices = {
                new Vector2f(),
                new Vector2f(),
                new Vector2f(),
                new Vector2f()
        };
        private final VertexData[] vertexByCorner = new VertexData[VERTEX_COUNT];
        private final InterpolationHelper interpolationHelper = new InterpolationHelper();
    }
}
