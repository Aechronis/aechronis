package net.aechronis.logger.commands

import net.minestom.server.command.CommandManager
import kotlin.test.Test
import kotlin.test.assertEquals

class RestoreChunkCommandTest {
    @Test
    fun `every restore chunk syntax has unique argument ids`() {
        val command = RestoreChunk()

        command.syntaxes.forEach { syntax ->
            val ids = syntax.arguments.map { it.id }
            assertEquals(ids.distinct(), ids, syntax.syntaxString)
        }

        val radiusSyntaxes = command.syntaxes.filter { syntax -> syntax.arguments.firstOrNull()?.id == "radius" }
        assertEquals(2, radiusSyntaxes.size)
        radiusSyntaxes.forEach { syntax ->
            assertEquals("chunk-radius", syntax.arguments.last().id)
        }

        val commandManager = CommandManager()
        commandManager.register(command)
        commandManager.parseCommand(commandManager.consoleSender, "restorechunk radius 2")
        commandManager.parseCommand(commandManager.consoleSender, "restorechunk radius 10 -3 2")
    }
}
