/**
 * Alliance request manager and storage structure
 *
 * Alliances are between nations only. Towns inherit
 * alliance status from their nation.
 *
 * Handles "/ally [nation]" commands.
 */

package luna.nodes.war

import luna.nodes.utils.ChatColor
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.constants.ErrorAllyRequestAlreadyAllies
import luna.nodes.constants.ErrorAllyRequestAlreadyCreated
import luna.nodes.constants.ErrorAllyRequestEnemies
import luna.nodes.objects.Nation
import luna.nodes.objects.NationPair
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

// alliance request status results
enum class AllianceRequest {
    NEW, // new offer created
    ACCEPTED, // offer accepted
}

// timeout for ally request to cancel (default 1200 ticks ~ 1 minute)
private const val ALLY_REQUEST_TIMEOUT: Int = 1200

/**
 * Alliance request manager
 */
object Alliance {

    // offers lists: maps NationPair involved -> Initiating Nation
    val requests: HashMap<NationPair, Nation> = hashMapOf()

    // threads to delete requests after timeout
    val requestTimers: HashMap<NationPair, Task> = hashMapOf()

    /**
     * Offer/accept request between two nations. Inputs:
     * - nation1: initiator
     * - nation2: other nation involved
     *
     * if request exists and nation1 is not == NationPair -> Nation, accept request
     * else, create new request
     *
     */
    fun request(nation1: Nation, nation2: Nation): Result<AllianceRequest> {
        // check nations are not enemies
        if (nation1.enemies.contains(nation2) || nation2.enemies.contains(nation1)) {
            return Result.failure(ErrorAllyRequestEnemies)
        }
        // check nations not already allied
        if (nation1.allies.contains(nation2) && nation2.allies.contains(nation1)) {
            return Result.failure(ErrorAllyRequestAlreadyAllies)
        }

        val nations = NationPair(nation1, nation2)
        val initiator = requests.get(nations)

        // no request, create new request
        if (initiator === null) {
            requests.put(nations, nation1)

            // create timeout thread
            val timeoutThread = MinecraftServer.getSchedulerManager()
                .buildTask { cancelRequest(nations) }
                .delay(TaskSchedule.tick(ALLY_REQUEST_TIMEOUT))
                .schedule()

            requestTimers.put(nations, timeoutThread)

            return Result.success(AllianceRequest.NEW)
        }
        // else, check request initiator
        else {
            // initiator is same nation
            if (initiator === nation1) {
                return Result.failure(ErrorAllyRequestAlreadyCreated)
            } else { // accept request
                // remove request
                requests.remove(nations)

                // cancel timeout thread
                val timeoutThread = requestTimers.remove(nations)
                if (timeoutThread !== null) {
                    timeoutThread.cancel()
                }

                Nodes.addAlly(nation1, nation2)
                return Result.success(AllianceRequest.ACCEPTED)
            }
        }
    }

    // remove and cancel ally request
    fun cancelRequest(nations: NationPair) {
        val initiator = requests.remove(nations)
        if (initiator !== null) {
            // cancel timeout thread
            val timeoutThread = requestTimers.remove(nations)
            if (timeoutThread !== null) {
                timeoutThread.cancel()
            }

            // message creator that request was not accepted
            val target = if (initiator === nations.nation1) {
                nations.nation2
            } else {
                nations.nation1
            }

            val msg = "${ChatColor.DARK_RED}Your alliance offer to ${target.name} was ignored..."
            for (town in initiator.towns) {
                for (r in town.residents) {
                    val p = r.player()
                    if (p !== null) {
                        Message.print(p, msg)
                    }
                }
            }
        }
    }
}
