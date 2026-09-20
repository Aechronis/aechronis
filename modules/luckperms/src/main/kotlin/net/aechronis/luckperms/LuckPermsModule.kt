package net.aechronis.luckperms

import me.lucko.luckperms.common.config.generic.adapter.EnvironmentVariableConfigAdapter
import me.lucko.luckperms.minestom.LPMinestomPlugin
import me.lucko.luckperms.minestom.LuckPermsMinestom
import net.aechronis.server.Permissions
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.minestom.server.MinecraftServer
import java.nio.file.Path
import java.time.Duration

class LuckPermsModule : AechronisModule {
    override val id = "luckperms"

    private var permissionProvider: AutoCloseable? = null
    private var started = false
    private var suggestions: PermissionSuggestions? = null

    override fun initialize(context: ModuleContext) {
        check(!started) { "LuckPerms module is already initialized" }
        val global = MinecraftServer.getGlobalEventHandler()
        val existingNodes = global.children.toSet()
        started = true
        try {
            lateinit var plugin: LPMinestomPlugin
            val api =
                measure("Permission platform") {
                    LuckPermsMinestom
                        .builder(Path.of("luckperms"))
                        .commandRegistry({ ModuleCommands.register(it) }, ModuleCommands::unregister)
                        .configurationAdapter {
                            plugin = it
                            EnvironmentVariableConfigAdapter(it)
                        }.enable()
                }
            suggestions = measure("Permission suggestions") { PermissionSuggestions(plugin.permissionRegistry) }
            // Reload does not repeat the login event for players already connected.
            measure("Online user data") {
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .map { api.userManager.loadUser(it.uuid, it.username) }
                    .forEach { it.join() }
            }
            measure("Permission provider") {
                permissionProvider =
                    Permissions.registerProvider { uuid, permission ->
                        api.userManager
                            .getUser(uuid)
                            ?.cachedData
                            ?.permissionData
                            ?.checkPermission(permission)
                            ?.asBoolean() == true
                    }
            }
        } finally {
            // LuckPerms attaches its listener node directly. Adopt it into module ownership
            // after setup so reload can detach and drain its callbacks before disabling it.
            measure("Event ownership") {
                (global.children - existingNodes).forEach { node ->
                    global.removeChild(node)
                    ModuleEvents.addChild(global, node)
                }
            }
        }
    }

    override fun shutdown(context: ModuleContext) {
        if (!started) return
        permissionProvider?.close()
        permissionProvider = null
        suggestions?.close()
        suggestions = null
        LuckPermsMinestom.disable()
        awaitBackgroundThreads()
        started = false
    }

    private fun awaitBackgroundThreads() {
        // The upstream scheduler starts one thread per async task, but shutdownExecutor is
        // a no-op. Drain those threads before ModuleManager closes this generation's JAR.
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (true) {
            val threads =
                Thread.getAllStackTraces().keys.filter {
                    it.isAlive && it.name == "LPMinestom Thread" && it.contextClassLoader === javaClass.classLoader
                }
            if (threads.isEmpty()) return
            threads.forEach { thread ->
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && thread.join(Duration.ofNanos(remaining))) {
                    "LuckPerms background work did not finish before unload"
                }
            }
        }
    }
}
