package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.kyori.adventure.text.Component
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack

const val ACTIVE_BUILDING_DURATION_MS = 3_600_000L
internal const val BUILDING_INPUT_SLOTS = 27

/** Shift-click deposits only visit the input area, never recipe or navigation buttons. */
internal class BuildingInputInventory(title: Component) : Inventory(InventoryType.CHEST_5_ROW, title) {
    override fun getInnerSize(): Int = BUILDING_INPUT_SLOTS
}

/** One approved structure per chunk; position is where finished output drops. */
class ActiveBuilding(
    val definitionName: String,
    val world: String,
    val position: BlockVec,
    tier: Int,
    production: Production? = null,
    inputs: List<ItemStack> = emptyList(),
    displayName: Component = Nodes.config.activeBuildings.firstOrNull { it.name == definitionName }?.displayName
        ?: Component.text(definitionName.replace('-', ' ')),
) : Building(Math.floorDiv(position.blockX(), 16), Math.floorDiv(position.blockZ(), 16), tier, displayName) {
    val outputPosition: Pos get() = Pos(position).add(0.5, 1.1, 0.5)

    internal val inputInventory = BuildingInputInventory(displayName).also { inventory ->
        require(inputs.size <= BUILDING_INPUT_SLOTS) { "Too many building input slots" }
        inputs.forEachIndexed { slot, stack -> inventory.setItemStack(slot, stack) }
    }

    internal fun inputItems(): List<ItemStack> = inputInventory.itemStacks.take(BUILDING_INPUT_SLOTS)

    internal fun inputsChanged() {
        needsUpdate()
        Nodes.markWorldDirty()
    }

    data class Production(val startedAt: Long, val recipeName: String, val output: List<ItemStack>) {
        fun isComplete(now: Long): Boolean = now - startedAt >= ACTIVE_BUILDING_DURATION_MS
        fun progress(now: Long): Int = ((now - startedAt).coerceIn(0, ACTIVE_BUILDING_DURATION_MS) * 100 / ACTIVE_BUILDING_DURATION_MS).toInt()
    }

    @Volatile
    var production: Production? = production
        private set

    override val type = "active"
    val definition: ActiveBuildingDefinition?
        get() = Nodes.config.activeBuildings.firstOrNull { it.name == definitionName }

    override val minimapIconCodepoint: Int
        get() = production?.let { 0xE021 + it.progress(System.currentTimeMillis()) / 10 } ?: 0xE020

    @Synchronized
    internal fun start(recipe: ActiveBuildingRecipe, now: Long) {
        check(production == null)
        production = Production(now, recipe.name, recipe.output.toList())
        changed()
    }

    @Synchronized
    internal fun finish() {
        production = null
        changed()
    }

    @Synchronized
    internal fun setProgress(percent: Int, now: Long): Boolean {
        require(percent in 0..100) { "Progress must be between 0 and 100" }
        val current = production ?: return false
        production = current.copy(startedAt = now - ACTIVE_BUILDING_DURATION_MS * percent / 100)
        changed()
        return true
    }

    private fun changed() {
        needsUpdate()
        Nodes.markWorldDirty()
        Resident.renderMinimaps()
    }

    override fun createSaveState(): ActiveBuildingSaveState = ActiveBuildingSaveState(this)

    class ActiveBuildingSaveState(building: ActiveBuilding) : BuildingSaveState() {
        override val type = building.type
        override val chunkX = building.chunkX
        override val chunkZ = building.chunkZ
        override val tier = building.tier
        val definitionName = building.definitionName
        val world = building.world
        val position = building.position
        val production = building.production
        val inputs = building.inputItems()
    }

    override fun printInfo(sender: CommandSender) {
        Message.print(sender, "$definitionName (tier $tier) at ${position.blockX()}, ${position.blockY()}, ${position.blockZ()}")
        Message.print(sender, production?.let { "${it.recipeName}: ${it.progress(System.currentTimeMillis())}%" } ?: "Idle")
    }

    companion object {
        fun create(definitionName: String, world: String, position: BlockVec, tier: Int): Result<ActiveBuilding> = runCatching {
            require(tier in MIN_TIER..MAX_TIER) { "Tier must be between 1 and 3" }
            require(Nodes.config.activeBuildings.any { it.name == definitionName }) { "Unknown active building: $definitionName" }
            require(Territory.fromBlock(position.blockX(), position.blockZ())?.town != null) { "Active buildings require a claimed territory" }
            val building = ActiveBuilding(definitionName, world, position, tier)
            if (hasAt(building.chunkX, building.chunkZ)) throw net.aechronis.nodes.constants.ErrorChunkHasBuilding
            register(building)
            Nodes.markWorldDirty()
            building
        }
    }
}
