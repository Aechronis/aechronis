package net.aechronis.utils

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import kotlin.math.floor
import kotlin.math.min

private val flatTestGenerator =
    Generator { unit ->
        unit.modifier().fillHeight(0, 60, Block.STONE)
    }

/**
 * Creates and starts a Minestom server with an instance suitable for integration
 * tests and manual playtesting.
 *
 * The instance uses [generator], and joining players are spawned at [spawnPoint]
 * in [gameMode] with an MSPT and memory boss bar. The caller is responsible for
 * stopping the server with [MinecraftServer.stopCleanly].
 */
fun createTestServer(
    generator: Generator = flatTestGenerator,
    gameMode: GameMode = GameMode.CREATIVE,
    spawnPoint: Pos = Pos(0.0, 60.0, 0.0),
    address: String = "0.0.0.0",
    port: Int = 25565,
    auth: Auth = Auth.Online(),
): InstanceContainer {
    val server = MinecraftServer.init(auth)
    val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
    instance.setGenerator(generator)

    addTestServerListeners(instance, gameMode, spawnPoint)
    server.start(address, port)

    return instance
}

private fun addTestServerListeners(
    instance: InstanceContainer,
    gameMode: GameMode,
    spawnPoint: Pos,
) {
    val eventNode = EventNode.all("test-server").setPriority(0)
    val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)

    eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
        event.spawningInstance = instance
        event.player.respawnPoint = spawnPoint
        event.player.gameMode = gameMode
    }

    eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
        event.player.showBossBar(bossBar)
    }

    eventNode.addListener(ServerTickMonitorEvent::class.java) { event ->
        val tickTime = floor(event.tickMonitor.tickTime * 100.0) / 100.0
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024

        bossBar.name(Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB"))
        bossBar.progress(min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat())
        bossBar.color(if (tickTime > MinecraftServer.TICK_MS) BossBar.Color.RED else BossBar.Color.GREEN)
    }

    MinecraftServer.getGlobalEventHandler().addChild(eventNode)
}
