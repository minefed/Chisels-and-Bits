package mod.chiselsandbits.profiling;

import mod.chiselsandbits.api.profiling.IProfiler;
import mod.chiselsandbits.api.profiling.IProfilerSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfilingManagerSectionNormalizationTest {

    private final ProfilingManager profilingManager = ProfilingManager.getInstance();

    @AfterEach
    void tearDown() {
        profilingManager.setProfiler(null);
    }

    @Test
    void normalizesLegacyModelBuildSectionName() {
        final RecordingProfiler profiler = new RecordingProfiler();
        profilingManager.setProfiler(profiler);

        try (IProfilerSection ignored = profilingManager.withSection("Extract model data from data")) {
            // no-op
        }

        assertEquals(List.of("cnb.chiseled.model.extract"), profiler.startedSections);
        assertEquals(1, profiler.endSectionCount);
    }

    @Test
    void normalizesLegacyMeshAndQuadSectionNames() {
        final RecordingProfiler profiler = new RecordingProfiler();
        profilingManager.setProfiler(profiler);

        try (IProfilerSection ignored = profilingManager.withSection("facegeneration")) {
            // no-op
        }

        try (IProfilerSection ignored = profilingManager.withSection("quadGeneration")) {
            // no-op
        }

        assertEquals(List.of(
                "cnb.chiseled.mesh.generate",
                "cnb.chiseled.quad.generate"
        ), profiler.startedSections);
        assertEquals(2, profiler.endSectionCount);
    }

    private static final class RecordingProfiler implements IProfiler {
        private final List<String> startedSections = new ArrayList<>();
        private int endSectionCount;

        @Override
        public void startSection(final String name) {
            startedSections.add(name);
        }

        @Override
        public void startSection(final Supplier<String> nameSupplier) {
            startedSections.add(nameSupplier.get());
        }

        @Override
        public void endSection() {
            endSectionCount++;
        }
    }
}
