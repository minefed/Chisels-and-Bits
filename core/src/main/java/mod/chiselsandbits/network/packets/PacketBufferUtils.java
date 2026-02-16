package mod.chiselsandbits.network.packets;

import net.minecraft.network.FriendlyByteBuf;

public final class PacketBufferUtils {

    private PacketBufferUtils() {
        throw new IllegalStateException("Cannot instantiate utility class");
    }

    public static byte[] copyWrittenBytes(final FriendlyByteBuf buffer) {
        final int writtenByteCount = buffer.writerIndex();
        final byte[] copiedBytes = new byte[writtenByteCount];
        buffer.getBytes(0, copiedBytes);
        return copiedBytes;
    }
}
