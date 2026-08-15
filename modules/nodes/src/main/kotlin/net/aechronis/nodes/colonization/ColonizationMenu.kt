package net.aechronis.nodes.colonization

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val ENTRIES_PER_PAGE = 45
private const val PREVIOUS_PAGE_SLOT = 45
private const val BACK_OR_CANCEL_SLOT = 49
private const val NEXT_PAGE_SLOT = 53

private sealed interface ColonizationMenuSession {
    val inventory: Inventory
    val page: Int
}

private data class NationMenuSession(
    override val inventory: Inventory,
    override val page: Int,
    val nationIds: List<UUID>,
    val hasNextPage: Boolean,
) : ColonizationMenuSession

private data class TownMenuSession(
    override val inventory: Inventory,
    override val page: Int,
    val nationId: UUID,
    val townIds: List<UUID>,
    val hasNextPage: Boolean,
) : ColonizationMenuSession

/** Two-stage inventory selection used by `/colonize`. */
object ColonizationMenu {
    private val initialized = AtomicBoolean()
    private val sessions = ConcurrentHashMap<UUID, ColonizationMenuSession>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onInventoryClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onInventoryClose)
    }

    fun openNations(
        player: Player,
        requestedPage: Int = 0,
    ) {
        val resident = Resident.fromPlayer(player)
        if (resident?.town == null) {
            Message.error(player, "You must be in a town to colonize")
            return
        }
        val attackingTown = resident.town ?: return
        val attackingNation = attackingTown.nation
        if (attackingNation == null) {
            Colonization.clearSelection(player)
            Message.error(player, "You must be in a nation to colonize")
            return
        }
        if (!canStartColonization(resident, attackingTown)) {
            Colonization.clearSelection(player)
            Message.error(player, "You must be a town officer or town leader in your nation to colonize")
            return
        }

        val nations = Nodes.nations.values
            .filter { nation ->
                nation !== attackingNation &&
                    nation.towns.any { town -> Colonization.canSelectTarget(attackingTown, town) }
            }
            .sortedBy { nation -> nation.name.lowercase() }
        if (nations.isEmpty()) {
            Message.error(player, "There are no bordering or port-reachable AI towns available to colonize")
            return
        }

        val lastPage = max(0, (nations.size - 1) / ENTRIES_PER_PAGE)
        val page = requestedPage.coerceIn(0, lastPage)
        val pageNations = nations.drop(page * ENTRIES_PER_PAGE).take(ENTRIES_PER_PAGE)
        val inventory = Inventory(
            InventoryType.CHEST_6_ROW,
            Component.text("Select Nation ${page + 1}/${lastPage + 1}", NamedTextColor.DARK_RED),
        )
        pageNations.forEachIndexed { slot, nation -> inventory.setItemStack(slot, nationItem(nation)) }
        if (page > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, navigationItem("Previous page"))
        Colonization.selectedTown(player)?.let { selected ->
            inventory.setItemStack(
                BACK_OR_CANCEL_SLOT,
                namedItem(Material.BARRIER, "Cancel ${selected.name} colonization", NamedTextColor.GRAY),
            )
        }
        if (page < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, navigationItem("Next page"))

        val session = NationMenuSession(inventory, page, pageNations.map(Nation::uuid), page < lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun close(player: Player) {
        sessions.remove(player.uuid)
    }

    private fun openTowns(
        player: Player,
        nation: Nation,
        requestedPage: Int = 0,
    ) {
        val resident = Resident.fromPlayer(player)
        val attackingTown = resident?.town
        if (attackingTown?.nation == null) {
            Message.error(player, "You must be in a nation to colonize")
            sessions.remove(player.uuid)
            return
        }
        if (!canStartColonization(resident, attackingTown)) {
            Colonization.clearSelection(player)
            Message.error(player, "You must be a town officer or town leader in your nation to colonize")
            sessions.remove(player.uuid)
            return
        }
        val towns = nation.towns
            .filter { town -> Colonization.canSelectTarget(attackingTown, town) }
            .sortedBy { town -> town.name.lowercase() }
        if (towns.isEmpty()) {
            Message.error(player, "${nation.name} has no bordering or port-reachable towns to colonize")
            openNations(player)
            return
        }

        val lastPage = max(0, (towns.size - 1) / ENTRIES_PER_PAGE)
        val page = requestedPage.coerceIn(0, lastPage)
        val pageTowns = towns.drop(page * ENTRIES_PER_PAGE).take(ENTRIES_PER_PAGE)
        val inventory = Inventory(
            InventoryType.CHEST_6_ROW,
            Component.text("Select ${nation.name} Town ${page + 1}/${lastPage + 1}", NamedTextColor.DARK_RED),
        )
        pageTowns.forEachIndexed { slot, town -> inventory.setItemStack(slot, townItem(town)) }
        if (page > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, navigationItem("Previous page"))
        inventory.setItemStack(BACK_OR_CANCEL_SLOT, namedItem(Material.ARROW, "Back to nations", NamedTextColor.GRAY))
        if (page < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, navigationItem("Next page"))

        val session = TownMenuSession(inventory, page, nation.uuid, pageTowns.map(Town::uuid), page < lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    private fun onInventoryClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (player.openInventory !== session.inventory) return
        event.isCancelled = true
        if (event.inventory !== session.inventory) return

        when (session) {
            is NationMenuSession -> handleNationClick(player, session, event.slot)
            is TownMenuSession -> handleTownClick(player, session, event.slot)
        }
    }

    private fun handleNationClick(
        player: Player,
        session: NationMenuSession,
        slot: Int,
    ) {
        when (slot) {
            PREVIOUS_PAGE_SLOT -> if (session.page > 0) nextTick(player) { openNations(player, session.page - 1) }
            BACK_OR_CANCEL_SLOT -> if (Colonization.selectedTown(player) != null) {
                Colonization.clearSelection(player)
                sessions.remove(player.uuid, session)
                nextTick(player) { player.closeInventory() }
                Message.print(player, "Colonization stopped; your active flags have been cancelled")
            }
            NEXT_PAGE_SLOT -> if (session.hasNextPage) nextTick(player) { openNations(player, session.page + 1) }
            in session.nationIds.indices -> {
                val nation = Nation.fromUuid(session.nationIds[slot])
                if (nation == null) {
                    Message.error(player, "That nation is no longer available")
                    nextTick(player) { openNations(player, session.page) }
                    return
                }
                nextTick(player) { openTowns(player, nation) }
            }
        }
    }

    private fun handleTownClick(
        player: Player,
        session: TownMenuSession,
        slot: Int,
    ) {
        when (slot) {
            PREVIOUS_PAGE_SLOT -> if (session.page > 0) {
                val nation = Nation.fromUuid(session.nationId) ?: return
                nextTick(player) { openTowns(player, nation, session.page - 1) }
            }
            BACK_OR_CANCEL_SLOT -> nextTick(player) { openNations(player) }
            NEXT_PAGE_SLOT -> if (session.hasNextPage) {
                val nation = Nation.fromUuid(session.nationId) ?: return
                nextTick(player) { openTowns(player, nation, session.page + 1) }
            }
            in session.townIds.indices -> {
                val nation = Nation.fromUuid(session.nationId)
                val town = Town.fromUuid(session.townIds[slot])
                if (nation == null || town?.nation !== nation || !town.isAi) {
                    Message.error(player, "That AI town is no longer available")
                    nextTick(player) { openNations(player) }
                    return
                }
                Colonization.selectTarget(player, town)
                    .onSuccess { selected ->
                        sessions.remove(player.uuid, session)
                        nextTick(player) { player.closeInventory() }
                        Message.print(
                            player,
                            "Started colonizing ${selected.name}.",
                        )
                    }
                    .onFailure { error -> Message.error(player, error.message ?: "Cannot colonize that town") }
            }
        }
    }

    private fun onInventoryClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
    }

    private fun nextTick(
        player: Player,
        action: () -> Unit,
    ) {
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) action()
        }
    }

    private fun nationItem(nation: Nation): ItemStack = namedItem(Material.WHITE_BANNER, nation.name, NamedTextColor.RED)
        .withLore(
            Component.text("${nation.towns.count(Town::isAi)} AI towns", NamedTextColor.GRAY),
            Component.text("${nation.towns.size} total towns", NamedTextColor.GRAY),
        )

    private fun townItem(town: Town): ItemStack {
        val config = town.aiConfig
        val guns = if (config.guns.isEmpty()) "No guns configured" else config.guns.joinToString(", ")
        val defenders = when {
            config.enabled -> "${config.enemyCount} defenders"
            config.configured -> "Defenders disabled: unavailable gun"
            else -> "No defenders configured"
        }
        return namedItem(Material.IRON_DOOR, town.name, NamedTextColor.GOLD)
            .withLore(
                Component.text(defenders, NamedTextColor.GRAY),
                Component.text(guns, NamedTextColor.GRAY),
            )
    }

    private fun navigationItem(name: String): ItemStack = namedItem(Material.ARROW, name, NamedTextColor.AQUA)

    private fun namedItem(
        material: Material,
        name: String,
        color: NamedTextColor,
    ): ItemStack = ItemStack.of(material).withCustomName(Component.text(name, color))
}
