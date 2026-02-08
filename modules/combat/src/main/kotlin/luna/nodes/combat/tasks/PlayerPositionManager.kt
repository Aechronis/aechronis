package luna.nodes.combat.tasks

import luna.nodes.combat.Combat
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

object PlayerPositionManager {
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    // get or create deque for player
                    val positions = Combat.playerPreviousPositions.getOrPut(player) { ArrayDeque() }

                    // append current position
                    positions.addLast(player.position)

                    // keep only the last 20 positions
                    while (positions.size > 20) positions.removeFirst()

                    // calculate speed by summing distances between consecutive positions
                    val speed =
                        positions
                            .zipWithNext { a, b -> a.distance(b) }
                            .sum()
                            .toFloat()

                    Combat.playerSpeeds[player] = speed
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
