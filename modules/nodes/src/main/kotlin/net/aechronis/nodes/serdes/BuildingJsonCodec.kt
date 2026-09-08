package net.aechronis.nodes.serdes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.aechronis.nodes.objects.BuildingSaveState
import net.aechronis.nodes.objects.Port

internal object BuildingJsonCodec {
    fun encode(snapshot: BuildingSaveState): String = buildJsonObject {
        put("type", snapshot.type)
        if (snapshot is Port.PortSaveState) put("name", snapshot.name)
        put("chunkX", snapshot.chunkX)
        put("chunkZ", snapshot.chunkZ)
        put("tier", snapshot.tier)
        if (snapshot is Port.PortSaveState) put("isPublic", snapshot.isPublic)
    }.toString()
}
