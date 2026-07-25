package net.aechronis.logger.listeners

import net.aechronis.logger.Logger
import net.aechronis.logger.commands.LOOKUP_USAGE
import net.aechronis.logger.objects.FeatureLogEntry
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.item.ItemStack
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

object LootListener {
    fun init() {
        val postNode = EventNode.all("logger-loot-post").setPriority(100)
        MinecraftServer.getGlobalEventHandler().addChild(postNode)
        postNode.addListener(
            EventListener
                .builder(PickupItemEvent::class.java)
                .ignoreCancelled(false)
                .handler { event ->
                    recordPickup(event)
                }.build(),
        )
        postNode.addListener(
            EventListener
                .builder(ItemDropEvent::class.java)
                .ignoreCancelled(false)
                .handler { event ->
                    recordDrop(event)
                }.build(),
        )
    }

    internal fun recordPickup(event: PickupItemEvent): CompletableFuture<Void>? {
        if (event.isCancelled) return null
        val player = event.livingEntity as? Player ?: return null
        val itemEntity = event.itemEntity
        val instance = itemEntity.instance ?: player.instance ?: return null
        return record(player, event.itemStack, "pickup", itemEntity.position, instance.uuid, itemEntity.uuid)
    }

    internal fun recordDrop(event: ItemDropEvent): CompletableFuture<Void>? {
        if (event.isCancelled) return null
        val player = event.player
        val instance = player.instance ?: return null
        return record(player, event.itemStack, "drop", player.position, instance.uuid)
    }

    private fun record(
        player: Player,
        item: ItemStack,
        action: String,
        position: net.minestom.server.coordinate.Point,
        instanceUuid: UUID,
        entityUuid: UUID? = null,
    ): CompletableFuture<Void> {
        val material = item.material().key().asString()
        val data =
            mutableMapOf(
                "material" to material,
                "amount" to item.amount().toString(),
                "item" to Base64.getEncoder().encodeToString(ItemCodec.encodeItem(item)),
                "instance_uuid" to instanceUuid.toString(),
            )
        entityUuid?.let { data["entity_uuid"] = it.toString() }
        return Logger.log(
            FeatureLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = player.uuid,
                playerName = player.username,
                source = LogMetadata.LOOT,
                action = action,
                summary = "${if (action == "pickup") "picked up" else "dropped"} ${item.amount()} $material",
                x = position.blockX(),
                y = position.blockY(),
                z = position.blockZ(),
                data = data,
            ),
        )
    }
}
