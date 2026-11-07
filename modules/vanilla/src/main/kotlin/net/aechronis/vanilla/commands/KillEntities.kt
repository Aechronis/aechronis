package net.aechronis.vanilla.commands

import net.aechronis.combat.managers.Corpses
import net.aechronis.combat.objects.Vehicle
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player

class KillEntities : Command("killentities", "vanilla.killentities") {
    private val radiusArg = ArgumentType.Double("radius").min(0.0)

    init {
        setDefaultExecutor { sender: Player, _ ->
            sender.sendMessage(Component.text("Usage: /killentities <radius|all>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, _ -> kill(sender) { true } }, ArgumentType.Literal("all"))

        addSyntax({ sender: Player, context ->
            val radiusSq = context[radiusArg].let { it * it }
            kill(sender) { it.instance === sender.instance && it.position.distanceSquared(sender.position) <= radiusSq }
        }, radiusArg)
    }

    private fun kill(
        sender: Player,
        filter: (Entity) -> Boolean,
    ) {
        val loot =
            Corpses.inventories
                .filterKeys(filter)
                .values
                .toList()
        loot.forEach(Corpses::despawn)
        val corpses = loot.size
        val vehicles = Vehicle.removeAll(filter)
        sender.sendMessage(Component.text("Removed $vehicles vehicle(s) and $corpses corpse(s).", NamedTextColor.LIGHT_PURPLE))
    }
}
