package net.aechronis.spark

import me.lucko.spark.common.SparkPlatform
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.aechronis.server.modules.ModuleStartupTimings.measure
import java.time.Duration

class SparkModule : AechronisModule {
    override val id = "spark"

    private var spark: SparkPlatform? = null
    private var plugin: MinestomSparkPlugin? = null

    override fun initialize(context: ModuleContext) {
        check(spark == null) { "Spark module is already initialized" }
        val adapter = measure("Platform adapter") { MinestomSparkPlugin(context) }
        plugin = adapter
        val platform = measure("Profiler platform") { SparkPlatform(adapter) }
        spark = platform
        measure("Enable profiler") { platform.enable() }
        measure("Commands and permissions") {
            ModulePermissions.register(*platform.commandManager.allSparkPermissions.toTypedArray())
            ModuleCommands.register(SparkCommand(platform))
        }
    }

    override fun shutdown(context: ModuleContext) {
        spark?.disable()
        plugin?.close()
        awaitWorkers()
        spark = null
        plugin = null
    }

    private fun awaitWorkers() {
        // Spark shuts down its executors without awaiting termination. Keep the module JAR
        // open until outstanding work has finished, including workers spawned by that work.
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (true) {
            val workers =
                Thread.getAllStackTraces().keys.filter {
                    it.isAlive && it.name.startsWith("spark-worker-pool-") && it.contextClassLoader === javaClass.classLoader
                }
            if (workers.isEmpty()) return
            workers.forEach { worker ->
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && worker.join(Duration.ofNanos(remaining))) {
                    "Spark background work did not finish before unload"
                }
            }
        }
    }
}
