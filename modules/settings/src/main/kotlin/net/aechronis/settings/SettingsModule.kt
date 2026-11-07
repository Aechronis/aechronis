package net.aechronis.settings

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent

class SettingsModule : AechronisModule {
    override val id = "settings"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) {
        context.addListener(InventoryPreClickEvent::class.java, SettingsMenu::onClick)
        context.addListener(InventoryCloseEvent::class.java, SettingsMenu::onClose)
        ModuleCommands.register(SettingsCommand())
    }

    override fun shutdown(context: ModuleContext) = SettingsMenu.shutdown()
}
