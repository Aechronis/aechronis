/**
 * Load war state from war.json format
 * See WarSerializer.kt for format
 */

package net.aechronis.nodes.war.serdes

import com.google.gson.JsonParser
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.war.FlagWar
import java.io.FileReader
import java.nio.file.Path
import java.util.UUID

object WarDeserializer {

    // parse war.json data file
    fun fromJson(path: Path) {
        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.asJsonObject

        // parse war state and flags
        val warStatus = jsonObj.get("war")?.asBoolean ?: false
        if (warStatus) {
            // parse war flags
            val canAnnexTerritories = jsonObj.get("flagAnnex")?.asBoolean ?: true
            val canOnlyAttackBorders = jsonObj.get("flagBordersOnly")?.asBoolean ?: false
            val destructionEnabled = jsonObj.get("flagDestruction")?.asBoolean ?: true

            FlagWar.enable(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled)
        }

        // ===============================
        // Occupied chunks
        // ===============================
        val jsonOccupiedChunks = jsonObj.get("occupied")?.asJsonObject
        if (jsonOccupiedChunks !== null) {
            for (townName in jsonOccupiedChunks.keySet()) {
                val chunkList = jsonOccupiedChunks[townName].asJsonArray
                for (i in 0 until chunkList.size() step 2) {
                    val cx = chunkList[i].asInt
                    val cz = chunkList[i + 1].asInt
                    val coord = Coord(cx, cz)

                    FlagWar.loadOccupiedChunk(townName, coord)
                }
            }
        }

        val jsonColonizedChunks = jsonObj.get("colonized")?.asJsonArray
        if (jsonColonizedChunks !== null) {
            require(jsonColonizedChunks.size() % 2 == 0) { "Colonized chunk coordinates must be x/z pairs" }
            for (i in 0 until jsonColonizedChunks.size() step 2) {
                val coord = Coord(jsonColonizedChunks[i].asInt, jsonColonizedChunks[i + 1].asInt)
                FlagWar.loadColonizedChunk(coord)
            }
        }

        val jsonTerritoryOccupations = jsonObj.get("territoryOccupations")?.asJsonObject
        jsonTerritoryOccupations?.entrySet()?.forEach { (territoryIdText, value) ->
            runCatching {
                val occupation = value.asJsonObject
                val ownerElement = occupation.get("owner")
                val ownerId = if (ownerElement == null || ownerElement.isJsonNull) {
                    null
                } else {
                    UUID.fromString(ownerElement.asString)
                }
                FlagWar.loadTerritoryOccupation(
                    TerritoryId(territoryIdText.toInt()),
                    ownerId,
                    occupation.get("colonized")?.asBoolean ?: false,
                )
            }.onFailure { error ->
                System.err.println("[Nodes] Ignoring invalid territory occupation $territoryIdText: ${error.message}")
            }
        }
        FlagWar.migrateLegacyTerritoryOccupations()
    }
}
