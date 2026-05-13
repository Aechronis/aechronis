package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.instance.block.Block

// based on ccnet elevators
object Elevator {
    private const val MAX_SEARCH = 220
    private val IRON = Block.IRON_BLOCK
    private val TYPE = Block.Getter.Condition.TYPE

    fun onInput(event: PlayerInputEvent) {
        val step =
            when {
                event.hasPressedJumpKey() -> {
                    1
                }

                event.hasPressedShiftKey() -> {
                    -1
                }

                else -> {
                    return
                }
            }
        val player = event.player
        val instance = player.instance ?: return
        val pos = player.position
        val bx = pos.blockX()
        val bz = pos.blockZ()
        val floorY = pos.blockY() - 1

        if (instance.getBlock(bx, floorY, bz, TYPE) !== IRON) return

        for (dy in 1..MAX_SEARCH) {
            val targetY = floorY + dy * step
            if (instance.getBlock(bx, targetY, bz, TYPE) !== IRON) {
                println("relooping $targetY")
                continue
            }

            if (instance.getBlock(bx, targetY + 1, bz, TYPE)?.isAir == true &&
                instance.getBlock(bx, targetY + 2, bz, TYPE)?.isAir == true
            ) {
                println("teleporting $player $targetY")
                player.teleport(Pos(pos.x(), (targetY + 1).toDouble(), pos.z(), pos.yaw(), pos.pitch()))
            }
            return
        }
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, Elevator::onInput)
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Elevators enabled in ${timeLoad}ms")
    }
}
