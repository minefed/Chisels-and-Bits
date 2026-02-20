package mod.chiselsandbits.network.packets;

import com.communi.suggestu.scena.core.dist.DistExecutor;
import io.netty.buffer.Unpooled;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.client.model.meshing.DirtyRegion;
import mod.chiselsandbits.network.handlers.ClientPacketHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.communi.suggestu.scena.core.dist.Dist.CLIENT;

public final class UpdateChiseledBlockDeltaPacket extends ModPacket {

    public static final int DELTA_FORMAT_VERSION = 1;

    private int payloadVersion;
    private BlockPos blockPos;
    private long baseStorageRevision;
    private long targetStorageRevision;
    private byte[] deltaPayload;

    public UpdateChiseledBlockDeltaPacket(
            final ChiseledBlockEntity tileEntity,
            final long baseStorageRevision,
            final long targetStorageRevision,
            final List<DirtyRegion> dirtyRegions
    ) {
        this(
                tileEntity.getBlockPos(),
                baseStorageRevision,
                targetStorageRevision,
                writeDeltaPayload(tileEntity, dirtyRegions)
        );
    }

    public UpdateChiseledBlockDeltaPacket(
            final BlockPos blockPos,
            final long baseStorageRevision,
            final long targetStorageRevision,
            final byte[] deltaPayload
    ) {
        this.payloadVersion = DELTA_FORMAT_VERSION;
        this.blockPos = blockPos;
        this.baseStorageRevision = baseStorageRevision;
        this.targetStorageRevision = targetStorageRevision;
        this.deltaPayload = deltaPayload == null ? new byte[0] : deltaPayload.clone();
    }

    public UpdateChiseledBlockDeltaPacket(final FriendlyByteBuf buffer) {
        readPayload(buffer);
    }

    @Override
    public void writePayload(final FriendlyByteBuf buffer) {
        buffer.writeVarInt(payloadVersion);
        buffer.writeBlockPos(blockPos);
        buffer.writeLong(baseStorageRevision);
        buffer.writeLong(targetStorageRevision);
        buffer.writeByteArray(deltaPayload);
    }

    @Override
    public void readPayload(final FriendlyByteBuf buffer) {
        this.payloadVersion = buffer.readVarInt();
        this.blockPos = buffer.readBlockPos();
        this.baseStorageRevision = buffer.readLong();
        this.targetStorageRevision = buffer.readLong();
        this.deltaPayload = buffer.readByteArray();
    }

    @Override
    public void client() {
        final int payloadBytes = deltaPayload.length;
        DistExecutor.runWhenOn(CLIENT, () -> () -> {
            final FriendlyByteBuf payloadBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(deltaPayload));
            try {
                ClientPacketHandlers.handleChiseledBlockDeltaUpdated(
                        blockPos,
                        payloadVersion,
                        baseStorageRevision,
                        targetStorageRevision,
                        payloadBuffer,
                        payloadBytes
                );
            } finally {
                payloadBuffer.release();
            }
        });
    }

    private static byte @NotNull [] writeDeltaPayload(
            final ChiseledBlockEntity tileEntity,
            final List<DirtyRegion> dirtyRegions
    ) {
        final List<DirtyRegion> sanitizedDirtyRegions = sanitizeDirtyRegions(dirtyRegions);
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(sanitizedDirtyRegions.size());
            for (final DirtyRegion dirtyRegion : sanitizedDirtyRegions) {
                buffer.writeByte(dirtyRegion.minX());
                buffer.writeByte(dirtyRegion.minY());
                buffer.writeByte(dirtyRegion.minZ());
                buffer.writeByte(dirtyRegion.maxX());
                buffer.writeByte(dirtyRegion.maxY());
                buffer.writeByte(dirtyRegion.maxZ());

                for (int x = dirtyRegion.minX(); x <= dirtyRegion.maxX(); x++) {
                    for (int y = dirtyRegion.minY(); y <= dirtyRegion.maxY(); y++) {
                        for (int z = dirtyRegion.minZ(); z <= dirtyRegion.maxZ(); z++) {
                            tileEntity.getBlockInformationForDeltaSync(x, y, z).serializeInto(buffer);
                        }
                    }
                }
            }

            return PacketBufferUtils.copyWrittenBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static List<DirtyRegion> sanitizeDirtyRegions(final List<DirtyRegion> dirtyRegions) {
        if (dirtyRegions == null || dirtyRegions.isEmpty()) {
            return List.of();
        }

        final List<DirtyRegion> sanitized = new ArrayList<>(dirtyRegions.size());
        for (final DirtyRegion dirtyRegion : dirtyRegions) {
            if (dirtyRegion != null) {
                sanitized.add(dirtyRegion);
            }
        }

        return List.copyOf(sanitized);
    }
}
