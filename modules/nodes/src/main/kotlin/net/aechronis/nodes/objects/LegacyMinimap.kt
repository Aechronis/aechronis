package net.aechronis.nodes.objects

import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.war.FlagWar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.scoreboard.Sidebar

/** What a resident's minimap needs from either display style. */
interface MinimapView {
    fun render(centerX: Int, centerZ: Int, force: Boolean = false)
    fun refresh()
    fun respawn()
    fun updateSettings()
    fun destroy()
    fun updateWaypointDisplayTransforms(position: Pos) {}
}

/** How a resident's minimap is shown. */
enum class MinimapMode(val id: String) {
    OFF("off"),
    MODERN("modern"),
    LEGACY("legacy"),
}

private const val LEGACY_RADIUS = 7

private const val SHADE_LIGHT = "▒"
private const val SHADE_DARK = "▓"
private const val SOLID = "█"
private const val CORE = "╫"
private const val OCCUPIED = "╞"
private const val ATTACK = "╬"
private const val BUILDING = "■"
private const val WAYPOINT = "◆"
private const val DEATH_WAYPOINT = "✖"
private const val PLAYER = "┼"

/**
 * Familiar sidebar territory map: an 11x11 grid of colored glyphs, one per chunk, north up.
 * Needs no resource pack; shows the same relationship colors, homes, cores, occupations,
 * attacks, buildings and waypoints as the modern HUD minimap.
 */
internal object LegacyMinimapRenderer {
    fun render(viewer: MinimapViewerSnapshot, centerChunkX: Int, centerChunkZ: Int): List<Component> {
        val waypointChunks = viewer.permanentWaypoints.mapTo(HashSet()) { Coord.fromBlockCoords(it.x, it.z) }
        val deathChunk = viewer.deathWaypoint?.let { Coord.fromBlockCoords(it.x, it.z) }
        // North is -Z, so the top row is the most negative z.
        return (-LEGACY_RADIUS..LEGACY_RADIUS).map { dz ->
            val row = Component.text()
            for (dx in -LEGACY_RADIUS..LEGACY_RADIUS) {
                val coord = Coord(centerChunkX + dx, centerChunkZ + dz)
                val isPlayer = dx == 0 && dz == 0
                row.append(glyph(viewer, coord, isPlayer, coord in waypointChunks, coord == deathChunk))
            }
            row.build()
        }
    }

    private fun glyph(viewer: MinimapViewerSnapshot, coord: Coord, isPlayer: Boolean, waypoint: Boolean, death: Boolean): Component {
        val chunk = TerritoryChunk.fromCoord(coord)
        if (isPlayer) return Component.text(PLAYER, NamedTextColor.WHITE, TextDecoration.BOLD)
        val attack = FlagWar.chunkToAttacker[coord]
        if (attack != null) {
            return Component.text(ATTACK, relationshipColor(MinimapMarkerRenderer.relationshipToTown(viewer, attack.town), false))
        }
        if (death) return Component.text(DEATH_WAYPOINT, NamedTextColor.RED)
        if (waypoint) return Component.text(WAYPOINT, NamedTextColor.LIGHT_PURPLE)
        if (chunk == null) return Component.text(SHADE_LIGHT, NamedTextColor.DARK_GRAY)

        val territory = chunk.territory
        val relationship = MinimapMarkerRenderer.territoryRelationship(viewer, chunk)
        val dark = territory.color % 2 == 1
        val color = relationshipColor(relationship, dark)
        return when {
            Building.getForMinimap(coord) != null -> Component.text(BUILDING, NamedTextColor.GOLD)
            territory.core == coord -> Component.text(CORE, color)
            viewer.warEnabled && chunk.occupier != null -> Component.text(OCCUPIED, color)
            territory.town?.home == territory.id -> Component.text(SOLID, color)
            else -> Component.text(if (dark) SHADE_DARK else SHADE_LIGHT, color)
        }
    }

    private fun relationshipColor(relationship: DiplomaticRelationship?, dark: Boolean): TextColor = when (relationship) {
        DiplomaticRelationship.TOWN -> if (dark) NamedTextColor.DARK_GREEN else NamedTextColor.GREEN
        DiplomaticRelationship.NATION -> NamedTextColor.DARK_GREEN
        DiplomaticRelationship.ALLY -> if (dark) NamedTextColor.DARK_AQUA else NamedTextColor.AQUA
        DiplomaticRelationship.NEUTRAL -> if (dark) NamedTextColor.GOLD else NamedTextColor.YELLOW
        DiplomaticRelationship.ENEMY -> if (dark) NamedTextColor.DARK_RED else NamedTextColor.RED
        null -> if (dark) NamedTextColor.DARK_GRAY else NamedTextColor.GRAY
    }
}

/** Sidebar-scoreboard minimap, the simple alternative to [Minimap]. */
class LegacyMinimap(
    private val resident: Resident,
    private val player: Player,
) : MinimapView {
    private val sidebar = Sidebar(Component.text("Territory Map", NamedTextColor.WHITE))
    private var destroyed = false
    private var lastChunk: Coord? = null
    private var lastRows: List<Component> = emptyList()

    init {
        for (row in 0 until LEGACY_RADIUS * 2 + 1) {
            sidebar.createLine(Sidebar.ScoreboardLine("row$row", Component.empty(), LEGACY_RADIUS * 2 - row, Sidebar.NumberFormat.blank()))
        }
        sidebar.addViewer(player)
        refresh()
    }

    override fun render(centerX: Int, centerZ: Int, force: Boolean) {
        if (destroyed || !player.isOnline) return
        val chunk = Coord.fromBlockCoords(centerX, centerZ)
        if (!force && chunk == lastChunk) return
        lastChunk = chunk
        val rows = LegacyMinimapRenderer.render(MinimapViewerSnapshot.capture(resident), chunk.x, chunk.z)
        // Refreshes fire on many world events; only resend the lines that actually changed.
        rows.forEachIndexed { row, content -> if (lastRows.getOrNull(row) != content) sidebar.updateLineContent("row$row", content) }
        lastRows = rows
    }

    override fun refresh() = render(player.position.blockX(), player.position.blockZ(), force = true)

    override fun respawn() {
        if (destroyed || !player.isOnline) return
        sidebar.removeViewer(player)
        sidebar.addViewer(player)
        lastRows = emptyList()
        refresh()
    }

    override fun updateSettings() = refresh()

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        sidebar.removeViewer(player)
    }
}
