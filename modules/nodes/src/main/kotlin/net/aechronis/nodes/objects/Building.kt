package net.aechronis.nodes.objects

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.serdes.BuildingJsonCodec
import net.aechronis.nodes.serdes.SaveState
import net.minestom.server.command.CommandSender
import net.minestom.server.item.Material

const val MIN_TIER: Int = 1
const val MAX_TIER: Int = 3

abstract class Building(
    val chunkX: Int,
    val chunkZ: Int,
    tier: Int,
) {
    companion object {
        private val buildings = mutableListOf<Building>()
        private val buildingsByChunk = java.util.concurrent.ConcurrentHashMap<Coord, Building>()

        internal fun all(): List<Building> = buildings.toList()

        internal fun clearRegistry() {
            buildings.clear()
            buildingsByChunk.clear()
        }

        fun getAt(chunkX: Int, chunkZ: Int): Building? = buildingsByChunk[Coord(chunkX, chunkZ)]

        internal fun getForMinimap(coord: Coord): Building? = buildingsByChunk[coord]

        internal fun register(building: Building) {
            buildings.add(building)
            buildingsByChunk[Coord(building.chunkX, building.chunkZ)] = building
            building.needsUpdate()
            Resident.renderMinimaps()
        }

        fun destroy(building: Building) {
            buildings.remove(building)
            buildingsByChunk.remove(Coord(building.chunkX, building.chunkZ), building)
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
        }

        fun setTier(building: Building, tier: Int) {
            building.setTier(tier)
            Nodes.markWorldDirty()
        }

        internal fun hasAt(chunkX: Int, chunkZ: Int): Boolean = buildingsByChunk.containsKey(Coord(chunkX, chunkZ))
    }

    // building tier
    var tier: Int = tier.coerceIn(MIN_TIER, MAX_TIER)
        private set

    fun setTier(newTier: Int) {
        this.tier = newTier.coerceIn(MIN_TIER, MAX_TIER)
        needsUpdate()
    }

    // type for buildings.json
    abstract val type: String

    // glyph displayed for this building on the minimap.
    open val minimapIconCodepoint: Int? = null

    open fun income(): Map<Material, Double> = emptyMap()

    protected abstract fun createSaveState(): BuildingSaveState

    private var cachedSaveState: BuildingSaveState? = null
    private var needsUpdate = true

    fun needsUpdate() {
        this.needsUpdate = true
    }

    fun getSaveState(): BuildingSaveState {
        val cached = cachedSaveState
        if (cached === null || needsUpdate) {
            val fresh = createSaveState()
            cachedSaveState = fresh
            needsUpdate = false
            return fresh
        }
        return cached
    }

    open fun printInfo(sender: CommandSender) {}
}

// common json save state for any building
abstract class BuildingSaveState : SaveState() {
    abstract val type: String
    abstract val chunkX: Int
    abstract val chunkZ: Int
    abstract val tier: Int

    final override fun encode(): String = BuildingJsonCodec.encode(this)
}
