package net.aechronis.nodes.listeners

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.permissions.LandAccess
import net.aechronis.nodes.permissions.LandAccessAction
import net.aechronis.nodes.permissions.LandAccessDecision
import net.aechronis.server.Server
import net.aechronis.vanilla.commands.ADMIN_ADVENTURE
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.item.EntityEquipEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack

/**
 * Puts players in adventure mode while they hold a block they cannot place where they stand.
 *
 * The client shows a block the moment it places it and only removes it when the server's refusal arrives,
 * so players could stand on it for a moment. In adventure mode the client never places the block at all.
 * War flags stay placeable because the land check allows them where a cap is allowed.
 * Anything that is not a block (guns, tools, melee) leaves the player in survival, because adventure mode
 * stops the client swinging at blocks and those items fire on the swing.
 * Both hands count, since the client falls back to the offhand when the main hand does nothing.
 * Adventure that an admin set with /gamemode is left alone; any other adventure is switched freely with survival,
 * and creative and spectator are never touched.
 */
object AdventureLandListener {
    private fun update(
        player: Player,
        position: Point,
        mainHand: ItemStack,
        offHand: ItemStack,
    ) {
        val mode = player.gameMode
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return
        // An admin chose adventure with /gamemode.
        if (player.getTag(ADMIN_ADVENTURE) == true) return
        // Land claims only apply in the main world; duel arenas reuse the same coordinates.
        if (player.instance !== Server.instance) return

        // Most players hold tools, guns or nothing: no land lookup and no allocation for them.
        val mainBlock = mainHand.material().block()
        val offBlock = offHand.material().block()
        if (mainBlock == null && offBlock == null) {
            if (mode == GameMode.ADVENTURE) player.gameMode = GameMode.SURVIVAL
            return
        }

        val blockPosition = BlockVec(position.blockX(), position.blockY(), position.blockZ())
        val refused = isRefused(player, blockPosition, mainBlock) || isRefused(player, blockPosition, offBlock)
        val wanted = if (refused) GameMode.ADVENTURE else GameMode.SURVIVAL
        if (mode != wanted) player.gameMode = wanted
    }

    private fun isRefused(
        player: Player,
        position: BlockVec,
        block: Block?,
    ): Boolean = block != null && LandAccess.check(player, position, LandAccessAction.PLACE, block) != LandAccessDecision.ALLOW

    private fun update(player: Player, position: Point) = update(player, position, player.itemInMainHand, player.itemInOffHand)

    private fun onMove(event: PlayerMoveEvent) {
        if (event.isCancelled) return
        val player = event.player
        val from = player.position
        val to = event.newPosition
        if (from.blockX() == to.blockX() && from.blockY() == to.blockY() && from.blockZ() == to.blockZ()) return
        update(player, to)
    }

    private fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        update(player, event.newPosition)
    }

    // Fires before the new item is stored, so the changed hand comes from the event.
    private fun onEquip(event: EntityEquipEvent) {
        val player = event.entity as? Player ?: return
        when (event.slot) {
            EquipmentSlot.MAIN_HAND -> update(player, player.position, event.equippedItem, player.itemInOffHand)
            EquipmentSlot.OFF_HAND -> update(player, player.position, player.itemInMainHand, event.equippedItem)
            else -> {}
        }
    }

    private fun onHeldSlot(event: PlayerChangeHeldSlotEvent) {
        val player = event.player
        update(player, player.position, player.inventory.getItemStack(event.newSlot.toInt()), player.itemInOffHand)
    }

    private fun onSpawn(event: PlayerSpawnEvent) = update(event.player, event.player.position)

    fun init() {
        Nodes.eventNode.addListener(PlayerMoveEvent::class.java, ::onMove)
        Nodes.eventNode.addListener(EntityTeleportEvent::class.java, ::onTeleport)
        Nodes.eventNode.addListener(EntityEquipEvent::class.java, ::onEquip)
        Nodes.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, ::onHeldSlot)
        Nodes.eventNode.addListener(PlayerSpawnEvent::class.java, ::onSpawn)
    }
}
