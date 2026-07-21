package net.aechronis.nodes.objects

import net.aechronis.nodes.MinimapIcons
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.PLAYER_ICON_CODEPOINT
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.war.FlagWar
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MAP_RADIUS = 63
private const val ENCODED_CHUNK_MODULUS = 256
private const val MAX_ENCODED_WAYPOINT_DISTANCE = 1_024.0
private val MARKER_FONT = Key.key("aechronis:minimap")

internal data class EncodedMarkerColor(
    val red: Int,
    val green: Int,
    val blue: Int,
)

internal data class MinimapViewerSnapshot(
    val residentTown: Town?,
    val residentNation: Nation?,
    val alliedNations: Set<Nation>,
    val enemyNations: Set<Nation>,
    val warEnabled: Boolean,
    val permanentWaypoints: List<Waypoint>,
    val deathWaypoint: Waypoint?,
) {
    companion object {
        fun capture(resident: Resident): MinimapViewerSnapshot {
            val residentTown = resident.town
            val residentNation = residentTown?.nation
            return MinimapViewerSnapshot(
                residentTown,
                residentNation,
                residentNation?.allies?.toSet().orEmpty(),
                residentNation?.enemies?.toSet().orEmpty(),
                FlagWar.enabled,
                resident.visiblePermanentWaypoints().map(VisibleWaypoint::waypoint),
                resident.deathWaypoint,
            )
        }
    }
}

/** Builds chunk markers and exact waypoint blocks; the shader positions them every frame. */
internal object MinimapMarkerRenderer {
    fun render(
        viewer: MinimapViewerSnapshot,
        centerX: Int,
        centerZ: Int,
        scale: Int,
    ): Component {
        val markers = Component.text()

        fun appendMarker(codepoint: Int, color: EncodedMarkerColor) {
            markers.append(
                Component.text(codepoint.toChar().toString())
                    .font(MARKER_FONT)
                    .color(TextColor.color(color.red, color.green, color.blue)),
            )
        }

        fun appendChunkMarker(
            codepoint: Int,
            chunkX: Int,
            chunkZ: Int,
            territoryBorderMask: Int = 0,
            isHomeTerritory: Boolean = false,
        ) {
            appendMarker(
                codepoint,
                markerColor(chunkX, chunkZ, scale, territoryBorderMask, isHomeTerritory),
            )
        }

        fun appendWaypointMarker(codepoint: Int, waypoint: Waypoint) {
            appendMarker(
                codepoint,
                waypointMarkerColor(waypoint.x, waypoint.z, centerX, centerZ),
            )
        }

        val visibleChunksX = cachedVisibleChunkRange(centerX, scale)
        val visibleChunksZ = cachedVisibleChunkRange(centerZ, scale)
        for (chunkX in visibleChunksX) {
            for (chunkZ in visibleChunksZ) {
                val coord = Coord(chunkX, chunkZ)
                Nodes.territoryChunks[coord]?.let { territoryChunk ->
                    val territory = territoryChunk.territory
                    appendChunkMarker(
                        MinimapIcons.territoryIconCodepoint(territoryRelationship(viewer, territoryChunk)),
                        chunkX,
                        chunkZ,
                        territoryBorderMask(territoryChunk),
                        territory.town?.home == territory.id,
                    )
                    if (territory.core == territoryChunk.coord) {
                        appendChunkMarker(MinimapIcons.coreIconCodepoint(), chunkX, chunkZ)
                    }
                }

                FlagWar.chunkToAttacker[coord]?.let { attack ->
                    appendChunkMarker(
                        MinimapIcons.attackIconCodepoint(relationshipToTown(viewer, attack.town)),
                        chunkX,
                        chunkZ,
                    )
                }

                Building.getForMinimap(coord)?.minimapIconCodepoint?.let { codepoint ->
                    appendChunkMarker(codepoint, chunkX, chunkZ)
                }
            }
        }

        viewer.permanentWaypoints.forEach { waypoint ->
            appendWaypointMarker(MinimapIcons.waypointIconCodepoint(scale, waypoint.sharing), waypoint)
        }
        viewer.deathWaypoint?.let { waypoint ->
            appendWaypointMarker(MinimapIcons.deathWaypointIconCodepoint(scale), waypoint)
        }
        appendChunkMarker(PLAYER_ICON_CODEPOINT, 0, 0)
        return markers.build()
    }

    internal fun territoryRelationship(
        viewer: MinimapViewerSnapshot,
        territoryChunk: TerritoryChunk,
    ): DiplomaticRelationship? {
        val territory = territoryChunk.territory
        val representedTown = if (viewer.warEnabled) {
            territoryChunk.occupier ?: territory.occupier ?: territory.town
        } else {
            territory.occupier ?: territory.town
        }
        return representedTown?.let { relationshipToTown(viewer, it) }
    }

    internal fun relationshipToTown(viewer: MinimapViewerSnapshot, town: Town): DiplomaticRelationship {
        val townNation = town.nation
        return when {
            town === viewer.residentTown -> DiplomaticRelationship.TOWN
            viewer.residentNation != null && townNation === viewer.residentNation -> DiplomaticRelationship.NATION
            townNation != null && townNation in viewer.alliedNations -> DiplomaticRelationship.ALLY
            townNation != null && townNation in viewer.enemyNations -> DiplomaticRelationship.ENEMY
            else -> DiplomaticRelationship.NEUTRAL
        }
    }

    /** Packs scale into bit 2 of the text color's blue byte. */
    internal fun markerMetadata(scale: Int): Int = when (scale) {
        4 -> 0
        12 -> 0x4
        else -> throw IllegalArgumentException("Unsupported minimap scale: $scale")
    }

    /** Red/green carry chunk coordinates; blue packs scale and territory metadata. */
    internal fun markerColor(
        chunkX: Int,
        chunkZ: Int,
        scale: Int,
        territoryBorderMask: Int = 0,
        isHomeTerritory: Boolean = false,
    ): EncodedMarkerColor = EncodedMarkerColor(
        Math.floorMod(chunkX, ENCODED_CHUNK_MODULUS),
        Math.floorMod(chunkZ, ENCODED_CHUNK_MODULUS),
        markerMetadata(scale) or
            (if (isHomeTerritory) 0x8 else 0) or
            ((territoryBorderMask and 0xf) shl 4),
    )

    /** Marks west, east, north, and south edges whose neighbor belongs to another territory. */
    internal fun territoryBorderMask(territoryChunk: TerritoryChunk): Int {
        val coord = territoryChunk.coord
        val territoryId = territoryChunk.territory.id
        var mask = 0
        if (Nodes.territoryChunks[Coord(coord.x - 1, coord.z)]?.territory?.id != territoryId) mask = mask or 1
        if (Nodes.territoryChunks[Coord(coord.x + 1, coord.z)]?.territory?.id != territoryId) mask = mask or 2
        if (Nodes.territoryChunks[Coord(coord.x, coord.z - 1)]?.territory?.id != territoryId) mask = mask or 4
        if (Nodes.territoryChunks[Coord(coord.x, coord.z + 1)]?.territory?.id != territoryId) mask = mask or 8
        return mask
    }

    /** Packs a waypoint's projected chunk and exact in-chunk X/Z into RGB. */
    internal fun waypointMarkerColor(
        blockX: Int,
        blockZ: Int,
        centerX: Int,
        centerZ: Int,
    ): EncodedMarkerColor {
        val deltaX = blockX.toLong() - centerX
        val deltaZ = blockZ.toLong() - centerZ
        val distance = hypot(deltaX.toDouble(), deltaZ.toDouble())
        val ratio = if (distance > MAX_ENCODED_WAYPOINT_DISTANCE) MAX_ENCODED_WAYPOINT_DISTANCE / distance else 1.0
        val markerX = centerX + (deltaX * ratio).roundToInt()
        val markerZ = centerZ + (deltaZ * ratio).roundToInt()
        return EncodedMarkerColor(
            Math.floorMod(Math.floorDiv(markerX, CHUNK_SIZE), ENCODED_CHUNK_MODULUS),
            Math.floorMod(Math.floorDiv(markerZ, CHUNK_SIZE), ENCODED_CHUNK_MODULUS),
            (Math.floorMod(markerX, CHUNK_SIZE) shl 4) or Math.floorMod(markerZ, CHUNK_SIZE),
        )
    }

    /** Union of all chunks that can become visible before the player enters another chunk. */
    internal fun cachedVisibleChunkRange(mapCenter: Int, scale: Int): IntRange {
        markerMetadata(scale)
        val centerChunk = Math.floorDiv(mapCenter, CHUNK_SIZE)
        val firstCenter = centerChunk.toLong() * CHUNK_SIZE
        val lastCenter = firstCenter + CHUNK_SIZE - 1
        val radius = MAP_RADIUS.toLong() * scale
        val firstChunk = Math.floorDiv(firstCenter - radius, CHUNK_SIZE.toLong()).toInt()
        val lastChunk = Math.floorDiv(lastCenter + radius, CHUNK_SIZE.toLong()).toInt()
        return firstChunk..lastChunk
    }
}
