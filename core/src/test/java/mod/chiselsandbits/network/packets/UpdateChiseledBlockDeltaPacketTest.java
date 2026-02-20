package mod.chiselsandbits.network.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpdateChiseledBlockDeltaPacketTest {

    @Test
    void writesAndReadsDeltaPayloadMetadata() {
        final BlockPos blockPos = new BlockPos(12, 34, 56);
        final long baseRevision = 101L;
        final long targetRevision = 109L;
        final byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04};

        final UpdateChiseledBlockDeltaPacket packet =
                new UpdateChiseledBlockDeltaPacket(blockPos, baseRevision, targetRevision, payload);

        final FriendlyByteBuf outbound = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.writePayload(outbound);

            final FriendlyByteBuf verifyBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(PacketBufferUtils.copyWrittenBytes(outbound)));
            try {
                assertEquals(UpdateChiseledBlockDeltaPacket.DELTA_FORMAT_VERSION, verifyBuffer.readVarInt());
                assertEquals(blockPos, verifyBuffer.readBlockPos());
                assertEquals(baseRevision, verifyBuffer.readLong());
                assertEquals(targetRevision, verifyBuffer.readLong());
                assertArrayEquals(payload, verifyBuffer.readByteArray());
                assertFalse(verifyBuffer.isReadable());
            } finally {
                verifyBuffer.release();
            }

            final FriendlyByteBuf inbound = new FriendlyByteBuf(Unpooled.wrappedBuffer(PacketBufferUtils.copyWrittenBytes(outbound)));
            try {
                final UpdateChiseledBlockDeltaPacket decoded = new UpdateChiseledBlockDeltaPacket(inbound);
                final FriendlyByteBuf rewritten = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    decoded.writePayload(rewritten);
                    assertArrayEquals(
                            PacketBufferUtils.copyWrittenBytes(outbound),
                            PacketBufferUtils.copyWrittenBytes(rewritten)
                    );
                } finally {
                    rewritten.release();
                }
            } finally {
                inbound.release();
            }
        } finally {
            outbound.release();
        }
    }
}
