/**
 * Basic chat pub/sub channels manager.
 */

package luna.nodes.chat

import org.bukkit.ChatColor
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChatEvent
import luna.nodes.Nodes
import luna.nodes.objects.Resident

public enum class ChatMode {
    GLOBAL,
    TOWN,
    NATION,
    ALLY,
}

public object Chat {

    val playersMuteGlobal: HashSet<Player> = hashSetOf()

    var colorDefault = ChatColor.WHITE
    val colorGreen = ChatColor.GREEN

    var colorTown = ChatColor.DARK_AQUA
    var colorNation = ChatColor.GOLD
    var colorAlly = ChatColor.GREEN

    var colorPlayerTownless = ChatColor.GRAY
    var colorPlayerOp = ChatColor.DARK_RED
    var colorPlayerTownOfficer = ChatColor.WHITE
    var colorPlayerTownLeader = ChatColor.BOLD
    var colorPlayerNationLeader = "${ChatColor.GOLD}${ChatColor.BOLD}"

    public fun process(event: PlayerChatEvent) {
        // FIRST MOST IMPORTANT: APPLY GREENTEXT
        var msg = event.rawMessage
        if (msg.get(0) == '>') {
            msg = "${colorGreen}$msg"
        }

        // get player chat mode
        val player = event.getPlayer()
        val fetchResident = Nodes.getResident(player)
        val resident: Resident = if (fetchResident != null) {
            fetchResident
        } else { // print normal message...
            return
        }

        val chatMode = resident.chatMode

        when (chatMode) {
            ChatMode.GLOBAL -> {
                // filter out players who muted global
                event.recipients.removeAll(Chat.playersMuteGlobal)
                event.setFormattedMessage(formatMsgGlobal(resident, player.username, msg))
            }
            ChatMode.TOWN -> {
                val town = resident.town
                if (town == null) {
                    event.isCancelled = true
                    return
                }
                event.recipients.clear()
                event.recipients.addAll(town.playersOnline)
                event.setFormattedMessage(formatMsgTown(resident, player.username, msg))
            }
            ChatMode.NATION -> {
                val nation = resident.nation
                if (nation == null) {
                    event.isCancelled = true
                    return
                }
                event.recipients.clear()
                event.recipients.addAll(nation.playersOnline)
                event.setFormattedMessage(formatMsgNation(resident, player.username, msg))
            }
            ChatMode.ALLY -> {
                val town = resident.town
                if (town == null) {
                    event.isCancelled = true
                    return
                }
                event.recipients.clear()
                event.recipients.addAll(town.playersOnline)
                for (allyTown in town.allies) {
                    event.recipients.addAll(allyTown.playersOnline)
                }
                event.setFormattedMessage(formatMsgAlly(resident, player.username, msg))
            }
        }
    }

    // unmute global chat for player
    public fun enableGlobalChat(player: Player) {
        Chat.playersMuteGlobal.remove(player)
    }

    // mute global chat for player
    public fun disableGlobalChat(player: Player) {
        Chat.playersMuteGlobal.add(player)
    }

    public fun isMuted(player: Player): Boolean {
        // TODO: hook into essentials, check muted player
        return false
    }

    public fun formatResidentName(resident: Resident, playerName: String): String {
        val town = resident.town
        val nation = resident.nation

        // get player name color
        val color = /** if (resident.player()?.isOp() == true) {
            colorPlayerOp
        } else */ if (town == null) {
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

        return if (resident.prefix != "" && resident.suffix != "") {
            "${color}${resident.prefix} $color$playerName ${color}${resident.suffix}"
        } else if (resident.prefix != "") {
            "${color}${resident.prefix} $color$playerName"
        } else if (resident.suffix != "") {
            "$color$playerName ${resident.suffix}"
        } else {
            "$color$playerName"
        }
    }

    public fun formatMsgGlobal(resident: Resident, playerName: String, message: String): Component {
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

    public fun formatMsgTown(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        return Component.text("$colorTown[Town] ${formattedResidentName}$colorTown: $message")
    }

    public fun formatMsgNation(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        return Component.text("$colorNation[Nation] ${formattedResidentName}$colorNation: $message")
    }

    public fun formatMsgAlly(resident: Resident, playerName: String, message: String): Component {
        // format player name
        val formattedResidentName = formatResidentName(resident, playerName)

        return Component.text("$colorAlly[Ally] ${formattedResidentName}$colorAlly: $message")
    }
}
