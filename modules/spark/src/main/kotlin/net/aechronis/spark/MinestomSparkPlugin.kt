package net.aechronis.spark

import me.lucko.spark.common.SparkPlatform
import me.lucko.spark.common.SparkPlugin
import me.lucko.spark.common.monitor.ping.PlayerPingProvider
import me.lucko.spark.common.platform.PlatformInfo
import me.lucko.spark.common.tick.AbstractTickHook
import me.lucko.spark.common.tick.AbstractTickReporter
import me.lucko.spark.common.util.SparkThreadFactory
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.stream.Stream

/** The platform-specific services required by upstream Spark. */
internal class MinestomSparkPlugin(
    context: ModuleContext,
) : SparkPlugin,
    AutoCloseable {
    private val diagnostics = SparkDiagnostics(context)

    override fun createClassSourceLookup() = diagnostics.sourceLookup()

    override fun getKnownSources() = diagnostics.sources()

    override fun createClassFinder() = diagnostics.classFinder(super<SparkPlugin>.createClassFinder())

    override fun createExtraMetadataProvider() = diagnostics.metadata()

    override fun createWorldInfoProvider() = MinestomWorldInfo()

    override fun getDefaultThreadDumper(): me.lucko.spark.common.sampler.ThreadDumper =
        me.lucko.spark.common.sampler.ThreadDumper.Regex(
            (
                MinecraftServer
                    .process()
                    .dispatcher()
                    .threads()
                    .map { it.name }
                    .toSet() + MinecraftServer.THREAD_NAME_TICK_SCHEDULER
            ).map {
                java.util.regex.Pattern
                    .quote(it)
            }.toSet(),
        )

    private val executor = Executors.newFixedThreadPool(4, SparkThreadFactory())
    private val logger = LoggerFactory.getLogger(MinestomSparkPlugin::class.java)

    override fun getVersion(): String = checkNotNull(SparkPlatform::class.java.`package`.implementationVersion)

    override fun getPluginDirectory(): Path = Path.of("spark")

    override fun getCommandName(): String = "spark"

    override fun getCommandSenders(): Stream<SparkCommandSender> =
        Stream.concat(
            MinecraftServer
                .getConnectionManager()
                .onlinePlayers
                .stream()
                .map { SparkCommandSender(it) },
            Stream.of(SparkCommandSender(MinecraftServer.getCommandManager().consoleSender)),
        )

    override fun executeAsync(task: Runnable) = executor.execute(task)

    override fun executeSync(task: Runnable) {
        ModuleScheduler.scheduleNextTick(task)
    }

    override fun createPlayerPingProvider(): PlayerPingProvider =
        PlayerPingProvider {
            MinecraftServer.getConnectionManager().onlinePlayers.associate { it.username to it.latency }
        }

    override fun createTickHook(): AbstractTickHook =
        object : AbstractTickHook() {
            private var task: Task? = null

            override fun start() {
                task =
                    ModuleScheduler
                        .buildTask(::onTick)
                        .delay(TaskSchedule.tick(1))
                        .repeat(TaskSchedule.tick(1))
                        .schedule()
            }

            override fun close() {
                task?.cancel()
                task = null
            }
        }

    override fun createTickReporter(): AbstractTickReporter =
        object : AbstractTickReporter() {
            private val node =
                EventNode.all("spark-tick-reporter").apply {
                    addListener(ServerTickMonitorEvent::class.java) { onTick(it.tickMonitor.tickTime) }
                }

            override fun start() = ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), node)

            override fun close() {
                MinecraftServer.getGlobalEventHandler().removeChild(node)
            }
        }

    override fun getPlatformInfo(): PlatformInfo =
        object : PlatformInfo {
            override fun getType(): PlatformInfo.Type = PlatformInfo.Type.SERVER

            override fun getName(): String = "Minestom"

            override fun getBrand(): String = MinecraftServer.getBrandName()

            override fun getVersion(): String = MinecraftServer.VERSION_NAME + "-" + MinecraftServer.getBrandName()

            override fun getMinecraftVersion(): String = MinecraftServer.VERSION_NAME
        }

    override fun log(
        level: Level,
        message: String,
    ) = log(level, message, null)

    override fun log(
        level: Level,
        message: String,
        throwable: Throwable?,
    ) {
        when {
            level.intValue() >= Level.SEVERE.intValue() -> logger.error(message, throwable)
            level.intValue() >= Level.WARNING.intValue() -> logger.warn(message, throwable)
            else -> logger.info(message, throwable)
        }
    }

    override fun close() {
        executor.shutdown()
        check(executor.awaitTermination(15, TimeUnit.SECONDS)) { "Spark adapter work did not finish before unload" }
    }
}
