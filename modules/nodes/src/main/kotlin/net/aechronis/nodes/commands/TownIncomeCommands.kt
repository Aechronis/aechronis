package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.TownBuildingsMenu
import net.aechronis.nodes.tasks.IncomeBreakdown
import net.aechronis.nodes.tasks.IncomeCalculator
import net.aechronis.nodes.utils.ChatColor
import java.util.Locale

class TownBuildingsCommand : NodesCommand("buildings") {
    init {
        addSyntax({ player, resident, town, _ -> TownBuildingsMenu.open(player, town) })
    }
}

class TownIncomeCommand : NodesCommand("income") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /town income")
            Message.print(player, "/town income info: View income rates per income tick")
        }

        addSyntax({ player, resident, town, _ ->
            if (!canViewIncome(resident, town)) {
                Message.error(player, "You do not have permissions to view town income")
                return@addSyntax
            }

            player.openInventory(Town.incomeInventory(town))
        })

        addSubcommand(TownIncomeInfoCommand())
    }

    companion object {
        fun canViewIncome(resident: Resident, town: Town): Boolean {
            if (isTownStaff(resident, town)) return true
            if (town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TOWN) && resident.town === town) return true
            if (town.permissions[TownPermissions.INCOME].contains(PermissionsGroup.TRUSTED) && resident.town === town && resident.trusted) return true
            return false
        }
    }
}

class TownIncomeInfoCommand : NodesCommand("info") {
    init {
        addSyntax({ player, resident, town, _ ->
            if (!TownIncomeCommand.canViewIncome(resident, town)) {
                Message.error(player, "You do not have permissions to view town income")
                return@addSyntax
            }

            val breakdown = IncomeCalculator.calculateBreakdown()[town] ?: IncomeBreakdown.EMPTY
            val rates = breakdown.total.filterValues { it > 0.0 }
            val buildingRates = breakdown.buildings.filterValues { it > 0.0 }
            Message.print(player, "${ChatColor.BOLD}Income rates for ${town.name}:")
            Message.print(player, "Total per income tick (every hour):")
            printRates(player, rates)
            Message.print(player, "Building income per income tick:")
            printRates(player, buildingRates)
            Message.print(player, "Fractional amounts are averages; income is randomly rounded when collected.")
        })
    }

    private fun printRates(player: net.minestom.server.entity.Player, rates: Map<net.minestom.server.item.Material, Double>) {
        if (rates.isEmpty()) {
            Message.print(player, "- None")
            return
        }
        rates.entries
            .sortedBy { (material, _) -> material.name() }
            .forEach { (material, amount) ->
                Message.print(player, "- ${material.name()}${ChatColor.WHITE}: ${formatIncome(amount)}")
            }
    }

    private fun formatIncome(amount: Double): String = String.format(Locale.ROOT, "%.2f", amount)
}
