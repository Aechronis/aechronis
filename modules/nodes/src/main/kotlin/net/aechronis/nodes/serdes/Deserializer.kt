/**
 * Deserializer
 *
 */

package net.aechronis.nodes.serdes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.aechronis.nodes.colonization.AiTownConfig
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.Farm
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.OilRig
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Port
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.TrainStationBuilding
import net.aechronis.nodes.objects.Waypoint
import net.aechronis.nodes.objects.WaypointSharing
import net.aechronis.nodes.utils.Color
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
import java.nio.file.Files
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
        val json = Json.parseToJsonElement(Files.readString(path))
        val jsonObj = json.jsonObject

        val jsonNodes = jsonObj.get("nodes")?.jsonObject
        val jsonTerritories = jsonObj.get("territories")?.jsonObject

        return WorldJsonState(jsonNodes, jsonTerritories)
    }

    // import towns.json definition file
    // contains
    fun townsFromJson(path: Path) {
        // Nation references are resolved after every nation has been loaded.
        val nations: ArrayList<Nation> = ArrayList()
        val nationAllies: ArrayList<ArrayList<String>> = ArrayList()
        val nationEnemies: ArrayList<ArrayList<String>> = ArrayList()

        val json = Json.parseToJsonElement(Files.readString(path))
        val jsonObj = json.jsonObject
        MiningBoostManager.load(jsonObj.get("miningBoost")?.takeIf { it is JsonObject }?.jsonObject)

        // ===============================
        // Residents
        // ===============================
        val jsonResidents = jsonObj.get("residents")?.jsonObject
        if (jsonResidents !== null) {
            jsonResidents.keys.forEach { uuid ->
                val resident = jsonResidents.getValue(uuid).jsonObject

                val name = resident.get("name")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) } ?: return@forEach

                // trusted
                val trusted = resident.get("trust")?.jsonPrimitive?.boolean ?: false
                val minimapEnabled = resident.get("minimap")?.jsonPrimitive?.boolean ?: true
                val minimapPosition = resident.get("minimapPosition")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) }
                    ?.let(MinimapPosition::fromId)
                    ?: MinimapPosition.DEFAULT
                val minimapShiftEnabled = resident.get("minimapShift")?.jsonPrimitive?.boolean ?: true
                val minimapNorthLocked = resident.get("minimapNorthLocked")?.jsonPrimitive?.boolean ?: true
                val townJoinLockedUntil = resident.get("townJoinLockedUntil")?.takeUnless { it is JsonNull }?.let { value ->
                    runCatching { value.jsonPrimitive.long }.getOrNull()
                }

                val waypointVisibility = resident.get("waypointVisibility")?.takeIf { it is JsonObject }?.jsonObject?.let { visibility ->
                    buildMap {
                        visibility.entries.forEach { (key, value) ->
                            runCatching { value.jsonPrimitive.boolean }.getOrNull()?.let { visible -> put(key, visible) }
                        }
                    }
                }.orEmpty()

                val waypoints = arrayListOf<Waypoint>()
                resident.get("waypoints")?.takeIf { it is JsonArray }?.jsonArray?.forEach waypointLoop@{ element ->
                    try {
                        val waypoint = element.jsonObject
                        val waypointName = waypoint.get("name")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) } ?: return@waypointLoop
                        val x = waypoint.get("x")?.jsonPrimitive?.int ?: return@waypointLoop
                        val y = waypoint.get("y")?.jsonPrimitive?.int ?: return@waypointLoop
                        val z = waypoint.get("z")?.jsonPrimitive?.int ?: return@waypointLoop
                        val sharing = waypoint.get("sharing")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) }?.let(WaypointSharing::fromId) ?: WaypointSharing.PRIVATE
                        val sharedGroupId = waypoint.get("sharedGroup")?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.let(UUID::fromString)
                        waypoints.add(Waypoint(Waypoint.normalizeName(waypointName), x, y, z, sharing, sharedGroupId))
                    } catch (error: RuntimeException) {
                        System.err.println("Invalid waypoint for resident $name: ${error.message}")
                    }
                }

                Resident.load(
                    UUID.fromString(uuid),
                    name,
                    trusted,
                    waypoints,
                    waypointVisibility,
                    minimapEnabled,
                    minimapPosition,
                    minimapShiftEnabled,
                    minimapNorthLocked,
                    townJoinLockedUntil,
                )
            }
        }

        // ===============================
        // Towns
        // ===============================
        val jsonTowns = jsonObj.get("towns")?.jsonObject
        if (jsonTowns !== null) {
            jsonTowns.keys.forEach { name ->
                val town = jsonTowns.getValue(name).jsonObject

                // parse uuid
                val uuidJson = town.get("uuid")
                val uuid: UUID = if (uuidJson !== null) {
                    UUID.fromString(requireNotNull(uuidJson.jsonPrimitive.contentOrNull))
                } else {
                    UUID.randomUUID()
                }

                // get home territory id, if missing skip town
                val homeId = town.get("home")?.jsonPrimitive?.int
                if (homeId == null) {
                    System.err.println("Cannot create $name: no home")
                    return@forEach
                }

                // parse leader uuid (may be null)
                val leaderJson = town.get("leader")
                val leader: UUID? = if (leaderJson == null || leaderJson is JsonNull) {
                    null
                } else {
                    UUID.fromString(requireNotNull(leaderJson.jsonPrimitive.contentOrNull))
                }

                // parse spawn location
                val spawnLocArray = town.get("spawn")?.jsonArray
                val spawn = if (spawnLocArray !== null && spawnLocArray.size == 3) {
                    Pos(spawnLocArray[0].jsonPrimitive.double, spawnLocArray[1].jsonPrimitive.double, spawnLocArray[2].jsonPrimitive.double)
                } else {
                    null
                }

                // parse color
                val colorArray = town.get("color")?.jsonArray
                val color = if (colorArray !== null && colorArray.size == 3) {
                    Color(colorArray[0].jsonPrimitive.int, colorArray[1].jsonPrimitive.int, colorArray[2].jsonPrimitive.int)
                } else {
                    null
                }

                val lives = town.get("lives")?.let { value ->
                    runCatching { value.jsonPrimitive.int.takeIf { it >= 0 } }.getOrNull()
                }
                val capitalLifeGranted = town.get("capitalLifeGranted")?.jsonPrimitive?.boolean ?: false
                val lifeRevision = town.get("lifeRevision")?.jsonPrimitive?.long?.coerceAtLeast(0L) ?: 0L

                // parse residents
                val residentsUUID: ArrayList<UUID> = ArrayList()
                val residentsArray = town.get("residents")?.jsonArray
                if (residentsArray !== null) {
                    residentsArray.forEach { uuid ->
                        residentsUUID.add(UUID.fromString(requireNotNull(uuid.jsonPrimitive.contentOrNull)))
                    }
                }

                // parse officers
                val officersUUID: ArrayList<UUID> = ArrayList()
                val officersArray = town.get("officers")?.jsonArray
                if (officersArray !== null) {
                    officersArray.forEach { uuid ->
                        officersUUID.add(UUID.fromString(requireNotNull(uuid.jsonPrimitive.contentOrNull)))
                    }
                }

                // parse territories
                val territoryIds: ArrayList<Int> = ArrayList()
                val territoryArray = town.get("territories")?.jsonArray
                if (territoryArray !== null) {
                    territoryArray.forEach { id ->
                        territoryIds.add(id.jsonPrimitive.int)
                    }
                }

                // parse captured territories
                val capturedIds: ArrayList<Int> = ArrayList()
                val capturedTerrArray = town.get("captured")?.jsonArray
                if (capturedTerrArray !== null) {
                    capturedTerrArray.forEach { id ->
                        capturedIds.add(id.jsonPrimitive.int)
                    }
                }

                // parse annexed territories
                val annexedIds: ArrayList<Int> = ArrayList()
                val annexedTerrArray = town.get("annexed")?.jsonArray
                if (annexedTerrArray !== null) {
                    annexedTerrArray.forEach { id ->
                        annexedIds.add(id.jsonPrimitive.int)
                    }
                }

                // parse stored income
                val income: MutableMap<Material, Int> = mutableMapOf()
                val townIncomeJson = town.get("income")?.jsonObject
                if (townIncomeJson !== null) {
                    townIncomeJson.keys.forEach { type ->
                        val material = Material.fromKey(type.lowercase())
                        if (material !== null) {
                            income.put(material, townIncomeJson.getValue(type).jsonPrimitive.int)
                        }
                    }
                }

                // parse permissions
                val permissions: EnumMap<TownPermissions, EnumSet<PermissionsGroup>> = enumValues<TownPermissions>().toList().associateWithTo(
                    EnumMap<TownPermissions, EnumSet<PermissionsGroup>>(TownPermissions::class.java),
                ) { _ -> EnumSet.noneOf(PermissionsGroup::class.java) }
                val permissionsJson = town.get("perms")?.jsonObject
                if (permissionsJson !== null) {
                    permissionsJson.keys.forEach { type ->
                        // get enum type
                        try {
                            val permType = TownPermissions.valueOf(type)
                            val permGroupList = permissionsJson.get(type)?.jsonArray
                            if (permGroupList !== null) {
                                for (group in permGroupList) {
                                    permissions[permType]!!.add(PermissionsGroup.values[group.jsonPrimitive.int])
                                }
                            }
                        } catch (err: IllegalArgumentException) {
                            System.err.println("Invalid town permission: $type")
                        }
                    }
                }

                // parse town protected blocks
                val protectedBlocks: HashSet<BlockVec> = hashSetOf()
                val protectedBlocksJsonArray = town.get("protect")?.jsonArray
                if (protectedBlocksJsonArray !== null) {
                    for (item in protectedBlocksJsonArray) {
                        val blockArray = item.jsonArray
                        if (blockArray.size == 3) {
                            val x = blockArray[0].jsonPrimitive.int
                            val y = blockArray[1].jsonPrimitive.int
                            val z = blockArray[2].jsonPrimitive.int
                            val block = BlockVec(x, y, z)
                            protectedBlocks.add(block)
                        }
                    }
                }

                val plots: ArrayList<Plot.PlotSaveState> = ArrayList()
                val plotsJsonArray = town.get("plots")?.jsonArray
                if (plotsJsonArray !== null) {
                    for (plotJson in plotsJsonArray) {
                        try {
                            val plot = plotJson.jsonObject
                            val name = plot.get("name")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) } ?: continue
                            val min = plot.get("min")?.jsonArray ?: continue
                            val max = plot.get("max")?.jsonArray ?: continue
                            if (min.size != 3 || max.size != 3) continue

                            val groupPermissions: MutableMap<PermissionsGroup, Map<TownPermissions, Boolean>> = hashMapOf()
                            val permissionsJson = plot.get("permissions")?.jsonObject
                            permissionsJson?.keys?.forEach { groupName ->
                                val group = runCatching { PermissionsGroup.valueOf(groupName) }.getOrNull() ?: return@forEach
                                val permissionJson = permissionsJson.get(groupName)?.jsonObject ?: return@forEach
                                val permissions = parsePlotPermissions(permissionJson)
                                if (permissions.isNotEmpty()) groupPermissions[group] = permissions
                            }

                            val playerPermissions: MutableMap<UUID, Map<TownPermissions, Boolean>> = hashMapOf()
                            val playersJson = plot.get("players")?.jsonObject
                            playersJson?.keys?.forEach { uuidString ->
                                val uuid = runCatching { UUID.fromString(uuidString) }.getOrNull() ?: return@forEach
                                val permissionJson = playersJson.get(uuidString)?.jsonObject ?: return@forEach
                                val permissions = parsePlotPermissions(permissionJson)
                                if (permissions.isNotEmpty()) playerPermissions[uuid] = permissions
                            }

                            plots.add(
                                Plot.PlotSaveState(
                                    name,
                                    min[0].jsonPrimitive.int,
                                    min[1].jsonPrimitive.int,
                                    min[2].jsonPrimitive.int,
                                    max[0].jsonPrimitive.int,
                                    max[1].jsonPrimitive.int,
                                    max[2].jsonPrimitive.int,
                                    groupPermissions,
                                    playerPermissions,
                                ),
                            )
                        } catch (err: RuntimeException) {
                            System.err.println("Invalid plot in town $name: ${err.message}")
                        }
                    }
                }

                val aiElement = town.get("ai")
                val aiConfig = aiTownConfigFromJson(aiElement) { field, error ->
                    System.err.println("Invalid AI field '$field' in town $name; using its default: ${error.message}")
                }
                runCatching { aiConfig.requireRegisteredGuns() }.onFailure { error ->
                    System.err.println("Invalid AI guns in town $name; defenders disabled: ${error.message}")
                }

                Town.load(
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
                    protectedBlocks,
                    plots,
                    aiConfig,
                    lives,
                    capitalLifeGranted,
                    lifeRevision,
                )
            }
        }

        // ===============================
        // Nations
        // ===============================
        val jsonNations = jsonObj.get("nations")?.jsonObject
        if (jsonNations !== null) {
            jsonNations.keys.forEach { name ->
                val nation = jsonNations.getValue(name).jsonObject

                // parse uuid
                val uuidJson = nation.get("uuid")
                val uuid: UUID = if (uuidJson !== null) {
                    UUID.fromString(requireNotNull(uuidJson.jsonPrimitive.contentOrNull))
                } else {
                    UUID.randomUUID()
                }

                // parse color
                val colorArray = nation.get("color")?.jsonArray
                val color = if (colorArray !== null && colorArray.size == 3) {
                    Color(colorArray[0].jsonPrimitive.int, colorArray[1].jsonPrimitive.int, colorArray[2].jsonPrimitive.int)
                } else {
                    null
                }

                val rallyCap = nation.get("rallyCap")?.takeUnless { it is JsonNull }?.let { value ->
                    runCatching { value.jsonPrimitive.int.takeIf { it > 0 } }.getOrNull()
                }

                // parse towns
                val towns: ArrayList<String> = arrayListOf()
                val townsArray = nation.get("towns")?.jsonArray
                if (townsArray !== null) {
                    townsArray.forEach { townName ->
                        towns.add(requireNotNull(townName.jsonPrimitive.contentOrNull))
                    }
                }

                // parse capital town name
                var capitalName = nation.get("capital")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) }
                if (capitalName == null) {
                    System.err.println("Capital for: $name not found, setting it to ${towns[0]}")
                    capitalName = towns[0]
                }

                // parse ally names
                val allies: ArrayList<String> = ArrayList()
                val alliesArray = nation.get("allies")?.jsonArray
                if (alliesArray !== null) {
                    alliesArray.forEach { name ->
                        allies.add(requireNotNull(name.jsonPrimitive.contentOrNull))
                    }
                }

                // parse enemy names
                val enemies: ArrayList<String> = ArrayList()
                val enemiesArray = nation.get("enemies")?.jsonArray
                if (enemiesArray !== null) {
                    enemiesArray.forEach { name ->
                        enemies.add(requireNotNull(name.jsonPrimitive.contentOrNull))
                    }
                }

                val nationObject = Nation.load(
                    uuid,
                    name,
                    capitalName,
                    color,
                    towns,
                    rallyCap,
                )

                nations.add(nationObject)
                nationAllies.add(allies)
                nationEnemies.add(enemies)
            }
        }

        // post process finish load:
        // handle diplomacy
        Nation.loadDiplomacy(
            nations,
            nationAllies,
            nationEnemies,
        )
    }

    internal fun aiTownConfigFromJson(
        element: JsonElement?,
        onInvalid: (String, RuntimeException) -> Unit,
    ): AiTownConfig {
        if (element == null || element is JsonNull) return AiTownConfig()
        if (element !is JsonObject) {
            onInvalid("ai", IllegalArgumentException("Town AI configuration must be an object"))
            return AiTownConfig()
        }

        val json = element.jsonObject
        var config = AiTownConfig()
        fun update(field: String, transform: (JsonElement) -> AiTownConfig) {
            val value = json.get(field) ?: return
            config = runCatching { transform(value) }.getOrElse { error ->
                onInvalid(field, error as? RuntimeException ?: IllegalArgumentException(error.message, error))
                config
            }
        }

        update("controlled") { config.copy(controlled = it.jsonPrimitive.boolean) }
        update("enemyCount") { config.copy(enemyCount = it.jsonPrimitive.int) }
        update("guns") { value -> config.copy(guns = value.jsonArray.map { requireNotNull(it.jsonPrimitive.contentOrNull) }) }
        return config
    }

    private fun parsePlotPermissions(json: JsonObject): Map<TownPermissions, Boolean> {
        val permissions: MutableMap<TownPermissions, Boolean> = hashMapOf()
        json.keys.forEach { permissionName ->
            val permission = runCatching { TownPermissions.valueOf(permissionName) }.getOrNull() ?: return@forEach
            permissions[permission] = json.getValue(permissionName).jsonPrimitive.boolean
        }
        return permissions
    }

    // parse buildings.json
    // entries are a JSON array; each is dispatched on its "type" discriminator
    fun buildingsFromJson(path: Path) {
        val json = Json.parseToJsonElement(Files.readString(path))
        val jsonObj = json.jsonObject

        val jsonBuildings = jsonObj.get("buildings")?.jsonArray ?: return
        for (element in jsonBuildings) {
            val building = element.jsonObject

            val type = building.get("type")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) }
            if (type == null) {
                System.err.println("Cannot create building: missing type")
                continue
            }

            when (type) {
                "port" -> loadPort(building)
                "farm" -> loadFarm(building)
                "oil_rig" -> loadOilRig(building)
                "train" -> loadTrainStation(building)
                else -> System.err.println("Cannot create building: unknown type \"$type\"")
            }
        }
    }

    private fun loadFarm(farm: JsonObject) {
        val chunkX = farm.get("chunkX")?.jsonPrimitive?.int
        val chunkZ = farm.get("chunkZ")?.jsonPrimitive?.int
        if (chunkX == null || chunkZ == null) {
            System.err.println("Cannot create farm: missing chunkX or chunkZ coordinate")
            return
        }
        val tier: Int = farm.get("tier")?.jsonPrimitive?.int ?: 1
        Farm.load(chunkX, chunkZ, tier)
    }

    private fun loadOilRig(oilRig: JsonObject) {
        val chunkX = oilRig.get("chunkX")?.jsonPrimitive?.int
        val chunkZ = oilRig.get("chunkZ")?.jsonPrimitive?.int
        if (chunkX == null || chunkZ == null) {
            System.err.println("Cannot create oil rig: missing chunkX or chunkZ coordinate")
            return
        }
        OilRig.load(chunkX, chunkZ, oilRig.get("tier")?.jsonPrimitive?.int ?: 1)
    }

    private fun loadTrainStation(train: JsonObject) {
        val chunkX = train.get("chunkX")?.jsonPrimitive?.int
        val chunkZ = train.get("chunkZ")?.jsonPrimitive?.int
        if (chunkX == null || chunkZ == null) {
            System.err.println("Cannot create train station: missing chunkX or chunkZ coordinate")
            return
        }
        TrainStationBuilding.load(chunkX, chunkZ, train.get("tier")?.jsonPrimitive?.int ?: 1)
    }

    private fun loadPort(port: JsonObject) {
        val name = port.get("name")?.let { requireNotNull(it.jsonPrimitive.contentOrNull) }
        if (name == null) {
            System.err.println("Cannot create port: missing name")
            return
        }
        val chunkX = port.get("chunkX")?.jsonPrimitive?.int
        val chunkZ = port.get("chunkZ")?.jsonPrimitive?.int
        if (chunkX == null || chunkZ == null) {
            System.err.println("Cannot create port $name: missing chunkX or chunkZ coordinate")
            return
        }

        val tier: Int = port.get("tier")?.jsonPrimitive?.int ?: 1
        val isPublic: Boolean = port.get("isPublic")?.jsonPrimitive?.boolean ?: false

        Port.load(
            name,
            chunkX,
            chunkZ,
            tier,
            isPublic,
        )
    }
}
