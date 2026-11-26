package net.aechronis.tablist

import net.kyori.adventure.text.Component
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket.Action
import java.util.EnumSet
import java.util.UUID

/** Fake tab entries for one viewer, diffed against what the client already has so only changes are sent. */
class TabSender {
    private class Live(
        var text: Component,
        var order: Int,
    )

    private val live = HashMap<UUID, Live>()

    fun update(
        viewer: Player,
        cells: List<Cell>,
    ) {
        val desired = LinkedHashMap<UUID, Pair<Cell, Int>>()
        var blank = 0
        cells.forEachIndexed { i, cell ->
            val owner = cell.owner
            val id = if (owner == null) blankId(blank++) else playerId(owner, i / ROWS)
            desired[id] = cell to (cells.size - i)
        }

        val removed = live.keys.filter { it !in desired }
        if (removed.isNotEmpty()) {
            viewer.sendPacket(PlayerInfoRemovePacket(removed))
            removed.forEach(live::remove)
        }
        val added = ArrayList<PlayerInfoUpdatePacket.Entry>()
        val renamed = ArrayList<PlayerInfoUpdatePacket.Entry>()
        val reordered = ArrayList<PlayerInfoUpdatePacket.Entry>()
        for ((id, v) in desired) {
            val (cell, order) = v
            val state = live[id]
            if (state == null) {
                live[id] = Live(cell.text, order)
                added += entry(id, cell, order)
                continue
            }
            if (state.text != cell.text) {
                state.text = cell.text
                renamed += entry(id, cell, order)
            }
            if (state.order != order) {
                state.order = order
                reordered += entry(id, cell, order)
            }
        }
        if (added.isNotEmpty()) viewer.sendPacket(PlayerInfoUpdatePacket(ADD_ACTIONS, added))
        if (renamed.isNotEmpty()) viewer.sendPacket(PlayerInfoUpdatePacket(Action.UPDATE_DISPLAY_NAME, renamed))
        if (reordered.isNotEmpty()) viewer.sendPacket(PlayerInfoUpdatePacket(Action.UPDATE_LIST_ORDER, reordered))
    }

    fun clear(viewer: Player?) {
        if (viewer != null && live.isNotEmpty()) viewer.sendPacket(PlayerInfoRemovePacket(live.keys.toList()))
        live.clear()
    }

    private fun entry(
        id: UUID,
        cell: Cell,
        order: Int,
    ): PlayerInfoUpdatePacket.Entry =
        PlayerInfoUpdatePacket.Entry(
            id,
            "tab" + Integer.toHexString(id.hashCode()),
            emptyList(),
            true,
            0,
            GameMode.SURVIVAL,
            cell.text,
            null,
            order,
            false,
        )

    companion object {
        private val ADD_ACTIONS =
            EnumSet.of(
                Action.ADD_PLAYER,
                Action.UPDATE_LISTED,
                Action.UPDATE_LATENCY,
                Action.UPDATE_GAME_MODE,
                Action.UPDATE_DISPLAY_NAME,
                Action.UPDATE_LIST_ORDER,
            )

        /**
         * Blank rows must hash to the default skin the resource pack makes transparent
         * (index 10 of 18 = wide/ari, see DefaultPlayerSkin).
         */
        private val blankIds: List<UUID> =
            generateSequence(0L) { it + 1 }
                .map { UUID(0x7AB0_0000_0000_0000L, it) }
                .filter { Math.floorMod(it.hashCode(), 18) == 10 }
                .take(ROWS * COLUMNS)
                .toList()

        private fun blankId(i: Int) = blankIds[i]

        fun playerId(
            owner: UUID,
            column: Int,
        ): UUID {
            // Same transparent-head trick as blank rows: nudge until the default skin is the overridden one.
            val msb = owner.mostSignificantBits xor (0x5AB0_0000_0000_0000L + column)
            var lsb = owner.leastSignificantBits
            while (Math.floorMod(UUID(msb, lsb).hashCode(), 18) != 10) lsb++
            return UUID(msb, lsb)
        }
    }
}
