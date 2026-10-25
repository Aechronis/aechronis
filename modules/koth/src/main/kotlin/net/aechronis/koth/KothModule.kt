package net.aechronis.koth

import net.aechronis.koth.commands.KothCommand
import net.aechronis.server.io.AtomicFiles
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class KothModule : AechronisModule {
    override val id = "koth"

    // Restore capture glow after vanish, and release it before vanilla shuts down.
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        val path = Path.of("koth", "koth.json")
        val legacyPath = Path.of("vanilla", "koth.json")
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS) && Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            // Keep the original available if this module upgrade is rolled back.
            AtomicFiles.copy(legacyPath, path, preservePermissions = true)
        }
        Koth.init(path)
        Koth.restoreTransientState(
            context.peekTransientState(ACTIVE_STATE_KEY) ?: context.peekTransientState(LEGACY_ACTIVE_STATE_KEY),
        )
        ModuleCommands.register(KothCommand())
    }

    override fun saveState(context: ModuleContext) {
        Koth.saveAll()
        context.publishTransientState(ACTIVE_STATE_KEY, Koth.captureTransientState())
    }

    override fun shutdown(context: ModuleContext) = Koth.shutdown()

    companion object {
        const val ACTIVE_STATE_KEY = "koth:active:v1"
        private const val LEGACY_ACTIVE_STATE_KEY = "vanilla:koth-active:v1"
    }
}
