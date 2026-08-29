package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val SNAPSHOTS_PER_PAGE = 45
private const val PREVIOUS_SLOT = 45
private const val BACK_SLOT = 45
private const val INFO_SLOT = 48
private const val RESTORE_SLOT = 49
private const val NEXT_SLOT = 53

private val snapshotTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private sealed interface SnapshotViewSession {
    val inventory: Inventory
    val targetName: String
}

private data class SnapshotListSession(
    override val inventory: Inventory,
    override val targetName: String,
    val page: Int,
    val snapshots: List<InventorySnapshot>,
    val hasNext: Boolean,
) : SnapshotViewSession

private data class SnapshotDetailSession(
    override val inventory: Inventory,
    override val targetName: String,
    val listPage: Int,
    val snapshotId: Long,
    val busy: AtomicBoolean = AtomicBoolean(),
) : SnapshotViewSession

object SnapshotViewer {
    private val sessions = ConcurrentHashMap<UUID, SnapshotViewSession>()

    fun init() {
        Logger.eventNode.addListener(InventoryPreClickEvent::class.java) { event ->
            val session = sessions[event.player.uuid] ?: return@addListener
            if (event.player.openInventory !== session.inventory) {
                sessions.remove(event.player.uuid, session)
                return@addListener
            }
            event.isCancelled = true
            if (event.inventory !== session.inventory) return@addListener

            when (session) {
                is SnapshotListSession -> handleListClick(event.player, event.slot, session)
                is SnapshotDetailSession -> handleDetailClick(event.player, event.slot, session)
            }
        }
        Logger.eventNode.addListener(InventoryCloseEvent::class.java) { event ->
            val session = sessions[event.player.uuid] ?: return@addListener
            if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
        }
        Logger.eventNode.addListener(PlayerDisconnectEvent::class.java) { event ->
            sessions.remove(event.player.uuid)
        }
    }

    fun close() {
        sessions.toMap().forEach { (uuid, session) ->
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)?.let { player ->
                if (player.openInventory === session.inventory) player.closeInventory()
            }
        }
        sessions.clear()
    }

    fun openList(
        viewer: Player,
        targetName: String,
        page: Int = 0,
    ) {
        if (page < 0) return
        viewer.sendMessage(Component.text("[Logger] loading snapshots...", NamedTextColor.GRAY))
        Logger.inventorySnapshot
            .findByPlayerNameAsync(targetName, SNAPSHOTS_PER_PAGE + 1, page * SNAPSHOTS_PER_PAGE)
            .whenComplete { rows, failure ->
                ModuleScheduler.scheduleNextTick {
                    if (!viewer.isOnline) return@scheduleNextTick
                    if (failure != null) {
                        viewer.sendMessage(Component.text("[Logger] failed to load snapshots", NamedTextColor.RED))
                        println("[Logger] snapshot lookup failed: $failure")
                        return@scheduleNextTick
                    }
                    if (rows.isEmpty()) {
                        viewer.sendMessage(
                            Component.text(
                                if (page == 0) "[Logger] no snapshots found for '$targetName'" else "[Logger] no more snapshots",
                                NamedTextColor.GRAY,
                            ),
                        )
                        return@scheduleNextTick
                    }
                    val snapshots = rows.take(SNAPSHOTS_PER_PAGE)
                    val resolvedName = snapshots.first().playerName
                    val inventory =
                        Inventory(
                            InventoryType.CHEST_6_ROW,
                            Component.text("$resolvedName snapshots", NamedTextColor.DARK_GREEN),
                        )
                    snapshots.forEachIndexed { slot, snapshot -> inventory.setItemStack(slot, snapshotIcon(snapshot)) }
                    if (page > 0) inventory.setItemStack(PREVIOUS_SLOT, navigationItem("Previous page"))
                    inventory.setItemStack(
                        INFO_SLOT,
                        ItemStack
                            .of(Material.BOOK)
                            .withCustomName(Component.text("Page ${page + 1}", NamedTextColor.GOLD))
                            .withLore(Component.text("${snapshots.size} snapshots shown", NamedTextColor.GRAY)),
                    )
                    if (rows.size > SNAPSHOTS_PER_PAGE) inventory.setItemStack(NEXT_SLOT, navigationItem("Next page"))
                    if (viewer.openInventory(inventory)) {
                        sessions[viewer.uuid] =
                            SnapshotListSession(
                                inventory = inventory,
                                targetName = resolvedName,
                                page = page,
                                snapshots = snapshots,
                                hasNext = rows.size > SNAPSHOTS_PER_PAGE,
                            )
                    }
                }
            }
    }

    private fun handleListClick(
        viewer: Player,
        slot: Int,
        session: SnapshotListSession,
    ) {
        when {
            slot in session.snapshots.indices -> openDetail(viewer, session.snapshots[slot], session.page)
            slot == PREVIOUS_SLOT && session.page > 0 -> openList(viewer, session.targetName, session.page - 1)
            slot == NEXT_SLOT && session.hasNext -> openList(viewer, session.targetName, session.page + 1)
        }
    }

    private fun openDetail(
        viewer: Player,
        snapshot: InventorySnapshot,
        listPage: Int,
    ) {
        if (snapshot.items.size != net.minestom.server.inventory.PlayerInventory.INVENTORY_SIZE) {
            viewer.sendMessage(Component.text("[Logger] snapshot ${snapshot.id} has an incompatible format", NamedTextColor.RED))
            return
        }
        val inventory =
            Inventory(
                InventoryType.CHEST_6_ROW,
                Component.text("${snapshot.playerName} ${snapshot.action.value}", NamedTextColor.DARK_GREEN),
            )
        inventory.setItemStack(2, snapshot.items[41])
        inventory.setItemStack(3, snapshot.items[42])
        inventory.setItemStack(4, snapshot.items[43])
        inventory.setItemStack(5, snapshot.items[44])
        inventory.setItemStack(7, snapshot.items[45])
        for (slot in 9..35) inventory.setItemStack(slot, snapshot.items[slot])
        for (slot in 0..8) inventory.setItemStack(36 + slot, snapshot.items[slot])
        inventory.setItemStack(BACK_SLOT, navigationItem("Back to snapshots"))
        inventory.setItemStack(INFO_SLOT, snapshotIcon(snapshot))
        inventory.setItemStack(
            RESTORE_SLOT,
            ItemStack
                .of(Material.GREEN_STAINED_GLASS_PANE)
                .withCustomName(Component.text("Restore this snapshot", NamedTextColor.GREEN))
                .withLore(
                    Component.text("Target must be online", NamedTextColor.GRAY),
                    Component.text("Click to replace their survival inventory", NamedTextColor.YELLOW),
                ),
        )
        if (viewer.openInventory(inventory)) {
            sessions[viewer.uuid] =
                SnapshotDetailSession(
                    inventory = inventory,
                    targetName = snapshot.playerName,
                    listPage = listPage,
                    snapshotId = snapshot.id,
                )
        }
    }

    private fun handleDetailClick(
        viewer: Player,
        slot: Int,
        session: SnapshotDetailSession,
    ) {
        when (slot) {
            BACK_SLOT -> openList(viewer, session.targetName, session.listPage)
            RESTORE_SLOT -> restore(viewer, session)
        }
    }

    private fun restore(
        viewer: Player,
        session: SnapshotDetailSession,
    ) {
        if (!viewer.hasPermission("logger.snapshot.rollback")) {
            viewer.sendMessage(Component.text("You don't have permission to restore snapshots", NamedTextColor.RED))
            return
        }
        if (!session.busy.compareAndSet(false, true)) return
        Logger.inventorySnapshot.findByIdAsync(session.snapshotId).whenComplete { snapshot, lookupFailure ->
            ModuleScheduler.scheduleNextTick {
                if (!viewer.isOnline) return@scheduleNextTick
                if (lookupFailure != null || snapshot == null) {
                    session.busy.set(false)
                    viewer.sendMessage(Component.text("[Logger] snapshot no longer exists", NamedTextColor.RED))
                    return@scheduleNextTick
                }
                sessions.remove(viewer.uuid, session)
                viewer.closeInventory()
                viewer.sendMessage(Component.text("[Logger] restoring snapshot...", NamedTextColor.GRAY))
                Logger.rollbackService
                    .restoreSnapshotAsync(RollbackActor(viewer.uuid, viewer.username), snapshot)
                    .whenComplete { result, restoreFailure ->
                        if (restoreFailure != null) {
                            val cause = generateSequence(restoreFailure) { it.cause }.last()
                            viewer.sendMessage(Component.text("[Logger] snapshot restore failed: ${cause.message}", NamedTextColor.RED))
                        } else {
                            viewer.sendMessage(
                                Component.text(
                                    "[Logger] snapshot restored: ${result.appliedCount} slots changed. Use /logger undo to reverse it.",
                                    NamedTextColor.GOLD,
                                ),
                            )
                        }
                    }
            }
        }
    }

    private fun snapshotIcon(snapshot: InventorySnapshot): ItemStack =
        ItemStack
            .of(if (snapshot.action == InventorySnapshotAction.DEATH) Material.REDSTONE else Material.CLOCK)
            .withCustomName(
                Component.text(
                    "${snapshot.action.value.replaceFirstChar(Char::uppercase)} snapshot",
                    if (snapshot.action == InventorySnapshotAction.DEATH) NamedTextColor.RED else NamedTextColor.GREEN,
                ),
            ).withLore(
                Component.text(snapshotTimeFormat.format(Instant.ofEpochMilli(snapshot.timestamp)), NamedTextColor.GRAY),
                Component.text("Player: ${snapshot.playerName}", NamedTextColor.GRAY),
                Component.text("Snapshot #${snapshot.id}", NamedTextColor.DARK_GRAY),
                Component.text("Click to inspect", NamedTextColor.YELLOW),
            )

    private fun navigationItem(name: String): ItemStack =
        ItemStack.of(Material.ARROW).withCustomName(Component.text(name, NamedTextColor.GOLD))
}
