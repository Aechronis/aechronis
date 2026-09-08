/**
 * Load war state from war.json format
 * See WarSerializer.kt for format
 */

package net.aechronis.nodes.war.serdes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.FlagWar
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object WarDeserializer {

    // parse war.json data file
    fun fromJson(path: Path) {
        val json = Json.parseToJsonElement(Files.readString(path))
        val jsonObj = json.jsonObject

        // parse war state and flags
        val warStatus = jsonObj.get("war")?.jsonPrimitive?.boolean ?: false
        if (warStatus) {
            // parse war flags
            val canAnnexTerritories = jsonObj.get("flagAnnex")?.jsonPrimitive?.boolean ?: true
            val canOnlyAttackBorders = jsonObj.get("flagBordersOnly")?.jsonPrimitive?.boolean ?: false
            val destructionEnabled = jsonObj.get("flagDestruction")?.jsonPrimitive?.boolean ?: true
            val deathWar = jsonObj.get("flagDeathWar")?.jsonPrimitive?.boolean ?: false

            FlagWar.enable(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled, deathWar)
        }

        val jsonSkirmishTargets = jsonObj.get("skirmishTargets")?.jsonObject
        jsonSkirmishTargets?.entries?.forEach { (nationIdText, territoryIdJson) ->
            runCatching {
                FlagWar.loadSkirmishTarget(
                    UUID.fromString(nationIdText),
                    TerritoryId(territoryIdJson.jsonPrimitive.int),
                )
            }.onFailure { error ->
                System.err.println("[Nodes] Ignoring invalid skirmish target $nationIdText: ${error.message}")
            }
        }

        val jsonTownLives = jsonObj.get("townLives")?.jsonObject
        jsonTownLives?.entries?.forEach { (townIdText, livesJson) ->
            runCatching {
                val townId = UUID.fromString(townIdText)
                val town = Town.fromUuid(townId) ?: error("unknown town")
                val lifeState = livesJson.jsonObject
                Town.restoreLives(
                    town,
                    lifeState.getValue("lives").jsonPrimitive.int,
                    lifeState.get("capitalGranted")?.jsonPrimitive?.boolean ?: false,
                    lifeState.getValue("revision").jsonPrimitive.long,
                )
            }.onFailure { error ->
                System.err.println("[Nodes] Ignoring invalid town lives $townIdText: ${error.message}")
            }
        }

        if (warStatus) {
            jsonObj.get("defeatedTowns")?.jsonArray?.forEach { townIdJson ->
                runCatching { FlagWar.loadDefeatedTown(UUID.fromString(requireNotNull(townIdJson.jsonPrimitive.contentOrNull))) }
                    .onFailure { error ->
                        System.err.println("[Nodes] Ignoring invalid defeated town $townIdJson: ${error.message}")
                    }
            }
        }

        // ===============================
        // Occupied chunks
        // ===============================
        val jsonOccupiedChunks = jsonObj.get("occupied")?.jsonObject
        if (jsonOccupiedChunks !== null) {
            for ((townIdText, chunksJson) in jsonOccupiedChunks) {
                val townId = UUID.fromString(townIdText)
                val chunkList = chunksJson.jsonArray
                for (i in 0 until chunkList.size step 2) {
                    val cx = chunkList[i].jsonPrimitive.int
                    val cz = chunkList[i + 1].jsonPrimitive.int
                    val coord = Coord(cx, cz)

                    FlagWar.loadOccupiedChunk(townId, coord)
                }
            }
        }

        val jsonColonizedChunks = jsonObj.get("colonized")?.jsonArray
        if (jsonColonizedChunks !== null) {
            require(jsonColonizedChunks.size % 2 == 0) { "Colonized chunk coordinates must be x/z pairs" }
            for (i in 0 until jsonColonizedChunks.size step 2) {
                val coord = Coord(jsonColonizedChunks[i].jsonPrimitive.int, jsonColonizedChunks[i + 1].jsonPrimitive.int)
                FlagWar.loadColonizedChunk(coord)
            }
        }

        val jsonTerritoryOccupations = jsonObj.get("territoryOccupations")?.jsonObject
        jsonTerritoryOccupations?.entries?.forEach { (territoryIdText, value) ->
            runCatching {
                val occupation = value.jsonObject
                val ownerElement = occupation.get("owner")
                val ownerId = if (ownerElement == null || ownerElement is JsonNull) {
                    null
                } else {
                    UUID.fromString(requireNotNull(ownerElement.jsonPrimitive.contentOrNull))
                }
                FlagWar.loadTerritoryOccupation(
                    TerritoryId(territoryIdText.toInt()),
                    ownerId,
                    occupation.get("colonized")?.jsonPrimitive?.boolean ?: false,
                )
            }.onFailure { error ->
                System.err.println("[Nodes] Ignoring invalid territory occupation $territoryIdText: ${error.message}")
            }
        }
    }
}
