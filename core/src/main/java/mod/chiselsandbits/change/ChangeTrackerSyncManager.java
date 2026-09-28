package mod.chiselsandbits.change;

import mod.chiselsandbits.ChiselsAndBits;
import mod.chiselsandbits.network.packets.ChangeTrackerUpdatedPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ChangeTrackerSyncManager {
    private static final ChangeTrackerSyncManager INSTANCE = new ChangeTrackerSyncManager();

    public static ChangeTrackerSyncManager getInstance() {
        return INSTANCE;
    }

    // Trackers are serialized when flushing, so repeated entries within one flush would produce identical packets.
    private final Set<Entry> entries = new LinkedHashSet<>();

    private ChangeTrackerSyncManager() {
    }

    private record Entry(ChangeTracker tracker, ServerPlayer serverPlayer) {
    }

    public void add(final ChangeTracker tracker, final ServerPlayer serverPlayer) {
        final Entry entry = new Entry(tracker, serverPlayer);
        // Move a repeated entry to its latest position, so every player still receives the same packet last.
        entries.remove(entry);
        entries.add(entry);
    }

    public void sync() {
        entries.forEach(e -> {
            ChiselsAndBits.getInstance().getNetworkChannel().sendToPlayer(
                    new ChangeTrackerUpdatedPacket(e.tracker().serializeNBT()),
                    e.serverPlayer()
            );
        });
        entries.clear();
    }
}
