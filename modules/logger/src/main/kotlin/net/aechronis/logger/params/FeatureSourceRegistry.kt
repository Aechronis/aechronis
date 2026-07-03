package net.aechronis.logger.params

import java.util.concurrent.ConcurrentHashMap

object FeatureSourceRegistry {
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()

    fun record(
        source: String,
        action: String,
    ) {
        seen.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() }.add(action)
    }

    fun sources(): List<String> = seen.keys.sorted()

    fun actionsFor(source: String): List<String> = seen[source]?.sorted() ?: emptyList()
}
