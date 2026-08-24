/**
 * Displays the effective resource rates for the territory at the player's location.
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Warzone
import java.util.Locale

class RatesCommand : NodesCommand("rates") {
    init {
        setDefaultExecutor { player, _, _ ->
            val territory = Territory.fromPlayer(player)
            if (territory == null) {
                Message.error(player, "No territory at current location")
                return@setDefaultExecutor
            }

            val multiplier = Warzone.multiplierFor(territory)
            Message.print(player, "${ChatColor.BOLD}Rates for ${territory.name.ifBlank { "Unnamed territory" }}:")
            if (multiplier != 1.0) Message.print(player, "Warzone multiplier${ChatColor.WHITE}: ${format(multiplier)}x")
            Message.print(player, "Income (per hour):")
            if (territory.income.isEmpty()) {
                Message.print(player, "- None")
            } else {
                territory.income.entries
                    .sortedBy { (material, _) -> material.name() }
                    .forEach { (material, amount) ->
                        Message.print(player, "- ${material.name()}${ChatColor.WHITE}: ${format(amount * multiplier)}")
                    }
            }

            Message.print(player, "Ores:")
            if (territory.ores.deposits.isEmpty()) {
                Message.print(player, "- None")
            } else {
                territory.ores.deposits
                    .sortedBy { it.material.name() }
                    .forEach { ore ->
                        val amount = if (ore.minAmount == ore.maxAmount) {
                            format(ore.minAmount * multiplier)
                        } else {
                            "${format(ore.minAmount * multiplier)}-${format(ore.maxAmount * multiplier)}"
                        }
                        Message.print(
                            player,
                            "- ${ore.material.name()}${ChatColor.WHITE}: ${format(ore.dropChance * 100.0)}% chance, $amount drop, Y ${ore.ymin}-${ore.ymax}",
                        )
                    }
            }
        }
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}
