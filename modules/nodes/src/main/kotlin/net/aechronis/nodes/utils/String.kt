/**
 * Utils for parsing collections to strings
 *
 * Used by serializer
 */

package net.aechronis.nodes.utils

fun <T> stringArrayFromSet(iter: Set<T>, itemName: (T) -> String): String = iter.joinToString(separator = ",", prefix = "[", postfix = "]", transform = itemName)

fun <K, V> stringMapFromMap(iter: Map<K, V>, keyString: (K) -> String, valString: (V) -> String): String = iter.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
    "${keyString(key)}:${valString(value)}"
}
