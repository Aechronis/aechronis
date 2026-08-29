package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.key.Key
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.event.item.EntityEquipEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment

object Efficiency {
    private val MODIFIER_ID =
        Key.key("minecraft:enchantment.efficiency/${EquipmentSlot.MAIN_HAND.nbtName()}")

    internal fun onEquip(event: EntityEquipEvent) {
        if (event.slot != EquipmentSlot.MAIN_HAND) return
        val player = event.entity as? Player ?: return
        update(player, event.equippedItem)
    }

    internal fun onHeldSlotChange(event: PlayerChangeHeldSlotEvent) {
        if (event.isCancelled) return
        update(event.player, event.itemInNewSlot)
    }

    internal fun update(
        player: Player,
        item: ItemStack = player.itemInMainHand,
    ) {
        val level = item.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).level(Enchantment.EFFICIENCY)
        val attribute = player.getAttribute(Attribute.MINING_EFFICIENCY)
        val current = attribute.modifiers().firstOrNull { it.id() == MODIFIER_ID }
        if (level <= 0) {
            if (current != null) attribute.removeModifier(MODIFIER_ID)
            return
        }

        val amount = level.toDouble() * level + 1.0
        if (current?.amount() == amount && current.operation() == AttributeOperation.ADD_VALUE) return
        attribute.addModifier(AttributeModifier(MODIFIER_ID, amount, AttributeOperation.ADD_VALUE))
    }

    internal fun onTick(event: PlayerTickEvent) = update(event.player)

    fun init() {
        val timeStart = System.currentTimeMillis()
        Vanilla.eventNode.addListener(EntityEquipEvent::class.java, ::onEquip)
        Vanilla.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, ::onHeldSlotChange)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java) { update(it.player) }
        Vanilla.eventNode.addListener(PlayerTickEvent::class.java, ::onTick)
        println("├─ Efficiency enabled in ${System.currentTimeMillis() - timeStart}ms")
    }

    fun shutdown() {
        net.minestom.server.MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            player.getAttribute(Attribute.MINING_EFFICIENCY).removeModifier(MODIFIER_ID)
        }
    }
}
