package net.aechronis.nodes.objects

import net.aechronis.nodes.constants.DiplomaticRelationship
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.EntityTeleportPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val WAYPOINT_DISPLAY_Y_OFFSET = 1.5
private const val WAYPOINT_DISPLAY_VIEW_RANGE = 1_000_000f
private const val WAYPOINT_DISPLAY_SCALE_PER_BLOCK = 0.1
private const val MIN_WAYPOINT_DISPLAY_DISTANCE = 0.1
private const val WAYPOINT_DISPLAY_RENDER_DISTANCE_MARGIN = 2.0
private const val WAYPOINT_DISPLAY_BACKGROUND_COLOR = 0x66000000
private const val TEXT_DISPLAY_SHADOW_AND_SEE_THROUGH_FLAGS: Byte = 0x03
private const val FULL_BRIGHT = 0x00F000F0

private data class WaypointDisplayKey(
    val ownerId: UUID,
    val waypointKey: String,
    val isDeath: Boolean,
)

private data class WaypointDisplay(
    val entityId: Int,
    val waypoint: Waypoint,
    val isDeath: Boolean,
    var lastTransform: WaypointDisplayTransform? = null,
)

internal data class WaypointDisplayTransform(
    val position: Vec,
    val distance: Double,
    val waypointDistance: Double,
)

/** Maintains private, packet-only world labels for the waypoints visible to one player. */
internal class WaypointDisplayManager(
    private val resident: Resident,
    private val player: Player,
) {
    private val displays = linkedMapOf<WaypointDisplayKey, WaypointDisplay>()
    private var lastViewerEyePosition: Vec? = null
    private var lastRenderDistance = Double.NaN

    fun refresh() {
        if (!player.isOnline) return

        val desired = buildMap {
            resident.visiblePermanentWaypoints().forEach { visible ->
                val waypoint = visible.waypoint
                put(
                    WaypointDisplayKey(visible.owner.uuid, waypoint.key, false),
                    WaypointDisplay(0, waypoint, false),
                )
            }
            resident.deathWaypoint?.let { waypoint ->
                put(
                    WaypointDisplayKey(resident.uuid, waypoint.key, true),
                    WaypointDisplay(0, waypoint, true),
                )
            }
        }

        val removedEntityIds = displays
            .filter { (key, display) ->
                val replacement = desired[key]
                replacement == null || display.waypoint != replacement.waypoint
            }
            .map { (key, display) ->
                displays.remove(key)
                display.entityId
            }
        if (removedEntityIds.isNotEmpty()) player.sendPacket(DestroyEntitiesPacket(removedEntityIds))

        desired.forEach { (key, display) ->
            if (key in displays) return@forEach
            val spawned = display.copy(entityId = Entity.generateId())
            displays[key] = spawned
            spawn(spawned)
        }
    }

    fun updateTransforms(viewerPosition: Pos) {
        val eyePosition = viewerPosition.eyePosition()
        val renderDistance = renderDistance()
        if (eyePosition == lastViewerEyePosition && renderDistance == lastRenderDistance) return
        lastViewerEyePosition = eyePosition
        lastRenderDistance = renderDistance
        if (!player.isOnline || displays.isEmpty()) return

        displays.values.forEach { display ->
            val transform = display.transform(eyePosition, renderDistance)
            val previous = display.lastTransform
            if (transform.position != previous?.position) {
                player.sendPacket(EntityTeleportPacket(display.entityId, transform.position.asPos(), Vec.ZERO, 0, false))
            }
            if (transform.distance != previous?.distance) player.sendPacket(scaleMetadata(display, transform.distance))
            if (waypointDistanceInBlocks(transform.waypointDistance) != previous?.let { waypointDistanceInBlocks(it.waypointDistance) }) {
                player.sendPacket(labelMetadata(display, transform.waypointDistance))
            }
            display.lastTransform = transform
        }
    }

    fun removeAll() {
        lastViewerEyePosition = null
        lastRenderDistance = Double.NaN
        if (displays.isEmpty()) return
        val entityIds = displays.values.map(WaypointDisplay::entityId)
        displays.clear()
        if (player.isOnline) player.sendPacket(DestroyEntitiesPacket(entityIds))
    }

    private fun spawn(display: WaypointDisplay) {
        val transform = display.transform(player.position.eyePosition(), renderDistance())
        display.lastTransform = transform
        player.sendPackets(
            SpawnEntityPacket(
                display.entityId,
                UUID.randomUUID(),
                EntityType.TEXT_DISPLAY,
                transform.position.asPos(),
                0f,
                0,
                Vec.ZERO,
            ),
            EntityMetaDataPacket(
                display.entityId,
                mapOf(
                    MetadataDef.HAS_NO_GRAVITY.index() to Metadata.Boolean(true),
                    MetadataDef.Display.BILLBOARD_CONSTRAINTS.index() to Metadata.Byte(3),
                    MetadataDef.Display.BRIGHTNESS_OVERRIDE.index() to Metadata.VarInt(FULL_BRIGHT),
                    MetadataDef.Display.VIEW_RANGE.index() to Metadata.Float(WAYPOINT_DISPLAY_VIEW_RANGE),
                    MetadataDef.Display.SCALE.index() to Metadata.Vector3(display.scale(transform.distance)),
                    MetadataDef.TextDisplay.TEXT.index() to Metadata.Component(
                        waypointDisplayLabel(display.waypoint, display.isDeath, transform.waypointDistance),
                    ),
                    MetadataDef.TextDisplay.BACKGROUND_COLOR.index() to Metadata.VarInt(WAYPOINT_DISPLAY_BACKGROUND_COLOR),
                    MetadataDef.TextDisplay.TEXT_DISPLAY_FLAGS.index() to Metadata.Byte(TEXT_DISPLAY_SHADOW_AND_SEE_THROUGH_FLAGS),
                ),
            ),
        )
    }

    private fun scaleMetadata(display: WaypointDisplay, distance: Double): EntityMetaDataPacket = EntityMetaDataPacket(
        display.entityId,
        mapOf(MetadataDef.Display.SCALE.index() to Metadata.Vector3(display.scale(distance))),
    )

    private fun labelMetadata(display: WaypointDisplay, distance: Double): EntityMetaDataPacket = EntityMetaDataPacket(
        display.entityId,
        mapOf(
            MetadataDef.TextDisplay.TEXT.index() to Metadata.Component(
                waypointDisplayLabel(display.waypoint, display.isDeath, distance),
            ),
        ),
    )

    private fun WaypointDisplay.transform(eyePosition: Vec, renderDistance: Double): WaypointDisplayTransform = projectWaypointDisplay(
        eyePosition,
        Vec(waypoint.x + 0.5, waypoint.y + WAYPOINT_DISPLAY_Y_OFFSET, waypoint.z + 0.5),
        renderDistance,
    )

    private fun WaypointDisplay.scale(distance: Double): Vec {
        val scale = waypointDisplayScale(distance)
        return Vec(scale, scale, scale)
    }

    private fun Pos.eyePosition(): Vec = Vec(x(), y() + player.eyeHeight, z())

    private fun renderDistance(): Double = (player.effectiveViewDistance() - 1).coerceAtLeast(1) * 16.0 - WAYPOINT_DISPLAY_RENDER_DISTANCE_MARGIN
}

internal fun waypointDisplayLabel(waypoint: Waypoint, isDeath: Boolean, distance: Double): Component {
    val title = Component.text(
        if (isDeath) "☠ Latest Death" else waypoint.name,
        waypointDisplayColor(waypoint, isDeath),
    )
    return title
        .append(Component.newline())
        .append(Component.text("${waypointDistanceInBlocks(distance)}m", NamedTextColor.GRAY))
}

internal fun waypointDisplayColor(waypoint: Waypoint, isDeath: Boolean): NamedTextColor = when {
    isDeath -> NamedTextColor.RED
    else -> waypointSharingColor(waypoint.sharing)
}

internal fun waypointSharingColor(sharing: WaypointSharing): NamedTextColor = when (sharing) {
    WaypointSharing.PRIVATE -> DiplomaticRelationship.NEUTRAL.textColor
    WaypointSharing.TOWN -> DiplomaticRelationship.TOWN.textColor
    WaypointSharing.NATION -> DiplomaticRelationship.NATION.textColor
    WaypointSharing.ALLY -> DiplomaticRelationship.ALLY.textColor
}

internal fun waypointDistanceInBlocks(distance: Double): Int = distance.roundToInt().coerceAtLeast(0)

internal fun projectWaypointDisplay(viewer: Vec, waypoint: Vec, renderDistance: Double): WaypointDisplayTransform {
    val dx = waypoint.x() - viewer.x()
    val dy = waypoint.y() - viewer.y()
    val dz = waypoint.z() - viewer.z()
    val distance = sqrt(dx * dx + dy * dy + dz * dz)
    if (distance <= renderDistance || distance == 0.0) return WaypointDisplayTransform(waypoint, distance, distance)

    val ratio = renderDistance / distance
    return WaypointDisplayTransform(
        Vec(viewer.x() + dx * ratio, viewer.y() + dy * ratio, viewer.z() + dz * ratio),
        renderDistance,
        distance,
    )
}

internal fun waypointDisplayScale(distance: Double): Double = distance.coerceAtLeast(MIN_WAYPOINT_DISPLAY_DISTANCE) * WAYPOINT_DISPLAY_SCALE_PER_BLOCK
