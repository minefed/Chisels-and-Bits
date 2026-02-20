package mod.chiselsandbits.network.handlers;

import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import mod.chiselsandbits.ChiselsAndBits;
import mod.chiselsandbits.api.block.entity.IMultiStateBlockEntity;
import mod.chiselsandbits.api.block.entity.INetworkUpdatableEntity;
import mod.chiselsandbits.api.change.IChangeTrackerManager;
import mod.chiselsandbits.api.client.screen.AbstractChiselsAndBitsScreen;
import mod.chiselsandbits.api.client.sharing.IPatternSharingManager;
import mod.chiselsandbits.api.client.sharing.PatternIOException;
import mod.chiselsandbits.api.item.multistate.IMultiStateItemStack;
import mod.chiselsandbits.api.profiling.IProfilerSection;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.client.model.data.ChiseledBlockModelDataExecutor;
import mod.chiselsandbits.client.screens.widgets.ChangeTrackerOperationsWidget;
import mod.chiselsandbits.clipboard.CreativeClipboardUtils;
import mod.chiselsandbits.item.multistate.SingleBlockMultiStateItemStack;
import mod.chiselsandbits.network.packets.GivePlayerPatternCommandPacket;
import mod.chiselsandbits.network.packets.UpdateChiseledBlockDeltaPacket;
import mod.chiselsandbits.network.packets.UpdateChiseledBlockPacket;
import mod.chiselsandbits.profiling.ProfilingManager;
import mod.chiselsandbits.registrars.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

public final class ClientPacketHandlers
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object CHISELED_SYNC_METRICS_LOCK = new Object();
    private static final long CHISELED_SYNC_METRICS_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private static long chiseledSyncWindowStartNanos = System.nanoTime();
    private static long chiseledSyncWindowBytes;
    private static long chiseledSyncWindowPackets;
    private static long chiseledSyncWindowModelRebuildBaseline = -1L;

    private ClientPacketHandlers()
    {
        throw new IllegalStateException("Can not instantiate an instance of: ClientPacketHandlers. This is a utility class");
    }

    public static void handleChiseledBlockUpdated(
            final BlockPos blockPos,
            final int payloadVersion,
            final long storageRevision,
            final FriendlyByteBuf updateData,
            final int payloadBytes
    ) {
        try (IProfilerSection ignored = ProfilingManager.getInstance().withSection("Handling tile entity update packet")) {
            if (payloadVersion != UpdateChiseledBlockPacket.FULL_FORMAT_VERSION) {
                requestFullChiseledBlockSync(blockPos);
                return;
            }

            final BlockEntity tileEntity = ensureNetworkUpdatableBlockEntity(blockPos);
            if (!(tileEntity instanceof INetworkUpdatableEntity networkUpdatableEntity)) {
                requestFullChiseledBlockSync(blockPos);
                return;
            }

            networkUpdatableEntity.deserializeFrom(updateData);
            if (tileEntity instanceof ChiseledBlockEntity chiseledBlockEntity) {
                chiseledBlockEntity.applyNetworkStorageRevision(storageRevision);
            }

            recordChiseledSyncMetrics(payloadBytes, "full");
        }
    }

    public static void handleChiseledBlockDeltaUpdated(
            final BlockPos blockPos,
            final int payloadVersion,
            final long baseStorageRevision,
            final long targetStorageRevision,
            final FriendlyByteBuf deltaPayload,
            final int payloadBytes
    ) {
        try (IProfilerSection ignored = ProfilingManager.getInstance().withSection("Handling tile entity delta update packet")) {
            if (payloadVersion != UpdateChiseledBlockDeltaPacket.DELTA_FORMAT_VERSION) {
                requestFullChiseledBlockSync(blockPos);
                return;
            }

            final BlockEntity tileEntity = ensureNetworkUpdatableBlockEntity(blockPos);
            if (!(tileEntity instanceof ChiseledBlockEntity chiseledBlockEntity)) {
                requestFullChiseledBlockSync(blockPos);
                return;
            }

            final boolean applied = chiseledBlockEntity.applyDeltaSyncPayload(
                    payloadVersion,
                    baseStorageRevision,
                    targetStorageRevision,
                    deltaPayload
            );

            if (!applied) {
                requestFullChiseledBlockSync(blockPos);
                return;
            }

            recordChiseledSyncMetrics(payloadBytes, "delta");
        }
    }

    private static BlockEntity ensureNetworkUpdatableBlockEntity(final BlockPos blockPos) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }

        BlockEntity tileEntity = Minecraft.getInstance().level.getBlockEntity(blockPos);
        if (tileEntity == null) {
            Minecraft.getInstance().level.setBlock(blockPos, ModBlocks.CHISELED_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
            tileEntity = Minecraft.getInstance().level.getBlockEntity(blockPos);
        }

        return tileEntity;
    }

    private static void requestFullChiseledBlockSync(final BlockPos blockPos) {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
            return;
        }

        ChiselsAndBits.getInstance().getNetworkChannel().sendToServer(new UpdateChiseledBlockPacket(blockPos));
    }

    private static void recordChiseledSyncMetrics(final int payloadBytes, final String packetType) {
        synchronized (CHISELED_SYNC_METRICS_LOCK) {
            final long nowNanos = System.nanoTime();
            if (chiseledSyncWindowModelRebuildBaseline < 0L) {
                chiseledSyncWindowModelRebuildBaseline = ChiseledBlockModelDataExecutor.getSchedulerMetrics().totalRequests();
            }

            chiseledSyncWindowBytes += Math.max(0, payloadBytes);
            chiseledSyncWindowPackets += 1L;

            final long elapsedNanos = Math.max(1L, nowNanos - chiseledSyncWindowStartNanos);
            if (elapsedNanos < CHISELED_SYNC_METRICS_WINDOW_NANOS) {
                return;
            }

            final long rebuildRequestsNow = ChiseledBlockModelDataExecutor.getSchedulerMetrics().totalRequests();
            final long rebuildRequestsDelta = Math.max(0L, rebuildRequestsNow - chiseledSyncWindowModelRebuildBaseline);
            final double elapsedSeconds = elapsedNanos / 1_000_000_000D;
            final double bytesPerSecond = chiseledSyncWindowBytes / elapsedSeconds;
            final double rebuildsPerSecond = rebuildRequestsDelta / elapsedSeconds;

            LOGGER.info(
                    "Chiseled sync metrics: packetType={}, packets={}, bytesPerSec={}, modelRebuildsPerSec={}",
                    packetType,
                    chiseledSyncWindowPackets,
                    Math.round(bytesPerSecond),
                    Math.round(rebuildsPerSecond * 100D) / 100D
            );

            chiseledSyncWindowStartNanos = nowNanos;
            chiseledSyncWindowBytes = 0L;
            chiseledSyncWindowPackets = 0L;
            chiseledSyncWindowModelRebuildBaseline = rebuildRequestsNow;
        }
    }

    public static void handleChangeTrackerUpdated(final CompoundTag tag) {
        IChangeTrackerManager.getInstance().getChangeTracker(Minecraft.getInstance().player).deserializeNBT(tag);
        if(Minecraft.getInstance().screen instanceof AbstractChiselsAndBitsScreen)
        {
            ((AbstractChiselsAndBitsScreen) Minecraft.getInstance().screen).getWidgets()
              .stream()
              .filter(ChangeTrackerOperationsWidget.class::isInstance)
              .map(ChangeTrackerOperationsWidget.class::cast)
              .forEach(ChangeTrackerOperationsWidget::updateState);
        }
    }

    public static void handleNeighborUpdated(final BlockPos toUpdate, final BlockPos from) {
        Minecraft.getInstance().level.getBlockState(toUpdate)
          .neighborChanged(
            Minecraft.getInstance().level,
            toUpdate,
            Minecraft.getInstance().level.getBlockState(from).getBlock(),
            from,
            false
          );
    }

    public static void handleAddMultiStateToClipboard(final ItemStack stack) {
        final IMultiStateItemStack itemStack = new SingleBlockMultiStateItemStack(stack);
        CreativeClipboardUtils.addBrokenBlock(itemStack);
    }

    public static void handleExportPatternCommandMessage(final BlockPos target, final String name) {
        final BlockEntity blockEntity = Minecraft.getInstance().level.getBlockEntity(target);
        if (!(blockEntity instanceof IMultiStateBlockEntity multiStateBlockEntity))
        {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal("Failed to export pattern: " + name + " - Not a multistate block."));
            return;
        }

        final IMultiStateItemStack multiStateItemStack = multiStateBlockEntity.createSnapshot().toItemStack();
        IPatternSharingManager.getInstance().exportPattern(multiStateItemStack, name);
    }

    public static void handleImportPatternCommandMessage(final String name) {;
        final Either<IMultiStateItemStack, PatternIOException> importResult = IPatternSharingManager.getInstance().importPattern(name);
        importResult.ifLeft(stack -> {
            ChiselsAndBits.getInstance().getNetworkChannel()
              .sendToServer(new GivePlayerPatternCommandPacket(stack.serializeNBT()));
        });
        importResult.ifRight(e -> {
            Minecraft.getInstance().player.sendSystemMessage(e.getErrorMessage());
        });
    }
}
