package mod.chiselsandbits.profiling;

import mod.chiselsandbits.api.profiling.IProfiler;
import mod.chiselsandbits.api.profiling.IProfilerResult;
import mod.chiselsandbits.api.profiling.IProfilerSection;
import mod.chiselsandbits.api.profiling.IProfilingManager;
import mod.chiselsandbits.profiling.jvm.jfr.JfrCandBProfiler;
import net.minecraft.util.profiling.jfr.Environment;
import net.minecraft.util.profiling.jfr.JvmProfiler;

import java.util.function.Consumer;

public class ProfilingManager implements IProfilingManager
{
    private static final ProfilingManager INSTANCE = new ProfilingManager();
    private static final String LEGACY_LAYER_BUILD_PREFIX = "Known render layer model building for: ";

    public static final String SECTION_MODEL_DATA_EXTRACT = "cnb.chiseled.model.extract";
    public static final String SECTION_MODEL_BUILD_KNOWN_LAYER = "cnb.chiseled.model.build.known_layers";
    public static final String SECTION_MODEL_BUILD_LAYER = "cnb.chiseled.model.build.layer";
    public static final String SECTION_MODEL_BUILD_COMBINED = "cnb.chiseled.model.build.combined";
    public static final String SECTION_MODEL_BUILD_SOLID = "cnb.chiseled.model.build.solid";
    public static final String SECTION_MODEL_BUILD_FLUID = "cnb.chiseled.model.build.fluid";
    public static final String SECTION_MODEL_BUILD_COMBINE = "cnb.chiseled.model.build.combine";
    public static final String SECTION_MODEL_BUILD_SINGLE = "cnb.chiseled.model.build.single";
    public static final String SECTION_MODEL_REFRESH_REQUEST = "cnb.chiseled.model.refresh.request";
    public static final String SECTION_MESH_GENERATION = "cnb.chiseled.mesh.generate";
    public static final String SECTION_QUAD_GENERATION = "cnb.chiseled.quad.generate";

    public static ProfilingManager getInstance()
    {
        return INSTANCE;
    }

    public IProfiler profiler = null;

    private ProfilingManager()
    {
    }

    @Override
    public IProfiler startProfiling(Environment profilingEnvironment)
    {
        if (JvmProfiler.INSTANCE.isAvailable()) {
            return new JfrCandBProfiler(profilingEnvironment);
        }

        return new CandBProfiler();
    }

    @Override
    public IProfilerResult endProfiling(final IProfiler profiler)
    {
        if (!(profiler instanceof final CandBProfiler candBProfiler))
            throw new IllegalArgumentException("Profiler is not a Chisels and Bits Profiler");

        return candBProfiler.getResult();
    }

    @Override
    public IProfilerResult stopProfiling(final IProfiler profiler)
    {
        if (!(profiler instanceof final CandBProfiler candBProfiler))
            throw new IllegalArgumentException("Profiler is not a Chisels and Bits Profiler");

        this.profiler = null;
        return candBProfiler.stop();
    }

    public IProfiler getProfiler()
    {
        return profiler;
    }

    public void setProfiler(final IProfiler profiler)
    {
        this.profiler = profiler;
    }

    public boolean hasProfiler() {
        return getProfiler() != null;
    }

    public void withProfiler(final Consumer<IProfiler> callback) {
        if (hasProfiler())
            callback.accept(getProfiler());
    }

    public IProfilerSection withSection(final String name) {
        final IProfiler profiler = getProfiler();
        final String sectionName = normalizeSectionName(name);

        if (profiler != null)
            profiler.startSection(sectionName);

        return () -> {
            if (profiler != null)
                profiler.endSection();
        };
    }

    private String normalizeSectionName(final String name) {
        if (name == null || name.isBlank()) {
            return name;
        }

        if (name.startsWith(LEGACY_LAYER_BUILD_PREFIX)) {
            return SECTION_MODEL_BUILD_LAYER;
        }

        return switch (name) {
            case "Extract model data from data" -> SECTION_MODEL_DATA_EXTRACT;
            case "Known render layer model building" -> SECTION_MODEL_BUILD_KNOWN_LAYER;
            case "Combined model building" -> SECTION_MODEL_BUILD_COMBINED;
            case "Solid" -> SECTION_MODEL_BUILD_SOLID;
            case "Fluid" -> SECTION_MODEL_BUILD_FLUID;
            case "Model combining" -> SECTION_MODEL_BUILD_COMBINE;
            case "Singular model building" -> SECTION_MODEL_BUILD_SINGLE;
            case "processing", "facegeneration" -> SECTION_MESH_GENERATION;
            case "quadGeneration" -> SECTION_QUAD_GENERATION;
            default -> name;
        };
    }
}
