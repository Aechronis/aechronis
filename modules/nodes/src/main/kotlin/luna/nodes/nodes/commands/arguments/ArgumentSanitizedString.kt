package luna.nodes.nodes.commands.arguments

import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.exception.ArgumentSyntaxException

object ArgumentSanitizedString {
    /**
     * Creates an argument that returns the string, sanitized and validated.
     */
    fun create(id: String): Argument<String> = ArgumentType.String(id).map { input ->
        if (input.length > 32) {
            throw ArgumentSyntaxException("String must be 32 characters or less", input, 1)
        }

        if (input.contains("\"") || input.contains("{") || input.contains("}")) {
            throw ArgumentSyntaxException("String cannot contain illegal characters", input, 2)
        }

        var inputEscaped = input.replace("$", "$$")
        inputEscaped = inputEscaped.replace("%", "%%")

        inputEscaped
    }
}
