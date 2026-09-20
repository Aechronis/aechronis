package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val BUILDINGS_PER_PAGE = 45
private const val PREVIOUS_PAGE = 45
private const val NEXT_PAGE = 53
private const val PRODUCTION_BACK = 36
private const val PRODUCTION_INFO = 40

/** Read-only town catalogue, plus a shared, persistent input inventory for each active building. */
object TownBuildingsMenu {
    private data class Browse(
        val town: Town,
        val inventory: Inventory,
        val page: Int,
        val buildings: List<Building>,
        val hasNextPage: Boolean,
    )

    private val browsers = ConcurrentHashMap<UUID, Browse>()
    private val inputs = ConcurrentHashMap<Inventory, ActiveBuilding>()
    private val displayedTiers = ConcurrentHashMap<ActiveBuilding, Int>()
    private val decoration = icon(Material.GRAY_STAINED_GLASS_PANE, " ")

    fun init() {
        Nodes.lowPriorityEventNode.addListener(InventoryPreClickEvent::class.java, ::click)
        Nodes.eventNode.addListener(InventoryItemChangeEvent::class.java) { event ->
            if (event.slot in 0 until BUILDING_INPUT_SLOTS) inputs[event.inventory]?.inputsChanged()
        }
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java) { event ->
            if (browsers[event.player.uuid]?.inventory === event.inventory) browsers.remove(event.player.uuid)
        }
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java) { event -> browsers.remove(event.player.uuid) }
    }

    fun open(player: Player, town: Town, requestedPage: Int = 0) {
        if (Town.fromPlayer(player) !== town) return
        val buildings = Building.all()
            .filter { Territory.fromCoord(Coord(it.chunkX, it.chunkZ))?.town === town }
            .sortedWith(compareBy<Building> { it.type }.thenBy { it.chunkX }.thenBy { it.chunkZ })
        val page = requestedPage.coerceIn(0, (buildings.size - 1).coerceAtLeast(0) / BUILDINGS_PER_PAGE)
        val shown = buildings.drop(page * BUILDINGS_PER_PAGE).take(BUILDINGS_PER_PAGE)
        val inventory = Inventory(InventoryType.CHEST_6_ROW, Component.text("${town.name}: Buildings (${page + 1})"))
        shown.forEachIndexed { slot, building -> inventory.setItemStack(slot, buildingIcon(building)) }
        if (shown.isEmpty()) inventory.setItemStack(22, icon(Material.PAPER, "No approved buildings", "Submit a structure for admin approval"))
        for (slot in BUILDINGS_PER_PAGE until inventory.size) inventory.setItemStack(slot, decoration)
        if (page > 0) inventory.setItemStack(PREVIOUS_PAGE, icon(Material.ARROW, "Previous page"))
        val hasNextPage = (page + 1) * BUILDINGS_PER_PAGE < buildings.size
        if (hasNextPage) inventory.setItemStack(NEXT_PAGE, icon(Material.ARROW, "Next page"))
        inventory.setItemStack(49, icon(Material.BOOK, "Town buildings", "Select an active building to deposit materials", "Completed output drops at the building"))
        player.openInventory(inventory)
        browsers[player.uuid] = Browse(town, inventory, page, shown, hasNextPage)
    }

    private fun openProduction(player: Player, building: ActiveBuilding) {
        if (!ActiveBuildings.canManage(player, building)) return
        inputs[building.inputInventory] = building
        refresh(building)
        player.openInventory(building.inputInventory)
    }

    internal fun refresh(building: ActiveBuilding) {
        val inventory = building.inputInventory
        if (inventory.title != building.displayName) inventory.title = building.displayName
        val controls = MutableList(inventory.size - BUILDING_INPUT_SLOTS) { decoration }
        fun control(slot: Int, item: ItemStack) {
            controls[slot - BUILDING_INPUT_SLOTS] = item
        }
        val production = building.production
        val recipes = building.definition?.recipes?.get(building.tier).orEmpty()
        recipes.forEachIndexed { index, recipe ->
            val lore = listOf(
                Component.text("Requires: ", NamedTextColor.GRAY).append(ActiveBuildings.formatItems(recipe.input)),
                Component.text("Produces: ", NamedTextColor.GRAY).append(ActiveBuildings.formatItems(recipe.output)),
                Component.text(if (production == null) "Click to start • 1 hour" else "Production in progress", NamedTextColor.GREEN),
            )
            control(BUILDING_INPUT_SLOTS + index, recipe.output.first().withAmount(1).withCustomName(recipe.displayName).withLore(lore))
        }
        control(PRODUCTION_BACK, icon(Material.ARROW, "All town buildings"))
        val status = production?.let { "${it.recipeName}: ${it.progress(System.currentTimeMillis())}%" } ?: "Idle"
        control(
            PRODUCTION_INFO,
            icon(
                Material.CHEST,
                "Shared inputs • $status",
                "Place materials in the top three rows",
                "Leaders/officers can click a recipe to start",
                "Unused materials stay here for your town",
                "Output: ${building.position.blockX()}, ${building.position.blockY()}, ${building.position.blockZ()}",
            ),
        )
        if (recipes.isEmpty()) control(BUILDING_INPUT_SLOTS, icon(Material.BARRIER, "Building type unavailable", "Stored inputs can still be withdrawn"))
        controls.forEachIndexed { index, item -> inventory.setItemStack(BUILDING_INPUT_SLOTS + index, item) }
        displayedTiers[building] = building.tier
    }

    private fun click(event: InventoryPreClickEvent) {
        if (event.isCancelled) return
        val player = event.player
        val open = player.openInventory ?: return
        val browser = browsers[player.uuid]?.takeIf { it.inventory === open }
        if (browser != null) {
            event.isCancelled = true
            if (Town.fromPlayer(player) !== browser.town) {
                player.closeInventory()
                return
            }
            if (event.inventory !== open || event.click !is Click.Left) return
            when (event.slot) {
                PREVIOUS_PAGE -> if (browser.page > 0) navigate(player, open) { open(player, browser.town, browser.page - 1) }
                NEXT_PAGE -> if (browser.hasNextPage) navigate(player, open) { open(player, browser.town, browser.page + 1) }
                in browser.buildings.indices -> {
                    val building = browser.buildings[event.slot]
                    if (!ActiveBuildings.canManage(player, building)) {
                        if (building is ActiveBuilding) {
                            Message.error(player, "Only the owning town's leader or officers can open active factories")
                            return
                        }
                        navigate(player, open) { open(player, browser.town, browser.page) }
                    } else if (building is ActiveBuilding) {
                        navigate(player, open) { openProduction(player, building) }
                    } else {
                        building.printInfo(player)
                    }
                }
            }
            return
        }
        val building = inputs[open] ?: return
        if (!ActiveBuildings.canManage(player, building)) {
            event.isCancelled = true
            player.closeInventory()
            return
        }
        val click = event.click
        // Double-click collection scans the whole container, including recipe display items.
        if (click is Click.Double || click is Click.Middle || click is Click.MiddleDrag) {
            event.isCancelled = true
            return
        }
        if (click is Click.Drag) {
            event.isCancelled = true
            if (event.inventory !== open || click.slots().none { it in BUILDING_INPUT_SLOTS until open.size }) transfer(event, building)
            return
        }
        event.isCancelled = true
        if (event.inventory !== open || event.slot < BUILDING_INPUT_SLOTS) {
            transfer(event, building)
            return
        }
        if (click !is Click.Left) return
        if (event.slot == PRODUCTION_BACK) {
            navigate(player, open) { Town.fromPlayer(player)?.let { open(player, it) } }
            return
        }
        if (displayedTiers[building] != building.tier) {
            refresh(building)
            Message.print(player, "Building tier changed; select a recipe again")
            return
        }
        val recipe = building.definition?.recipes?.get(building.tier)?.getOrNull(event.slot - BUILDING_INPUT_SLOTS) ?: return
        ActiveBuildings.start(player, building, recipe)
    }

    // Apply native transfers under the same lock as recipe consumption. This prevents two
    // town members moving/consuming the shared ingredients concurrently from different chunks.
    private fun transfer(event: InventoryPreClickEvent, building: ActiveBuilding) {
        synchronized(ActiveBuildings) {
            if (!ActiveBuildings.canManage(event.player, building)) return
            val inventory = building.inputInventory
            val nativeClick = Click.fromWindow(Click.Window(event.inventory === inventory, event.click), inventory.size)
            inventory.handleClick(event.player, nativeClick)
        }
    }

    private fun navigate(player: Player, expected: AbstractInventory, action: () -> Unit) {
        ModuleScheduler.scheduleNextTick {
            if (player.isOnline && player.openInventory === expected) action()
        }
    }

    internal fun refreshOpenMenus() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            val open = player.openInventory ?: continue
            val building = inputs[open]
            if (building != null && !ActiveBuildings.canManage(player, building)) player.closeInventory()
            val browse = browsers[player.uuid]
            if (browse != null && Town.fromPlayer(player) !== browse.town) player.closeInventory()
        }
        inputs.values.toList().forEach { building ->
            if (Building.getAt(building.chunkX, building.chunkZ) !== building) {
                inputs.remove(building.inputInventory)
                displayedTiers.remove(building)
            } else if (building.inputInventory.viewers.isNotEmpty()) {
                refresh(building)
            }
        }
        browsers.values.toList().forEach { browse ->
            browse.buildings.forEachIndexed { index, building -> browse.inventory.setItemStack(index, buildingIcon(building)) }
        }
    }

    fun closeAll() {
        val openMenus = inputs.keys + browsers.values.map { it.inventory }
        MinecraftServer.getConnectionManager().onlinePlayers.filter { it.openInventory in openMenus }.forEach(Player::closeInventory)
        browsers.clear()
        inputs.clear()
        displayedTiers.clear()
    }

    private fun buildingIcon(building: Building): ItemStack {
        val material = when (building) {
            is ActiveBuilding -> Material.FURNACE
            is Farm -> Material.WHEAT
            is Port -> Material.COD
            is OilRig -> Material.DRAGON_BREATH
            is TrainStationBuilding -> Material.RAIL
            else -> Material.BRICKS
        }
        val name = building.displayName.colorIfAbsent(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
        val lore = mutableListOf<Component>(
            Component.text("Tier ${building.tier} • Chunk ${building.chunkX}, ${building.chunkZ}", NamedTextColor.GRAY),
        )
        if (building is ActiveBuilding) {
            val status = building.production?.let { "${it.recipeName}: ${it.progress(System.currentTimeMillis())}%" } ?: "Idle"
            lore += Component.text(status, NamedTextColor.YELLOW)
            lore += Component.text("Click to open shared inputs and recipes", NamedTextColor.GREEN)
        } else {
            val income = if (building is TrainStationBuilding) Trains.incomeAt(building.chunkX, building.chunkZ) else building.income()
            lore += Component.text("Passive • hourly town income", NamedTextColor.GRAY)
            income.forEach { (item, amount) -> lore += Component.text("${amount.toInt()} ${item.name().lowercase().replace('_', ' ')}", NamedTextColor.GRAY) }
            lore += Component.text("Click for details", NamedTextColor.GREEN)
        }
        return ItemStack.of(material).withCustomName(name).withLore(lore)
    }

    private fun icon(material: Material, name: String, vararg lore: String): ItemStack = ItemStack.of(material)
        .withCustomName(Component.text(name))
        .withLore(lore.map { Component.text(it, NamedTextColor.GRAY) })
}
