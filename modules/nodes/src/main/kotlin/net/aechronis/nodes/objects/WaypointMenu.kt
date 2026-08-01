package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
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
import net.minestom.server.dialog.DialogBody
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

private const val WAYPOINTS_PER_PAGE = 45
private const val PREVIOUS_PAGE_SLOT = 45
private const val PERSONAL_CATEGORY_SLOT = 46
private const val TOWN_CATEGORY_SLOT = 47
private const val NATION_CATEGORY_SLOT = 48
private const val CREATE_WAYPOINT_SLOT = 49
private const val ALLY_CATEGORY_SLOT = 50
private const val DEATH_WAYPOINT_SLOT = 51
private const val NEXT_PAGE_SLOT = 53
private const val DEFAULT_WAYPOINT_NAME = "Waypoint"
private const val NAME_INPUT_KEY = "name"
private const val X_INPUT_KEY = "x"
private const val Y_INPUT_KEY = "y"
private const val Z_INPUT_KEY = "z"
private const val SHARING_INPUT_KEY = "sharing"
private const val SESSION_TOKEN_KEY = "session"
private const val MAX_COORDINATE_LENGTH = 11
private val CREATE_WAYPOINT_ACTION = Key.key("nodes", "create_waypoint")
private val CANCEL_WAYPOINT_ACTION = Key.key("nodes", "cancel_waypoint")

private sealed interface WaypointMenuSession

private data class CreateWaypointSession(
    val resident: Resident,
    val token: String = UUID.randomUUID().toString(),
    val initialName: String = DEFAULT_WAYPOINT_NAME,
    val initialX: String,
    val initialY: String,
    val initialZ: String,
    val initialSharing: String = WaypointSharing.PRIVATE.id,
) : WaypointMenuSession

private data class BrowseWaypointSession(
    val inventory: Inventory,
    val resident: Resident,
    val category: WaypointSharing,
    val page: Int,
    val waypoints: List<VisibleWaypoint>,
    val hasNextPage: Boolean,
) : WaypointMenuSession

private data class WaypointSubmission(
    val x: Int,
    val y: Int,
    val z: Int,
    val sharing: WaypointSharing,
)

internal fun waypointCreationDialog(
    token: String,
    initialName: String = DEFAULT_WAYPOINT_NAME,
    initialX: String,
    initialY: String,
    initialZ: String,
    sharingOptions: List<WaypointSharing> = emptyList(),
    initialSharing: String = WaypointSharing.PRIVATE.id,
    error: String? = null,
): Dialog.Confirmation {
    val body = buildList<DialogBody> {
        error?.let { message ->
            add(
                DialogBody.PlainMessage(
                    Component.text(message, NamedTextColor.RED),
                    260,
                ),
            )
        }
    }
    val tokenPayload = CompoundBinaryTag.builder().putString(SESSION_TOKEN_KEY, token).build()
    val inputs = buildList<DialogInput> {
        add(
            DialogInput.Text(
                NAME_INPUT_KEY,
                260,
                Component.text("Name"),
                true,
                initialName,
                Waypoint.MAX_NAME_LENGTH,
                null,
            ),
        )
        add(coordinateInput(X_INPUT_KEY, "Coordinates", initialX))
        add(coordinateInput(Y_INPUT_KEY, null, initialY))
        add(coordinateInput(Z_INPUT_KEY, null, initialZ))
        if (sharingOptions.size > 1) add(sharingInput(sharingOptions, initialSharing))
    }
    val metadata = DialogMetadata(
        Component.text("Create waypoint", NamedTextColor.DARK_AQUA),
        null,
        true,
        false,
        DialogAfterAction.CLOSE,
        body,
        inputs,
    )
    return Dialog.Confirmation(
        metadata,
        DialogActionButton(
            Component.text("Create", NamedTextColor.GREEN),
            Component.text("Save this location permanently", NamedTextColor.GRAY),
            120,
            DialogAction.DynamicCustom(CREATE_WAYPOINT_ACTION, tokenPayload),
        ),
        DialogActionButton(
            Component.text("Cancel", NamedTextColor.RED),
            null,
            120,
            DialogAction.DynamicCustom(CANCEL_WAYPOINT_ACTION, tokenPayload),
        ),
    )
}

private fun coordinateInput(key: String, label: String?, initial: String): DialogInput.Text = DialogInput.Text(
    key,
    260,
    Component.text(label ?: ""),
    label != null,
    initial,
    MAX_COORDINATE_LENGTH,
    null,
)

private fun sharingInput(options: List<WaypointSharing>, initial: String): DialogInput.SingleOption {
    val selected = initial.takeIf { id -> options.any { sharing -> sharing.id == id } } ?: WaypointSharing.PRIVATE.id
    return DialogInput.SingleOption(
        SHARING_INPUT_KEY,
        260,
        options.map { sharing ->
            DialogInput.SingleOption.Option(
                sharing.id,
                Component.text(
                    when (sharing) {
                        WaypointSharing.PRIVATE -> "Private"
                        WaypointSharing.TOWN -> "Town members"
                        WaypointSharing.NATION -> "Nation members"
                        WaypointSharing.ALLY -> "Nation and allies"
                    },
                ),
                sharing.id == selected,
            )
        },
        Component.text("Share waypoint", NamedTextColor.AQUA),
        true,
    )
}

internal fun parseWaypointCoordinate(axis: String, input: String): Int = input.trim().toIntOrNull() ?: throw IllegalArgumentException("$axis coordinate must be a whole number")

internal fun parseWaypointSharing(input: String): WaypointSharing = if (input.isBlank()) WaypointSharing.PRIVATE else WaypointSharing.fromId(input)

/** Native waypoint creation dialog and server-side waypoint browser. */
object WaypointMenu {
    private val initialized = AtomicBoolean()
    private val sessions = ConcurrentHashMap<UUID, WaypointMenuSession>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Nodes.eventNode.addListener(PlayerCustomClickEvent::class.java, this::onCustomClick)
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onInventoryClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onInventoryClose)
    }

    fun openCreate(player: Player, resident: Resident) {
        if (player.instance == null) {
            Message.error(player, "You must be in a world to create a waypoint")
            return
        }

        if (player.openInventory != null) player.closeInventory()
        val position = player.position
        val session = CreateWaypointSession(
            resident,
            initialX = position.blockX().toString(),
            initialY = position.blockY().toString(),
            initialZ = position.blockZ().toString(),
        )
        sessions[player.uuid] = session
        showCreateDialog(player, session)
    }

    fun openBrowse(
        player: Player,
        resident: Resident,
        requestedPage: Int = 0,
        category: WaypointSharing = WaypointSharing.PRIVATE,
    ) {
        val waypoints = resident.availablePermanentWaypoints().filter { visible -> visible.waypoint.sharing == category }
        val lastPage = max(0, (waypoints.size - 1) / WAYPOINTS_PER_PAGE)
        val page = requestedPage.coerceIn(0, lastPage)
        val inventory = Inventory(
            InventoryType.CHEST_6_ROW,
            Component.text("${waypointCategoryName(category)} Waypoints ${page + 1}/${lastPage + 1}", NamedTextColor.DARK_AQUA),
        )
        val categoryWaypoints = waypoints.drop(page * WAYPOINTS_PER_PAGE).take(WAYPOINTS_PER_PAGE)
        categoryWaypoints.forEachIndexed { slot, visible ->
            inventory.setItemStack(slot, permanentWaypointItem(visible, resident))
        }
        if (page > 0) inventory.setItemStack(PREVIOUS_PAGE_SLOT, namedItem(Material.ARROW, "Previous page", NamedTextColor.AQUA))
        inventory.setItemStack(PERSONAL_CATEGORY_SLOT, categoryItem(WaypointSharing.PRIVATE, resident, category))
        inventory.setItemStack(TOWN_CATEGORY_SLOT, categoryItem(WaypointSharing.TOWN, resident, category))
        inventory.setItemStack(NATION_CATEGORY_SLOT, categoryItem(WaypointSharing.NATION, resident, category))
        inventory.setItemStack(
            CREATE_WAYPOINT_SLOT,
            namedItem(Material.EMERALD, "Create waypoint", NamedTextColor.GREEN)
                .withLore(Component.text("Saves your current location permanently", NamedTextColor.GRAY)),
        )
        inventory.setItemStack(ALLY_CATEGORY_SLOT, categoryItem(WaypointSharing.ALLY, resident, category))
        resident.deathWaypoint?.let { death ->
            inventory.setItemStack(DEATH_WAYPOINT_SLOT, deathWaypointItem(death))
        }
        if (page < lastPage) inventory.setItemStack(NEXT_PAGE_SLOT, namedItem(Material.ARROW, "Next page", NamedTextColor.AQUA))

        val session = BrowseWaypointSession(inventory, resident, category, page, categoryWaypoints, page < lastPage)
        sessions[player.uuid] = session
        if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
    }

    fun close(player: Player) {
        sessions.remove(player.uuid)
    }

    internal fun closeBrowse(player: Player, resident: Resident) {
        val session = sessions[player.uuid] as? BrowseWaypointSession ?: return
        if (session.resident !== resident || !sessions.remove(player.uuid, session)) return
        if (player.isOnline && player.openInventory === session.inventory) player.closeInventory()
    }

    private fun onCustomClick(event: PlayerCustomClickEvent) {
        if (event.key != CREATE_WAYPOINT_ACTION && event.key != CANCEL_WAYPOINT_ACTION) return
        val player = event.player
        val session = sessions[player.uuid] as? CreateWaypointSession ?: return
        val payload = event.payload as? CompoundBinaryTag ?: return
        if (payload.getString(SESSION_TOKEN_KEY) != session.token) return
        if (!sessions.remove(player.uuid, session)) return
        if (event.key == CANCEL_WAYPOINT_ACTION) return
        if (Resident.fromPlayer(player) !== session.resident) return

        val inputName = payload.getString(NAME_INPUT_KEY)
        val inputX = payload.getString(X_INPUT_KEY)
        val inputY = payload.getString(Y_INPUT_KEY)
        val inputZ = payload.getString(Z_INPUT_KEY)
        val inputSharing = payload.getString(SHARING_INPUT_KEY)
        val submission = runCatching {
            WaypointSubmission(
                parseWaypointCoordinate("X", inputX),
                parseWaypointCoordinate("Y", inputY),
                parseWaypointCoordinate("Z", inputZ),
                parseWaypointSharing(inputSharing),
            )
        }
        submission.onSuccess { (x, y, z, sharing) ->
            session.resident.createPermanentWaypoint(
                inputName,
                x,
                y,
                z,
                sharing,
            ).onSuccess { waypoint ->
                Message.print(player, "Created waypoint ${waypoint.name} at ${waypoint.x}, ${waypoint.y}, ${waypoint.z}")
            }.onFailure { failure ->
                retryCreateDialog(player, session, inputName, inputX, inputY, inputZ, inputSharing, failure.message)
            }
        }.onFailure { failure ->
            retryCreateDialog(player, session, inputName, inputX, inputY, inputZ, inputSharing, failure.message)
        }
    }

    private fun retryCreateDialog(
        player: Player,
        session: CreateWaypointSession,
        inputName: String,
        inputX: String,
        inputY: String,
        inputZ: String,
        inputSharing: String,
        error: String?,
    ) {
        val message = error ?: "Could not create waypoint"
        Message.error(player, message)
        val retry = session.copy(
            token = UUID.randomUUID().toString(),
            initialName = inputName,
            initialX = inputX,
            initialY = inputY,
            initialZ = inputZ,
            initialSharing = inputSharing,
        )
        sessions[player.uuid] = retry
        showCreateDialog(player, retry, message)
    }

    private fun showCreateDialog(player: Player, session: CreateWaypointSession, error: String? = null) {
        player.showDialog(
            waypointCreationDialog(
                session.token,
                session.initialName,
                session.initialX,
                session.initialY,
                session.initialZ,
                session.resident.availableWaypointSharing().toList(),
                session.initialSharing,
                error,
            ),
        )
    }

    private fun onInventoryClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] as? BrowseWaypointSession ?: return
        if (player.openInventory !== session.inventory) return
        event.isCancelled = true
        if (event.inventory !== session.inventory) return
        handleBrowseClick(player, session, event)
    }

    private fun handleBrowseClick(player: Player, session: BrowseWaypointSession, event: InventoryPreClickEvent) {
        when (event.slot) {
            PREVIOUS_PAGE_SLOT -> if (session.page > 0) openBrowseNextTick(player, session.resident, session.category, session.page - 1)
            PERSONAL_CATEGORY_SLOT -> openBrowseNextTick(player, session.resident, WaypointSharing.PRIVATE)
            TOWN_CATEGORY_SLOT -> openBrowseNextTick(player, session.resident, WaypointSharing.TOWN)
            NATION_CATEGORY_SLOT -> openBrowseNextTick(player, session.resident, WaypointSharing.NATION)
            CREATE_WAYPOINT_SLOT -> MinecraftServer.getSchedulerManager().scheduleNextTick {
                if (player.isOnline) openCreate(player, session.resident)
            }
            ALLY_CATEGORY_SLOT -> openBrowseNextTick(player, session.resident, WaypointSharing.ALLY)
            NEXT_PAGE_SLOT -> if (session.hasNextPage) openBrowseNextTick(player, session.resident, session.category, session.page + 1)
            in 0 until session.waypoints.size -> {
                val visible = session.waypoints[event.slot]
                if (visible !in session.resident.availablePermanentWaypoints()) {
                    closeBrowse(player, session.resident)
                    return
                }
                val waypoint = visible.waypoint
                val rightClick = event.click is Click.Right || event.click is Click.RightShift
                val leftClick = event.click is Click.Left || event.click is Click.LeftShift
                if (rightClick && session.resident.canRemovePermanentWaypoint(visible)) {
                    if (session.resident.removePermanentWaypoint(visible)) {
                        Message.print(player, "Removed waypoint ${waypoint.name}")
                        openBrowseNextTick(player, session.resident, session.category, session.page)
                    }
                } else if (leftClick) {
                    val isVisible = session.resident.toggleWaypointVisibility(visible)
                    val state = if (isVisible) "shown" else "hidden"
                    Message.print(player, "${waypoint.name} is now $state")
                    openBrowseNextTick(player, session.resident, session.category, session.page)
                } else {
                    val owner = if (visible.owner === session.resident) "" else " (shared by ${visible.owner.name})"
                    Message.print(player, "${waypoint.name}: ${waypoint.x}, ${waypoint.y}, ${waypoint.z}$owner")
                }
            }
        }
    }

    private fun openBrowseNextTick(
        player: Player,
        resident: Resident,
        category: WaypointSharing,
        page: Int = 0,
    ) {
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) openBrowse(player, resident, page, category)
        }
    }

    private fun onInventoryClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] as? BrowseWaypointSession ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
    }

    private fun permanentWaypointItem(visible: VisibleWaypoint, viewer: Resident): ItemStack {
        val waypoint = visible.waypoint
        val owned = visible.owner === viewer
        val canRemove = viewer.canRemovePermanentWaypoint(visible)
        val isVisible = viewer.isWaypointVisible(visible)
        val scopeColor = waypointSharingColor(waypoint.sharing)
        val lore = buildList {
            add(Component.text("${waypoint.x}, ${waypoint.y}, ${waypoint.z}", NamedTextColor.GRAY))
            if (!owned) add(Component.text("Shared by ${visible.owner.name}", scopeColor))
            when (waypoint.sharing) {
                WaypointSharing.PRIVATE -> Unit
                WaypointSharing.TOWN -> add(
                    Component.text(
                        if (owned) "Shared with town members" else "Town waypoint",
                        scopeColor,
                    ),
                )
                WaypointSharing.NATION -> add(
                    Component.text(
                        if (owned) "Shared with nation members" else "Nation waypoint",
                        scopeColor,
                    ),
                )
                WaypointSharing.ALLY -> add(
                    Component.text(
                        if (owned) "Shared with nation and allies" else "Ally waypoint",
                        scopeColor,
                    ),
                )
            }
            add(Component.text(if (isVisible) "Shown" else "Hidden", if (isVisible) NamedTextColor.GREEN else NamedTextColor.GRAY))
            add(Component.text(if (isVisible) "Left-click to hide" else "Left-click to show", NamedTextColor.DARK_GRAY))
            if (canRemove) add(Component.text("Right-click to remove", NamedTextColor.DARK_GRAY))
        }
        return namedItem(
            Material.COMPASS,
            waypoint.name,
            if (isVisible) scopeColor else NamedTextColor.GRAY,
        ).withLore(lore)
    }

    private fun categoryItem(
        category: WaypointSharing,
        resident: Resident,
        selectedCategory: WaypointSharing,
    ): ItemStack {
        val count = resident.availablePermanentWaypoints().count { visible -> visible.waypoint.sharing == category }
        val selected = category == selectedCategory
        val material = when (category) {
            WaypointSharing.PRIVATE -> Material.YELLOW_DYE
            WaypointSharing.TOWN -> Material.LIME_DYE
            WaypointSharing.NATION -> Material.GREEN_DYE
            WaypointSharing.ALLY -> Material.CYAN_DYE
        }
        return namedItem(
            material,
            "${waypointCategoryName(category)} waypoints ($count)",
            if (selected) NamedTextColor.WHITE else NamedTextColor.GRAY,
        ).withLore(Component.text(if (selected) "Selected" else "Click to open", NamedTextColor.DARK_GRAY))
    }

    private fun waypointCategoryName(sharing: WaypointSharing): String = when (sharing) {
        WaypointSharing.PRIVATE -> "Personal"
        WaypointSharing.TOWN -> "Town"
        WaypointSharing.NATION -> "Nation"
        WaypointSharing.ALLY -> "Ally"
    }

    private fun deathWaypointItem(waypoint: Waypoint): ItemStack = namedItem(
        Material.RECOVERY_COMPASS,
        waypoint.name,
        NamedTextColor.RED,
    ).withLore(
        Component.text("${waypoint.x}, ${waypoint.y}, ${waypoint.z}", NamedTextColor.GRAY),
        Component.text("Locked until your next death or logout", NamedTextColor.DARK_GRAY),
    )

    private fun namedItem(material: Material, name: String, color: NamedTextColor): ItemStack = ItemStack.of(material).with(DataComponents.CUSTOM_NAME, Component.text(name, color))
}
