/**
 * Basic chat pub/sub channels manager.
 */

package net.aechronis.nodes.chat

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChatEvent

enum class ChatMode {
    GLOBAL,
    TOWN,
    NATION,
    ALLY,
}

object Chat {

    val playersMuteGlobal: HashSet<Player> = hashSetOf()

    var colorDefault = ChatColor.WHITE
    val colorGreen = ChatColor.GREEN

    var colorTown = ChatColor.DARK_AQUA
    var colorNation = ChatColor.GOLD
    var colorAlly = ChatColor.GREEN

    var colorPlayerTownless = ChatColor.GRAY
    var colorPlayerTownOfficer = ChatColor.WHITE
    var colorPlayerTownLeader = ChatColor.BOLD
    var colorPlayerNationLeader = "${ChatColor.GOLD}${ChatColor.BOLD}"

    fun process(event: PlayerChatEvent) {
        // FIRST MOST IMPORTANT: APPLY GREENTEXT
        var msg = event.rawMessage
        if (msg[0] == '>') {
            msg = "${colorGreen}$msg"
        }

        // get player chat mode
        val player = event.player
        val fetchResident = Nodes.getResident(player)
        val resident: Resident = fetchResident ?: return // print normal message...

        val chatMode = resident.chatMode

        when (chatMode) {
            ChatMode.GLOBAL -> {
                // filter out players who muted global
                event.recipients.removeAll(playersMuteGlobal)
                event.formattedMessage = formatMsgGlobal(resident, player.username, msg)
            }
            ChatMode.TOWN -> {
                val town = resident.town
                if (town == null) {
                    event.isCancelled = true
                    return
                }
                event.recipients.clear()
                event.recipients.addAll(town.playersOnline)
                event.formattedMessage = formatMsgTown(resident, player.username, msg)
            }
            ChatMode.NATION -> {
                val nation = resident.nation
                if (nation == null) {
                    event.isCancelled = true
                    return
                }
                event.recipients.clear()
                event.recipients.addAll(nation.playersOnline)
                event.formattedMessage = formatMsgNation(resident, player.username, msg)
            }
            ChatMode.ALLY -> {
                val town = resident.town
                if (town == null) {
                    event.isCancelled = true
                    return
                }
                event.recipients.clear()
                event.recipients.addAll(town.playersOnline)
                // add players from allied nations
                val nation = town.nation
                if (nation !== null) {
                    for (allyNation in nation.allies) {
                        for (allyTown in allyNation.towns) {
                            event.recipients.addAll(allyTown.playersOnline)
                        }
                    }
                }
                event.formattedMessage = formatMsgAlly(resident, player.username, msg)
            }
        }
    }

    // unmute global chat for player
    fun enableGlobalChat(player: Player) {
        playersMuteGlobal.remove(player)
    }

    // mute global chat for player
    fun disableGlobalChat(player: Player) {
        playersMuteGlobal.add(player)
    }

    // toggle chat mode then print message
    fun toggleChatMode(player: Player, resident: Resident, chatMode: ChatMode) {
        val newChatMode = Nodes.toggleChatMode(resident, chatMode)

        when (newChatMode) {
            ChatMode.GLOBAL -> Message.print(player, "${ChatColor.BOLD}Now talking in global chat")
            ChatMode.TOWN -> Message.print(player, "${ChatColor.DARK_AQUA}${ChatColor.BOLD}Now talking in town chat")
            ChatMode.NATION -> Message.print(player, "${ChatColor.GOLD}${ChatColor.BOLD}Now talking in nation chat")
            ChatMode.ALLY -> Message.print(player, "${ChatColor.GREEN}${ChatColor.BOLD}Now talking in ally chat")
        }
    }

    fun formatResidentName(resident: Resident, playerName: String): String {
        val town = resident.town
        val nation = resident.nation

        // get player name color
        val color = if (town == null) {
            colorPlayerTownless
        } else { // town != null
            if (resident === nation?.capital?.leader) {
                colorPlayerNationLeader
            } else if (resident.uuid == town.leader?.uuid) {
                colorPlayerTownLeader
            } else if (town.officers.contains(resident)) {
                colorPlayerTownOfficer
            } else {
                colorDefault
            }
        }

        return color + playerName
    }

    fun formatMsgGlobal(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        // format town, nation
        val formattedResidentAllegiance = if (resident.town != null && resident.nation != null) {
            "[${colorNation}${resident.nation?.name}$colorDefault|${colorTown}${resident.town?.name}$colorDefault] "
        } else if (resident.town != null) {
            "[${colorTown}${resident.town?.name}$colorDefault] "
        } else {
            ""
        }

        return Component.text("${formattedResidentAllegiance}${formattedResidentName}$colorDefault: $message")
    }

    fun formatMsgTown(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        return Component.text("$colorTown[Town] ${formattedResidentName}$colorTown: $message")
    }

    fun formatMsgNation(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        return Component.text("$colorNation[Nation] ${formattedResidentName}$colorNation: $message")
    }

    fun formatMsgAlly(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        return Component.text("$colorAlly[Ally] ${formattedResidentName}$colorAlly: $message")
    }
}
