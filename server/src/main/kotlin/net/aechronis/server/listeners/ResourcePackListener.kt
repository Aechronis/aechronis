package net.aechronis.server.listeners

import net.aechronis.server.Server
import net.aechronis.server.resourcepack.ResourcePackServer
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent

/** Always serves the embedded Aechronis pack, independently of an iteration module. */
object ResourcePackListener {
    private val eventNode: EventNode<Event> = EventNode.all("aechronis-resource-pack").setPriority(Int.MAX_VALUE)
    private var initialized = false

    fun initialize(resourcePackServer: ResourcePackServer) {
        check(!initialized) { "Resource-pack listener is already initialized" }
        initialized = true
        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            // The permanent server must remain joinable even when an iteration module is absent.
            event.spawningInstance = Server.instance
            event.player.sendResourcePacks(
                ResourcePackRequest
                    .resourcePackRequest()
                    .packs(resourcePackServer.resourcePackInfo(event.player.playerConnection.serverAddress))
                    .prompt(Component.text("A resource pack is required to play"))
                    .required(true)
                    .build(),
            )
        }
        Server.eventNode.addChild(eventNode)
    }
}
