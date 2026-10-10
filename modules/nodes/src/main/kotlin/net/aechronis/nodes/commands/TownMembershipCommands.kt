package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.commands.arguments.ArgumentResident
import net.aechronis.nodes.commands.arguments.ArgumentTown
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.TestTownSelection
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.TownMembershipRequests
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.dialog.Dialog
import net.minestom.server.dialog.DialogAction
import net.minestom.server.dialog.DialogActionButton
import net.minestom.server.dialog.DialogAfterAction
import net.minestom.server.dialog.DialogBody
import net.minestom.server.dialog.DialogMetadata
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerCustomClickEvent

class TownPromoteCommand : NodesCommand("promote", null, "officer") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town promote <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            if (!requireTownLeader(player, resident, town)) return@addSyntax

            if (context[playerArg] === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            if (!requireTownMember(player, context[playerArg], town)) return@addSyntax

            val targetPlayer = context[playerArg].player()

            // add officer
            if (!town.officers.contains(context[playerArg])) {
                Town.addOfficer(town, context[playerArg])
                Message.print(player, "Made ${context[playerArg].name} a town officer")

                if (targetPlayer !== null) {
                    Message.print(targetPlayer, "You are now a town officer")
                }
            }
        }, playerArg)
    }
}

class TownDemoteCommand : NodesCommand("demote") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town demote <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->

            if (!requireTownLeader(player, resident, town)) return@addSyntax

            if (context[playerArg] === resident) {
                Message.error(player, "You are already the town leader")
                return@addSyntax
            }

            if (!requireTownMember(player, context[playerArg], town)) return@addSyntax

            val targetPlayer = context[playerArg].player()

            // remove officer
            if (town.officers.contains(context[playerArg])) {
                Town.removeOfficer(town, context[playerArg])
                Message.print(player, "Removed ${context[playerArg].name} from town officers")

                if (targetPlayer !== null) {
                    Message.error(targetPlayer, "You are no longer a town officer")
                }
            }
        }, playerArg)
    }
}

class TownApplyCommand : NodesCommand("apply", null, "join") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town apply <town-name>")
        }

        val townArg = ArgumentTown.create("town-name")

        addSyntax({ player, resident, context ->
            if (TestTownSelection.isEnabled()) {
                TestTownSelection.join(player, resident, context[townArg])
                    .onSuccess { town -> Message.print(player, "You joined ${town.name} and were teleported to its spawn") }
                    .onFailure { error -> Message.error(player, error.message ?: "Could not join that town") }
                return@addSyntax
            }

            Town.joinRestriction(context[townArg], resident)?.let { restriction ->
                Message.error(player, restriction)
                return@addSyntax
            }

            if (TownMembershipRequests.hasApplication(context[townArg], resident)) {
                Message.error(player, "You have already applied to ${context[townArg].name}")
                return@addSyntax
            }

            val activeApplicationTown = TownMembershipRequests.applicationTown(resident)
            if (activeApplicationTown != null) {
                Message.error(player, "You have already applied to ${activeApplicationTown.name}")
                return@addSyntax
            }

            val approvers: ArrayList<Player> = ArrayList()
            MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[townArg].leader!!.name)?.let { player ->
                approvers.add(player)
            }
            context[townArg].officers.forEach { officer ->
                MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(officer.name)?.let { player ->
                    approvers.add(player)
                }
            }

            if (approvers.isEmpty()) {
                Message.error(player, "There are no officers online from ${context[townArg].name} to receive your application")
                return@addSyntax
            }

            approvers.forEach { approver ->
                Message.print(approver, "${resident.name} has applied to join to your town. \nType \"/t accept\" to let them in or \"/t reject\" to refuse the offer.")
            }
            Message.print(player, "Your application has been sent")

            TownMembershipRequests.apply(context[townArg], resident, player)
        }, townArg)
    }
}

class TownInviteCommand : NodesCommand("invite") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town invite <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            if (TestTownSelection.isEnabled()) {
                Message.error(player, "Invitations are disabled during test town selection; players must use /town join <town>")
                return@addSyntax
            }

            val invitee: Player? = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(context[playerArg].name)
            if (invitee == null) {
                Message.error(player, "That player is not online")
                return@addSyntax
            } else if (invitee == player) {
                Message.error(player, "You're already in your town")
                return@addSyntax
            }

            val invitation = TownMembershipRequests.invitation(context[playerArg])
            if (invitation?.town == town) {
                Message.error(player, "This player has already been invited to the town")
                return@addSyntax
            } else if (invitation != null) {
                Message.error(player, "This player is considering another town invitation")
                return@addSyntax
            }
            val inviteeTown = context[playerArg].town
            if (inviteeTown != null) {
                Message.error(player, "This player is already a member of a town")
                return@addSyntax
            }
            Town.joinRestriction(town, context[playerArg])?.let { restriction ->
                Message.error(player, restriction)
                return@addSyntax
            }

            if (isTownStaff(resident, town)) {
                Message.print(player, "${invitee.username} has been invited to your town.")
                Message.print(invitee, "You have been invited to become a member of ${town.name}.\nType \"/t accept\" to join the town or \"/t reject\" to refuse the offer.")
                TownMembershipRequests.invite(town, context[playerArg], player, invitee)
            } else {
                Message.error(player, "You are not allowed to invite new members")
            }
        }, playerArg)
    }
}

class TownAcceptCommand : NodesCommand("accept") {
    init {
        registerMembershipResponse(accept = true)
    }
}

class TownDenyCommand : NodesCommand("deny", null, "reject") {
    init {
        registerMembershipResponse(accept = false)
    }
}

private fun NodesCommand.registerMembershipResponse(accept: Boolean) {
    val command = if (accept) "accept" else "deny"
    setDefaultExecutor { player, _, _ ->
        Message.print(player, "Usage:")
        Message.print(player, "/town $command")
        Message.print(player, "/town $command <player-name>")
    }

    val playerArg = ArgumentResident.create("player-name")
    addSyntax({ player, resident, _ ->
        respondToMembershipRequest(player, resident, null, accept)
    })
    addSyntax({ player, resident, context ->
        respondToMembershipRequest(player, resident, context[playerArg], accept)
    }, playerArg)
}

private fun respondToMembershipRequest(player: Player, resident: Resident, requestedApplicant: Resident?, accept: Boolean) {
    val town = resident.town
    if (town == null) {
        val invitation = TownMembershipRequests.invitation(resident)
        if (invitation == null) {
            Message.error(player, "You have not been invited to any town or your invitation expired")
            return
        }

        if (accept) {
            if (!Town.addResident(invitation.town, resident)) {
                Message.error(player, "You are already a member of a town")
                return
            }
            Message.print(player, "You are now a member of ${invitation.town.name}! Type \"/t spawn\" to teleport to your new town.")
            Message.print(invitation.inviter, "${resident.name} has accepted your invitation!")
        } else {
            Message.print(player, "You have rejected the invitation to join ${invitation.town.name}")
            Message.print(invitation.inviter, "${resident.name} has rejected your invitation!")
            TownMembershipRequests.cancelInvitation(resident)
        }
        return
    }

    if (town.leader != resident && !town.officers.contains(resident)) {
        Message.error(player, "You aren't allowed to consider town applications")
        return
    }

    val applicants = TownMembershipRequests.applicants(town)
    if (applicants.isEmpty()) {
        Message.error(player, "There are no active applications")
        return
    }

    val applicant = if (applicants.size == 1) {
        val onlyApplicant = applicants.single()
        if (requestedApplicant != null && requestedApplicant.name != onlyApplicant.name) {
            Message.error(player, "That player has not applied or their application has expired")
            return
        }
        onlyApplicant
    } else {
        if (requestedApplicant == null) {
            val command = if (accept) "accept" else "deny"
            val applicantsString = applicants.joinToString(", ") { it.name }
            Message.print(player, "There are multiple town applications. Please use \"/town $command [player]\".\nCurrent applicants: $applicantsString")
            return
        }
        if (!TownMembershipRequests.hasApplication(town, requestedApplicant)) {
            Message.error(player, "That player has not applied or their application has expired")
            return
        }
        requestedApplicant
    }

    if (accept) {
        if (!Town.addResident(town, applicant)) {
            TownMembershipRequests.cancelApplication(town, applicant)
            Message.error(player, "${applicant.name} is already a member of a town")
            return
        }
        Message.print(player, "${applicant.name} has been accepted into your town!")
        val applicantPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(applicant.name)
        if (applicantPlayer != null) {
            Message.print(applicantPlayer, "You have been accepted into ${town.name}!")
        }
    } else {
        Message.print(player, "${applicant.name} has been denied residence in your town!")
        val applicantPlayer = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(applicant.name)
        if (applicantPlayer != null) {
            Message.print(applicantPlayer, "Your application to ${town.name} has been rejected!")
        }
        TownMembershipRequests.cancelApplication(town, applicant)
    }
}

class TownLeaveCommand : NodesCommand("leave") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /town leave")
        }

        addSyntax({ player, resident, town, _ ->
            if (!canLeave(player, resident, town)) return@addSyntax
            player.showDialog(leaveConfirmationDialog(town))
        })

        Nodes.eventNode.addListener(PlayerCustomClickEvent::class.java, ::onLeaveConfirmation)
    }

    companion object {
        private val confirmLeaveAction = Key.key("nodes", "confirm_town_leave")
        private val cancelLeaveAction = Key.key("nodes", "cancel_town_leave")

        fun canLeave(player: Player, resident: Resident, town: Town): Boolean {
            if (TestTownSelection.isEnabled()) {
                Message.error(player, "You cannot leave during test town selection; use /town join <town> to switch sides")
                return false
            }
            if (town.leader == resident) {
                Message.error(player, "You must transfer leadership before leaving the town")
                return false
            }
            // Warzones are independent weekday activities, so they must not apply the global-war membership lock.
            if (!Nodes.config.canLeaveTownDuringWar && FlagWar.enabled && !Warzone.hasActiveZones()) {
                Message.error(player, "Cannot leave your town during war")
                return false
            }
            return true
        }

        private fun onLeaveConfirmation(event: PlayerCustomClickEvent) {
            if (event.key != confirmLeaveAction) return
            val player = event.player
            val resident = Resident.fromPlayer(player) ?: return
            val town = resident.town ?: return
            if (!canLeave(player, resident, town)) return
            applyLeavePenalty(resident)
            Town.removeResident(town, resident)
            Message.print(player, "You have left ${town.name}")
        }

        internal fun applyLeavePenalty(resident: Resident) {
            if (Nodes.config.townLeavePenaltyEnabled) resident.lockTownJoining()
        }

        private fun leaveConfirmationDialog(town: Town): Dialog.Confirmation {
            val leaveMessage = if (Nodes.config.townLeavePenaltyEnabled) {
                "You will be temporarily unable to join another town."
            } else {
                "You will be able to join another town immediately."
            }
            val metadata = DialogMetadata(
                Component.text("Leave ${town.name}?", NamedTextColor.RED),
                null,
                true,
                false,
                DialogAfterAction.CLOSE,
                listOf(DialogBody.PlainMessage(Component.text(leaveMessage, NamedTextColor.GRAY), 300)),
                emptyList(),
            )
            return Dialog.Confirmation(
                metadata,
                DialogActionButton(
                    Component.text("Leave town", NamedTextColor.RED),
                    Component.text("Confirm that you want to leave ${town.name}", NamedTextColor.GRAY),
                    150,
                    DialogAction.Custom(confirmLeaveAction, CompoundBinaryTag.empty()),
                ),
                DialogActionButton(
                    Component.text("Cancel", NamedTextColor.GRAY),
                    null,
                    150,
                    DialogAction.Custom(cancelLeaveAction, CompoundBinaryTag.empty()),
                ),
            )
        }
    }
}

class TownKickCommand : NodesCommand("kick") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /town kick <player-name>")
        }

        val playerArg = ArgumentResident.create("player-name")

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can kick players")) return@addSyntax

            // get other resident
            if (context[playerArg] === null) {
                Message.error(player, "Player not found")
                return@addSyntax
            }

            if (!requireTownMember(player, context[playerArg], town)) return@addSyntax

            // cannot kick leaders or officers
            if (isTownStaff(context[playerArg], town)) {
                Message.error(player, "You cannot kick the leader or other officers")
                return@addSyntax
            }

            Message.print(player, "You have kicked ${context[playerArg].name} from the town")

            val targetPlayer = context[playerArg].player()
            if (targetPlayer !== null) {
                Message.print(targetPlayer, "${ChatColor.DARK_RED}You have been kicked from ${town.name}")
            }

            Town.removeResident(town, context[playerArg])
        }, playerArg)
    }
}
