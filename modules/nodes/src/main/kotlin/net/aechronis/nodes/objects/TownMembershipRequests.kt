package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

/** Owns temporary membership requests and cancels their expiry whenever a request ends. */
internal object TownMembershipRequests {
    data class Invitation(val town: Town, val inviter: Player)

    private class Application {
        lateinit var expiry: Task
    }

    private class PendingInvitation(val invitation: Invitation) {
        lateinit var expiry: Task
    }

    private val applications = HashMap<Town, HashMap<Resident, Application>>()
    private val invitations = HashMap<Resident, PendingInvitation>()

    fun applicants(town: Town): List<Resident> = applications[town]?.keys?.toList().orEmpty()

    fun hasApplication(town: Town, resident: Resident): Boolean = applications[town]?.containsKey(resident) == true

    fun applicationTown(resident: Resident): Town? = applications.entries.firstOrNull { (_, requests) -> resident in requests }?.key

    fun invitation(resident: Resident): Invitation? = invitations[resident]?.invitation

    fun apply(town: Town, resident: Resident, player: Player) {
        check(applicationTown(resident) == null) { "Resident already has a pending application" }
        val request = Application()
        request.expiry = ModuleScheduler.buildTask {
            if (applications[town]?.get(resident) !== request) return@buildTask
            try {
                if (resident.town == null) player.sendMessage("No one in ${town.name} responded to your application!")
            } finally {
                cancelApplication(town, resident)
            }
        }.delay(TaskSchedule.tick(1200)).schedule()
        applications.getOrPut(town, ::HashMap)[resident] = request
    }

    fun invite(town: Town, resident: Resident, inviter: Player, invitee: Player) {
        check(resident !in invitations) { "Resident already has a pending invitation" }
        val request = PendingInvitation(Invitation(town, inviter))
        request.expiry = ModuleScheduler.buildTask {
            if (invitations[resident] !== request) return@buildTask
            try {
                Message.print(inviter, "${invitee.username} didn't respond to your town invitation!")
            } finally {
                cancelInvitation(resident)
            }
        }.delay(TaskSchedule.tick(1200)).schedule()
        invitations[resident] = request
    }

    fun cancelApplication(town: Town, resident: Resident) {
        val requests = applications[town] ?: return
        val request = requests.remove(resident) ?: return
        if (requests.isEmpty()) applications.remove(town)
        request.expiry.cancel()
    }

    fun cancelInvitation(resident: Resident) {
        invitations.remove(resident)?.expiry?.cancel()
    }

    fun cancel(resident: Resident) {
        applications.keys.toList().forEach { town -> cancelApplication(town, resident) }
        cancelInvitation(resident)
    }

    fun cancelTown(town: Town) {
        applications.remove(town)?.values?.forEach { it.expiry.cancel() }
        invitations.filterValues { it.invitation.town == town }.keys.forEach(::cancelInvitation)
    }

    fun clear() {
        val expiryTasks = applications.values.flatMap { requests -> requests.values.map { it.expiry } } +
            invitations.values.map { it.expiry }
        applications.clear()
        invitations.clear()
        expiryTasks.forEach(Task::cancel)
    }
}
