package mod.chiselsandbits.network.packets;

import com.communi.suggestu.scena.core.dist.DistExecutor;
import io.netty.buffer.Unpooled;
import mod.chiselsandbits.api.block.entity.INetworkUpdatableEntity;
import mod.chiselsandbits.network.handlers.ClientPacketHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import static com.communi.suggestu.scena.core.dist.Dist.CLIENT;

public final class UpdateChiseledBlockPacket extends ModPacket
{

    private BlockPos blockPos;
    private byte[] data;

    public UpdateChiseledBlockPacket(final INetworkUpdatableEntity tileEntity)
    {
        this.blockPos = tileEntity.getBlockPos();
        this.data = writeBlockEntity(tileEntity);
    }

    private static byte @NotNull [] writeBlockEntity(INetworkUpdatableEntity tileEntity) {
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
        buffer.writeBlockPos(blockPos);
        buffer.writeByteArray(data);
    }

    @Override
    public void readPayload(final FriendlyByteBuf buffer)
    {
        this.blockPos = buffer.readBlockPos();
        this.data = buffer.readByteArray();
    }

    @Override
    public void client()
    {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        DistExecutor.runWhenOn(CLIENT, () -> () -> ClientPacketHandlers.handleChiseledBlockUpdated(blockPos, buf));
        buf.release();
    }
}
