/**
 * Load war state from war.json format
 * See WarSerializer.kt for format
 */

package net.aechronis.nodes.war.serdes

import com.google.gson.JsonParser
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.war.FlagWar
import java.io.FileReader
import java.nio.file.Path

object WarDeserializer {

    // parse war.json data file
    fun fromJson(path: Path) {
        val json = JsonParser.parseReader(FileReader(path.toString()))
        val jsonObj = json.asJsonObject

        // parse war state and flags
        val warStatus = jsonObj.get("war")?.asBoolean ?: false
        if (!warStatus) {
            return
        }

        // parse war flags
        val canAnnexTerritories = jsonObj.get("flagAnnex")?.asBoolean ?: true
        val canOnlyAttackBorders = jsonObj.get("flagBordersOnly")?.asBoolean ?: false
        val destructionEnabled = jsonObj.get("flagDestruction")?.asBoolean ?: true

        // war enabled, parse full state
        Nodes.enableWar(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled)

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
    }
}
