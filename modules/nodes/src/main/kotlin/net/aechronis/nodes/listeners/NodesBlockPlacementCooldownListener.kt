package net.aechronis.nodes.listeners

import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.vanilla.listeners.BlockPlacementCooldownListener
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

object NodesBlockPlacementCooldownListener {
    private const val HOTBAR_SIZE = 9
    private const val COOLDOWN_TICKS = 4

    fun apply(player: Player, blockX: Int, blockZ: Int) {
        val claimOwner = Territory.fromBlock(blockX, blockZ)?.town ?: return
        val relationship = Town.relationshipOfPlayerToTown(player, claimOwner)
        if (relationship in FRIENDLY_RELATIONSHIPS) return

        val cooldownGroups = mutableSetOf<String>()
        for (slot in 0 until HOTBAR_SIZE) {
            val item = player.inventory.getItemStack(slot)
            if (!item.material().isBlock()) continue

            val group = item.cooldownGroup()
            if (cooldownGroups.add(group)) {
                BlockPlacementCooldownListener.apply(player, group, COOLDOWN_TICKS)
            }
        }
    }

    private fun ItemStack.cooldownGroup(): String = get(DataComponents.USE_COOLDOWN)?.cooldownGroup() ?: material().key().asString()

    private val FRIENDLY_RELATIONSHIPS = setOf(
        DiplomaticRelationship.TOWN,
        DiplomaticRelationship.NATION,
        DiplomaticRelationship.ALLY,
    )
}
