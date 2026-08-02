package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.util.TreeSet
import java.util.UUID
import kotlin.math.abs

// based on ccnet elevators
object Elevator {
    private val IRON = Block.IRON_BLOCK
    private val TYPE = Block.Getter.Condition.TYPE
    private val columns = HashMap<UUID, HashMap<Long, TreeSet<Int>>>()

    private fun columnKey(
        x: Int,
        z: Int,
    ): Long = x.toLong() shl 32 or (z.toLong() and 0xFFFFFFFFL)

    private fun getOrScanColumn(
        instance: Instance,
        x: Int,
        z: Int,
    ): TreeSet<Int> {
        val byInstance = columns.getOrPut(instance.uuid) { HashMap() }
        return byInstance.getOrPut(columnKey(x, z)) {
            val set = TreeSet<Int>()
            for (y in -64..320) {
                if (instance.getBlock(x, y, z, TYPE) === IRON) set.add(y)
            }
            set
        }
    }

    fun onInput(event: PlayerInputEvent) {
        val step =
            when {
                event.hasPressedJumpKey() -> 1
                event.hasPressedShiftKey() -> -1
                else -> return
            }
        val player = event.player
        val instance = player.instance ?: return
        val pos = player.position
        val bx = pos.blockX()
        val bz = pos.blockZ()
        val floorY = pos.blockY() - 1

        if (instance.getBlock(bx, floorY, bz, TYPE) !== IRON) return

        val col = getOrScanColumn(instance, bx, bz)
        val targetY = if (step > 0) col.higher(floorY) else col.lower(floorY)
        if (targetY == null || abs(targetY - floorY) > Vanilla.config.elevatorMaxSearch) return

        if (instance.getBlock(bx, targetY + 1, bz, TYPE)?.isAir == true &&
            instance.getBlock(bx, targetY + 2, bz, TYPE)?.isAir == true
        ) {
            player.teleport(Pos(pos.x(), (targetY + 1).toDouble(), pos.z(), pos.yaw(), pos.pitch()))
        }
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, Elevator::onInput)
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java) { event ->
            if (event.block === IRON) {
                val iid = event.player.instance?.uuid ?: return@addListener
                val key = columnKey(event.blockPosition.blockX(), event.blockPosition.blockZ())
                columns[iid]?.get(key)?.add(event.blockPosition.blockY())
            }
        }
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java) { event ->
            if (event.block === IRON) {
                val iid = event.player.instance?.uuid ?: return@addListener
                val key = columnKey(event.blockPosition.blockX(), event.blockPosition.blockZ())
                columns[iid]?.get(key)?.remove(event.blockPosition.blockY())
            }
        }
        println("├─ Elevators enabled in ${System.currentTimeMillis() - timeStart}ms")
    }
}
