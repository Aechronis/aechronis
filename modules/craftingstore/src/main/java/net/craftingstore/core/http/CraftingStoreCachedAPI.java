package net.craftingstore.core.http;

import net.craftingstore.core.CraftingStore;
import net.craftingstore.core.exceptions.CraftingStoreApiException;
import net.craftingstore.core.models.api.ApiInventory;
import net.craftingstore.core.models.api.ApiPayment;
import net.craftingstore.core.models.api.ApiTopDonator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class CraftingStoreCachedAPI extends CraftingStoreAPIImpl {

    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    public CraftingStoreCachedAPI(CraftingStore instance) {
        super(instance);
    }

    @Override
    public Future<ApiInventory> getGUI() throws CraftingStoreApiException {
        return executor.submit(() -> {
            String key = "plugin/inventory";
            if (!cache.containsKey(key)) {
                ApiInventory value = super.getGUI().get();
                if (value != null) cache.putIfAbsent(key, value);
            }
            return (ApiInventory) cache.get(key);
        });
    }

    @Override
    public Future<ApiTopDonator[]> getTopDonators() throws CraftingStoreApiException {
        return executor.submit(() -> {
            String key = "buyers/top";
            if (cache.containsKey(key)) {
                ApiTopDonator[] value = (ApiTopDonator[]) cache.get(key);
                return value == null ? null : value.clone();
            }
            return null;
        });
    }

    @Override
    public Future<ApiPayment[]> getPayments() throws CraftingStoreApiException {
        return executor.submit(() -> {
            String key = "buyers/recent";
            if (cache.containsKey(key)) {
                ApiPayment[] value = (ApiPayment[]) cache.get(key);
                return value == null ? null : value.clone();
            }
            return null;
        });
    }

    public void refreshGUICache() throws CraftingStoreApiException, ExecutionException, InterruptedException {
        ApiInventory value = super.getGUI().get();
        if (value != null) cache.put("plugin/inventory", value);
    }

    public void refreshTopDonatorsCache() throws CraftingStoreApiException, ExecutionException, InterruptedException {
        ApiTopDonator[] value = super.getTopDonators().get();
        if (value != null) cache.put("buyers/top", value.clone());
    }

    public void refreshPaymentsCache() throws CraftingStoreApiException, ExecutionException, InterruptedException {
        ApiPayment[] value = super.getPayments().get();
        if (value != null) cache.put("buyers/recent", value.clone());
    }
}
