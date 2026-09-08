package net.aechronis.nodes.serdes

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.aechronis.nodes.colonization.AiTownConfig
import net.aechronis.nodes.objects.Town

internal object TownJsonCodec {
    fun encode(snapshot: Town.TownSaveState): String = buildJsonObject {
        put("uuid", snapshot.uuid.toString())
        put("leader", snapshot.leader?.toString())
        put("home", snapshot.home.toInt())
        putJsonArray("spawn") {
            add(snapshot.spawnpoint.x)
            add(snapshot.spawnpoint.y)
            add(snapshot.spawnpoint.z)
        }
        putJsonArray("color") {
            add(snapshot.color.r)
            add(snapshot.color.g)
            add(snapshot.color.b)
        }
        putJsonObject("perms") {
            snapshot.permissions.forEach { (permission, groups) ->
                putJsonArray(permission.toString()) { groups.forEach { add(it.ordinal) } }
            }
        }
        putJsonArray("residents") { snapshot.residents.forEach { add(it.toString()) } }
        putJsonArray("officers") { snapshot.officers.forEach { add(it.toString()) } }
        putJsonArray("territories") { snapshot.territories.forEach { add(it.toInt()) } }
        putJsonArray("annexed") { snapshot.annexed.forEach { add(it.toInt()) } }
        putJsonArray("captured") { snapshot.captured.forEach { add(it.toInt()) } }
        put("lives", snapshot.lives)
        put("capitalLifeGranted", snapshot.capitalLifeGranted)
        put("lifeRevision", snapshot.lifeRevision)
        putJsonObject("income") { snapshot.income.forEach { (material, amount) -> put(material.toString(), amount) } }
        if (snapshot.aiConfig != AiTownConfig()) put("ai", snapshot.aiConfig.toJsonElement())
        putJsonArray("protect") {
            snapshot.protectedBlocks.forEach { block ->
                add(
                    buildJsonArray {
                        add(block.blockX)
                        add(block.blockY)
                        add(block.blockZ)
                    },
                )
            }
        }
        putJsonArray("plots") { snapshot.plots.forEach { add(PlotJsonCodec.toJsonElement(it)) } }
    }.toString()
}
