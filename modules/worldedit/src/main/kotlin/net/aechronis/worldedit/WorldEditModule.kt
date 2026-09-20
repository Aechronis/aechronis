package net.aechronis.worldedit

import io.github.openminigameserver.worldedit.MinestomWorldEdit
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure

class WorldEditModule : AechronisModule {
    override val id = "worldedit"
    override val dependencies = setOf("utils")

    private var worldEdit: MinestomWorldEdit? = null

    override fun initialize(context: ModuleContext) {
        check(worldEdit == null) { "WorldEdit module is already initialized" }
        val instance = measure("Platform construction") { MinestomWorldEdit() }
        worldEdit = instance
        instance.init()
    }

    override fun prepareForShutdown(context: ModuleContext) {
        worldEdit?.prepareForShutdown()
    }

    override fun shutdown(context: ModuleContext) {
        val instance = worldEdit ?: return
        instance.shutdown()
        worldEdit = null
    }
}
