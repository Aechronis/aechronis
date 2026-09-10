package net.aechronis.logger.objects

import net.aechronis.logger.utils.EntityStateCodec
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Instance
import java.util.concurrent.CompletableFuture

private data class InventoryWrite(
    val change: RollbackChange,
    val slot: Int,
    val before: net.minestom.server.item.ItemStack,
    val after: net.minestom.server.item.ItemStack,
)

/** Applies storage, inventory, and entity changes while recording successful mutations. */
internal class RollbackExternalChanges(
    private val lifecycle: RollbackLifecycle,
) {
    fun applyStorage(
        changes: List<RollbackChange>,
        reverse: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Void> {
        var chain = CompletableFuture.completedFuture<Void>(null)
        for (change in changes) {
            chain =
                chain.thenCompose {
                    if (lifecycle.isClosing) {
                        return@thenCompose CompletableFuture.failedFuture(
                            IllegalStateException("rollback service is closing"),
                        )
                    }
                    val source =
                        change.storageSource
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage source is missing"))
                    val storageId =
                        change.storageId
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage ID is missing"))
                    val action =
                        change.storageAction
                            ?: return@thenCompose CompletableFuture.failedFuture(
                                IllegalStateException("storage action is missing"),
                            )
                    val itemData =
                        change.itemData
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage item is missing"))
                    val amount =
                        change.amount
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage amount is missing"))
                    val adapter =
                        StorageRollbackAdapters.adapter(source)
                            ?: return@thenCompose CompletableFuture.failedFuture(
                                IllegalStateException("no rollback adapter for storage source '$source'"),
                            )
                    val effectiveAction = if (reverse) action.inverse() else action
                    lifecycle
                        .trackedStage(
                            start = {
                                adapter.apply(
                                    storageId,
                                    change.storageSlot,
                                    ItemCodec.decodeItem(itemData),
                                    amount,
                                    effectiveAction,
                                )
                            },
                        ).thenApply { success ->
                            check(success) { "storage '$storageId' rejected $effectiveAction" }
                            applied += change
                            null
                        }
                }
        }
        return chain
    }

    fun applyInventory(
        changes: List<RollbackChange>,
        reverse: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Void> {
        val writes =
            changes.map { change ->
                val playerUuid = change.inventoryPlayerUuid ?: error("inventory player is missing")
                val slot = change.inventorySlot ?: error("inventory slot is missing")
                val beforeData = if (reverse) change.afterItemData else change.beforeItemData
                val afterData = if (reverse) change.beforeItemData else change.afterItemData
                InventoryWrite(
                    change = change,
                    slot = slot,
                    before = beforeData?.let(ItemCodec::decodeItem) ?: error("inventory before item is missing"),
                    after = afterData?.let(ItemCodec::decodeItem) ?: error("inventory after item is missing"),
                ) to playerUuid
            }
        var chain = CompletableFuture.completedFuture<Void>(null)
        for ((playerUuid, playerWrites) in writes.groupBy({ it.second }, { it.first })) {
            chain =
                chain.thenCompose {
                    if (lifecycle.isClosing) {
                        return@thenCompose CompletableFuture.failedFuture(
                            IllegalStateException("rollback service is closing"),
                        )
                    }
                    val player =
                        MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(playerUuid)
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("player $playerUuid must be online"))
                    val future = CompletableFuture<Void>()
                    ModuleScheduler.scheduleNextTick {
                        if (lifecycle.isClosing) {
                            future.completeExceptionally(IllegalStateException("rollback service is closing"))
                            return@scheduleNextTick
                        }
                        try {
                            val simulated = mutableMapOf<Int, net.minestom.server.item.ItemStack>()
                            for (write in playerWrites) {
                                val current = simulated[write.slot] ?: player.inventory.getItemStack(write.slot)
                                check(current == write.before) { "inventory slot ${write.slot} changed after preview" }
                                simulated[write.slot] = write.after
                            }
                            RollbackMutationGuard.suppressInventory(playerUuid) {
                                for (write in playerWrites) player.inventory.setItemStack(write.slot, write.after)
                            }
                            applied += playerWrites.map(InventoryWrite::change)
                            future.complete(null)
                        } catch (exception: Exception) {
                            future.completeExceptionally(exception)
                        }
                    }
                    future
                }
        }
        return chain
    }

    fun applyEntities(
        instance: Instance,
        changes: List<RollbackChange>,
        reverse: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Void> {
        var chain = CompletableFuture.completedFuture<Void>(null)
        for (change in changes) {
            chain =
                chain.thenCompose {
                    if (lifecycle.isClosing) {
                        return@thenCompose CompletableFuture.failedFuture(
                            IllegalStateException("rollback service is closing"),
                        )
                    }
                    val entityUuid =
                        change.entityUuid
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity UUID is missing"))
                    val action =
                        change.entityAction
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity action is missing"))
                    val effectiveAction = if (reverse) action.inverse() else action
                    if (effectiveAction == EntityChangeAction.DESPAWN) {
                        val entity =
                            instance.getEntityByUuid(entityUuid)
                                ?: return@thenCompose CompletableFuture.failedFuture(
                                    IllegalStateException("entity $entityUuid no longer exists"),
                                )
                        RollbackMutationGuard.suppressEntity(entityUuid) { entity.remove() }
                        applied += change
                        CompletableFuture.completedFuture(null)
                    } else {
                        if (instance.getEntityByUuid(entityUuid) != null) {
                            return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity $entityUuid already exists"))
                        }
                        val typeKey =
                            change.entityType
                                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity type is missing"))
                        val type =
                            net.minestom.server.entity.EntityType
                                .fromKey(typeKey)
                                ?: return@thenCompose CompletableFuture.failedFuture(
                                    IllegalStateException("unknown entity type '$typeKey'"),
                                )
                        val position =
                            change.entityPosition
                                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity position is missing"))
                        val state = EntityStateCodec.decode(change.entityTagData)
                        val entity = EntityStateCodec.create(type, entityUuid, state)
                        change.entityVelocity?.let(entity::setVelocity)
                        EntityStateCodec.restore(entity, state)
                        RollbackMutationGuard.beginEntity(entityUuid)
                        lifecycle
                            .trackedStage(
                                start = { entity.setInstance(instance, position) },
                                onSettled = { _, _ -> RollbackMutationGuard.endEntity(entityUuid) },
                            ).thenApply {
                                applied += change
                                null
                            }
                    }
                }
        }
        return chain
    }
}
