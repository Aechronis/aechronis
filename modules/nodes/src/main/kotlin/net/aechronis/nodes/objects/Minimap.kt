package net.aechronis.nodes.objects

import net.aechronis.nodes.Nodes
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SetPassengersPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val DEFAULT_MINIMAP_SCALE = 4
private const val SNEAKING_MINIMAP_SCALE = 12
private const val YAW_BUCKET_COUNT = 252
private const val YAW_OPACITY_OFFSET = 4

internal object MinimapYawCodec {
    fun index(yawDegrees: Float): Int {
        if (!yawDegrees.isFinite()) return 0
        val normalized = ((yawDegrees % 360f) + 360f) % 360f
        return (normalized * YAW_BUCKET_COUNT / 360f).roundToInt().coerceIn(0, YAW_BUCKET_COUNT - 1)
    }

    fun opacity(index: Int): Byte = (index.coerceIn(0, YAW_BUCKET_COUNT - 1) + YAW_OPACITY_OFFSET).toByte()
}

internal fun augmentPassengerIds(
    vehicleEntityId: Int,
    passengerIds: List<Int>,
    trackedVehicleEntityId: Int,
    virtualPassengerIds: List<Int>,
): List<Int>? {
    if (vehicleEntityId != trackedVehicleEntityId) return null
    val merged = buildList {
        addAll(passengerIds)
        virtualPassengerIds.filterTo(this) { it !in passengerIds }
    }
    return merged.takeUnless { it == passengerIds }
}

private data class TrackedMinimapPassengers(
    val vehicleEntityId: Int,
    val virtualPassengerIds: List<Int>,
)

/** Keeps packet-only minimap passengers attached when Minestom updates real passengers. */
internal object MinimapPassengerTracker {
    private val initialized = AtomicBoolean()
    private val tracked = ConcurrentHashMap<UUID, TrackedMinimapPassengers>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Nodes.eventNode.addListener(PlayerPacketOutEvent::class.java, this::onPacketOut)
    }

    fun register(player: Player, virtualPassengerIds: List<Int>) {
        tracked[player.uuid] = TrackedMinimapPassengers(player.entityId, virtualPassengerIds.toList())
    }

    fun unregister(player: Player, virtualPassengerIds: List<Int>) {
        tracked.remove(player.uuid, TrackedMinimapPassengers(player.entityId, virtualPassengerIds))
    }

    private fun onPacketOut(event: PlayerPacketOutEvent) {
        val packet = event.packet as? SetPassengersPacket ?: return
        val entry = tracked[event.player.uuid] ?: return
        if (augmentPassengerIds(packet.vehicleEntityId, packet.passengersId, entry.vehicleEntityId, entry.virtualPassengerIds) == null) return

        event.isCancelled = true
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            val player = event.player
            if (!player.isOnline) return@scheduleNextTick
            val actualPassengerIds = player.passengers.map { it.entityId }
            val current = tracked[player.uuid]
            val passengerIds =
                if (current?.vehicleEntityId == player.entityId) {
                    augmentPassengerIds(player.entityId, actualPassengerIds, current.vehicleEntityId, current.virtualPassengerIds)
                        ?: actualPassengerIds
                } else {
                    actualPassengerIds
                }
            player.sendPacket(SetPassengersPacket(player.entityId, passengerIds))
        }
    }
}

private data class PreparedMinimapRender(
    val markers: Component,
    val markerHash: Int,
)

/**
 * Per-player HUD minimap rendered by a packet-only text display and the bundled core shader.
 * No entity is added to the player's instance.
 */
class Minimap(
    val resident: Resident,
    val player: Player,
) {
    var scale: Int = scaleForPlayer()
        private set

    private val markerEntityId = Entity.generateId()
    private val virtualPassengerEntityIds = listOf(markerEntityId)
    private val waypointDisplays = WaypointDisplayManager(resident, player)

    private var task: Task? = null
    private var destroyed = false
    private var entitiesSpawned = false
    private var renderGeneration = 0L
    private var lastYawIndex = MinimapYawCodec.index(player.position.yaw)
    private var lastMarkerHash: Int? = null
    private var lastRasterX = Int.MIN_VALUE
    private var lastRasterZ = Int.MIN_VALUE
    private var lastInstanceId: UUID? = null

    init {
        spawnEntities()
        renderCurrent(force = true)
        task = MinecraftServer.getSchedulerManager()
            .buildTask {
                if (destroyed) return@buildTask
                if (!player.isOnline) {
                    destroy()
                    return@buildTask
                }
                val newScale = scaleForPlayer()
                if (scale != newScale) {
                    scale = newScale
                    refresh()
                }
                waypointDisplays.updateTransforms(player.position)
            }
            .repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun render(centerX: Int, centerZ: Int, force: Boolean = false) {
        if (destroyed || !player.isOnline) return
        val instance = player.instance ?: return
        if (force) lastMarkerHash = null
        val renderScale = scale
        val rasterX = Math.floorDiv(centerX, CHUNK_SIZE)
        val rasterZ = Math.floorDiv(centerZ, CHUNK_SIZE)
        if (!force &&
            rasterX == lastRasterX &&
            rasterZ == lastRasterZ &&
            instance.uuid == lastInstanceId
        ) {
            return
        }

        waypointDisplays.refresh()
        val viewerSnapshot = MinimapViewerSnapshot.capture(resident)
        val requestGeneration = ++renderGeneration

        lastRasterX = rasterX
        lastRasterZ = rasterZ
        lastInstanceId = instance.uuid

        CompletableFuture
            .supplyAsync {
                val markers = MinimapMarkerRenderer.render(viewerSnapshot, centerX, centerZ, renderScale)
                PreparedMinimapRender(markers, markers.hashCode())
            }
            .whenComplete { prepared, throwable ->
                MinecraftServer.getSchedulerManager().scheduleNextTick {
                    if (throwable != null) {
                        MinecraftServer.getExceptionManager().handleException(throwable)
                        if (!destroyed && requestGeneration == renderGeneration) invalidateRasterPosition()
                        return@scheduleNextTick
                    }
                    if (destroyed || !player.isOnline || requestGeneration != renderGeneration) return@scheduleNextTick
                    applyRender(prepared)
                }
            }
    }

    fun renderCurrent(force: Boolean = false) {
        val position = player.position
        render(position.blockX(), position.blockZ(), force)
    }

    fun updateWaypointDisplayTransforms(position: Pos) {
        if (destroyed) return
        waypointDisplays.updateTransforms(position)
    }

    fun updateYaw(yawDegrees: Float) {
        if (destroyed || !entitiesSpawned || !player.isOnline) return
        val nextYawIndex = MinimapYawCodec.index(yawDegrees)
        if (nextYawIndex == lastYawIndex) return
        lastYawIndex = nextYawIndex
        player.sendPacket(
            EntityMetaDataPacket(
                markerEntityId,
                mapOf(
                    MetadataDef.TextDisplay.TEXT_OPACITY.index() to Metadata.Byte(MinimapYawCodec.opacity(lastYawIndex)),
                ),
            ),
        )
    }

    /** Recreates virtual entities after a respawn or instance switch. */
    fun respawn() {
        if (destroyed) return
        removeEntities()
        spawnEntities()
        invalidateRasterPosition()
        renderCurrent(force = true)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        renderGeneration++
        task?.cancel()
        task = null
        removeEntities()
    }

    fun refresh() {
        invalidateRasterPosition()
        renderCurrent()
    }

    private fun invalidateRasterPosition() {
        lastRasterX = Int.MIN_VALUE
        lastRasterZ = Int.MIN_VALUE
    }

    private fun applyRender(prepared: PreparedMinimapRender) {
        if (prepared.markerHash == lastMarkerHash) return
        lastMarkerHash = prepared.markerHash
        player.sendPacket(markerMetadata(prepared.markers))
    }

    private fun spawnEntities() {
        if (destroyed || entitiesSpawned || !player.isOnline) return
        player.sendPackets(
            SpawnEntityPacket(
                markerEntityId,
                UUID.randomUUID(),
                EntityType.TEXT_DISPLAY,
                player.position,
                0f,
                0,
                Vec.ZERO,
            ),
            markerMetadata(Component.empty()),
        )
        MinimapPassengerTracker.register(player, virtualPassengerEntityIds)
        val passengers = player.passengers.map { it.entityId } + virtualPassengerEntityIds
        player.sendPacket(SetPassengersPacket(player.entityId, passengers))
        entitiesSpawned = true
    }

    private fun removeEntities() {
        waypointDisplays.removeAll()
        if (!entitiesSpawned) return
        MinimapPassengerTracker.unregister(player, virtualPassengerEntityIds)
        if (player.isOnline) {
            player.sendPacket(DestroyEntitiesPacket(virtualPassengerEntityIds))
            player.sendPacket(SetPassengersPacket(player.entityId, player.passengers.map { it.entityId }))
        }
        entitiesSpawned = false
    }

    private fun markerMetadata(markers: Component): EntityMetaDataPacket = EntityMetaDataPacket(
        markerEntityId,
        mapOf(
            MetadataDef.Display.BILLBOARD_CONSTRAINTS.index() to Metadata.Byte(3),
            MetadataDef.TextDisplay.TEXT.index() to Metadata.Component(markers),
            MetadataDef.TextDisplay.BACKGROUND_COLOR.index() to Metadata.VarInt(0x000000FF),
            MetadataDef.TextDisplay.TEXT_OPACITY.index() to Metadata.Byte(MinimapYawCodec.opacity(lastYawIndex)),
            MetadataDef.TextDisplay.TEXT_DISPLAY_FLAGS.index() to Metadata.Byte(0),
        ),
    )

    private fun scaleForPlayer(): Int = if (player.isSneaking) SNEAKING_MINIMAP_SCALE else DEFAULT_MINIMAP_SCALE
}
