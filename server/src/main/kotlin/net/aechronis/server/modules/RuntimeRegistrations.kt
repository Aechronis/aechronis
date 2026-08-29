package net.aechronis.server.modules

import net.aechronis.server.Server
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.network.packet.server.CachedPacket
import net.minestom.server.recipe.Recipe
import java.util.Collections
import java.util.IdentityHashMap

internal class RuntimeRegistrations private constructor(
    private val commands: Set<Command>,
    private val globalEventChildren: Set<EventNode<Event>>,
    private val serverEventChildren: Set<EventNode<Event>>,
    private val recipes: Set<Recipe>,
    private val moduleClassLoader: ClassLoader,
) {
    private var recipePacketInvalidationRequired = false

    fun instrumentCallbacks(scope: ModuleResourceScope) {
        val global = MinecraftServer.getGlobalEventHandler()
        global.children
            .identityDifference(globalEventChildren)
            .forEach { node -> validateInstrumented(node, scope) }
        Server.eventNode.children
            .identityDifference(serverEventChildren)
            .filterNot { node -> node === scope.eventNode }
            .forEach { node -> validateInstrumented(node, scope) }
    }

    private fun validateInstrumented(
        node: EventNode<Event>,
        scope: ModuleResourceScope,
    ) {
        check(scope.ownsEventNode(node)) {
            "Module event node '${node.name}' was attached without ModuleEvents.addChild"
        }
        check(!ModuleEventCallbackTracker.instrument(node, scope)) {
            "Module event node '${node.name}' was mutated after attachment"
        }
    }

    fun detachInputs() {
        val commandManager = MinecraftServer.getCommandManager()
        commandManager.commands.identityDifference(commands).forEach(commandManager::unregister)

        val global = MinecraftServer.getGlobalEventHandler()
        global.children.identityDifference(globalEventChildren).forEach(global::removeChild)
        Server.eventNode.children
            .identityDifference(serverEventChildren)
            .forEach(Server.eventNode::removeChild)
    }

    @Synchronized
    fun cleanup() {
        detachInputs()
        EventNodeCachePruner.prune(moduleClassLoader)
        val recipeManager = MinecraftServer.getRecipeManager()
        val addedRecipes = recipeManager.recipes.identityDifference(recipes)
        if (addedRecipes.isNotEmpty()) recipePacketInvalidationRequired = true
        addedRecipes.forEach(recipeManager::removeRecipe)
        if (recipePacketInvalidationRequired) {
            (recipeManager.declareRecipesPacket as? CachedPacket)?.invalidate()
            recipePacketInvalidationRequired = false
        }
    }

    companion object {
        fun capture(moduleClassLoader: ClassLoader): RuntimeRegistrations =
            RuntimeRegistrations(
                commands = MinecraftServer.getCommandManager().commands.identityCopy(),
                globalEventChildren = MinecraftServer.getGlobalEventHandler().children.identityCopy(),
                serverEventChildren = Server.eventNode.children.identityCopy(),
                recipes = MinecraftServer.getRecipeManager().recipes.identityCopy(),
                moduleClassLoader = moduleClassLoader,
            )
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

private fun <T : Any> Collection<T>.identityCopy(): Set<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).also { it.addAll(this) }

private fun <T : Any> Collection<T>.identityDifference(baseline: Set<T>): List<T> =
    filter { candidate ->
        baseline.none {
            it === candidate
        }
    }
