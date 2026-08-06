package net.aechronis.guard.commands

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.flags.FlagValueParser
import net.aechronis.guard.objects.WorldEditSelection
import net.aechronis.guard.objects.Zone
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class GuardCommand(
    permission: String,
) : Command("guard", permission) {
    private val nameArg = ArgumentType.Word("name")
    private val priorityArg = ArgumentType.Integer("priority")
    private val flagArg = ArgumentType.Word("flag").from(*FlagName.entries.map { it.id }.toTypedArray())
    private val valueArg = ArgumentType.StringArray("value")

    init {
        setDefaultExecutor { player, _ -> usage(player) }
        addSyntax({ player: Player, _ -> list(player) }, ArgumentType.Literal("list"))
        addSyntax({ player: Player, context -> remove(player, context[nameArg]) }, ArgumentType.Literal("remove"), nameArg)
        addSyntax(
            { player: Player, context ->
                create(
                    player,
                    context[nameArg],
                    context[priorityArg],
                )
            },
            ArgumentType.Literal("create"),
            nameArg,
            priorityArg,
        )
        addSyntax(
            { player: Player, context -> setFlag(player, context[nameArg], context[flagArg], context[valueArg].joinToString(" ")) },
            ArgumentType.Literal("set"),
            nameArg,
            flagArg,
            valueArg,
        )
        addSyntax(
            { player: Player, context -> removeFlag(player, context[nameArg], context[flagArg]) },
            ArgumentType.Literal("unset"),
            nameArg,
            flagArg,
        )
        addSyntax({ player: Player, context -> show(player, context[nameArg]) }, ArgumentType.Literal("show"), nameArg)
    }

    private fun create(
        player: Player,
        name: String,
        priority: Int,
    ) {
        runCatching {
            val selection = WorldEditSelection.read(player)
            Guard.zones().add(Zone(name, selection.instanceId, selection.bounds, priority))
            Guard.save()
            send(player, "Created zone $name.")
        }.onFailure { send(player, it.message ?: "Could not create zone.", NamedTextColor.RED) }
    }

    private fun remove(
        player: Player,
        name: String,
    ) {
        if (Guard.zones().remove(name) == null) {
            send(player, "Unknown zone: $name", NamedTextColor.RED)
            return
        }
        Guard.save()
        send(player, "Removed zone $name.")
    }

    private fun setFlag(
        player: Player,
        name: String,
        flagId: String,
        rawValue: String,
    ) {
        val zone = Guard.zones().get(name)
        val flag = FlagName.fromId(flagId)
        if (zone == null || flag == null) {
            send(player, "Unknown zone or flag.", NamedTextColor.RED)
            return
        }
        runCatching {
            val flags = zone.flags.toMutableMap()
            flags[flag] = FlagValueParser.parse(rawValue)
            Guard.zones().replace(zone.copy(flags = flags))
            Guard.save()
            send(player, "Set ${flag.id} in ${zone.name} to $rawValue.")
        }.onFailure { send(player, it.message ?: "Could not set flag.", NamedTextColor.RED) }
    }

    private fun removeFlag(
        player: Player,
        name: String,
        flagId: String,
    ) {
        val zone = Guard.zones().get(name)
        val flag = FlagName.fromId(flagId)
        if (zone == null || flag == null) {
            send(player, "Unknown zone or flag.", NamedTextColor.RED)
            return
        }
        Guard.zones().replace(zone.copy(flags = zone.flags - flag))
        Guard.save()
        send(player, "Removed ${flag.id} from ${zone.name}.")
    }

    private fun list(player: Player) {
        val zones = Guard.zones().all()
        send(player, if (zones.isEmpty()) "No zones configured." else zones.joinToString(prefix = "Zones: ") { it.name })
    }

    private fun show(
        player: Player,
        name: String,
    ) {
        val zone = Guard.zones().get(name)
        if (zone == null) {
            send(player, "Unknown zone: $name", NamedTextColor.RED)
            return
        }
        send(player, "${zone.name}: ${zone.instanceId}, priority ${zone.priority}, flags ${zone.flags}")
    }

    private fun usage(player: Player) {
        send(player, "Usage: /guard list | create <name> <priority> | remove | set | unset | show")
        send(player, "Select both corners with the WorldEdit wand before creating a zone.")
    }

    private fun send(
        player: Player,
        message: String,
        color: NamedTextColor = NamedTextColor.LIGHT_PURPLE,
    ) {
        player.sendMessage(Component.text(message, color))
    }
}
