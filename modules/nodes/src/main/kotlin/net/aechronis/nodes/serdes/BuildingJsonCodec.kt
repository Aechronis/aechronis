package net.aechronis.nodes.serdes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.aechronis.nodes.objects.ActiveBuilding
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.BuildingSaveState
import net.aechronis.nodes.objects.Port
import net.kyori.adventure.nbt.TagStringIO
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.item.ItemStack

internal object BuildingJsonCodec {
    fun encode(snapshot: BuildingSaveState): String = buildJsonObject {
        put("type", snapshot.type)
        if (snapshot is Port.PortSaveState) put("name", snapshot.name)
        put("chunkX", snapshot.chunkX)
        put("chunkZ", snapshot.chunkZ)
        put("tier", snapshot.tier)
        if (snapshot is Port.PortSaveState) put("isPublic", snapshot.isPublic)
        if (snapshot is ActiveBuilding.ActiveBuildingSaveState) {
            put("definition", snapshot.definitionName)
            put("world", snapshot.world)
            put("x", snapshot.position.blockX())
            put("y", snapshot.position.blockY())
            put("z", snapshot.position.blockZ())
            putJsonArray("inputs") {
                snapshot.inputs.forEach { add(TagStringIO.tagStringIO().asString(it.toItemNBT())) }
            }
            snapshot.production?.let { production ->
                putJsonObject("production") {
                    put("startedAt", production.startedAt)
                    put("recipe", production.recipeName)
                    putJsonArray("output") {
                        production.output.forEach { add(TagStringIO.tagStringIO().asString(it.toItemNBT())) }
                    }
                }
            }
        }
    }.toString()

    fun loadActive(json: JsonObject) {
        val building = decodeActive(json)
        require(!Building.hasAt(building.chunkX, building.chunkZ)) { "Duplicate building at ${building.chunkX}, ${building.chunkZ}" }
        Building.register(building)
    }

    internal fun decodeActive(json: JsonObject): ActiveBuilding {
        val position = BlockVec(json.getValue("x").jsonPrimitive.int, json.getValue("y").jsonPrimitive.int, json.getValue("z").jsonPrimitive.int)
        val production = json["production"]?.jsonObject?.let {
            ActiveBuilding.Production(
                it.getValue("startedAt").jsonPrimitive.long,
                it.getValue("recipe").jsonPrimitive.content,
                it.getValue("output").jsonArray.map { item -> ItemStack.fromItemNBT(TagStringIO.tagStringIO().asCompound(item.jsonPrimitive.content)) },
            ).also { saved ->
                require(saved.output.isNotEmpty() && saved.output.all { item -> !item.isAir && item.amount() > 0 }) { "Invalid active building output" }
            }
        }
        return ActiveBuilding(
            json.getValue("definition").jsonPrimitive.content,
            json.getValue("world").jsonPrimitive.content,
            position,
            json.getValue("tier").jsonPrimitive.int,
            production,
            json["inputs"]?.jsonArray?.map { item -> ItemStack.fromItemNBT(TagStringIO.tagStringIO().asCompound(item.jsonPrimitive.content)) }.orEmpty(),
        )
    }
}
