package net.aechronis.printer

import net.aechronis.combat.managers.CombatTags
import net.aechronis.grimac.GrimModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Printer mode turns Grim off for building with client-side printers, and ends as soon as its player fights. */
object Printer {
    private val enabled = ConcurrentHashMap.newKeySet<UUID>()
    private var grimDisabler: AutoCloseable? = null

    fun init(context: ModuleContext) {
        grimDisabler = GrimModule.registerDisabler(enabled::contains)

        // Ahead of Guard, so any swing from a printer player is refused, even one Guard would cancel anyway.
        val attacks = EventNode.all("printer-attacks").setPriority(-2000)
        attacks.addListener(EntityDamageEvent::class.java, ::onAttack)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), attacks)

        // After protection, matching combat tags, so only hits that land end printer mode for the victim.
        val hits = EventNode.all("printer-hits").setPriority(1000)
        hits.addListener(EntityDamageEvent::class.java, ::onHit)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), hits)

        context.addListener(PlayerDisconnectEvent::class.java) { enabled.remove(it.player.uuid) }
    }

    fun toggle(player: Player) {
        if (enabled.contains(player.uuid)) {
            disable(player, "Printer disabled.")
            return
        }
        if (CombatTags.isInCombat(player)) {
            player.sendMessage(Component.text("You can't use printer while in combat.", NamedTextColor.RED))
            return
        }

        enabled += player.uuid
        GrimModule.refresh(player)
        player.sendMessage(Component.text("Printer enabled. Fighting will turn it off.", NamedTextColor.GREEN))
    }

    private fun disable(
        player: Player,
        message: String,
    ) {
        if (!enabled.remove(player.uuid)) return
        GrimModule.refresh(player)
        player.sendMessage(Component.text(message, NamedTextColor.YELLOW))
    }

    // Damage dealt with the anticheat off never lands.
    private fun onAttack(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val attacker = event.damage.attacker as? Player ?: return
        if (attacker.uuid !in enabled || attacker.uuid == event.entity.uuid) return

        event.isCancelled = true
        disable(attacker, "Printer disabled: you attacked something.")
    }

    private fun onHit(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val victim = event.entity as? Player ?: return
        val attacker = event.damage.attacker as? Player ?: return
        if (attacker.uuid == victim.uuid) return

        disable(victim, "Printer disabled: you entered combat.")
    }

    fun shutdown() {
        grimDisabler?.close()
        grimDisabler = null
        val players = enabled.mapNotNull(MinecraftServer.getConnectionManager()::getOnlinePlayerByUuid)
        enabled.clear()
        players.forEach(GrimModule::refresh)
    }

    internal fun captureTransientState(): ByteArray = enabled.joinToString("\n").toByteArray()

    internal fun restoreTransientState(payload: ByteArray?) {
        if (payload == null) return
        val connections = MinecraftServer.getConnectionManager()
        payload
            .decodeToString()
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { connections.getOnlinePlayerByUuid(UUID.fromString(it)) }
            .forEach {
                enabled += it.uuid
                GrimModule.refresh(it)
            }
    }
}
