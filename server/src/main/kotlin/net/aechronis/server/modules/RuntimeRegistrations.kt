package net.aechronis.server.modules

import net.aechronis.server.Server
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.network.packet.server.CachedPacket
import java.util.Collections
import java.util.IdentityHashMap

internal class RuntimeRegistrations(
    private val scope: ModuleResourceScope,
) {
    /** Reject untracked registrations before admitting callbacks from this module. */
    fun record(action: () -> Unit) {
        val manager = MinecraftServer.getCommandManager()
        val commandsBefore = synchronized(manager) { manager.commands.toSet() }
        val global = MinecraftServer.getGlobalEventHandler()
        val globalBefore = global.children.toSet()
        val serverBefore = Server.eventNode.children.toSet()
        try {
            action()
        } finally {
            val addedCommands = synchronized(manager) { manager.commands - commandsBefore }
            val untrackedCommands = addedCommands.filterNot(ModuleRuntime::ownsCommand)
            val addedNodes = (global.children - globalBefore) + (Server.eventNode.children - serverBefore)
            val untrackedNodes = addedNodes.filterNot(ModuleRuntime::ownsNode)
            // Retain unexpected additions for cleanup even when validation rejects startup.
            scope.commands.addAll(untrackedCommands)
            untrackedNodes.forEach {
                global.removeChild(it)
                Server.eventNode.removeChild(it)
            }
            check(untrackedCommands.isEmpty()) { "Module commands must use ModuleCommands.register" }
            check(untrackedNodes.isEmpty()) { "Module event nodes must use ModuleEvents.addChild" }
            scope.validateEventNodes()
        }
    }

    fun detachInputs() = scope.detachInputs()

    fun cleanup() {
        detachInputs()
        EventNodeCachePruner.prune(scope.classLoader)
        scope.recipes.forEach(ModuleRecipes::removeRecipe)
        scope.recipes.clear()
        scope.commands.clear()
        (MinecraftServer.getRecipeManager().declareRecipesPacket as? CachedPacket)?.invalidate()
    }
}

/**
 * Minestom caches event handles by their event [Class]. Removing a module event node invalidates
 * the cached consumer but does not remove that strong class key, which would pin every old module
 * classloader after it dispatched a custom event through the global tree.
 *
 * Keep this compatibility shim isolated: Minestom does not currently expose cache eviction. A
 * failed prune is deliberately reported as an unload failure instead of silently claiming that the
 * generation can be discarded safely.
 */
private object EventNodeCachePruner {
    private val implementationClass =
        Class.forName(
            "net.minestom.server.event.EventNodeImpl",
            false,
            EventNode::class.java.classLoader,
        )
    private val globalChildLock = implementationClass.getDeclaredField("GLOBAL_CHILD_LOCK").readable().get(null)
    private val handleMapField = implementationClass.getDeclaredField("handleMap").readable()
    private val listenerMapField = implementationClass.getDeclaredField("listenerMap").readable()
    private val childrenField = implementationClass.getDeclaredField("children").readable()

    fun prune(moduleClassLoader: ClassLoader) {
        synchronized(globalChildLock) {
            val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            pruneTree(MinecraftServer.getGlobalEventHandler(), moduleClassLoader, visited)
            pruneTree(Server.eventNode, moduleClassLoader, visited)
        }
    }

    private fun pruneTree(
        node: Any,
        moduleClassLoader: ClassLoader,
        visited: MutableSet<Any>,
    ) {
        check(implementationClass.isInstance(node)) {
            "Unsupported Minestom event node implementation: ${node.javaClass.name}"
        }
        if (!visited.add(node)) return

        pruneClassKeys(handleMapField.get(node), moduleClassLoader)
        pruneClassKeys(listenerMapField.get(node), moduleClassLoader)

        @Suppress("UNCHECKED_CAST")
        val children = (childrenField.get(node) as Collection<Any>).toList()
        children.forEach { child -> pruneTree(child, moduleClassLoader, visited) }
    }

    private fun pruneClassKeys(
        value: Any,
        moduleClassLoader: ClassLoader,
    ) {
        @Suppress("UNCHECKED_CAST")
        val map = value as MutableMap<Any, Any>
        map.keys.removeIf { key -> key is Class<*> && key.classLoader === moduleClassLoader }
    }

    private fun java.lang.reflect.Field.readable(): java.lang.reflect.Field =
        apply {
            check(trySetAccessible()) { "Cannot access Minestom event cache field '$name'" }
        }
}
