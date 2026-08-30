package net.aechronis.server.listeners

import net.aechronis.server.Server
import net.aechronis.server.resourcepack.ResourcePackServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent

/** Applies the complete pack stack discovered from the active module generation. */
object ResourcePackListener {
    private val eventNode: EventNode<Event> = EventNode.all("aechronis-resource-pack").setPriority(Int.MAX_VALUE)
    private var initialized = false

    fun initialize(resourcePackServer: ResourcePackServer) {
        check(!initialized) { "Resource-pack listener is already initialized" }
        initialized = true
        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            resourcePackServer.sendResourcePacks(event.player)
        }
        Server.eventNode.addChild(eventNode)
    }
}
