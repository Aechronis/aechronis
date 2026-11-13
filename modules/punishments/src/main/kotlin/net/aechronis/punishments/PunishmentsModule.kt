package net.aechronis.punishments

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerCommandEvent
import net.minestom.server.event.player.PlayerDisconnectEvent

class PunishmentsModule : AechronisModule {
    override val id = "punishments"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Punishments.repository = PunishmentRepository()
        PunishPerms.register()
        Enforcement.loadOnline()
        context.addListener(AsyncPlayerPreLoginEvent::class.java, Enforcement::onPreLogin)
        context.addListener(PlayerChatEvent::class.java, Enforcement::onChat)
        context.addListener(PlayerCommandEvent::class.java, Enforcement::onCommand)
        context.addListener(PlayerDisconnectEvent::class.java, Enforcement::onDisconnect)
        context.addListener(InventoryPreClickEvent::class.java, PunishMenu::onClick)
        context.addListener(InventoryCloseEvent::class.java, PunishMenu::onClose)
        ModuleCommands.register(
            PunishCommand(),
            HistoryCommand(),
            AddStrikeCommand(),
            RemoveStrikeCommand(),
            AltsCommand(),
            BlacklistCommand(),
            NewIterationCommand(),
            Evidence.EvidenceCommand(),
        )
        PunishmentApi.start(Integer.getInteger("aechronis.punishments.apiPort", 8126))
    }

    override fun shutdown(context: ModuleContext) {
        PunishmentApi.stop()
        PunishMenu.shutdown()
        Evidence.shutdown()
        Enforcement.clear()
        Punishments.listeners.clear()
        Punishments.noticeListeners.clear()
        Punishments.discordLookup = null
        runCatching { Punishments.repository.close() }.onFailure { System.err.println("[Punishments] Database close failed: $it") }
    }
}
