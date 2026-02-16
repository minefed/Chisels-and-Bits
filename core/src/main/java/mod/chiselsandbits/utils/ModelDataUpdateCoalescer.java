package mod.chiselsandbits.utils;

/**
 * Coalesces bursts of update requests into one in-flight update and one queued rerun.
 */
public final class ModelDataUpdateCoalescer {

    private final Object stateLock = new Object();
    private boolean updateInFlight = false;
    private boolean rerunRequested = false;

    public void requestUpdate(final Runnable submitUpdateCallback) {
        final boolean shouldSubmitNow;
        synchronized (stateLock) {
            if (updateInFlight) {
                rerunRequested = true;
                return;
            }

            updateInFlight = true;
            shouldSubmitNow = true;
        }

        if (shouldSubmitNow) {
            submitUpdateCallback.run();
        }
    }

    public void markCompleted(final Runnable submitUpdateCallback) {
        final boolean shouldSubmitRerun;
        synchronized (stateLock) {
            if (rerunRequested) {
                rerunRequested = false;
                shouldSubmitRerun = true;
            } else {
                updateInFlight = false;
                shouldSubmitRerun = false;
            }
        }

        if (shouldSubmitRerun) {
            submitUpdateCallback.run();
        }
    }
}
