package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.BLOCK_FOLDERS
import net.aechronis.nodes.constants.BlockFolder
import net.aechronis.nodes.constants.PURCHASABLE_BLOCKS
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
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
import net.minestom.server.event.player.PlayerDisconnectEvent
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
private const val QUERY_INPUT_KEY = "query"
private const val SEARCH_SESSION_TOKEN_KEY = "session"
private const val MAX_QUERY_LENGTH = 32

// Layout: a glass frame around rows 0 and 5 and columns 0 and 8. The seven-by-four interior
// (rows 1-4, columns 1-7) holds the entries and the bottom row holds the controls.
private val CONTENT_SLOTS: List<Int> = (1..4).flatMap { row -> (1..7).map { column -> row * 9 + column } }
private val CONTENT_SLOT_INDEX: Map<Int, Int> = CONTENT_SLOTS.withIndex().associate { (index, slot) -> slot to index }
private val ITEMS_PER_PAGE = CONTENT_SLOTS.size

private const val BACK_SLOT = 45
private const val PREVIOUS_PAGE_SLOT = 46
private const val SEARCH_SLOT = 47
private const val CLEAR_SEARCH_SLOT = 48
private const val BALANCE_SLOT = 49
private const val HELP_SLOT = 50
private const val PAGE_SLOT = 51
private const val NEXT_PAGE_SLOT = 53

private const val ROOT_SEARCH_ALL_SLOT = 47
private const val ROOT_BALANCE_SLOT = 49
private const val ROOT_HELP_SLOT = 51

private val BLOCKSHOP_SEARCH_ACTION = Key.key("nodes", "blockshop_search")
private val BLOCKSHOP_CANCEL_SEARCH_ACTION = Key.key("nodes", "blockshop_cancel_search")

private sealed interface ShopSession

private data class CategoryRootSession(
    val inventory: Inventory,
    val resident: Resident,
) : ShopSession

/** [folder] null means "search across every folder" (reached via /blockshop <query> or the root's Search All button). */
private data class BrowseShopSession(
    val inventory: Inventory,
    val resident: Resident,
    val folder: BlockFolder?,
    val query: String,
    val page: Int,
    val entries: List<Material>,
    val hasNextPage: Boolean,
) : ShopSession

private data class SearchShopSession(
    val resident: Resident,
    val folder: BlockFolder?,
    val previousQuery: String,
    val previousPage: Int,
    val token: String = UUID.randomUUID().toString(),
) : ShopSession

/**
 * Server-side block shop: a root screen of folder folders (Wood, Stone, ...), each
 * opening a paginated/searchable list of that folder's blocks, plus a global search that
 * cuts across every folder. Buys any [PURCHASABLE_BLOCKS] material for [COST_PER_BLOCK]
 * stone coin each, earned by mining stone or with /sell. Every purchase takes the coins first
 * and refunds them if the items do not fit, so a full inventory never costs anything. Structured like WaypointMenu.kt (session-per-player map, cancel-all-clicks
 * inventory, native dialog for text search instead of an anvil-rename hack).
 */
object BlockShop {
    private val initialized = AtomicBoolean()
    private val sessions = ConcurrentHashMap<UUID, ShopSession>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Nodes.eventNode.addListener(PlayerCustomClickEvent::class.java, this::onCustomClick)
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onInventoryClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onInventoryClose)
        // A closed search dialog sends no event, so drop any session left behind when the player leaves.
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java) { event -> sessions.remove(event.player.uuid) }
    }

    fun openRoot(player: Player, resident: Resident) {
        val inventory = Inventory(InventoryType.CHEST_6_ROW, Component.text("Block Shop", NamedTextColor.DARK_AQUA))
        fillFrame(inventory)
        BLOCK_FOLDERS.zip(CONTENT_SLOTS).forEach { (folder, slot) ->
            inventory.setItemStack(
                slot,
                ItemStack.of(folder.icon)
                    .withCustomName(Component.text(folder.label, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
                    .withLore(
                        listOf(
                            lore("${folder.materials.size} blocks", NamedTextColor.GRAY),
                            Component.empty(),
                            lore("Click to browse", NamedTextColor.YELLOW),
                        ),
                    ),
            )
        }
        inventory.setItemStack(
            ROOT_SEARCH_ALL_SLOT,
            namedItem(Material.COMPASS, "Search all blocks", NamedTextColor.AQUA).withLore(listOf(lore("Click to search every folder", NamedTextColor.GRAY))),
        )
        inventory.setItemStack(ROOT_BALANCE_SLOT, balanceItem(resident))
        inventory.setItemStack(ROOT_HELP_SLOT, helpItem())

        val session = CategoryRootSession(inventory, resident)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun open(player: Player, resident: Resident, folder: BlockFolder?, query: String = "", page: Int = 0) {
        val base = folder?.materials ?: PURCHASABLE_BLOCKS
        val entries = filterEntries(base, query)
        val lastPage = max(0, (entries.size - 1) / ITEMS_PER_PAGE)
        val clampedPage = page.coerceIn(0, lastPage)
        val pageEntries = entries.drop(clampedPage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)

        val title = folder?.label ?: "All Blocks"
        val inventory = Inventory(
            InventoryType.CHEST_6_ROW,
            Component.text("$title (${entries.size})", NamedTextColor.DARK_AQUA),
        )
        fillFrame(inventory)
        pageEntries.forEachIndexed { index, material -> inventory.setItemStack(CONTENT_SLOTS[index], shopItem(material)) }

        inventory.setItemStack(BACK_SLOT, namedItem(Material.ARROW, "Back to all folders", NamedTextColor.YELLOW))
        if (clampedPage > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, navItem(Material.ARROW, "Previous page"))
        inventory.setItemStack(
            SEARCH_SLOT,
            namedItem(Material.COMPASS, "Search", NamedTextColor.AQUA)
                .withLore(listOf(lore(if (query.isBlank()) "Click to search" else "Current: \"$query\"", NamedTextColor.GRAY))),
        )
        if (query.isNotBlank()) {
            inventory.setItemStack(CLEAR_SEARCH_SLOT, namedItem(Material.BARRIER, "Clear search", NamedTextColor.RED))
        }
        inventory.setItemStack(BALANCE_SLOT, balanceItem(resident))
        inventory.setItemStack(HELP_SLOT, helpItem())
        inventory.setItemStack(PAGE_SLOT, namedItem(Material.PAPER, "Page ${clampedPage + 1}/${lastPage + 1}", NamedTextColor.WHITE))
        if (clampedPage < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, navItem(Material.ARROW, "Next page"))

        val session = BrowseShopSession(inventory, resident, folder, query, clampedPage, pageEntries, clampedPage < lastPage)
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
                if (player.isOnline) openSearchDialog(player, session.resident, folder = null, previousQuery = "", previousPage = 0)
            }
            return
        }
        val folder = CONTENT_SLOT_INDEX[slot]?.let(BLOCK_FOLDERS::getOrNull) ?: return
        openNextTick(player, session.resident, folder, "", 0)
    }

    private fun handleShopClick(player: Player, session: BrowseShopSession, event: InventoryPreClickEvent) {
        when (val slot = event.slot) {
            BACK_SLOT -> openRootNextTick(player, session.resident)
            PREVIOUS_PAGE_SLOT -> if (session.page > 0) openNextTick(player, session.resident, session.folder, session.query, session.page - 1)
            SEARCH_SLOT -> MinecraftServer.getSchedulerManager().scheduleNextTick {
                if (player.isOnline) openSearchDialog(player, session.resident, session.folder, session.query, session.page)
            }
            CLEAR_SEARCH_SLOT -> if (session.query.isNotBlank()) openNextTick(player, session.resident, session.folder, "", 0)
            NEXT_PAGE_SLOT -> if (session.hasNextPage) openNextTick(player, session.resident, session.folder, session.query, session.page + 1)
            else -> {
                val index = CONTENT_SLOT_INDEX[slot] ?: return
                if (index < session.entries.size) handlePurchase(player, session, index, event.click)
            }
        }
    }

    // The coins are the gate: they are taken first, so a stale or doubled click can never hand out free items,
    // and they are refunded when the items do not fit. A resident that is no longer registered (after a
    // reload) gets the menu closed instead of spending a balance that would not be saved.
    private fun handlePurchase(player: Player, session: BrowseShopSession, index: Int, click: Click) {
        val resident = session.resident
        if (Resident.fromPlayer(player) !== resident) {
            player.closeInventory()
            return
        }
        val material = session.entries[index]
        val shiftClick = click is Click.LeftShift || click is Click.RightShift
        val requested = if (shiftClick) material.maxStackSize() else 1
        val affordable = min(resident.blockCoins / COST_PER_BLOCK, Int.MAX_VALUE.toLong()).toInt()
        val amount = min(requested, affordable)
        if (amount <= 0) {
            Message.error(player, "Not enough stone coins")
            return
        }
        val cost = amount * COST_PER_BLOCK
        if (!resident.removeBlockCoins(cost)) {
            Message.error(player, "Not enough stone coins")
            return
        }
        if (!player.inventory.addItemStack(ItemStack.of(material, amount))) {
            resident.addBlockCoins(cost)
            Message.error(player, "Not enough inventory space")
            return
        }
        Message.print(player, "Bought $amount ${prettyName(material)} for ${coins(cost)} (balance: ${coins(resident.blockCoins)})")
        openNextTick(player, resident, session.folder, session.query, session.page)
    }

    private fun openSearchDialog(player: Player, resident: Resident, folder: BlockFolder?, previousQuery: String, previousPage: Int) {
        if (player.openInventory != null) player.closeInventory()
        val session = SearchShopSession(resident, folder, previousQuery, previousPage)
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
            openNextTick(player, session.resident, session.folder, session.previousQuery, session.previousPage)
            return
        }
        val query = payload.getString(QUERY_INPUT_KEY).take(MAX_QUERY_LENGTH)
        openNextTick(player, session.resident, session.folder, query, 0)
    }

    private fun openNextTick(player: Player, resident: Resident, folder: BlockFolder?, query: String, page: Int) {
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) open(player, resident, folder, query, page)
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

    private fun coins(amount: Long): String = "$amount stone coin${if (amount == 1L) "" else "s"}"

    private fun shopItem(material: Material): ItemStack = ItemStack.of(material)
        .withCustomName(Component.text(prettyName(material), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
        .withLore(
            listOf(
                lore("Cost: $COST_PER_BLOCK stone coin", NamedTextColor.GRAY),
                Component.empty(),
                lore("Left-click: buy 1", NamedTextColor.YELLOW),
                lore("Shift-click: buy a stack", NamedTextColor.YELLOW),
            ),
        )

    private fun balanceItem(resident: Resident): ItemStack = namedItem(Material.EMERALD, "Stone Coins: ${resident.blockCoins}", NamedTextColor.GREEN)
        .withLore(
            listOf(
                lore("Mine stone to earn coins", NamedTextColor.GRAY),
                lore("or sell blocks with /sell.", NamedTextColor.GRAY),
                lore("Check anytime with /balance.", NamedTextColor.GRAY),
            ),
        )

    private fun helpItem(): ItemStack = namedItem(Material.BOOK, "How it works", NamedTextColor.GOLD).withLore(
        listOf(
            lore("1. Earn stone coins:", NamedTextColor.GRAY),
            lore("    mine stone, or /sell cobblestone,", NamedTextColor.DARK_GRAY),
            lore("    dirt and logs for 1 coin each.", NamedTextColor.DARK_GRAY),
            lore("2. Pick a folder or search.", NamedTextColor.GRAY),
            lore("3. Click a block to buy 1,", NamedTextColor.GRAY),
            lore("    shift-click to buy a stack.", NamedTextColor.DARK_GRAY),
            Component.empty(),
            lore("Every block costs 1 stone coin.", NamedTextColor.DARK_GRAY),
        ),
    )

    private fun navItem(material: Material, name: String): ItemStack = namedItem(material, name, NamedTextColor.AQUA)

    private fun lore(text: String, color: NamedTextColor): Component = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun namedItem(material: Material, name: String, color: NamedTextColor): ItemStack = ItemStack.of(material)
        .withCustomName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))

    /** Fills the border (rows 0 and 5, columns 0 and 8) with blank glass; the interior stays empty. */
    private fun fillFrame(inventory: Inventory) {
        val glass = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).withCustomName(Component.text(" "))
        for (slot in 0 until inventory.size) {
            val row = slot / 9
            val column = slot % 9
            if (row == 0 || row == 5 || column == 0 || column == 8) inventory.setItemStack(slot, glass)
        }
    }
}
