package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.managers.Items
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture

/** Serializes starts and completion so two users cannot consume inputs for the same run. */
object ActiveBuildings {
    private val chunkLoads = mutableMapOf<ActiveBuilding, CompletableFuture<*>>()
    private var task: Task? = null
    private var lastMapProgress = emptyMap<ActiveBuilding, Int>()

    // Time left seen last tick, so each warning fires once when a run crosses its mark
    // (not again after a restart that lands past it).
    private val lastTimeLeft = mutableMapOf<ActiveBuilding, Long>()
    private val WARNING_MINUTES = listOf(30L, 20L, 10L)

    fun init() {
        require(Nodes.config.activeBuildings.map { it.name }.distinct().size == Nodes.config.activeBuildings.size) {
            "Active building names must be unique"
        }
        TownBuildingsMenu.init()
        task = ModuleScheduler.buildTask(::tick).repeat(TaskSchedule.tick(20)).schedule()
    }

    @Synchronized
    fun shutdown() {
        task?.cancel()
        task = null
        ActiveBuildingPresentation.clear()
        TownBuildingsMenu.closeAll()
        chunkLoads.clear()
        lastMapProgress = emptyMap()
        lastTimeLeft.clear()
    }

    internal fun canManage(player: Player, building: Building): Boolean {
        val resident = Resident.fromPlayer(player) ?: return false
        val town = resident.town ?: return false
        if (building is ActiveBuilding && resident !== town.leader && resident !in town.officers) return false
        return Building.getAt(building.chunkX, building.chunkZ) === building &&
            Territory.fromCoord(Coord(building.chunkX, building.chunkZ))?.town === town
    }

    @Synchronized
    internal fun start(player: Player, building: ActiveBuilding, recipe: ActiveBuildingRecipe) {
        if (building.production != null || !canManage(player, building)) return
        if (recipe !in building.definition?.recipes?.get(building.tier).orEmpty()) return
        val remaining = consumeInputs(building.inputItems(), recipe.input)
        if (remaining == null) {
            player.sendMessage(
                Component.text("Place the required materials in the building's input slots: ", NamedTextColor.RED)
                    .append(formatItems(recipe.input)),
            )
            return
        }
        remaining.forEachIndexed { slot, stack -> building.inputInventory.setItemStack(slot, stack) }
        building.start(recipe, System.currentTimeMillis())
        TownBuildingsMenu.refresh(building)
        Message.print(player, "Production started; output will drop at the building in ${ActiveBuildings.durationText()}")
    }

    @Synchronized
    internal fun setProgress(building: ActiveBuilding, percent: Int): Boolean {
        if (Building.getAt(building.chunkX, building.chunkZ) !== building) return false
        if (!building.setProgress(percent, System.currentTimeMillis())) return false
        TownBuildingsMenu.refresh(building)
        return true
    }

    /** Returns a complete plan, or null without changing any items when inputs are missing. */
    internal fun consumeInputs(available: List<ItemStack>, required: List<ItemStack>): List<ItemStack>? {
        val remaining = available.toMutableList()
        for (input in required) {
            var needed = input.amount()
            for (slot in remaining.indices) {
                val stack = remaining[slot]
                if (!stack.isSimilar(input)) continue
                val taken = minOf(needed, stack.amount())
                remaining[slot] = stack.consume(taken)
                needed -= taken
                if (needed == 0) break
            }
            if (needed != 0) return null
        }
        return remaining
    }

    @Synchronized
    private fun tick() {
        TownBuildingsMenu.refreshOpenMenus()
        val buildings = Building.all().filterIsInstance<ActiveBuilding>()
        val now = System.currentTimeMillis()
        for (building in buildings) {
            val production = building.production ?: continue
            if (!production.isComplete(now)) {
                warnIfDue(building, production.endsAt - now)
                continue
            }
            val instance = MinecraftServer.getInstanceManager().instances.firstOrNull { it.getDimensionName() == building.world } ?: continue
            if (instance.getChunk(building.chunkX, building.chunkZ)?.isLoaded != true) {
                val loading = chunkLoads[building]
                if (loading == null || loading.isDone) {
                    chunkLoads[building] = instance.loadChunk(building.chunkX, building.chunkZ)
                }
                continue
            }
            chunkLoads.remove(building)
            // Completion is automatic; nobody needs to interact or be online to collect it.
            production.output.forEach { stack ->
                var left = stack.amount()
                while (left > 0) {
                    val amount = minOf(left, stack.maxStackSize())
                    Items.spawn(instance, building.outputPosition, stack.withAmount(amount))
                    left -= amount
                }
            }
            building.finish()
            TownBuildingsMenu.refresh(building)
        }
        ActiveBuildingPresentation.refresh(buildings, now)
        chunkLoads.keys.retainAll(buildings.toSet())
        lastTimeLeft.keys.retainAll { it.production != null && Building.getAt(it.chunkX, it.chunkZ) === it }
        val progress = buildings.associateWith { it.minimapIconCodepoint }
        if (progress != lastMapProgress) {
            lastMapProgress = progress
            Resident.renderMinimaps()
        }
    }

    private fun warnIfDue(building: ActiveBuilding, timeLeft: Long) {
        val previous = lastTimeLeft.put(building, timeLeft) ?: return
        // If one tick crosses several marks, only the nearest is announced.
        val minutes = WARNING_MINUTES.lastOrNull { previous > it * 60_000 && timeLeft <= it * 60_000 } ?: return
        val town = Territory.fromCoord(Coord(building.chunkX, building.chunkZ))?.town?.name ?: "Unclaimed"
        val type = PlainTextComponentSerializer.plainText().serialize(building.displayName)
        val pos = building.position
        Message.broadcast("$town $type goes off in $minutes minutes at ${pos.blockX()}, ${pos.blockY()}, ${pos.blockZ()}")
    }

    internal fun durationText(): String {
        val minutes = Nodes.config.activeBuildingDurationMs / 60_000
        val hours = minutes / 60
        return when {
            minutes % 60 != 0L -> "$minutes minutes"
            hours == 1L -> "1 hour"
            else -> "$hours hours"
        }
    }

    internal fun formatItems(items: List<ItemStack>): Component = Component.join(
        JoinConfiguration.separator(Component.text(", ")),
        items.map { stack ->
            val name = stack.get(DataComponents.CUSTOM_NAME)
                ?: stack.get(DataComponents.ITEM_NAME)
                ?: Component.translatable(stack.material().translationKey())
            Component.text("${stack.amount()} ").append(name)
        },
    )
}
