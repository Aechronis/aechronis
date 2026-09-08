package net.aechronis.nodes.serdes

import java.util.Collections

/** Copy before wrapping: neither live state nor snapshot consumers may mutate the saved values. */
internal fun <T> Iterable<T>.snapshotList(): List<T> = Collections.unmodifiableList(toMutableList())

internal fun <K, V> Map<K, V>.snapshotMap(): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(this))

internal fun <K, V, W> Map<K, Map<V, W>>.snapshotNestedMap(): Map<K, Map<V, W>> = mapValues { (_, values) -> values.snapshotMap() }.snapshotMap()
