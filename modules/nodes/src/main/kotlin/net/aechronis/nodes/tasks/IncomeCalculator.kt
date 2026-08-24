package net.aechronis.nodes.tasks

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.Trains
import net.aechronis.nodes.war.Warzone
import net.minestom.server.item.Material

/** The per-tick income assigned to one town, separated by its producer. */
data class IncomeBreakdown(
    val territory: Map<Material, Double>,
    val buildings: Map<Material, Double>,
    val trains: Map<Material, Double>,
) {
    val total: Map<Material, Double>
        get() = buildMap {
            addAll(territory)
            addAll(buildings)
            addAll(trains)
        }

    private fun MutableMap<Material, Double>.addAll(income: Map<Material, Double>) {
        income.forEach { (material, amount) ->
            this[material] = (this[material] ?: 0.0) + amount
        }
    }

    companion object {
        val EMPTY = IncomeBreakdown(emptyMap(), emptyMap(), emptyMap())
    }
}

object IncomeCalculator {
    /**
     * Calculates the total rate that is collected by each town. Kept as the
     * collection API so income collection and player-facing rates cannot drift.
     */
    fun calculate(): Map<Town, Map<Material, Double>> = calculateBreakdown()
        .mapValues { (_, breakdown) -> breakdown.total }

    /**
     * Calculates the same rates as [calculate], retaining the source for town
     * income information. Building income belongs to the town which owns the
     * territory containing the building, and is taxed while that territory is
     * occupied just like resource-node and train income.
     */
    fun calculateBreakdown(): Map<Town, IncomeBreakdown> {
        val incomes = LinkedHashMap<Town, MutableIncomeBreakdown>()

        Nodes.towns.values.forEach { town ->
            // A town may already have tax income from an earlier town's occupied
            // territory. Do not replace that accumulator when its own turn runs.
            incomes.getOrPut(town, ::MutableIncomeBreakdown)
            town.territories.forEach { territoryId ->
                val territory = Territory.fromId(territoryId) ?: return@forEach
                val territoryIncome = incomeForTerritory(territory)
                val occupier = territory.occupier
                if (occupier == null) {
                    incomes.getValue(town).add(territoryIncome)
                } else {
                    val taxRate = Nodes.config.taxIncomeRate.coerceIn(0.0, 1.0)
                    incomes.getOrPut(occupier, ::MutableIncomeBreakdown).add(territoryIncome, taxRate)
                    incomes.getValue(town).add(territoryIncome, 1.0 - taxRate)
                }
            }
        }

        return incomes.mapValues { (_, income) -> income.toImmutable() }
    }

    private fun incomeForTerritory(territory: Territory): IncomeBreakdown {
        val buildings = mutableMapOf<Material, Double>()
        val trains = mutableMapOf<Material, Double>()
        territory.chunks.forEach { coord ->
            Nodes.chunkToBuilding[listOf(coord.x, coord.z)]?.income()?.forEach { (material, amount) ->
                add(buildings, material, amount)
            }
            Trains.incomeAt(coord.x, coord.z).forEach { (material, amount) ->
                add(trains, material, amount)
            }
        }

        val multiplier = Warzone.multiplierFor(territory)
        return IncomeBreakdown(
            territory = territory.income.withMultiplier(multiplier),
            buildings = buildings.withMultiplier(multiplier),
            trains = trains.withMultiplier(multiplier),
        )
    }

    private fun Map<Material, Double>.withMultiplier(
        multiplier: Double,
    ): Map<Material, Double> = if (multiplier == 1.0) {
        toMap()
    } else {
        mapValues { (_, amount) -> amount * multiplier }
    }

    private fun add(income: MutableMap<Material, Double>, material: Material, amount: Double) {
        income[material] = (income[material] ?: 0.0) + amount
    }

    private class MutableIncomeBreakdown {
        private val territory = mutableMapOf<Material, Double>()
        private val buildings = mutableMapOf<Material, Double>()
        private val trains = mutableMapOf<Material, Double>()

        fun add(income: IncomeBreakdown, multiplier: Double = 1.0) {
            add(territory, income.territory, multiplier)
            add(buildings, income.buildings, multiplier)
            add(trains, income.trains, multiplier)
        }

        fun toImmutable(): IncomeBreakdown = IncomeBreakdown(territory.toMap(), buildings.toMap(), trains.toMap())

        private fun add(
            destination: MutableMap<Material, Double>,
            source: Map<Material, Double>,
            multiplier: Double,
        ) {
            source.forEach { (material, amount) ->
                this@IncomeCalculator.add(destination, material, amount * multiplier)
            }
        }
    }
}
