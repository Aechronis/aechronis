package net.aechronis.combat

import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.generator.Generator

/** A single Minestom lifecycle shared by every test class in this worker process. */
internal object CombatTestServer {
    private val server by lazy { MinecraftServer.init(Auth.Online()) }
    private var started = false

    fun initialize(): MinecraftServer = server

    @Synchronized
    fun createInstance(
        generator: Generator,
        gameMode: GameMode,
        spawnPoint: Pos = Pos(0.0, 60.0, 0.0),
    ): InstanceContainer {
        val minecraftServer = server
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.setGenerator(generator)

        val eventNode = EventNode.all("combat-test-server")
        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            event.spawningInstance = instance
            event.player.respawnPoint = spawnPoint
            event.player.gameMode = gameMode
        }
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        if (!started) {
            minecraftServer.start("0.0.0.0", 25565)
            started = true
        }
        return instance
    }
}
