package net.aechronis.logger.objects

import net.minestom.server.item.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

fun interface StorageRollbackAdapter {
    fun apply(
        storageId: String,
        slot: Int?,
        item: ItemStack,
        amount: Int,
        action: StorageChangeAction,
    ): CompletableFuture<Boolean>
}

object StorageRollbackAdapters {
    private val adapters = ConcurrentHashMap<String, StorageRollbackAdapter>()

    fun register(
        source: String,
        adapter: StorageRollbackAdapter,
    ) {
        adapters[source.lowercase()] = adapter
    }

    fun unregister(source: String) {
        adapters.remove(source.lowercase())
    }

    fun adapter(source: String): StorageRollbackAdapter? = adapters[source.lowercase()]
}
