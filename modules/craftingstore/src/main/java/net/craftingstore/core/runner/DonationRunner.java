package net.craftingstore.core.runner;

import net.craftingstore.core.CraftingStore;
import net.craftingstore.core.exceptions.CraftingStoreApiException;
import net.craftingstore.core.jobs.ExecuteDonationsJob;
import net.craftingstore.core.models.donation.Donation;
import net.craftingstore.core.util.ArrayUtil;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes polling, websocket and join-triggered donation execution. */
public class DonationRunner {
    protected final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "craftingstore-donations");
        thread.setDaemon(true);
        return thread;
    });
    protected final CraftingStore craftingStore;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public DonationRunner(CraftingStore craftingStore) { this.craftingStore = craftingStore; }

    public void runDonations() {
        if (shutdown.get()) return;
        craftingStore.getLogger().debug("Scheduling new donation runner job.");
        try {
            executor.submit(() -> {
                try {
                    Donation[] donationQueue = craftingStore.getApi().getDonationQueue().get();
                    if (donationQueue == null) return;
                    craftingStore.getLogger().debug(String.format("Found %d donations in queue.", donationQueue.length));
                    Donation[][] chunks = ArrayUtil.splitArray(donationQueue, ExecuteDonationsJob.CHUNK_SIZE);
                    for (Donation[] chunk : chunks) {
                        if (shutdown.get()) return;
                        new ExecuteDonationsJob(craftingStore, chunk);
                    }
                } catch (CraftingStoreApiException | InterruptedException | ExecutionException exception) {
                    if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (craftingStore.getLogger().isDebugging()) {
                        craftingStore.getLogger().error("Donation retrieval failed: " + exception);
                    } else {
                        craftingStore.getLogger().info("Failed to retrieve donation. The plugin will retry later.");
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown raced with a websocket or scheduled poll.
        }
    }

    /** Retries the pending entries for one player on this same serialized executor. */
    public void runPendingDonations(String username) {
        if (shutdown.get()) return;
        try {
            executor.submit(() -> {
                Donation[] pending = craftingStore.getPendingDonations().values().stream()
                    .filter(donation -> donation.getPlayer() != null
                        && donation.getPlayer().getUsername() != null
                        && donation.getPlayer().getUsername().equalsIgnoreCase(username))
                    .toArray(Donation[]::new);
                if (pending.length == 0 || shutdown.get()) return;
                try {
                    new ExecuteDonationsJob(craftingStore, pending);
                } catch (CraftingStoreApiException exception) {
                    craftingStore.getLogger().error("Pending donation execution failed: " + exception.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) executor.shutdownNow();
    }

    public ExecutorService getExecutor() { return executor; }
}
