package net.aechronis.regeneratingores

import net.aechronis.regeneratingores.commands.Ore
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Path

class RegeneratingOresModule : AechronisModule {
    override val id = "regenerating-ores"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Ores.init(Path.of("regenerating-ores", "ores.json"))
        Ores.restoreTransientState(context.peekTransientState(COOLDOWNS_STATE_KEY))
        ModuleCommands.register(Ore())
    }

    override fun saveState(context: ModuleContext) {
        Ores.saveAll()
        context.publishTransientState(COOLDOWNS_STATE_KEY, Ores.captureTransientState())
    }

    override fun shutdown(context: ModuleContext) = Ores.shutdown()

    companion object {
        const val COOLDOWNS_STATE_KEY = "regenerating-ores:cooldowns:v1"
    }
}
