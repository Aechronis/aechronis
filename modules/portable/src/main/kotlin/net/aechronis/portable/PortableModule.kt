package net.aechronis.portable

import net.aechronis.portable.commands.Craft
import net.aechronis.portable.commands.EnderChest
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.vanilla.Vanilla

class PortableModule : AechronisModule {
    override val id = "portable"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        ModuleCommands.register(EnderChest())
        if (Vanilla.config.recipesEnabled) ModuleCommands.register(Craft())
    }
}
