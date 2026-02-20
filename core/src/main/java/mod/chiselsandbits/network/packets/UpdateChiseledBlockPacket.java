package mod.chiselsandbits.network.packets;

import com.communi.suggestu.scena.core.dist.DistExecutor;
import io.netty.buffer.Unpooled;
import mod.chiselsandbits.ChiselsAndBits;
import mod.chiselsandbits.api.block.entity.INetworkUpdatableEntity;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.network.handlers.ClientPacketHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import static com.communi.suggestu.scena.core.dist.Dist.CLIENT;

public final class UpdateChiseledBlockPacket extends ModPacket
{
    public static final int FULL_FORMAT_VERSION = 1;

    private boolean fullSyncRequest;
    private int payloadVersion;
    private BlockPos blockPos;
    private long storageRevision;
    private byte[] data;

    public UpdateChiseledBlockPacket(final INetworkUpdatableEntity tileEntity)
    {
        this(tileEntity, resolveRevisionFrom(tileEntity));
    }

    public UpdateChiseledBlockPacket(final INetworkUpdatableEntity tileEntity, final long storageRevision)
    {
        this.fullSyncRequest = false;
        this.payloadVersion = FULL_FORMAT_VERSION;
        this.blockPos = tileEntity.getBlockPos();
        this.storageRevision = storageRevision;
        this.data = writeBlockEntity(tileEntity);
    }

    public UpdateChiseledBlockPacket(final BlockPos blockPos)
    {
        this.fullSyncRequest = true;
        this.payloadVersion = FULL_FORMAT_VERSION;
        this.blockPos = blockPos;
        this.storageRevision = -1L;
        this.data = new byte[0];
    }

    private static long resolveRevisionFrom(final INetworkUpdatableEntity tileEntity) {
        if (tileEntity instanceof ChiseledBlockEntity chiseledBlockEntity) {
            return chiseledBlockEntity.getShapeIdentifierRevision();
        }

        return -1L;
    }

    private static byte @NotNull [] writeBlockEntity(final INetworkUpdatableEntity tileEntity) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            tileEntity.serializeInto(buf);
            return PacketBufferUtils.copyWrittenBytes(buf);
        } finally {
            buf.release();
        }
    }

    public UpdateChiseledBlockPacket(final FriendlyByteBuf buffer)
    {
        readPayload(buffer);
    }

    @Override
    public void writePayload(final FriendlyByteBuf buffer)
    {
        buffer.writeBoolean(fullSyncRequest);
        buffer.writeBlockPos(blockPos);

        if (fullSyncRequest) {
            return;
        }

        buffer.writeVarInt(payloadVersion);
        buffer.writeLong(storageRevision);
        buffer.writeByteArray(data);
    }

    @Override
    public void readPayload(final FriendlyByteBuf buffer)
    {
        this.fullSyncRequest = buffer.readBoolean();
        this.blockPos = buffer.readBlockPos();

        if (fullSyncRequest) {
            this.payloadVersion = FULL_FORMAT_VERSION;
            this.storageRevision = -1L;
            this.data = new byte[0];
            return;
        }

        this.payloadVersion = buffer.readVarInt();
        this.storageRevision = buffer.readLong();
        this.data = buffer.readByteArray();
    }

    @Override
    public void client()
    {
        if (fullSyncRequest) {
            return;
        }

        final int payloadBytes = data.length;
        DistExecutor.runWhenOn(CLIENT, () -> () -> {
            final FriendlyByteBuf payloadBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            try {
                ClientPacketHandlers.handleChiseledBlockUpdated(blockPos, payloadVersion, storageRevision, payloadBuffer, payloadBytes);
            } finally {
                payloadBuffer.release();
            }
        });
    }

    @Override
    public void server(final ServerPlayer playerEntity)
    {
        if (!fullSyncRequest || playerEntity.level() == null || !playerEntity.level().isLoaded(blockPos)) {
            return;
        }

        final BlockEntity blockEntity = playerEntity.level().getBlockEntity(blockPos);
        if (!(blockEntity instanceof INetworkUpdatableEntity networkUpdatableEntity)) {
            return;
        }

        ChiselsAndBits.getInstance().getNetworkChannel().sendToPlayer(new UpdateChiseledBlockPacket(networkUpdatableEntity), playerEntity);
    }
}
