package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.HatListener
import net.aechronis.server.modules.ModuleResources
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.component.TooltipDisplay
import net.minestom.server.item.enchant.Enchantment
import net.minestom.server.tag.Tag
import java.util.concurrent.ConcurrentHashMap

/** A cosmetic type; each owned copy is a separately numbered [HatInstance]. */
class Hat(
    val name: String,
    val displayName: Component,
    val model: String = "${Tags.NAMESPACE}:$name",
) {
    // These stacks are only menu icons and equipment packets, never server equipment.
    internal val preview: ItemStack =
        ItemStack
            .of(Material.WARPED_FUNGUS_ON_A_STICK)
            .withItemModel(model)
            .withCustomName(displayName)
            .withMaxStackSize(1)
            .withTag(cosmeticTag, name)

    internal val appearance: ItemStack =
        // Binding prevents vanilla survival clients predicting a pickup from the helmet slot.
        // Only the cosmetic copy receives this; the actual helmet remains untouched.
        preview
            .with(DataComponents.ENCHANTMENTS, EnchantmentList(Enchantment.BINDING_CURSE, 1))
            .with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
            .with(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, setOf(DataComponents.ENCHANTMENTS)))
            .with(
                DataComponents.EQUIPPABLE,
                requireNotNull(ItemStack.of(Material.CARVED_PUMPKIN).get(DataComponents.EQUIPPABLE))
                    .withCameraOverlay(null)
                    .withDispensable(false)
                    .withSwappable(false)
                    .withDamageOnHurt(false)
                    .withEquipOnInteract(false),
            )

    companion object {
        internal val cosmeticTag = Tag.String("${Tags.NAMESPACE}:hat-cosmetic")
        val registeredHats = ConcurrentHashMap<String, Hat>()

        fun registerHats(vararg hats: Hat) {
            require(hats.map { it.name }.distinct().size == hats.size) { "Duplicate names in hat catalogue" }
            require(hats.none { registeredHats.containsKey(it.name) }) { "A hat name is already registered" }
            hats.forEach { registeredHats[it.name] = it }
            ModuleResources.own(
                AutoCloseable {
                    hats.forEach { registeredHats.remove(it.name, it) }
                    // Disabling a catalogue also restores helmets when no replacement starts.
                    MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
                        HatListener.refresh(player)
                        player.inventory.update()
                    }
                },
            )
        }

        fun getFromName(name: String): Hat? = registeredHats[name]

        internal fun isCosmeticItem(stack: ItemStack): Boolean = stack.hasTag(cosmeticTag)
    }
}
