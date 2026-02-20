package mod.chiselsandbits.config;

import mod.chiselsandbits.api.config.IClientConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPerformanceModeProfileTest {

    @Test
    void compatModeHasMostConservativeBudgets() {
        final IClientConfiguration.ChiseledRenderingPerformanceMode mode =
                IClientConfiguration.ChiseledRenderingPerformanceMode.COMPAT;

        assertEquals(1, mode.resolveModelBuildingThreadCap(8, 12));
        assertEquals(512, mode.getModelUpdateQueueBudget());
        assertEquals(0.75D, mode.getModelCacheSizeMultiplier(), 0.0001D);
        assertEquals(0.20D, mode.getIncrementalRebuildThresholdRatio(), 0.0001D);
    }

    @Test
    void balancedModeUsesModerateBudgets() {
        final IClientConfiguration.ChiseledRenderingPerformanceMode mode =
                IClientConfiguration.ChiseledRenderingPerformanceMode.BALANCED;

        assertEquals(4, mode.resolveModelBuildingThreadCap(8, 8));
        assertEquals(2048, mode.getModelUpdateQueueBudget());
        assertEquals(1.0D, mode.getModelCacheSizeMultiplier(), 0.0001D);
        assertEquals(0.35D, mode.getIncrementalRebuildThresholdRatio(), 0.0001D);
    }

    @Test
    void aggressiveModeAllowsLargestBudgets() {
        final IClientConfiguration.ChiseledRenderingPerformanceMode mode =
                IClientConfiguration.ChiseledRenderingPerformanceMode.AGGRESSIVE;

        assertEquals(12, mode.resolveModelBuildingThreadCap(16, 12));
        assertEquals(4096, mode.getModelUpdateQueueBudget());
        assertEquals(1.5D, mode.getModelCacheSizeMultiplier(), 0.0001D);
        assertEquals(0.60D, mode.getIncrementalRebuildThresholdRatio(), 0.0001D);
    }

    @Test
    void queueAndThresholdIncreaseByModeAggressiveness() {
        final IClientConfiguration.ChiseledRenderingPerformanceMode compat =
                IClientConfiguration.ChiseledRenderingPerformanceMode.COMPAT;
        final IClientConfiguration.ChiseledRenderingPerformanceMode balanced =
                IClientConfiguration.ChiseledRenderingPerformanceMode.BALANCED;
        final IClientConfiguration.ChiseledRenderingPerformanceMode aggressive =
                IClientConfiguration.ChiseledRenderingPerformanceMode.AGGRESSIVE;

        assertTrue(compat.getModelUpdateQueueBudget() < balanced.getModelUpdateQueueBudget());
        assertTrue(balanced.getModelUpdateQueueBudget() < aggressive.getModelUpdateQueueBudget());

        assertTrue(compat.getIncrementalRebuildThresholdRatio() < balanced.getIncrementalRebuildThresholdRatio());
        assertTrue(balanced.getIncrementalRebuildThresholdRatio() < aggressive.getIncrementalRebuildThresholdRatio());
    }
}
