package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.BlockCategory
import net.aechronis.nodes.constants.CATEGORY_BLOCKS
import net.aechronis.nodes.constants.PURCHASABLE_BLOCKS
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.dialog.Dialog
import net.minestom.server.dialog.DialogAction
import net.minestom.server.dialog.DialogActionButton
import net.minestom.server.dialog.DialogAfterAction
import net.minestom.server.dialog.DialogInput
import net.minestom.server.dialog.DialogMetadata
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerCustomClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private const val COST_PER_BLOCK = 1L
private const val ITEMS_PER_PAGE = 45
private const val BACK_SLOT = 45
private const val PREVIOUS_PAGE_SLOT = 46
private const val SEARCH_SLOT = 47
private const val CLEAR_SEARCH_SLOT = 48
private const val BALANCE_SLOT = 50
private const val NEXT_PAGE_SLOT = 53
private const val ROOT_SEARCH_ALL_SLOT = 49
private const val QUERY_INPUT_KEY = "query"
private const val SEARCH_SESSION_TOKEN_KEY = "session"
private const val MAX_QUERY_LENGTH = 32
private val BLOCKSHOP_SEARCH_ACTION = Key.key("nodes", "blockshop_search")
private val BLOCKSHOP_CANCEL_SEARCH_ACTION = Key.key("nodes", "blockshop_cancel_search")
private val CATEGORY_ORDER = BlockCategory.entries.toList()

private sealed interface ShopSession

private data class CategoryRootSession(
    val inventory: Inventory,
    val resident: Resident,
) : ShopSession

/** [category] null means "search across every category" (reached via /blockshop <query> or the root's Search All button). */
private data class BrowseShopSession(
    val inventory: Inventory,
    val resident: Resident,
    val category: BlockCategory?,
    val query: String,
    val page: Int,
    val entries: List<Material>,
    val hasNextPage: Boolean,
) : ShopSession

private data class SearchShopSession(
    val resident: Resident,
    val category: BlockCategory?,
    val previousQuery: String,
    val previousPage: Int,
    val token: String = UUID.randomUUID().toString(),
) : ShopSession

/**
 * Server-side block shop: a root screen of category folders (Wood, Stone, ...), each
 * opening a paginated/searchable list of that category's blocks, plus a global search that
 * cuts across every category. Buys any [PURCHASABLE_BLOCKS] material for [COST_PER_BLOCK]
 * block coins each, earned via /sell. Every purchase attempts to give the item first and
 * only deducts coins if that succeeds -- see the plan doc's anti-dupe section. Structured
 * like WaypointMenu.kt (session-per-player map, cancel-all-clicks inventory, native dialog
 * for text search instead of an anvil-rename hack).
 */
object BlockShop {
    private val initialized = AtomicBoolean()
    private val sessions = ConcurrentHashMap<UUID, ShopSession>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Nodes.eventNode.addListener(PlayerCustomClickEvent::class.java, this::onCustomClick)
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onInventoryClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onInventoryClose)
    }

    fun openRoot(player: Player, resident: Resident) {
        val inventory = Inventory(InventoryType.CHEST_6_ROW, Component.text("Block Shop", NamedTextColor.DARK_AQUA))
        CATEGORY_ORDER.forEachIndexed { slot, category ->
            val count = CATEGORY_BLOCKS[category]?.size ?: 0
            inventory.setItemStack(
                slot,
                namedItem(category.icon, category.label, NamedTextColor.GOLD)
                    .withLore(listOf(Component.text("$count blocks", NamedTextColor.GRAY), Component.text("Click to browse", NamedTextColor.DARK_GRAY))),
            )
        }
        inventory.setItemStack(
            ROOT_SEARCH_ALL_SLOT,
            namedItem(Material.NAME_TAG, "Search all blocks", NamedTextColor.AQUA),
        )
        inventory.setItemStack(
            BALANCE_SLOT,
            namedItem(Material.EMERALD, "Balance: ${resident.blockCoins} block coin${if (resident.blockCoins == 1L) "" else "s"}", NamedTextColor.GREEN),
        )

        val session = CategoryRootSession(inventory, resident)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun open(player: Player, resident: Resident, category: BlockCategory?, query: String = "", page: Int = 0) {
        val base = if (category != null) CATEGORY_BLOCKS[category].orEmpty() else PURCHASABLE_BLOCKS
        val entries = filterEntries(base, query)
        val lastPage = max(0, (entries.size - 1) / ITEMS_PER_PAGE)
        val clampedPage = page.coerceIn(0, lastPage)
        val pageEntries = entries.drop(clampedPage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)

        val title = category?.label ?: "All Blocks"
        val inventory = Inventory(
            InventoryType.CHEST_6_ROW,
            Component.text("$title ${clampedPage + 1}/${lastPage + 1} (${entries.size})", NamedTextColor.DARK_AQUA),
        )
        pageEntries.forEachIndexed { slot, material -> inventory.setItemStack(slot, shopItem(material)) }
        inventory.setItemStack(BACK_SLOT, namedItem(Material.ARROW, "Back to categories", NamedTextColor.YELLOW))
        if (clampedPage > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, navItem(Material.ARROW, "Previous page"))
        inventory.setItemStack(
            SEARCH_SLOT,
            namedItem(Material.NAME_TAG, "Search", NamedTextColor.AQUA)
                .withLore(listOf(Component.text(if (query.isBlank()) "Click to search" else "Current: \"$query\"", NamedTextColor.GRAY))),
        )
        if (query.isNotBlank()) {
            inventory.setItemStack(CLEAR_SEARCH_SLOT, namedItem(Material.BARRIER, "Clear search", NamedTextColor.RED))
        }
        inventory.setItemStack(
            BALANCE_SLOT,
            namedItem(Material.EMERALD, "Balance: ${resident.blockCoins} block coin${if (resident.blockCoins == 1L) "" else "s"}", NamedTextColor.GREEN),
        )
        if (clampedPage < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, navItem(Material.ARROW, "Next page"))

        val session = BrowseShopSession(inventory, resident, category, query, clampedPage, pageEntries, clampedPage < lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    private fun filterEntries(base: List<Material>, query: String): List<Material> {
        if (query.isBlank()) return base
        return base.filter { material ->
            // Match the material's own id/name, not the "minecraft:" namespace prefix --
            // otherwise a query like "mine" would match every single block.
            material.key().asString().substringAfter(':').contains(query, ignoreCase = true) ||
                prettyName(material).contains(query, ignoreCase = true)
        }
    }

    private fun onInventoryClick(event: InventoryPreClickEvent) {
        val player = event.player
        when (val session = sessions[player.uuid]) {
            is CategoryRootSession -> {
                if (player.openInventory !== session.inventory) return
                event.isCancelled = true
                if (event.inventory !== session.inventory) return
                handleRootClick(player, session, event)
            }
            is BrowseShopSession -> {
                if (player.openInventory !== session.inventory) return
                event.isCancelled = true
                if (event.inventory !== session.inventory) return
                handleShopClick(player, session, event)
            }
            else -> return
        }
    }

    private fun handleRootClick(player: Player, session: CategoryRootSession, event: InventoryPreClickEvent) {
        val slot = event.slot
        if (slot == ROOT_SEARCH_ALL_SLOT) {
            MinecraftServer.getSchedulerManager().scheduleNextTick {
                if (player.isOnline) openSearchDialog(player, session.resident, category = null, previousQuery = "", previousPage = 0)
            }
            return
        }
        val category = CATEGORY_ORDER.getOrNull(slot) ?: return
        openNextTick(player, session.resident, category, "", 0)
    }

    private fun handleShopClick(player: Player, session: BrowseShopSession, event: InventoryPreClickEvent) {
        when (val slot = event.slot) {
            BACK_SLOT -> openRootNextTick(player, session.resident)
            PREVIOUS_PAGE_SLOT -> if (session.page > 0) openNextTick(player, session.resident, session.category, session.query, session.page - 1)
            SEARCH_SLOT -> MinecraftServer.getSchedulerManager().scheduleNextTick {
                if (player.isOnline) openSearchDialog(player, session.resident, session.category, session.query, session.page)
            }
            CLEAR_SEARCH_SLOT -> if (session.query.isNotBlank()) openNextTick(player, session.resident, session.category, "", 0)
            NEXT_PAGE_SLOT -> if (session.hasNextPage) openNextTick(player, session.resident, session.category, session.query, session.page + 1)
            in 0 until session.entries.size -> handlePurchase(player, session, slot, event.click)
            else -> Unit
        }
    }

    // Attempts to give the item BEFORE spending any coins -- a full inventory means the
    // purchase silently doesn't happen and nothing is deducted. Never spend coins first.
    private fun handlePurchase(player: Player, session: BrowseShopSession, slot: Int, click: Click) {
        val resident = session.resident
        val material = session.entries[slot]
        val shiftClick = click is Click.LeftShift || click is Click.RightShift
        val requested = if (shiftClick) material.maxStackSize() else 1
        val affordable = min(resident.blockCoins / COST_PER_BLOCK, Int.MAX_VALUE.toLong()).toInt()
        val amount = min(requested, affordable)
        if (amount <= 0) {
            Message.error(player, "Not enough block coins")
            return
        }
        if (!player.inventory.addItemStack(ItemStack.of(material, amount))) {
            Message.error(player, "Not enough inventory space")
            return
        }
        val cost = amount * COST_PER_BLOCK
        resident.removeBlockCoins(cost)
        Message.print(player, "Bought $amount ${prettyName(material)} for $cost block coin${if (cost == 1L) "" else "s"} (balance: ${resident.blockCoins})")
        openNextTick(player, resident, session.category, session.query, session.page)
    }

    private fun openSearchDialog(player: Player, resident: Resident, category: BlockCategory?, previousQuery: String, previousPage: Int) {
        if (player.openInventory != null) player.closeInventory()
        val session = SearchShopSession(resident, category, previousQuery, previousPage)
        sessions[player.uuid] = session
        player.showDialog(searchDialog(session.token, previousQuery))
    }

    private fun searchDialog(token: String, initialQuery: String): Dialog.Confirmation {
        val tokenPayload = CompoundBinaryTag.builder().putString(SEARCH_SESSION_TOKEN_KEY, token).build()
        val metadata = DialogMetadata(
            Component.text("Search blocks", NamedTextColor.DARK_AQUA),
            null,
            true,
            false,
            DialogAfterAction.CLOSE,
            emptyList(),
            listOf(DialogInput.Text(QUERY_INPUT_KEY, 260, Component.text("Search"), false, initialQuery, MAX_QUERY_LENGTH, null)),
        )
        return Dialog.Confirmation(
            metadata,
            DialogActionButton(
                Component.text("Search", NamedTextColor.GREEN),
                null,
                120,
                DialogAction.DynamicCustom(BLOCKSHOP_SEARCH_ACTION, tokenPayload),
            ),
            DialogActionButton(
                Component.text("Cancel", NamedTextColor.RED),
                null,
                120,
                DialogAction.DynamicCustom(BLOCKSHOP_CANCEL_SEARCH_ACTION, tokenPayload),
            ),
        )
    }

    private fun onCustomClick(event: PlayerCustomClickEvent) {
        if (event.key != BLOCKSHOP_SEARCH_ACTION && event.key != BLOCKSHOP_CANCEL_SEARCH_ACTION) return
        val player = event.player
        val session = sessions[player.uuid] as? SearchShopSession ?: return
        val payload = event.payload as? CompoundBinaryTag ?: return
        if (payload.getString(SEARCH_SESSION_TOKEN_KEY) != session.token) return
        if (!sessions.remove(player.uuid, session)) return
        if (Resident.fromPlayer(player) !== session.resident) return

        if (event.key == BLOCKSHOP_CANCEL_SEARCH_ACTION) {
            openNextTick(player, session.resident, session.category, session.previousQuery, session.previousPage)
            return
        }
        val query = payload.getString(QUERY_INPUT_KEY).take(MAX_QUERY_LENGTH)
        openNextTick(player, session.resident, session.category, query, 0)
    }

    private fun openNextTick(player: Player, resident: Resident, category: BlockCategory?, query: String, page: Int) {
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) open(player, resident, category, query, page)
        }
    }

    private fun openRootNextTick(player: Player, resident: Resident) {
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) openRoot(player, resident)
        }
    }

    private fun onInventoryClose(event: InventoryCloseEvent) {
        when (val session = sessions[event.player.uuid]) {
            is CategoryRootSession -> if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
            is BrowseShopSession -> if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
            else -> Unit
        }
    }

    private fun prettyName(material: Material): String = material.key().asString()
        .substringAfter(':')
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun shopItem(material: Material): ItemStack = namedItem(material, prettyName(material), NamedTextColor.WHITE)
        .withLore(
            listOf(
                Component.text("Cost: $COST_PER_BLOCK block coin", NamedTextColor.GRAY),
                Component.text("Left-click: buy 1", NamedTextColor.DARK_GRAY),
                Component.text("Shift-click: buy a stack", NamedTextColor.DARK_GRAY),
            ),
        )

    private fun navItem(material: Material, name: String): ItemStack = namedItem(material, name, NamedTextColor.AQUA)

    private fun namedItem(material: Material, name: String, color: NamedTextColor): ItemStack = ItemStack.of(material).with(DataComponents.CUSTOM_NAME, Component.text(name, color))
}
