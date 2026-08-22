package net.craftingstore.core;

import net.craftingstore.core.exceptions.CraftingStoreApiException;
import net.craftingstore.core.http.CraftingStoreCachedAPI;
import net.craftingstore.core.jobs.ExecuteDonationsJob;
import net.craftingstore.core.logging.CraftingStoreLogger;
import net.craftingstore.core.models.api.Root;
import net.craftingstore.core.models.api.misc.CraftingStoreInformation;
import net.craftingstore.core.models.api.misc.UpdateInformation;
import net.craftingstore.core.models.donation.Donation;
import net.craftingstore.core.provider.ProviderSelector;
import net.craftingstore.core.runner.DonationRunner;
import net.craftingstore.core.scheduler.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** The platform-independent CraftingStore runtime. */
public class CraftingStore {
    private final CraftingStorePlugin plugin;
    private final CraftingStoreAPI api;
    private final ProviderSelector selector;
    private final DonationRunner donationRunner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(namedFactory("craftingstore-core"));
    private final Map<Integer, Donation> pendingDonations = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean recurringJobsRegistered = new AtomicBoolean();
    private volatile CraftingStoreInformation information;
    private volatile boolean enabled;
    public final String ADMIN_PERMISSION = "craftingstore.admin";

    public CraftingStore(CraftingStorePlugin implementation) {
        this.plugin = implementation;
        this.api = new CraftingStoreCachedAPI(this);
        this.selector = new ProviderSelector(this);
        this.donationRunner = new DonationRunner(this);

        // Register jobs even when the first start has no key. This makes setting a key
        // later behave like a normal reload rather than requiring a restart.
        registerRecurringJobsOnce();
        this.getImplementation().runAsyncTask(() -> {
            try {
                reload().get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException exception) {
                getLogger().error("CraftingStore startup failed: " + exception.getCause());
            }
        });
    }

    private static ThreadFactory namedFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void registerRecurringJobsOnce() {
        if (!recurringJobsRegistered.compareAndSet(false, true)) return;
        plugin.registerRunnable(new DonationChecker(this), 10, 5 * 60);
        plugin.registerRunnable(new ProviderChecker(this), 60, 60);
        plugin.registerRunnable(new InventoryRenewer(this), 20 * 60, 20 * 60);
        plugin.registerRunnable(new APICacheRenewer(this), 10, 60 * 25);
        plugin.registerRunnable(new InformationUpdater(this), 24 * 60 * 60, 24 * 60 * 60);
    }

    public CraftingStorePlugin getImplementation() { return plugin; }
    public CraftingStoreAPI getApi() { return api; }
    public ProviderSelector getProviderSelector() { return selector; }
    public CraftingStoreLogger getLogger() { return plugin.getLogger(); }

    public Future<Boolean> reload() {
        if (shutdown.get()) return CompletableFuture.completedFuture(false);
        api.setToken(plugin.getToken());
        return executor.submit(() -> {
            if (shutdown.get()) return false;
            try {
                String token = api.getToken();
                if (token == null || token.trim().isEmpty()) {
                    getLogger().error(String.format("API key not set in the config. You need to set the correct api key using /%s key <key>.", plugin.getConfiguration().getMainCommands()[0]));
                    setEnabled(false);
                    return false;
                }
                Root keyResult = api.checkKey().get();
                if (keyResult == null || !keyResult.isSuccess()) {
                    getLogger().error("API key is invalid. The plugin will not work.");
                    setEnabled(false);
                    return false;
                }
                information = api.getInformation().get();
                if (information != null && information.getUpdateInformation() != null) {
                    UpdateInformation update = information.getUpdateInformation();
                    getLogger().info(update.getMessage());
                    if (update.shouldDisable()) {
                        getLogger().error("Plugin will be disabled until you install the latest update.");
                        setEnabled(false);
                        return false;
                    }
                }
                selector.disconnect();
                if (information == null || information.getProviders() == null || information.getProviders().length == 0) {
                    getLogger().error("CraftingStore returned no usable providers.");
                    setEnabled(false);
                    return false;
                }
                selector.setProviders(information.getProviders());
                setEnabled(true);
                selector.selectProvider();
                // The GUI refresh is retained for compatibility, but a GUI failure must
                // not prevent donation queue polling.
                new InventoryRenewer(this).run();
                getLogger().debug("Startup complete");
                return true;
            } catch (CraftingStoreApiException | InterruptedException | ExecutionException exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                getLogger().error("CraftingStore reload failed: " + exception.getMessage());
                setEnabled(false);
                return false;
            }
        });
    }

    public void executeQueue() { if (!shutdown.get()) donationRunner.runDonations(); }
    public boolean isEnabled() { return enabled && !shutdown.get(); }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) selector.disconnect();
    }

    public CraftingStoreInformation getInformation() { return information; }
    public Map<Integer, Donation> getPendingDonations() { return pendingDonations; }
    public DonationRunner getDonationRunner() { return donationRunner; }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        enabled = false;
        selector.disconnect();
        donationRunner.shutdown();
        api.shutdown();
        executor.shutdownNow();
    }

}
