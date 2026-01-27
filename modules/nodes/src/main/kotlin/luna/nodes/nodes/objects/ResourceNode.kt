/**
 * Resource Node
 *
 * Composable resource modifier attached to territory:
 *    income: [(blockId, count), ...]
 *    ore: [(blockId, rate, count), ...]
 * Territory calculates net resources from array of
 * nodes during initialization
 */

package luna.nodes.nodes.objects

import com.google.gson.JsonObject
import luna.nodes.nodes.Message
import luna.nodes.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender
import net.minestom.server.item.Material

/**
 * Interface for resource attributes. These are modifiers
 * that are applied to territories to modify their resource
 * properties.
 */
interface ResourceAttribute {
    /**
     * Sort priority within a ResourceNode object's attributes.
     * Lower priority attributes are applied first.
     */
    val priority: Int

    /**
     * Apply this attribute to a TerritoryResources to generate a
     * new TerritoryResources with modified properties.
     */
    fun apply(resources: TerritoryResources): TerritoryResources

    /**
     * Return string description of this attribute
     */
    fun describe(): String
}

/**
 * Interface for resource attribute loading system.
 */
interface ResourceAttributeLoader {
    /**
     * Load resource attribute definitions from a parsed JsonObject.
     * This takes the current resource nodes state and outputs a new
     * state with new attributes loaded from the JsonObject.
     * The load implementation determines which fields correspond to
     * different resource attributes.
     */
    fun load(json: JsonObject): HashMap<String, ResourceNode>
}

/**
 * Resources are composed of a list of ResourceAttribute
 * objects. ResourceAttribute objects are not unique and can be
 * duplicated in a Resource.
 */
data class ResourceNode(
    val name: String,
    val icon: String?,
    val priority: Int, // sort priority vs. other resource nodes, lower = applied first
    val attributes: List<ResourceAttribute>,
) {
    // keep internal sorted attributes list to protect against
    // client passing a non-sorted attributes list
    val attributesSorted = attributes.sortedBy { it.priority }

    /**
     * Apply resource node attributes to a TerritoryResources.
     */
    fun apply(terr: TerritoryResources): TerritoryResources = this.attributesSorted.fold(terr) { t, attribute ->
        attribute.apply(t)
    }

    /**
     * Print resource node attributes info to sender.
     */
    fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}Resource node \"${this.name}\":")
        for (attribute in this.attributesSorted) {
            Message.print(sender, attribute.describe())
        }
    }
}

// ============================================================================
// RESOURCE ATTRIBUTE IMPLEMENTATIONS
// ============================================================================

data class ResourceAttributeIncome(
    private val income: MutableMap<Material, Double>,
) : ResourceAttribute {
    override val priority: Int = 1
    val description: String

    init {
        val s = StringBuilder("Income:\n")
        for ((item, value) in this.income.entries) {
            s.append("- $item: ${value}\n")
        }

        description = s.toString()
    }

    /**
     * Add income values from this attribute into territory income.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newIncome = resources.income.toMutableMap()

        this.income.forEach { k, v ->
            newIncome[k]?.let { currentVal ->
                newIncome.put(k, currentVal + v)
            } ?: run {
                newIncome.put(k, v)
            }
        }

        return resources.copy(
            income = newIncome,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeOre(
    private val ores: MutableMap<Material, OreDeposit>,
) : ResourceAttribute {
    override val priority: Int = 2
    val description: String

    init {
        val s = StringBuilder("Ore:\n")
        for (entry in this.ores.entries) {
            val ore = entry.value
            s.append("- ${entry.key}: ${ore.dropChance} ${ore.minAmount}-${ore.maxAmount}\n")
        }

        description = s.toString()
    }

    /**
     * Add ore rate probability from this attribute into territory.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        // deep clone ores
        val newOres: MutableMap<Material, OreDeposit> = mutableMapOf()
        for ((mat, ore) in resources.ores) {
            newOres.put(mat, ore.copy())
        }

        // merge ore deposits
        this.ores.forEach { k, v ->
            newOres[k]?.let { oreDeposit ->
                newOres.put(k, oreDeposit.merge(v))
            } ?: run {
                newOres.put(k, v)
            }
        }

        return resources.copy(
            ores = newOres,
        )
    }

    override fun describe(): String = this.description
}

// ============================================================================
// MULTIPLIER ATTRIBUTES
// ============================================================================

data class ResourceAttributeTotalIncomeMultiplier(
    val multiplier: Double,
) : ResourceAttribute {
    override val priority: Int = 50
    val description: String = "Income Multiplier: ${this.multiplier}"

    /**
     * Apply multiplier to resource's income.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newIncome = resources.income.toMutableMap()

        newIncome.forEach { (type, amount) ->
            newIncome[type] = amount * multiplier
        }

        return resources.copy(
            income = newIncome,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeTotalOreMultiplier(
    val multiplier: Double,
) : ResourceAttribute {
    override val priority: Int = 50
    val description: String = "Ore Multiplier: ${this.multiplier}"

    /**
     * Apply multiplier to resource's ores.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newOres = resources.ores.toMutableMap()

        newOres.forEach { (type, ore) ->
            newOres[type] = ore.copy(dropChance = ore.dropChance * multiplier)
        }

        return resources.copy(
            ores = newOres,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeIncomeMultiplier(
    private val income: MutableMap<Material, Double>,
) : ResourceAttribute {
    override val priority: Int = 50
    val description: String

    init {
        val s = StringBuilder("Income Multiplier:\n")
        for ((item, value) in this.income.entries) {
            s.append("- $item: ${value}\n")
        }

        description = s.toString()
    }

    /**
     * Apply multipliers to matching keys in the resource.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newIncome = resources.income.toMutableMap()

        this.income.forEach { type, multiplier ->
            if (newIncome.containsKey(type)) {
                newIncome[type] = newIncome[type]!! * multiplier
            }
        }

        return resources.copy(
            income = newIncome,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeOreMultiplier(
    private val multiplier: MutableMap<Material, Double>,
) : ResourceAttribute {
    override val priority: Int = 50
    val description: String

    init {
        val s = StringBuilder("Ore Multiplier:\n")
        for ((type, entry) in this.multiplier) {
            s.append("- $type: ${entry}\n")
        }

        description = s.toString()
    }

    /**
     * Apply multipliers to matching keys in the resource.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        // deep clone ores
        val newOres = resources.ores.toMutableMap()
        for ((mat, ore) in newOres) {
            newOres.put(mat, ore.copy())
        }

        // merge ore deposits
        this.multiplier.forEach { type, multiplier ->
            if (newOres.containsKey(type)) {
                val oreDeposit = newOres[type]!!
                newOres[type] = oreDeposit.copy(dropChance = oreDeposit.dropChance * multiplier)
            }
        }

        return resources.copy(
            ores = newOres,
        )
    }

    override fun describe(): String = this.description
}

// ============================================================================
// NEIGHBOR ATTRIBUTES
// ============================================================================

data class ResourceAttributeNeighborIncome(
    private val income: MutableMap<Material, Double>,
) : ResourceAttribute {
    override val priority: Int = 100
    val description: String

    init {
        val s = StringBuilder("Neighbor Income:\n")
        for ((item, value) in this.income.entries) {
            s.append("- $item: ${value}\n")
        }

        description = s.toString()
    }

    /**
     * Add together neighbor multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newIncome = resources.neighborIncome?.toMutableMap() ?: mutableMapOf()

        this.income.forEach { k, v ->
            newIncome[k]?.let { currentVal ->
                newIncome.put(k, currentVal + v)
            } ?: run {
                newIncome.put(k, v)
            }
        }

        return resources.copy(
            neighborIncome = newIncome,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeNeighborOre(
    private val ores: MutableMap<Material, OreDeposit>,
) : ResourceAttribute {
    override val priority: Int = 100
    val description: String

    init {
        val s = StringBuilder("Neighbor Ore:\n")
        for (entry in this.ores.entries) {
            val ore = entry.value
            s.append("- ${entry.key}: ${ore.dropChance} ${ore.minAmount}-${ore.maxAmount}\n")
        }

        description = s.toString()
    }

    /**
     * Add ore rate probability from this attribute into territory.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        // deep clone ores
        val newOres = resources.neighborOres?.toMutableMap() ?: mutableMapOf()
        for ((mat, ore) in newOres) {
            newOres.put(mat, ore.copy())
        }

        // merge ore deposits
        this.ores.forEach { k, v ->
            newOres[k]?.let { oreDeposit ->
                newOres.put(k, oreDeposit.merge(v))
            } ?: run {
                newOres.put(k, v)
            }
        }

        return resources.copy(
            neighborOres = newOres,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeNeighborTotalIncomeMultiplier(
    private val neighborTotalIncomeMultiplier: Double,
) : ResourceAttribute {
    override val priority: Int = 100
    val description: String = "Neighbor Total Income Multiplier: ${this.neighborTotalIncomeMultiplier}"

    /**
     * Add together neighbor multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val neighborTotalIncomeMultiplier = resources.neighborTotalIncomeMultiplier ?: 0.0
        return resources.copy(
            neighborTotalIncomeMultiplier = neighborTotalIncomeMultiplier + this.neighborTotalIncomeMultiplier,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeNeighborIncomeMultiplier(
    private val neighborIncomeMultiplier: MutableMap<Material, Double>,
) : ResourceAttribute {
    override val priority: Int = 100
    val description: String

    init {
        val s = StringBuilder("Neighbor Income Multipliers:\n")
        for (entry in this.neighborIncomeMultiplier.entries) {
            s.append("- ${entry.key}: ${entry.value}\n")
        }

        description = s.toString()
    }

    /**
     * Add together neighbor multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newNeighborIncomeMultiplier = resources.neighborIncomeMultiplier?.toMutableMap() ?: mutableMapOf()

        this.neighborIncomeMultiplier.forEach { k, v ->
            newNeighborIncomeMultiplier[k]?.let { currentVal ->
                newNeighborIncomeMultiplier.put(k, currentVal + v)
            } ?: run {
                newNeighborIncomeMultiplier.put(k, v)
            }
        }

        return resources.copy(
            neighborIncomeMultiplier = newNeighborIncomeMultiplier,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeNeighborTotalOreMultiplier(
    private val neighborTotalOresMultiplier: Double,
) : ResourceAttribute {
    override val priority: Int = 100
    val description: String = "Neighbor Total Ore Multiplier: ${this.neighborTotalOresMultiplier}"

    /**
     * Add together neighbor multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val neighborTotalOresMultiplier = resources.neighborTotalOresMultiplier ?: 0.0
        return resources.copy(
            neighborTotalOresMultiplier = neighborTotalOresMultiplier + this.neighborTotalOresMultiplier,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeNeighborOreMultiplier(
    private val neighborOresMultiplier: MutableMap<Material, Double>,
) : ResourceAttribute {
    override val priority: Int = 100
    val description: String

    init {
        val s = StringBuilder("Neighbor Ores Multipliers:\n")
        for (entry in this.neighborOresMultiplier.entries) {
            s.append("- ${entry.key}: ${entry.value}\n")
        }

        description = s.toString()
    }

    /**
     * Add together neighbor multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources {
        val newNeighborOresMultiplier = resources.neighborOresMultiplier?.toMutableMap() ?: mutableMapOf()

        this.neighborOresMultiplier.forEach { k, v ->
            newNeighborOresMultiplier[k]?.let { currentVal ->
                newNeighborOresMultiplier.put(k, currentVal + v)
            } ?: run {
                newNeighborOresMultiplier.put(k, v)
            }
        }

        return resources.copy(
            neighborOresMultiplier = newNeighborOresMultiplier,
        )
    }

    override fun describe(): String = this.description
}

data class ResourceAttributeAttackerTimeMultiplier(
    val multiplier: Double,
) : ResourceAttribute {
    override val priority: Int = 200
    val description: String = "Attacker Time Multiplier: ${this.multiplier}"

    /**
     * Multiply together multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources = resources.copy(
        attackerTimeMultiplier = resources.attackerTimeMultiplier * multiplier,
    )

    override fun describe(): String = this.description
}

data class ResourceAttributeDefenderTimeMultiplier(
    val multiplier: Double,
) : ResourceAttribute {
    override val priority: Int = 201
    val description: String = "Defender Time Multiplier: ${this.multiplier}"

    /**
     * Multiply together multipliers.
     */
    override fun apply(resources: TerritoryResources): TerritoryResources = resources.copy(
        defenderTimeMultiplier = resources.defenderTimeMultiplier * multiplier,
    )

    override fun describe(): String = this.description
}

// ============================================================================
// RESOURCE ATTRIBUTE LOADER IMPLEMENTATION
// ============================================================================

object DefaultResourceAttributeLoader : ResourceAttributeLoader {
    override fun load(json: JsonObject): HashMap<String, ResourceNode> {
        // this should always run first. ignore existing resources input
        val resources: HashMap<String, ResourceNode> = hashMapOf()

        for (name in json.keySet()) {
            try {
                val node = json[name].asJsonObject

                val attributes: ArrayList<ResourceAttribute> = arrayListOf()

                // icon
                val icon = node.get("icon")?.let { jsonIcon ->
                    if (jsonIcon.isJsonPrimitive) {
                        jsonIcon.asString
                    } else {
                        null
                    }
                }

                // priority
                val priority = node.get("priority")?.asInt ?: 0

                // ATTRIBUTES

                // main resource attributes
                node.get("income")?.asJsonObject?.let { jsonIncome ->
                    if (jsonIncome.size() > 0) {
                        val income = parseJsonIncome(jsonIncome)
                        attributes.add(ResourceAttributeIncome(income))
                    }
                }
                node.get("ore")?.asJsonObject?.let { jsonOre ->
                    if (jsonOre.size() > 0) {
                        val ores = parseJsonMapMaterialToOre(jsonOre)
                        attributes.add(ResourceAttributeOre(ores))
                    }
                }

                // territory total modifiers
                node.get("income_total_multiplier")?.asDouble?.let { multiplier ->
                    attributes.add(ResourceAttributeTotalIncomeMultiplier(multiplier))
                }
                node.get("ore_total_multiplier")?.asDouble?.let { multiplier ->
                    attributes.add(ResourceAttributeTotalOreMultiplier(multiplier))
                }

                // territory specific type multipliers
                node.get("income_multiplier")?.asJsonObject?.let { jsonIncome ->
                    if (jsonIncome.size() > 0) {
                        val incomeMultiplier = parseJsonIncome(jsonIncome)
                        attributes.add(ResourceAttributeIncomeMultiplier(incomeMultiplier))
                    }
                }
                node.get("ore_multiplier")?.asJsonObject?.let { jsonOre ->
                    if (jsonOre.size() > 0) {
                        val oresMultiplier = parseJsonMapMaterialToDouble(jsonOre)
                        attributes.add(ResourceAttributeOreMultiplier(oresMultiplier))
                    }
                }

                // neighbor direct properties
                node.get("neighbor_income")?.asJsonObject?.let { jsonIncome ->
                    if (jsonIncome.size() > 0) {
                        val income = parseJsonIncome(jsonIncome)
                        attributes.add(ResourceAttributeNeighborIncome(income))
                    }
                }
                node.get("neighbor_ore")?.asJsonObject?.let { jsonOre ->
                    if (jsonOre.size() > 0) {
                        val ores = parseJsonMapMaterialToOre(jsonOre)
                        attributes.add(ResourceAttributeNeighborOre(ores))
                    }
                }

                // neighbor modifiers
                node.get("neighbor_income_total_multiplier")?.asDouble?.let { multiplier ->
                    attributes.add(ResourceAttributeNeighborTotalIncomeMultiplier(multiplier))
                }
                node.get("neighbor_ore_total_multiplier")?.asDouble?.let { multiplier ->
                    attributes.add(ResourceAttributeNeighborTotalOreMultiplier(multiplier))
                }

                // neighbor specific multipliers
                node.get("neighbor_income_multiplier")?.asJsonObject?.let { jsonIncome ->
                    if (jsonIncome.size() > 0) {
                        val incomeMultiplier = parseJsonIncome(jsonIncome)
                        attributes.add(ResourceAttributeNeighborIncomeMultiplier(incomeMultiplier))
                    }
                }
                node.get("neighbor_ore_multiplier")?.asJsonObject?.let { jsonOre ->
                    if (jsonOre.size() > 0) {
                        val oresMultiplier = parseJsonMapMaterialToDouble(jsonOre)
                        attributes.add(ResourceAttributeNeighborOreMultiplier(oresMultiplier))
                    }
                }

                // claim time modifiers
                node.get("attacker_time_multiplier")?.asDouble?.let { multiplier ->
                    attributes.add(ResourceAttributeAttackerTimeMultiplier(multiplier))
                }
                node.get("defender_time_multiplier")?.asDouble?.let { multiplier ->
                    attributes.add(ResourceAttributeDefenderTimeMultiplier(multiplier))
                }

                resources.put(
                    name,
                    ResourceNode(
                        name,
                        icon,
                        priority,
                        attributes,
                    ),
                )
            } catch (err: Exception) {
                println("Failed to parse resource $name: $err")
            }
        }

        return resources
    }
}

// helper functions for parsing json resource node format

/**
 * Parse income from an income json section.
 */
private fun parseJsonIncome(json: JsonObject): MutableMap<Material, Double> {
    val income = mutableMapOf<Material, Double>()

    json.keySet().forEach { type ->
        val itemName = type.uppercase()

        val material = Material.fromKey(type)
        if (material !== null) {
            income.put(material, json.get(type).asDouble)
        } else {
            println("parseJsonIncome(): Failed to parse income material type: $itemName")
        }
    }

    return income
}

/**
 * Parse json section as map of material name to ore deposit drop.
 */
private fun parseJsonMapMaterialToOre(json: JsonObject): MutableMap<Material, OreDeposit> {
    val ores = mutableMapOf<Material, OreDeposit>()

    json.keySet().forEach { type ->
        val material = Material.fromKey(type)
        if (material !== null) {
            val oreData = json.get(type)

            // parse array format: [rate, minDrop, maxDrop]
            if (oreData?.isJsonArray ?: false) {
                val oreDataAsArray = oreData.asJsonArray
                if (oreDataAsArray.size() == 3) {
                    ores.put(
                        material,
                        OreDeposit(
                            material,
                            oreDataAsArray[0].asDouble,
                            oreDataAsArray[1].asInt,
                            oreDataAsArray[2].asInt,
                        ),
                    )
                }
            }
            // parse number format: rate (default minDrop = maxDrop = 1)
            else if (oreData?.isJsonPrimitive ?: false) {
                val oreDataRate = oreData.asDouble
                ores.put(
                    material,
                    OreDeposit(
                        material,
                        oreDataRate,
                        1,
                        1,
                    ),
                )
            }
        }
    }

    return ores
}

/**
 * Parse json section as a map of a material name to a Double value.
 */
private fun parseJsonMapMaterialToDouble(json: JsonObject): MutableMap<Material, Double> {
    val map = mutableMapOf<Material, Double>()

    json.keySet().forEach { type ->
        val material = Material.fromKey(type)
        if (material !== null) {
            map[material] = json.get(type).asDouble
        } else {
            println("parseJsonMapMaterialToDouble(): Failed to parse material type: $type")
        }
    }

    return map
}
