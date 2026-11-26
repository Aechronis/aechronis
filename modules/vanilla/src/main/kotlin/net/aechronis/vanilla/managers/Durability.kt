package net.aechronis.vanilla.managers

import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment
import java.util.concurrent.ThreadLocalRandom

object Durability {
    /** Vanilla tool wear: each point of damage is ignored with chance level / (level + 1) per Unbreaking level. */
    fun damageTool(
        item: ItemStack,
        amount: Int = 1,
    ): ItemStack {
        val level = item.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).level(Enchantment.UNBREAKING)
        val random = ThreadLocalRandom.current()
        val points = (0 until amount).count { level <= 0 || random.nextInt(level + 1) == 0 }
        return if (points > 0) item.damage(points) else item
    }
}
