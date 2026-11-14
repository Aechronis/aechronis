package net.aechronis.nodes.commands.arguments

/** Town and nation names may contain spaces; commands take them as one word with underscores. */
internal object NameArgument {
    fun toArg(name: String): String = name.replace(' ', '_')

    /** Exact name first, so names that really contain underscores still match. */
    fun <T> resolve(input: String, fromName: (String) -> T?): T? = fromName(input) ?: fromName(input.replace('_', ' '))
}
