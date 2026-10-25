package net.aechronis.warps

import net.aechronis.server.io.AtomicFiles
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.warps.commands.Back
import net.aechronis.warps.commands.Warp
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class WarpsModule : AechronisModule {
    override val id = "warps"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) {
        val config = takeConfiguration()
        val path = Path.of(config.path, config.warpsPath)
        val legacyPath = Path.of("vanilla", "warps.json")
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS) && Files.exists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            // Keep the original available if this module upgrade is rolled back.
            AtomicFiles.copy(legacyPath, path, preservePermissions = true)
        }
        Warps.init(path, config)
        ModuleCommands.register(Warp(), Back())
    }

    override fun saveState(context: ModuleContext) = Warps.saveAll()

    override fun shutdown(context: ModuleContext) = Warps.shutdown()

    companion object {
        private var configured: WarpsConfig? = null

        fun configure(config: WarpsConfig) {
            check(configured == null) { "Warps was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): WarpsConfig = (configured ?: WarpsConfig()).also { configured = null }
    }
}
