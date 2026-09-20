package net.aechronis.server.modules

/** Synchronous, nested startup timings. Calls outside a module startup report just run the action. */
object ModuleStartupTimings {
    private val current = ThreadLocal<Entry>()

    fun <T> measure(
        name: String,
        action: () -> T,
    ): T {
        val parent = current.get() ?: return action()
        val entry = Entry(name)
        parent.children += entry
        return record(entry, action)
    }

    internal fun <T> report(
        name: String,
        action: () -> T,
    ): T {
        if (current.get() != null) return measure(name, action)
        val root = Entry(name)
        return try {
            record(root, action)
        } finally {
            println(render(root))
        }
    }

    private fun <T> record(
        entry: Entry,
        action: () -> T,
    ): T {
        val parent = current.get()
        current.set(entry)
        val started = System.nanoTime()
        try {
            return action()
        } catch (error: Throwable) {
            entry.failed = true
            throw error
        } finally {
            entry.elapsedNanos = System.nanoTime() - started
            if (parent == null) current.remove() else current.set(parent)
        }
    }

    private fun render(root: Entry): String =
        buildString {
            fun appendEntry(
                entry: Entry,
                prefix: String,
                branch: String,
                childPrefix: String,
            ) {
                append(prefix).append(branch).append(entry.name)
                append(if (entry.failed) " failed after " else " loaded in ")
                append(entry.elapsedNanos / 1_000_000).append("ms")
                entry.children.forEachIndexed { index, child ->
                    append('\n')
                    val last = index == entry.children.lastIndex
                    appendEntry(child, childPrefix, if (last) "└─ " else "├─ ", childPrefix + if (last) "   " else "│  ")
                }
            }
            appendEntry(root, "[Modules] ", "", "")
        }

    private class Entry(
        val name: String,
    ) {
        val children = mutableListOf<Entry>()
        var elapsedNanos = 0L
        var failed = false
    }
}
