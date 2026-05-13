package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.block.Block

object StoragePlaceListener {
    fun onPlace(event: PlayerBlockPlaceEvent) {
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        val instance = player.instance ?: return
        val placedItem = player.getItemInHand(event.hand)
        val storedTag = Storage.extractContentsTag(placedItem)

        val pos = event.blockPosition.asVec()
        val key = Storage.keyFor(instance, pos)

        val contents =
            if (storedTag != null) {
                StorageDeserializer.deserialize(storedTag)
            } else {
                StorageContents()
            }

        Storage.register(key, contents)
        Storage.save(key)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, StoragePlaceListener::onPlace)
    }
}
