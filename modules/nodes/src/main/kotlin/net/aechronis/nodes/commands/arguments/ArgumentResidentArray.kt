package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.objects.Resident
import net.aechronis.vanilla.commands.PlayerTargets
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.exception.ArgumentSyntaxException

object ArgumentResidentArray {
    /**
     * Creates an argument that accepts multiple residents and returns a list of Resident objects.
     */
    fun create(id: String): Argument<List<Resident>> = PlayerTargets.arguments(id).map { inputs ->
        inputs.map { input ->
            Resident.fromName(input)
                ?: throw ArgumentSyntaxException("Resident not found", input, 1)
        }
    }
}
