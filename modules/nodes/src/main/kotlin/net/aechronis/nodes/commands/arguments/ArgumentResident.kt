package net.aechronis.nodes.commands.arguments

import net.aechronis.nodes.objects.Resident
import net.aechronis.vanilla.commands.PlayerTargets
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.exception.ArgumentSyntaxException

object ArgumentResident {
    /**
     * Creates an argument that autocompletes and returns a Resident object.
     */
    fun create(id: String): Argument<Resident> = PlayerTargets.argument(id).map { input ->
        Resident.fromName(input)
            ?: throw ArgumentSyntaxException("Resident not found", input, 1)
    }
}
