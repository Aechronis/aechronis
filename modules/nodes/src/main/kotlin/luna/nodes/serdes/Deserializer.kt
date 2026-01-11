/**
 * Deserializer
 *
 */

package luna.nodes.serdes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
//import org.bukkit.block.Block
import luna.nodes.Nodes
import luna.nodes.constants.PermissionsGroup
import luna.nodes.constants.TownPermissions
import luna.nodes.objects.Nation
import luna.nodes.objects.PortGroup
import luna.nodes.objects.Town
import luna.nodes.utils.Color
import java.io.FileReader
import java.nio.file.Path
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

/**
 * Utility class to hold resources and territories json
 * object sections.
 */
data class WorldJsonState(
    val resources: JsonObject?,
    val territories: JsonObject?,
)

object Deserializer {

    // parse the world.json definition file:
    // contains resource nodes and territories
    fun worldFromJson(path: Path): WorldJsonState {
        val json = JsonParser.parseReader(FileReader(path.toString())) // newer gson
        // val json = JsonParser().parse(FileReader(path.toString()))        // gson bundled in mineman
        val jsonObj = json.asJsonObject

        val jsonNodes = jsonObj.get("nodes")?.asJsonObject
        val jsonTerritories = jsonObj.get("territories")?.asJsonObject

        return WorldJsonState(jsonNodes, jsonTerritories)
    }

    // import towns.json definition file
    // contains
    fun townsFromJson(path: Path) {
        // list of towns, nations and relations, for post-process adding diplomacy
        val towns: ArrayList<Town> = ArrayList()
        val townAllies: ArrayList<ArrayList<String>> = ArrayList()
        val townEnemies: ArrayList<ArrayList<String>> = ArrayList()
        val nations: ArrayList<Nation> = ArrayList()
        val nationAllies: ArrayList<ArrayList<String>> = ArrayList()
        val nationEnemies: ArrayList<ArrayList<String>> = ArrayList()

        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.asJsonObject

        // ===============================
        // Residents
        // ===============================
        val jsonResidents = jsonObj.get("residents")?.asJsonObject
        if (jsonResidents !== null) {
            jsonResidents.keySet().forEach { uuid ->
                val resident = jsonResidents[uuid].asJsonObject

                // prefix, suffix
                val prefix = resident.get("prefix")?.asString ?: ""
                val suffix = resident.get("suffix")?.asString ?: ""

                // trusted
                val trusted = resident.get("trust")?.asBoolean ?: false

                // town create cooldown
                val townCreateCooldown: Long = resident.get("townCool")?.asLong ?: 0L

                Nodes.loadResident(
                    UUID.fromString(uuid),
                    prefix,
                    suffix,
                    trusted,
                    townCreateCooldown,
                )
            }
        }

        // ===============================
        // Towns
        // ===============================
        val jsonTowns = jsonObj.get("towns")?.asJsonObject
        if (jsonTowns !== null) {
            jsonTowns.keySet().forEach { name ->
                val town = jsonTowns[name].asJsonObject

                // parse uuid
                val uuidJson = town.get("uuid")
                val uuid: UUID = if (uuidJson !== null) {
                    UUID.fromString(uuidJson.asString)
                } else {
                    UUID.randomUUID()
                }

                // get home territory id, if missing skip town
                val homeId = town.get("home")?.asInt
                if (homeId == null) {
                    System.err.println("Cannot create $name: no home")
                    return@forEach
                }

                // parse leader uuid (may be null)
                val leaderJson = town.get("leader")
                val leader: UUID? = if (leaderJson == null || leaderJson.isJsonNull) {
                    null
                } else {
                    UUID.fromString(leaderJson.asString)
                }

                // parse spawn location
                val spawnLocArray = town.get("spawn")?.asJsonArray
                val spawn = if (spawnLocArray !== null && spawnLocArray.size() == 3) {
                    Pos(spawnLocArray[0].asDouble, spawnLocArray[1].asDouble, spawnLocArray[2].asDouble)
                } else {
                    null
                }

                // parse color
                val colorArray = town.get("color")?.asJsonArray
                val color = if (colorArray !== null && colorArray.size() == 3) {
                    Color(colorArray[0].asInt, colorArray[1].asInt, colorArray[2].asInt)
                } else {
                    null
                }

                // parse residents
                val residentsUUID: ArrayList<UUID> = ArrayList()
                val residentsArray = town.get("residents")?.asJsonArray
                if (residentsArray !== null) {
                    residentsArray.forEach { uuid ->
                        residentsUUID.add(UUID.fromString(uuid.asString))
                    }
                }

                // parse officers
                val officersUUID: ArrayList<UUID> = ArrayList()
                val officersArray = town.get("officers")?.asJsonArray
                if (officersArray !== null) {
                    officersArray.forEach { uuid ->
                        officersUUID.add(UUID.fromString(uuid.asString))
                    }
                }

                // parse territories
                val territoryIds: ArrayList<Int> = ArrayList()
                val territoryArray = town.get("territories")?.asJsonArray
                if (territoryArray !== null) {
                    territoryArray.forEach { id ->
                        territoryIds.add(id.asInt)
                    }
                }

                // parse captured territories
                val capturedIds: ArrayList<Int> = ArrayList()
                val capturedTerrArray = town.get("captured")?.asJsonArray
                if (capturedTerrArray !== null) {
                    capturedTerrArray.forEach { id ->
                        capturedIds.add(id.asInt)
                    }
                }

                // parse annexed territories
                val annexedIds: ArrayList<Int> = ArrayList()
                val annexedTerrArray = town.get("annexed")?.asJsonArray
                if (annexedTerrArray !== null) {
                    annexedTerrArray.forEach { id ->
                        annexedIds.add(id.asInt)
                    }
                }

                // parse stored income
                val income: MutableMap<Material, Int> = mutableMapOf()
                val townIncomeJson = town.get("income")?.asJsonObject
                if (townIncomeJson !== null) {
                    townIncomeJson.keySet().forEach { type ->
                        val material = Material.fromKey(type.lowercase())
                        if (material !== null) {
                            income.put(material, townIncomeJson.get(type).asInt)
                        }
                    }
                }

                // parse ally names
                val allies: ArrayList<String> = ArrayList()
                val alliesArray = town.get("allies")?.asJsonArray
                if (alliesArray !== null) {
                    alliesArray.forEach { name ->
                        allies.add(name.asString)
                    }
                }

                // parse enemy names
                val enemies: ArrayList<String> = ArrayList()
                val enemiesArray = town.get("enemies")?.asJsonArray
                if (enemiesArray !== null) {
                    enemiesArray.forEach { name ->
                        enemies.add(name.asString)
                    }
                }

                // parse permissions
                val permissions: EnumMap<TownPermissions, EnumSet<PermissionsGroup>> = enumValues<TownPermissions>().toList().associateWithTo(
                    EnumMap<TownPermissions, EnumSet<PermissionsGroup>>(TownPermissions::class.java),
                ) { _ -> EnumSet.noneOf(PermissionsGroup::class.java) }
                val permissionsJson = town.get("perms")?.asJsonObject
                if (permissionsJson !== null) {
                    permissionsJson.keySet().forEach { type ->
                        // get enum type
                        try {
                            val permType = TownPermissions.valueOf(type)
                            val permGroupList = permissionsJson.get(type)?.asJsonArray
                            if (permGroupList !== null) {
                                for (group in permGroupList) {
                                    permissions[permType]!!.add(PermissionsGroup.values[group.asInt])
                                }
                            }
                        } catch (err: IllegalArgumentException) {
                            System.err.println("Invalid town permission: $type")
                        }
                    }
                }

                // parse town isOpen
                val isOpen: Boolean = town.get("open")?.asBoolean ?: false

//                // parse town protected blocks
//                val protectedBlocks: HashSet<Block> = hashSetOf()
//                val protectedBlocksJsonArray = town.get("protect")?.getAsJsonArray()
//                if (protectedBlocksJsonArray !== null) {
//                    val world = Bukkit.getWorlds().get(0)
//                    for (item in protectedBlocksJsonArray) {
//                        val blockArray = item.getAsJsonArray()
//                        if (blockArray !== null && blockArray.size() == 3) {
//                            val x = blockArray[0].getAsInt()
//                            val y = blockArray[1].getAsInt()
//                            val z = blockArray[2].getAsInt()
//                            val block = world.getBlockAt(x, y, z)
//                            protectedBlocks.add(block)
//                        }
//                    }
//                }

                // town move home cooldown
                val moveHomeCooldown: Long = town.get("homeCool")?.asLong ?: 0L

                val townObject: Town? = Nodes.loadTown(
                    uuid,
                    name,
                    leader,
                    homeId,
                    spawn,
                    color,
                    residentsUUID,
                    officersUUID,
                    territoryIds,
                    capturedIds,
                    annexedIds,
                    income,
                    permissions,
                    isOpen,
//                    protectedBlocks,
                    moveHomeCooldown,
                )

                if (townObject !== null) {
                    towns.add(townObject)
                    townAllies.add(allies)
                    townEnemies.add(enemies)
                }
            }
        }

        // ===============================
        // Nations
        // ===============================
        val jsonNations = jsonObj.get("nations")?.asJsonObject
        if (jsonNations !== null) {
            jsonNations.keySet().forEach { name ->
                val nation = jsonNations[name].asJsonObject

                // parse uuid
                val uuidJson = nation.get("uuid")
                val uuid: UUID = if (uuidJson !== null) {
                    UUID.fromString(uuidJson.asString)
                } else {
                    UUID.randomUUID()
                }

                // parse color
                val colorArray = nation.get("color")?.asJsonArray
                val color = if (colorArray !== null && colorArray.size() == 3) {
                    Color(colorArray[0].asInt, colorArray[1].asInt, colorArray[2].asInt)
                } else {
                    null
                }

                // parse towns
                val towns: ArrayList<String> = arrayListOf()
                val townsArray = nation.get("towns")?.asJsonArray
                if (townsArray !== null) {
                    townsArray.forEach { townName ->
                        towns.add(townName.asString)
                    }
                }

                // parse capital town name
                var capitalName = nation.get("capital")?.asString
                if (capitalName == null) {
                    System.err.println("Capital for: $name not found, setting it to ${towns[0]}")
                    capitalName = towns[0]
                }

                // parse ally names
                val allies: ArrayList<String> = ArrayList()
                val alliesArray = nation.get("allies")?.asJsonArray
                if (alliesArray !== null) {
                    alliesArray.forEach { name ->
                        allies.add(name.asString)
                    }
                }

                // parse enemy names
                val enemies: ArrayList<String> = ArrayList()
                val enemiesArray = nation.get("enemies")?.asJsonArray
                if (enemiesArray !== null) {
                    enemiesArray.forEach { name ->
                        enemies.add(name.asString)
                    }
                }

                val nationObject = Nodes.loadNation(
                    uuid,
                    name,
                    capitalName,
                    color,
                    towns,
                )

                nations.add(nationObject)
                nationAllies.add(allies)
                nationEnemies.add(enemies)
            }
        }

        // post process finish load:
        // handle diplomacy
        Nodes.loadDiplomacy(
            towns,
            townAllies,
            townEnemies,
        )
    }

    // parse ports.json
    fun portsFromJson(path: Path) {
        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.asJsonObject

        val groups = jsonObj.getAsJsonArray("groups")
        if (groups !== null) {
            for (group in groups) {
                val name = group?.asString
                if (name == null) {
                    System.err.println("Cannot create port group: missing name")
                    continue
                }
                Nodes.loadPortGroup(name)
            }
        }

        val jsonPorts = jsonObj.get("ports")?.asJsonObject
        if (jsonPorts !== null) {
            jsonPorts.keySet().forEach { name ->
                val port = jsonPorts[name].asJsonObject

                // parse location (x,z)
                val x = port.get("x")?.asInt
                val z = port.get("z")?.asInt
                if (x == null || z == null) {
                    System.err.println("Cannot create port $name: missing x or z coordinate")
                    return@forEach
                }

                // get port groups
                val groups: HashSet<PortGroup> = hashSetOf()
                val groupsArray = port.get("groups")?.asJsonArray
                if (groupsArray !== null) {
                    for (groupElement in groupsArray) {
                        val groupName = groupElement.asString
                        val group = Nodes.getPortGroupFromName(groupName)
                        if (group !== null) {
                            groups.add(group)
                        }
                    }
                }

                // parse port isPublic
                val isPublic: Boolean = port.get("isPublic")?.asBoolean ?: false

                Nodes.loadPort(
                    name,
                    x,
                    z,
                    groups,
                    isPublic,
                )
            }
        }
    }
}
