package net.aechronis.nodes.tasks

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.Trains
import net.aechronis.nodes.war.Warzone
import net.minestom.server.item.Material

object IncomeCalculator {
    fun calculate(): Map<Town, Map<Material, Double>> {
        val incomes = LinkedHashMap<Town, MutableMap<Material, Double>>()

        Nodes.towns.values.forEach { town ->
            incomes[town] = mutableMapOf()
            town.territories.forEach { territoryId ->
                val territory = Territory.fromId(territoryId) ?: return@forEach
                val territoryIncome = incomeForTerritory(territory)
                territory.occupier?.let { occupier ->
                    val occupierIncome = incomes.getOrPut(occupier) { mutableMapOf() }
                    val taxRate = Nodes.config.taxIncomeRate.coerceIn(0.0, 1.0)
                    val keptRate = 1.0 - taxRate
                    territoryIncome.forEach { (material, amount) ->
                        add(occupierIncome, material, amount * taxRate)
                        add(incomes.getValue(town), material, amount * keptRate)
                    }
                } ?: territoryIncome.forEach { (material, amount) ->
                    add(incomes.getValue(town), material, amount)
                }
            }
        }

        return incomes.mapValues { (_, income) -> income.toMap() }
    }

    private fun incomeForTerritory(territory: Territory): Map<Material, Double> {
        val income = mutableMapOf<Material, Double>()
        territory.income.forEach { (material, amount) -> add(income, material, amount) }
        territory.chunks.forEach { coord ->
            Nodes.chunkToBuilding[listOf(coord.x, coord.z)]?.income()?.forEach { (material, amount) ->
                add(income, material, amount)
            }
            Trains.incomeAt(coord.x, coord.z).forEach { (material, amount) ->
                add(income, material, amount)
            }
        }
        val multiplier = Warzone.multiplierFor(territory)
        if (multiplier != 1.0) income.replaceAll { _, amount -> amount * multiplier }
        return income
    }

    private fun add(income: MutableMap<Material, Double>, material: Material, amount: Double) {
        income[material] = (income[material] ?: 0.0) + amount
    }
}
