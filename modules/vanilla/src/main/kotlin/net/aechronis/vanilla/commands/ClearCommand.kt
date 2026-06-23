package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class ClearCommand : Command("clear", "vanilla.clear") {
    private val targetArg = ArgumentType.Word("target")
    private val whitelistArg = ArgumentType.StringArray("whitelist")

    init {
        setDefaultExecutor { player, _ ->
            Message.print(player, "Usage: /clear <player|*> [item ...]")
        }

        addSyntax({ sender: Player, context ->
            val targets = resolveTargets(sender, context[targetArg]) ?: return@addSyntax
            for (target in targets) {
                target.inventory.clear()
            }
            Message.print(sender, "Cleared inventory of ${targets.size} player(s).")
        }, targetArg)

        addSyntax({ sender: Player, context ->
            val targets = resolveTargets(sender, context[targetArg]) ?: return@addSyntax

            val whitelist = HashSet<Material>()
            val unknown = ArrayList<String>()
            for (name in context[whitelistArg]) {
                val material = Material.fromKey(name)
                if (material == null) {
                    unknown.add(name)
                } else {
                    whitelist.add(material)
                }
            }

            if (unknown.isNotEmpty()) {
                Message.error(sender, "Unknown item(s): ${unknown.joinToString(", ")}")
                return@addSyntax
            }

            var cleared = 0
            for (target in targets) {
                val inv = target.inventory
                for (slot in 0 until inv.size) {
                    val stack = inv.getItemStack(slot)
                    if (!stack.isAir && whitelist.contains(stack.material())) {
                        inv.setItemStack(slot, ItemStack.AIR)
                        cleared += stack.amount()
                    }
                }
            }
            Message.print(sender, "Cleared $cleared matching item(s) from ${targets.size} player(s).")
        }, targetArg, whitelistArg)
    }

    private fun resolveTargets(
        sender: Player,
        target: String,
    ): List<Player>? {
        if (target == "*") {
            return MinecraftServer.getConnectionManager().onlinePlayers.toList()
        }

        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(target)
        if (player == null) {
            Message.error(sender, "Player not found: $target")
            return null
        }
        return listOf(player)
    }
}
