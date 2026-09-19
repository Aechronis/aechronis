package net.aechronis.server.modules

import java.util.Locale

/** Permission suggestions, owned by the registering module generation. Does not grant access. */
object ModulePermissions {
    private val core = linkedSetOf<String>()
    private val modules = mutableMapOf<ModuleResourceScope, MutableSet<String>>()
    private val listeners = linkedSetOf<(Set<String>) -> Unit>()

    @Synchronized
    fun register(vararg permissions: String?) {
        // Shared helpers such as utils.Command must register for their caller's generation.
        val scope = ModuleRuntime.captureScope(preferContext = true)
        check(scope != null || !ModuleRuntime.isManagedRuntime()) { "No module scope is active" }
        val target = if (scope == null) core else modules.getOrPut(scope) { linkedSetOf() }
        val changed = target.addAll(permissions.filterNotNull().filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) })
        if (changed) notifyListeners()
    }

    @Synchronized
    fun snapshot(): Set<String> = (core + modules.values.flatten()).toSortedSet()

    /** Immediately sends the current declarations; close before the subscriber's module unloads. */
    @Synchronized
    fun subscribe(listener: (Set<String>) -> Unit): AutoCloseable {
        listener(snapshot())
        listeners += listener
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    @Synchronized
    internal fun remove(scope: ModuleResourceScope) {
        if (modules.remove(scope) != null) notifyListeners()
    }

    private fun notifyListeners() {
        val permissions = snapshot()
        listeners.toList().forEach { it(permissions) }
    }
}
