package net.aechronis.vanilla.serdes

import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.managers.KillShop
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.item.ItemStack
import java.util.UUID

/** Captured during a player callback or at a global tick boundary; retains no live inventories. */
class PlayerDataSnapshot private constructor(
    val uuid: UUID,
    val health: Float,
    val food: Int,
    val foodSaturation: Float,
    val points: Int,
    val gameMode: GameMode,
    val allowFlying: Boolean,
    val flying: Boolean,
    val position: Pos,
    val inventory: List<ItemStack>,
    val enderChest: List<ItemStack>,
    val cursorItem: ItemStack,
    val ignored: Set<UUID>,
) {
    companion object {
        fun capture(player: Player): PlayerDataSnapshot =
            PlayerDataSnapshot(
                uuid = player.uuid,
                health = player.health,
                food = player.food,
                foodSaturation = player.foodSaturation,
                points = player.getTag(KillShop.POINTS_TAG) ?: 0,
                gameMode = player.gameMode,
                allowFlying = player.isAllowFlying,
                flying = player.isFlying,
                position = player.position,
                inventory = captureInventory(player.inventory),
                enderChest = captureInventory(Commands.getEnderChest(player)),
                cursorItem = player.inventory.cursorItem,
                ignored = java.util.Collections.unmodifiableSet(LinkedHashSet(Commands.getIgnored(player))),
            )

        // ItemStack and Pos are immutable Minestom values; only the slot collection needs copying.
        private fun captureInventory(inventory: AbstractInventory): List<ItemStack> =
            java.util.List.copyOf(List(inventory.size, inventory::getItemStack))
    }
}
