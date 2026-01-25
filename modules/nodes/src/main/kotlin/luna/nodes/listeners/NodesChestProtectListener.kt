/**
 * Handlers for town chest protection actions:
 *
 * NodesChestProtectListener:
 * - handler for clicking chest for protecting it, created
 *   dynamically per player
 *
 * NodesProtectedChestDestructionListener:
 * - handle detecting chest destruction to remove protected blocks
 */

package luna.nodes.listeners

import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.constants.PROTECTED_BLOCKS
import luna.nodes.objects.Resident
import luna.nodes.objects.Territory
import luna.nodes.objects.Town
import luna.nodes.utils.ChatColor
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent

/**
 * Listener for any special chest protection
 */
object NodesChestProtectionListener {
    private fun onBlockInteract(event: PlayerBlockInteractEvent) {
        val player: Player = event.player
        val resident: Resident = Nodes.getResident(player)!!
        if (!resident.isProtectingChests) {
            return
        }

        if (PROTECTED_BLOCKS.contains(event.block)) {
            val town: Town = resident.town!!
            val territory: Territory? =
                Nodes.getTerritoryFromBlock(event.blockPosition.blockX, event.blockPosition.blockZ)
            val territoryTown: Town? = territory?.town

            if (town !== territoryTown) {
                Message.error(player, "This is not your town (stopping, use /t protect to start protecting again)")
                println(town)
                println(territoryTown)
                Nodes.stopProtectingChests(resident)
                return
            }

            // unprotect
            if (town.protectedBlocks.contains(event.blockPosition)) {
                Nodes.protectTownChest(town, event.blockPosition, false)

                Message.print(player, "${ChatColor.DARK_AQUA}Removed chest protection")
            }
            // protect
            else {
                Nodes.protectTownChest(town, event.blockPosition, true)

                Message.print(player, "You have protected this chest")
            }

            event.isCancelled = true
            return
        }

        Message.error(player, "Not a chest (stopping, use /t protect to start protecting again)")
        Nodes.stopProtectingChests(resident)
    }

    fun init() {
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}

/**
 * Listen to block destruction, unprotect chests if occurs
 */
object NodesChestProtectionDestroyListener {
    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val town: Town? = Nodes.getTerritoryFromBlock(event.blockPosition.blockX, event.blockPosition.blockZ)?.town
        val resident = Nodes.getResident(event.player)!!

        if (event.isCancelled || town == null || !town.protectedBlocks.contains(event.blockPosition)) {
            return
        }

        if (resident.hasTownProtectedChestPermissions(town)) {
            Nodes.protectTownChest(town, event.blockPosition, false)
            return
        }

        Message.error(event.player, "This chest is for trusted residents only")
        event.isCancelled = true
    }

    fun init() {
        Nodes.lowPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
    }
}
