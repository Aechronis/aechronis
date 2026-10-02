package net.aechronis.viaversion

import com.viaversion.viabackwards.api.ViaBackwardsPlatform
import com.viaversion.viaversion.ViaManagerImpl
import com.viaversion.viaversion.api.Via
import com.viaversion.viaversion.api.connection.UserConnection
import com.viaversion.viaversion.api.platform.PlatformTask
import com.viaversion.viaversion.api.platform.ViaPlatformLoader
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import com.viaversion.viaversion.commands.ViaCommandHandler
import com.viaversion.viaversion.platform.NoopInjector
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import java.io.File
import java.util.Collections
import java.util.SortedSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

internal class ViaPlatform(
    directory: File,
) : UserConnectionViaVersionPlatform(directory),
    AutoCloseable {
    private val scheduler =
        ScheduledThreadPoolExecutor(2, Thread.ofPlatform().name("Via-Platform-", 0).factory()).apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }

    fun initialize() {
        check(ProtocolVersion.isRegistered(MinecraftServer.PROTOCOL_VERSION)) {
            "ViaVersion does not recognize Minestom's protocol ${MinecraftServer.PROTOCOL_VERSION}"
        }
        val backwards =
            object : ViaBackwardsPlatform {
                override fun getLogger(): Logger = createLogger("ViaBackwards")

                override fun getDataFolder(): File = this@ViaPlatform.dataFolder

                override fun disable(): Unit = error("Incompatible ViaBackwards dependency")
            }
        ViaManagerImpl.initAndLoad(
            this,
            object : NoopInjector() {
                override fun getServerProtocolVersions(): SortedSet<ProtocolVersion> =
                    Collections.unmodifiableSortedSet(sortedSetOf(SERVER_VERSION))
            },
            ViaCommandHandler(false),
            object : ViaPlatformLoader {
                override fun load() {
                    backwards.enable()
                }

                override fun unload() = Unit
            },
            Runnable { backwards.init(File(dataFolder, "viabackwards.yml")) },
        )
        // Finish loading before accepting players or allowing this generation to be retired.
        val protocols = Via.getManager().protocolManager
        val mappings = protocols.protocols.mapNotNull { protocols.getMappingLoaderFuture(it.javaClass) }
        CompletableFuture.allOf(*mappings.toTypedArray()).get(30, TimeUnit.SECONDS)
    }

    override fun runAsync(runnable: Runnable): PlatformTask<*> = task(scheduler.submit(runnable))

    override fun runSync(runnable: Runnable): PlatformTask<*> = runAsync(runnable)

    override fun runSync(
        runnable: Runnable,
        delay: Long,
    ): PlatformTask<*> = task(scheduler.schedule(runnable, delay * 50, TimeUnit.MILLISECONDS))

    override fun runRepeatingAsync(
        runnable: Runnable,
        ticks: Long,
    ): PlatformTask<*> = task(scheduler.scheduleAtFixedRate(runnable, ticks * 50, ticks * 50, TimeUnit.MILLISECONDS))

    override fun runRepeatingSync(
        runnable: Runnable,
        period: Long,
    ): PlatformTask<*> = runRepeatingAsync(runnable, period)

    private fun task(future: Future<*>): PlatformTask<*> = PlatformTask<Future<*>> { future.cancel(false) }

    override fun close() {
        scheduler.shutdownNow()
        check(scheduler.awaitTermination(15, TimeUnit.SECONDS)) { "ViaVersion platform tasks did not stop" }
        if (Via.isLoaded()) {
            // The periodic completion task may not have run before an immediate unload.
            Via.getManager().protocolManager.checkForMappingCompletion()
        }
    }

    override fun createLogger(name: String): Logger = Logger.getLogger("Aechronis/$name")

    override fun isProxy(): Boolean = false

    override fun getPlatformName(): String = "Aechronis"

    override fun getPlatformVersion(): String = MinecraftServer.VERSION_NAME

    override fun kickPlayer(
        connection: UserConnection,
        message: String,
    ): Boolean {
        val player = connection.channel?.attr(ViaPlayerConnection.KEY)?.get() ?: return false
        player.kick(Component.text(message))
        return true
    }

    override fun sendMessage(
        connection: UserConnection,
        message: String,
    ) {
        connection.channel
            ?.attr(ViaPlayerConnection.KEY)
            ?.get()
            ?.player
            ?.sendMessage(Component.text(message))
    }

    companion object {
        val SERVER_VERSION: ProtocolVersion = ProtocolVersion.getProtocol(MinecraftServer.PROTOCOL_VERSION)
    }
}
