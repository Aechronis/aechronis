/**
 * Json save state with lazily created json string
 */

package net.aechronis.nodes.serdes

interface SaveState {
    // json string, lazily created
    var jsonString: String?

    // create the json string
    fun createJsonString(): String

    // memoized access to json string
    fun toJsonString(): String = jsonString ?: createJsonString().also { jsonString = it }
}
