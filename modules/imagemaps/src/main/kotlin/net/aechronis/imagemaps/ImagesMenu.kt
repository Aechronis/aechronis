package net.aechronis.imagemaps

import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val GRAB_COOLDOWN_MILLIS = 30_000L
private const val MAX_SLOTS = 54
private const val MAIN_SLOTS = 36
private const val IMAGES_PER_PAGE = 45
private const val PREVIOUS_PAGE_SLOT = 45
private const val NEXT_PAGE_SLOT = 53

private data class MenuSession(
    val inventory: Inventory,
    val entries: List<ImageMaps.Image>,
    val page: Int,
    val lastPage: Int,
)

/** `/images` browser, laid out like `/flags`: any player can take every tile of any image, with a cooldown between grabs. */
object ImagesMenu {
    private val sessions = ConcurrentHashMap<UUID, MenuSession>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    fun init(context: ModuleContext) {
        context.addListener(InventoryPreClickEvent::class.java, this::onClick)
        context.addListener(InventoryCloseEvent::class.java, this::onClose)
    }

    fun open(
        player: Player,
        requestedPage: Int = 0,
    ) {
        val images = ImageMaps.all()
        // Images that fit in one chest fill it; past that the bottom row is kept for the page buttons.
        val paged = images.size > MAX_SLOTS
        val lastPage = if (paged) (images.size - 1) / IMAGES_PER_PAGE else 0
        val page = requestedPage.coerceIn(0, lastPage)
        val entries = if (paged) images.drop(page * IMAGES_PER_PAGE).take(IMAGES_PER_PAGE) else images
        val inventory =
            Inventory(
                if (paged) InventoryType.CHEST_6_ROW else inventoryTypeFor(entries.size.coerceAtLeast(1)),
                Component.text(if (paged) "Images ${page + 1}/${lastPage + 1}" else "Images", NamedTextColor.DARK_GREEN),
            )
        entries.forEachIndexed { slot, image -> inventory.setItemStack(slot, icon(image)) }
        if (page > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, navigationItem("Previous page"))
        if (page < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, navigationItem("Next page"))
        val session = MenuSession(inventory, entries, page, lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun closeAll() {
        sessions.toMap().forEach { (uuid, session) ->
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)?.let { player ->
                if (player.openInventory === session.inventory) player.closeInventory()
            }
        }
        sessions.clear()
        cooldowns.clear()
    }

    // A filled map slot icon is the generic map sprite; the picture only renders once the tiles are placed in frames.
    private fun icon(image: ImageMaps.Image): ItemStack =
        ImageMaps
            .items(image)
            .first()
            .with(
                DataComponents.CUSTOM_NAME,
                line(image.name, NamedTextColor.LIGHT_PURPLE),
            ).with(
                DataComponents.LORE,
                listOf(
                    line("By ${image.ownerLabel}", NamedTextColor.GRAY),
                    line("${image.cols}x${image.rows} tiles", NamedTextColor.GRAY),
                    line("Click to take all ${image.cols * image.rows} tile(s)", NamedTextColor.AQUA),
                ),
            )

    private fun line(
        text: String,
        color: NamedTextColor,
    ) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun navigationItem(name: String): ItemStack =
        ItemStack.of(Material.ARROW).withCustomName(Component.text(name, NamedTextColor.AQUA))

    private fun openPage(
        player: Player,
        session: MenuSession,
        page: Int,
    ) {
        ModuleScheduler.scheduleNextTick {
            if (player.isOnline && player.openInventory === session.inventory) open(player, page)
        }
    }

    private fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        // Cancel clicks in the player's own inventory too, or shift-click would drop items into the temporary chest.
        event.isCancelled = true
        if (event.inventory !== session.inventory) return
        if (event.slot == PREVIOUS_PAGE_SLOT && session.page > 0) return openPage(player, session, session.page - 1)
        if (event.slot == NEXT_PAGE_SLOT && session.page < session.lastPage) return openPage(player, session, session.page + 1)
        val image = session.entries.getOrNull(event.slot) ?: return
        if (ImageMaps.all().none { it.firstMapId == image.firstMapId }) return player.error("That image was deleted")

        val now = System.currentTimeMillis()
        cooldowns.values.removeIf { it <= now }
        val expiry = cooldowns[player.uuid]
        if (expiry != null && expiry > now) {
            player.error("You can take another image in ${(expiry - now) / 1000 + 1}s")
            return
        }
        // Distinct tiles never stack, so each needs its own free slot. Only the 36 main slots count: the
        // armor and offhand slots are empty too but addItemStack never fills them.
        val tiles = ImageMaps.items(image)
        if ((0 until MAIN_SLOTS).count { player.inventory.getItemStack(it).isAir } < tiles.size) {
            player.error("You need ${tiles.size} free inventory slot(s)")
            return
        }
        tiles.forEach(player.inventory::addItemStack)
        ImageMaps.sendInventory(player)
        cooldowns[player.uuid] = now + GRAB_COOLDOWN_MILLIS
    }

    private fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid)
    }

    private fun Player.error(message: String) = sendMessage(Component.text(message, NamedTextColor.RED))

    private fun inventoryTypeFor(count: Int): InventoryType =
        when {
            count <= 9 -> InventoryType.CHEST_1_ROW
            count <= 18 -> InventoryType.CHEST_2_ROW
            count <= 27 -> InventoryType.CHEST_3_ROW
            count <= 36 -> InventoryType.CHEST_4_ROW
            count <= 45 -> InventoryType.CHEST_5_ROW
            else -> InventoryType.CHEST_6_ROW
        }
}
