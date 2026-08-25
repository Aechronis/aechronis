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
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import kotlin.math.abs

class GuardCommand(
    permission: String,
    bypassPermission: String = "guard.bypass",
) : Command("guard", permission) {
    private val nameArg =
        ArgumentType.Word("name").setSuggestionCallback { _, _, suggestion ->
            val input = suggestion.getInput()
            val start = suggestion.getStart().coerceIn(0, input.length)
            val end = (start + suggestion.getLength()).coerceIn(start, input.length)
            val prefix = input.substring(start, end)
            runCatching { Guard.zones().all() }
                .getOrDefault(emptyList())
                .sortedBy { it.name.lowercase() }
                .filter { it.name.startsWith(prefix, ignoreCase = true) }
                .forEach { zone ->
                    suggestion.addEntry(
                        SuggestionEntry(
                            zone.name,
                            Component.text("Priority ${zone.priority}", NamedTextColor.GRAY),
                        ),
                    )
                }
        }
    private val priorityArg = ArgumentType.Integer("priority")
    private val flagArg = ArgumentType.Word("flag").from(*FlagName.entries.map { it.id }.toTypedArray())
    private val valueArg =
        ArgumentType.StringArray("value").setSuggestionCallback { _, context, suggestion ->
            if (FlagName.fromId(context[flagArg]) == null) return@setSuggestionCallback
            listOf("true", "false")
                .filter { it.startsWith(suggestion.getInput().substringAfterLast(' '), ignoreCase = true) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }

    init {
        setDefaultExecutor { player, _ -> usage(player) }
        addSyntax({ player: Player, _ -> list(player) }, ArgumentType.Literal("list"))
        addSyntax({ player: Player, _ -> here(player) }, ArgumentType.Literal("here"))
        addSyntax(
            bypassPermission,
            { player: Player, _ -> toggleBypass(player) },
            ArgumentType.Literal("bypass"),
        )
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
        addSyntax({ player: Player, context -> info(player, context[nameArg]) }, ArgumentType.Literal("info"), nameArg)
        addSyntax({ player: Player, context -> info(player, context[nameArg]) }, ArgumentType.Literal("show"), nameArg)
        addSyntax({ player: Player, context -> borders(player, context[nameArg]) }, ArgumentType.Literal("borders"), nameArg)
    }

    private fun create(
        player: Player,
        name: String,
        priority: Int,
    ) {
        runCatching {
            val selection = WorldEditSelection.read(player)
            Guard.zones().add(Zone(name, selection.bounds, priority))
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

    private fun here(player: Player) {
        val position = player.position
        val zones = Guard.zones().findAll(position.blockX(), position.blockY(), position.blockZ())
        if (zones.isEmpty()) {
            send(player, "You are not inside any guard zones.")
            return
        }
        send(player, "Zones here (highest priority first):")
        zones.forEachIndexed { index, zone -> send(player, "${index + 1}. ${zone.name} (priority ${zone.priority})") }
    }

    private fun toggleBypass(player: Player) {
        val enabled = Guard.toggleBypass(player)
        send(player, "Guard bypass ${if (enabled) "enabled" else "disabled"}.")
    }

    private fun info(
        player: Player,
        name: String,
    ) {
        val zone = Guard.zones().get(name)
        if (zone == null) {
            send(player, "Unknown zone: $name", NamedTextColor.RED)
            return
        }
        send(player, "${zone.name}: priority ${zone.priority}, bounds ${zone.bounds}, flags ${zone.flags}")
    }

    private fun borders(
        player: Player,
        name: String,
    ) {
        val zone = Guard.zones().get(name)
        if (zone == null) {
            send(player, "Unknown zone: $name", NamedTextColor.RED)
            return
        }
        val particles = borderParticles(zone).toTypedArray()
        player.sendPackets(*particles)
        send(player, "Showing ${zone.name} borders with orange wax particles.")
    }

    private fun borderParticles(zone: Zone): List<ParticlePacket> {
        val bounds = zone.bounds
        val positions = linkedSetOf<Triple<Int, Int, Int>>()

        fun addEdge(
            startX: Int,
            startY: Int,
            startZ: Int,
            endX: Int,
            endY: Int,
            endZ: Int,
        ) {
            val length = maxOf(abs(endX - startX), abs(endY - startY), abs(endZ - startZ))
            val xStep = (endX - startX).compareTo(0)
            val yStep = (endY - startY).compareTo(0)
            val zStep = (endZ - startZ).compareTo(0)
            for (step in 0..length) {
                positions += Triple(startX + step * xStep, startY + step * yStep, startZ + step * zStep)
            }
        }

        addEdge(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.minY, bounds.minZ)
        addEdge(bounds.minX, bounds.minY, bounds.maxZ, bounds.maxX, bounds.minY, bounds.maxZ)
        addEdge(bounds.minX, bounds.maxY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.minZ)
        addEdge(bounds.minX, bounds.maxY, bounds.maxZ, bounds.maxX, bounds.maxY, bounds.maxZ)
        addEdge(bounds.minX, bounds.minY, bounds.minZ, bounds.minX, bounds.minY, bounds.maxZ)
        addEdge(bounds.maxX, bounds.minY, bounds.minZ, bounds.maxX, bounds.minY, bounds.maxZ)
        addEdge(bounds.minX, bounds.maxY, bounds.minZ, bounds.minX, bounds.maxY, bounds.maxZ)
        addEdge(bounds.maxX, bounds.maxY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)
        addEdge(bounds.minX, bounds.minY, bounds.minZ, bounds.minX, bounds.maxY, bounds.minZ)
        addEdge(bounds.maxX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.minZ)
        addEdge(bounds.minX, bounds.minY, bounds.maxZ, bounds.minX, bounds.maxY, bounds.maxZ)
        addEdge(bounds.maxX, bounds.minY, bounds.maxZ, bounds.maxX, bounds.maxY, bounds.maxZ)

        return positions.map { (x, y, z) ->
            ParticlePacket(
                Particle.WAX_ON,
                Pos(x + 0.5, y + 0.5, z + 0.5),
                Vec(0.05, 0.05, 0.05),
                0F,
                1,
            )
        }
    }

    private fun usage(player: Player) {
        send(player, "Usage: /guard list | here | info <name> | borders <name> | bypass")
        send(player, "Admin: /guard create <name> <priority> | remove <name> | set <name> <flag> <value> | unset <name> <flag>")
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
