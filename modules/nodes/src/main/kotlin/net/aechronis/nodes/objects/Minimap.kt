/**
 * Minimap object for player
 *
 * Displays a fixed 11x11 chunk area around player
 * [-x, x] chunks in each direction, x in [3, 4, 5]
 *
 * Internally uses the Minestom Sidebar API for rendering.
 *
 * Each glyph in minimap is 3 chars (color code "&x" = 2 chars)
 * For [-5, 5] extent -> 33 chars
 * For [-6, 6] extent -> 39 chars
 * Initial line symbol required to distinguish lines,
 * as a result [-5, 5] is max minimap size such that each line < 40 chars
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.WorldMap
import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.scoreboard.Sidebar

// used to start each line in scoreboard
// ensures each line name is unique
private val LINE_ID = arrayOf(
    "${ChatColor.AQUA}${ChatColor.RESET}",
    "${ChatColor.BLACK}${ChatColor.RESET}",
    "${ChatColor.BLUE}${ChatColor.RESET}",
    "${ChatColor.DARK_AQUA}${ChatColor.RESET}",
    "${ChatColor.DARK_BLUE}${ChatColor.RESET}",
    "${ChatColor.DARK_GRAY}${ChatColor.RESET}",
    "${ChatColor.DARK_GREEN}${ChatColor.RESET}",
    "${ChatColor.DARK_PURPLE}${ChatColor.RESET}",
    "${ChatColor.DARK_RED}${ChatColor.RESET}",
    "${ChatColor.GOLD}${ChatColor.RESET}",
    "${ChatColor.GRAY}${ChatColor.RESET}",
    "${ChatColor.GREEN}${ChatColor.RESET}",
    "${ChatColor.LIGHT_PURPLE}${ChatColor.RESET}",
    "${ChatColor.RED}${ChatColor.RESET}",
    "${ChatColor.WHITE}${ChatColor.RESET}",
    "${ChatColor.YELLOW}${ChatColor.RESET}",
)

// line header displaying X edges
private val HEADER = arrayOf(
    "${ChatColor.RED}-3    0      3",
    "${ChatColor.RED}-4      0        4",
    "${ChatColor.RED}-5         0          5",
)

class Minimap(
    val resident: Resident,
    val player: Player,
    var size: Int, // display square half extend, renders [-size, size]
) {
    // rendering target for Minestom Sidebar API
    val sidebar: Sidebar

    init {
        // ensure size within limits [3..5]
        this.size = this.size.coerceIn(3, 5)

        // create sidebar with title
        this.sidebar = Sidebar(Component.text("Minimap"))

        // add player as viewer
        this.sidebar.addViewer(player)

        // initial render, get player current location
        val loc = this.player.position
        val coordX = kotlin.math.floor(loc.x()).toInt()
        val coordZ = kotlin.math.floor(loc.z()).toInt()
        val coord = Coord.fromBlockCoords(coordX, coordZ)

        this.render(coord)
    }

    // render minimap centered at coord (current player location)
    fun render(coord: Coord) {
        // remove all existing lines
        for (lineId in this.sidebar.lines.map { it.id }) {
            this.sidebar.removeLine(lineId)
        }

        // create header line
        this.sidebar.createLine(
            Sidebar.ScoreboardLine(
                "header",
                Component.text(HEADER[size - 3]),
                size + 1,
            ),
        )

        // create map lines
        val size = this.size
        for ((i, y) in (size downTo -size).withIndex()) {
            val lineIdString = LINE_ID[i]
            val renderedLine = WorldMap.renderLine(resident, coord, coord.z - y, coord.x - size, coord.x + size)

            this.sidebar.createLine(
                Sidebar.ScoreboardLine(
                    "line_$i",
                    Component.text("${lineIdString}$renderedLine"),
                    y,
                ),
            )
        }
    }

    fun destroy() {
        // remove player from sidebar viewers
        this.sidebar.removeViewer(player)
    }
}
