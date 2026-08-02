package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
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
                    var speed = 0.0
                    val iterator = positions.iterator()
                    if (iterator.hasNext()) {
                        var previous = iterator.next()
                        while (iterator.hasNext()) {
                            val current = iterator.next()
                            speed += previous.distance(current)
                            previous = current
                        }
                    }

                    Combat.playerSpeeds[player] = speed.toFloat()
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }
}
