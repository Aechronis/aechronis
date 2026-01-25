/**
 * Load war state from war.json format
 * See WarSerializer.kt for format
 */

package luna.nodes.war.serdes

import com.google.gson.JsonParser
import luna.nodes.Nodes
import luna.nodes.objects.Coord
import luna.nodes.war.FlagWar
import net.minestom.server.coordinate.BlockVec
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

        // ===============================
        // Attacks
        // ===============================
        val jsonAttackList = jsonObj.get("attacks")?.asJsonArray
        if (jsonAttackList !== null) {
            for (jsonAttack in jsonAttackList) {
                val attack = jsonAttack.asJsonObject

                // parse attacker player uuid
                val uuidJson = attack.get("id")
                if (uuidJson == null) {
                    break
                }
                val uuid: UUID = UUID.fromString(uuidJson.asString)

                // parse attack coord
                val coordJson = attack.get("c")?.asJsonArray
                if (coordJson == null) {
                    break
                }
                val coord = Coord(coordJson[0].asInt, coordJson[1].asInt)

                // parse attack flagBase block
                val blockJson = attack.get("b")?.asJsonArray
                if (blockJson == null) {
                    break
                }
                val flagBase = BlockVec(
                    blockJson[0].asInt,
                    blockJson[1].asInt,
                    blockJson[2].asInt,
                )

                // parse progress
                val progress = attack.get("p")?.asLong
                if (progress == null) {
                    break
                }

                FlagWar.loadAttack(
                    uuid,
                    coord,
                    flagBase,
                    progress,
                )
            }
        }
    }
}
