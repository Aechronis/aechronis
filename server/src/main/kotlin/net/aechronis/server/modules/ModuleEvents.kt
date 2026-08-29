package net.aechronis.server.modules

import net.minestom.server.event.Event
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import java.util.function.Consumer

/**
 * Event-node facade for runtime modules. Direct Minestom listener consumers are cached, so merely
 * removing their node cannot prove that a callback already executing on another thread has ended.
 * This facade instruments the complete child tree before it becomes reachable from a live root.
 */
object ModuleEvents {
    fun <T : Event> addChild(
        parent: EventNode<T>,
        child: EventNode<out T>,
    ) = ModuleRuntime.addChild(parent, child)
}

/** Compatibility shim for Minestom's private listener storage; see [ModuleEvents]. */
internal object ModuleEventCallbackTracker {
    private val implementationClass =
        Class.forName(
            "net.minestom.server.event.EventNodeImpl",
            false,
            EventNode::class.java.classLoader,
        )
    private val listenerEntryClass =
        Class.forName(
            "net.minestom.server.event.EventNodeImpl\$ListenerEntry",
            false,
            EventNode::class.java.classLoader,
        )
    private val globalChildLock = implementationClass.getDeclaredField("GLOBAL_CHILD_LOCK").readable().get(null)
    private val listenerMapField = implementationClass.getDeclaredField("listenerMap").readable()
    private val childrenField = implementationClass.getDeclaredField("children").readable()
    private val predicateField = implementationClass.getDeclaredField("predicate").readable()
    private val filterField = implementationClass.getDeclaredField("filter").readable()
    private val registeredMappedNodeField = implementationClass.getDeclaredField("registeredMappedNode").readable()
    private val listenersField = listenerEntryClass.getDeclaredField("listeners").readable()
    private val bindingConsumersField = listenerEntryClass.getDeclaredField("bindingConsumers").readable()
    private val invalidateEventMethod =
        implementationClass
            .getDeclaredMethod("invalidateEvent", Class::class.java)
            .readable()

    fun instrument(
        root: EventNode<out Event>,
        scope: ModuleResourceScope,
    ): Boolean =
        synchronized(globalChildLock) {
            instrumentTree(root, scope)
        }

    private fun instrumentTree(
        node: EventNode<out Event>,
        scope: ModuleResourceScope,
    ): Boolean {
        check(implementationClass.isInstance(node)) {
            "Unsupported Minestom event node implementation: ${node.javaClass.name}"
        }
        check(predicateField.get(node) == null) {
            "Module event node '${node.name}' uses an unscoped predicate"
        }
        val filter = filterField.get(node)
        check(filter.javaClass.classLoader !== scope.classLoader) {
            "Module event node '${node.name}' uses a generation-owned filter"
        }
        @Suppress("UNCHECKED_CAST")
        val mappedNodes = registeredMappedNodeField.get(node) as Map<Any, Any>
        check(mappedNodes.isEmpty()) {
            "Module event node '${node.name}' uses mapped callbacks that cannot be safely quiesced"
        }

        @Suppress("UNCHECKED_CAST")
        val listenerMap = listenerMapField.get(node) as Map<Class<out Event>, Any>
        var treeChanged = false
        listenerMap.forEach { (eventType, entry) ->
            var changed = instrumentListeners(entry, scope)
            changed = instrumentBindings(entry, scope) || changed
            if (changed) {
                invalidateEventMethod.invoke(node, eventType)
                treeChanged = true
            }
        }

        @Suppress("UNCHECKED_CAST")
        val children = (childrenField.get(node) as Collection<EventNode<out Event>>).toList()
        children.forEach { child -> treeChanged = instrumentTree(child, scope) || treeChanged }
        return treeChanged
    }

    private fun instrumentListeners(
        entry: Any,
        scope: ModuleResourceScope,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val listeners = listenersField.get(entry) as MutableList<EventListener<Event>>
        var changed = false
        listeners.indices.forEach { index ->
            val listener = listeners[index]
            if (listener is ScopedEventListener && listener.scope === scope) return@forEach
            listeners[index] = ScopedEventListener(listener, scope)
            changed = true
        }
        return changed
    }

    private fun instrumentBindings(
        entry: Any,
        scope: ModuleResourceScope,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val consumers = bindingConsumersField.get(entry) as MutableSet<Consumer<Event>>
        val replacements =
            consumers.mapNotNull { consumer ->
                if (consumer is ScopedEventConsumer && consumer.scope === scope) null else consumer to ScopedEventConsumer(consumer, scope)
            }
        if (replacements.isEmpty()) return false
        replacements.forEach { (original, replacement) ->
            consumers.remove(original)
            consumers.add(replacement)
        }
        return true
    }

    private class ScopedEventListener(
        private val delegate: EventListener<Event>,
        val scope: ModuleResourceScope,
    ) : EventListener<Event> {
        override fun eventType(): Class<Event> = delegate.eventType()

        override fun run(event: Event): EventListener.Result = scope.dispatchCallback(EventListener.Result.INVALID) { delegate.run(event) }
    }

    private class ScopedEventConsumer(
        private val delegate: Consumer<Event>,
        val scope: ModuleResourceScope,
    ) : Consumer<Event> {
        override fun accept(event: Event) {
            scope.dispatchCallback(Unit) { delegate.accept(event) }
        }
    }

    private fun java.lang.reflect.Field.readable(): java.lang.reflect.Field =
        apply {
            check(trySetAccessible()) { "Cannot access Minestom event field '$name'" }
        }

    private fun java.lang.reflect.Method.readable(): java.lang.reflect.Method =
        apply {
            check(trySetAccessible()) { "Cannot access Minestom event method '$name'" }
        }
}
