package mod.chiselsandbits.network.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketBufferUtilsTest {

    @Test
    void copiesOnlyBytesWrittenToBuffer() {
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(0x11);
            buffer.writeByte(0x22);
            buffer.writeByte(0x33);

            final byte[] copied = PacketBufferUtils.copyWrittenBytes(buffer);

            assertArrayEquals(new byte[]{0x11, 0x22, 0x33}, copied);
            assertEquals(3, copied.length);
        } finally {
            buffer.release();
        }
    }

    @Test
    void returnsDetachedCopy() {
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(0x2A);

            final byte[] copied = PacketBufferUtils.copyWrittenBytes(buffer);
            buffer.writeByte(0x7F);

            assertArrayEquals(new byte[]{0x2A}, copied);
            assertEquals(1, copied.length);
        } finally {
            buffer.release();
        }
    }
}
