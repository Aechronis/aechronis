package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.utils.LogMetadata
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object VanillaStorage {
    private val actors = ConcurrentHashMap<Inventory, StorageActor>()
    private val generation = AtomicLong()

    fun init() {
        Logger.eventNode.addListener(InventoryPreClickEvent::class.java) { event ->
            val inventory =
                listOfNotNull(event.player.openInventory as? Inventory, event.inventory as? Inventory)
                    .firstOrNull(Storage.inventoryToKey::containsKey)
                    ?: return@addListener
            val context = StorageActor(event.player, generation.incrementAndGet())
            actors[inventory] = context
            event.player.scheduleNextTick { actors.remove(inventory, context) }
        }
        Logger.eventNode.addListener(InventoryItemChangeEvent::class.java) { event ->
            val inventory = event.inventory as? Inventory ?: return@addListener
            val key = Storage.inventoryToKey[inventory] ?: return@addListener
            val storageId = storageId(key)
            if (RollbackMutationGuard.storageSuppressed(storageId)) return@addListener
            val actor = actors[inventory]?.player
            val changes = changes(event.previousItem, event.newItem, event.slot, storageId, actor)
            Logger.storageChange.insertAllAsync(changes).exceptionally { failure ->
                println("[Logger] failed to record Vanilla barrel change: $failure")
                null
            }
        }
        StorageRollbackAdapters.register(LogMetadata.VANILLA) { storageId, slot, item, amount, action ->
            apply(storageId, slot, item, amount, action)
        }
    }

    fun applyBlockTransition(
        instance: Instance,
        x: Int,
        y: Int,
        z: Int,
        target: Block,
    ): Boolean {
        setBlockAndReconcile(instance, Vec(x.toDouble(), y.toDouble(), z.toDouble()), target)
        return true
    }

    private fun setBlockAndReconcile(
        instance: Instance,
        position: Vec,
        target: Block,
    ) {
        val key = Storage.keyFor(instance, position)
        Storage.barrels[key]
            ?.inventory
            ?.viewers
            ?.toList()
            ?.forEach(Player::closeInventory)
        Storage.remove(key)
        if (target.compare(Block.BARREL)) {
            val contents = StorageDeserializer.deserialize(target.nbtOrEmpty())
            instance.setBlock(position, Storage.withContents(target, contents), false)
            Storage.register(key, contents)
        } else {
            instance.setBlock(position, target, false)
        }
    }

    fun storageId(key: BlockKey): String = storageId(key.instance.uuid, key.pos.blockX(), key.pos.blockY(), key.pos.blockZ())

    internal fun storageId(
        instanceUuid: UUID,
        x: Int,
        y: Int,
        z: Int,
    ): String = "$instanceUuid:$x:$y:$z"

    internal fun snapshotBlock(
        instance: Instance,
        x: Int,
        y: Int,
        z: Int,
    ): Block {
        val position = Vec(x.toDouble(), y.toDouble(), z.toDouble())
        val block = instance.getBlock(position)
        if (!block.compare(Block.BARREL)) return block
        val contents = Storage.barrels[Storage.keyFor(instance, position)] ?: return block
        return Storage.withContents(block, contents)
    }

    internal fun projectBlock(
        block: Block,
        slot: Int?,
        item: ItemStack,
        amount: Int,
        action: StorageChangeAction,
    ): Block? {
        if (!block.compare(Block.BARREL) || slot == null || amount <= 0) return null
        return runCatching {
            val contents = StorageDeserializer.deserialize(block.nbtOrEmpty())
            if (slot !in 0 until contents.inventory.size) return@runCatching null
            val current = contents.inventory.getItemStack(slot)
            val target = changedItem(current, item, amount, action) ?: return@runCatching null
            contents.inventory.setItemStack(slot, target)
            Storage.withContents(block, contents)
        }.getOrNull()
    }

    private fun changes(
        oldItem: ItemStack,
        newItem: ItemStack,
        slot: Int,
        storageId: String,
        actor: Player?,
    ): List<StorageChange> {
        val timestamp = System.currentTimeMillis()

        fun change(
            action: StorageChangeAction,
            item: ItemStack,
            amount: Int,
        ) = StorageChange(
            timestamp = timestamp,
            storageId = storageId,
            action = action,
            item = item.withAmount(1),
            amount = amount,
            slot = slot,
            playerUuid = actor?.uuid,
            playerName = actor?.username,
            source = LogMetadata.VANILLA,
            origin = LogMetadata.VANILLA,
        )

        return when {
            oldItem.isAir && !newItem.isAir -> {
                listOf(change(StorageChangeAction.DEPOSIT, newItem, newItem.amount()))
            }

            !oldItem.isAir && newItem.isAir -> {
                listOf(change(StorageChangeAction.WITHDRAW, oldItem, oldItem.amount()))
            }

            oldItem.isSimilar(newItem) && newItem.amount() > oldItem.amount() -> {
                listOf(change(StorageChangeAction.DEPOSIT, newItem, newItem.amount() - oldItem.amount()))
            }

            oldItem.isSimilar(newItem) && newItem.amount() < oldItem.amount() -> {
                listOf(change(StorageChangeAction.WITHDRAW, oldItem, oldItem.amount() - newItem.amount()))
            }

            oldItem == newItem -> {
                emptyList()
            }

            else -> {
                listOf(
                    change(StorageChangeAction.WITHDRAW, oldItem, oldItem.amount()),
                    change(StorageChangeAction.DEPOSIT, newItem, newItem.amount()),
                )
            }
        }
    }

    private fun apply(
        storageId: String,
        slot: Int?,
        item: ItemStack,
        amount: Int,
        action: StorageChangeAction,
    ): CompletableFuture<Boolean> {
        val location = parseStorageId(storageId) ?: return CompletableFuture.completedFuture(false)
        val exactSlot = slot ?: return CompletableFuture.completedFuture(false)
        val instance =
            MinecraftServer.getInstanceManager().getInstance(location.first)
                ?: return CompletableFuture.completedFuture(false)
        val result = CompletableFuture<Boolean>()
        instance.scheduleNextTick {
            try {
                val position =
                    Vec(
                        location.second.toDouble(),
                        location.third.toDouble(),
                        location.fourth.toDouble(),
                    )
                if (!instance.getBlock(position).compare(Block.BARREL)) {
                    result.complete(false)
                    return@scheduleNextTick
                }
                val key = Storage.keyFor(instance, position)
                val inventory = Storage.loadOrCreate(key).inventory
                if (exactSlot !in 0 until inventory.size || amount <= 0) {
                    result.complete(false)
                    return@scheduleNextTick
                }
                val current = inventory.getItemStack(exactSlot)
                val target = changedItem(current, item, amount, action)
                if (target == null) {
                    result.complete(false)
                    return@scheduleNextTick
                }
                RollbackMutationGuard.suppressStorage(storageId) {
                    inventory.setItemStack(exactSlot, target)
                    Storage.save(key)
                }
                result.complete(true)
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    private fun changedItem(
        current: ItemStack,
        item: ItemStack,
        amount: Int,
        action: StorageChangeAction,
    ): ItemStack? =
        when (action) {
            StorageChangeAction.DEPOSIT -> {
                if (!current.isAir && !current.isSimilar(item)) {
                    null
                } else {
                    val newAmount = (if (current.isAir) 0 else current.amount()) + amount
                    if (newAmount > item.maxStackSize()) null else item.withAmount(newAmount)
                }
            }

            StorageChangeAction.WITHDRAW -> {
                if (current.isAir || !current.isSimilar(item) || current.amount() < amount) {
                    null
                } else if (current.amount() == amount) {
                    ItemStack.AIR
                } else {
                    current.withAmount(current.amount() - amount)
                }
            }
        }

    internal fun parseStorageId(storageId: String): StorageLocation? {
        val parts = storageId.split(':')
        if (parts.size != 4) return null
        return runCatching {
            StorageLocation(UUID.fromString(parts[0]), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        }.getOrNull()
    }
}
