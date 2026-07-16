package net.aechronis.nodes.objects

import net.aechronis.nodes.Nodes
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
        fun getAt(chunkX: Int, chunkZ: Int): Building? = Nodes.chunkToBuilding[listOf(chunkX, chunkZ)]

        internal fun register(building: Building) {
            Nodes.buildings.add(building)
            Nodes.chunkToBuilding[listOf(building.chunkX, building.chunkZ)] = building
            building.needsUpdate()
        }

        fun destroy(building: Building) {
            Nodes.buildings.remove(building)
            val chunk = listOf(building.chunkX, building.chunkZ)
            if (Nodes.chunkToBuilding[chunk] === building) Nodes.chunkToBuilding.remove(chunk)
            Nodes.needsSave = true
        }

        fun setTier(building: Building, tier: Int) {
            building.setTier(tier)
            Nodes.needsSave = true
        }

        internal fun hasAt(chunkX: Int, chunkZ: Int): Boolean = Nodes.chunkToBuilding.containsKey(listOf(chunkX, chunkZ))
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

    // minimap
    open val showOnMinimap: Boolean = false
    open val minimapToken: String? = null

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
abstract class BuildingSaveState : SaveState {
    abstract val type: String

    override var jsonString: String? = null
}
