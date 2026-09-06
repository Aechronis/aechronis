package net.aechronis.server.modules

import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import java.util.IdentityHashMap

/** Mapped arguments read live module state during parsing, before any command executor runs. */
internal fun scopeArguments(
    command: Command,
    scope: ModuleResourceScope,
) {
    val wrapped = IdentityHashMap<Argument<*>, Argument<*>>()
    command.syntaxes.forEach { syntax ->
        scopeDefaultValues(syntax, scope)
        syntax.arguments.indices.forEach { index ->
            val argument = syntax.arguments[index]
            syntax.arguments[index] =
                wrapped.computeIfAbsent(argument) {
                    if (argument.javaClass.name.startsWith("net.minestom.server.command.builder.arguments.Argument\$Argument") ||
                        argument.javaClass.classLoader === scope.classLoader
                    ) {
                        ScopedArgument(argument, scope)
                    } else {
                        // Keep Minestom's special literal/enum/group types visible to its packet converter.
                        scopeArgumentCallbacks(argument, scope)
                        argument
                    }
                }
        }
    }
}

private fun <T> scopeArgumentCallbacks(
    argument: Argument<T>,
    scope: ModuleResourceScope,
) {
    argument.suggestionCallback?.let { callback ->
        argument.setSuggestionCallback { sender, context, suggestion ->
            scope.dispatchCallback(Unit) { callback.apply(sender, context, suggestion) }
        }
    }
    argument.callback?.let { callback ->
        argument.setCallback { sender, error -> scope.dispatchCallback(Unit) { callback.apply(sender, error) } }
    }
    argument.defaultValue?.let { value ->
        argument.setDefaultValue { sender ->
            scope.dispatchCallback<T?>(null) { value.apply(sender) }
        }
    }
}

private class ScopedArgument<T>(
    private val delegate: Argument<T>,
    private val scope: ModuleResourceScope,
) : Argument<T>(delegate.id, delegate.allowSpace(), delegate.useRemaining()) {
    init {
        delegate.suggestionCallback?.let(::setSuggestionCallback)
        delegate.callback?.let(::setCallback)
        delegate.defaultValue?.let { setDefaultValue(it) }
        scopeArgumentCallbacks(this, scope)
    }

    override fun parse(
        sender: CommandSender,
        input: String,
    ): T {
        val result = scope.dispatchCallback(null) { delegate.parse(sender, input) to true }
        return result?.first ?: throw ArgumentSyntaxException("Module is reloading", input, -1)
    }

    override fun parser() = delegate.parser()

    override fun nodeProperties(): ByteArray? = delegate.nodeProperties()

    override fun suggestionType() = delegate.suggestionType()

    override fun equals(other: Any?): Boolean = other is ScopedArgument<*> && delegate == other.delegate

    override fun hashCode(): Int = delegate.hashCode()
}

/** Minestom copies optional argument suppliers into each syntax at declaration time. */
private val syntaxDefaults =
    net.minestom.server.command.builder.CommandSyntax::class.java
        .getDeclaredField("defaultValuesMap")
        .apply { check(trySetAccessible()) }

private fun scopeDefaultValues(
    syntax: net.minestom.server.command.builder.CommandSyntax,
    scope: ModuleResourceScope,
) {
    @Suppress("UNCHECKED_CAST")
    val defaults = syntaxDefaults.get(syntax) as? MutableMap<String, java.util.function.Function<CommandSender, Any?>> ?: return
    defaults.replaceAll { _, value ->
        java.util.function.Function { sender -> scope.dispatchCallback<Any?>(null) { value.apply(sender) } }
    }
}
