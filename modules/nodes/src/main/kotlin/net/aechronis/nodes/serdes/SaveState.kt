package net.aechronis.nodes.serdes

/** A detached snapshot whose encoded representation is cached without exposing mutable state. */
abstract class SaveState {
    private val jsonString: String by lazy { encode() }

    protected abstract fun encode(): String

    fun toJsonString(): String = jsonString
}
