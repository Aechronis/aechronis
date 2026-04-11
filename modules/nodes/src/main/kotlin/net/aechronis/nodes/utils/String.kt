/**
 * Utils for parsing collections to strings
 *
 * Used by serializer
 * TODO: benchmark if string array funcs are necessary
 */

package net.aechronis.nodes.utils

inline fun <T> stringArrayFromSet(iter: Set<T>, itemName: (T) -> String): String {
    var s = "["
    for ((i, v) in iter.withIndex()) {
        s += itemName(v)
        if (i < iter.size - 1) {
            s += ","
        }
    }
    s += "]"

    return s
}

inline fun <K, V> stringMapFromMap(iter: Map<K, V>, keyString: (K) -> String, valString: (V) -> String): String {
    var s = "{"
    for ((i, entry) in iter.entries.withIndex()) {
        val key = keyString(entry.key)
        val value = valString(entry.value)
        s += "$key:$value"
        if (i < iter.size - 1) {
            s += ","
        }
    }
    s += "}"
    return s
}
