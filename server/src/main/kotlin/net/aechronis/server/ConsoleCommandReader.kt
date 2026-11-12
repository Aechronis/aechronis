package net.aechronis.server

import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.CommandResult

/** Reads commands typed into the server's standard input and runs them as the console sender. */
internal object ConsoleCommandReader {
    fun start() {
        Thread
            .ofPlatform()
            .daemon()
            .name("console-command-reader")
            .start(::read)
    }

    private fun read() {
        val input = System.`in`.bufferedReader()
        while (true) {
            // EOF means there is no attached console (for example stdin is /dev/null), so stop quietly.
            val line = input.readLine() ?: return
            val command = line.trim().removePrefix("/")
            if (command.isEmpty()) continue
            // Player commands run on the tick thread, so console commands do too.
            MinecraftServer.getSchedulerManager().scheduleNextTick { execute(command) }
        }
    }

    private fun execute(command: String) {
        val manager = MinecraftServer.getCommandManager()
        try {
            when (manager.execute(manager.consoleSender, command).type) {
                CommandResult.Type.UNKNOWN -> System.err.println("Unknown command: ${command.substringBefore(' ')}")
                CommandResult.Type.INVALID_SYNTAX -> System.err.println("Invalid syntax: $command")
                else -> {}
            }
        } catch (error: Throwable) {
            System.err.println("Console command '$command' failed: ${error.message}")
            error.printStackTrace()
        }
    }
}
