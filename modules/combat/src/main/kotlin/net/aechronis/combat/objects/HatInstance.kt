package net.aechronis.combat.objects

import kotlinx.serialization.Serializable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.item.ItemStack

/** A unique cosmetic copy. Its number is global and never changes or gets reused. */
@Serializable
data class HatInstance(
    val number: Long,
    val hatName: String,
) {
    val definition: Hat? get() = Hat.getFromName(hatName)

    internal val preview: ItemStack get() = numbered(requireNotNull(definition).preview)

    internal val appearance: ItemStack get() = numbered(requireNotNull(definition).appearance)

    private fun numbered(stack: ItemStack): ItemStack =
        stack.withCustomName(
            requireNotNull(definition).displayName.append(Component.text(" #$number", NamedTextColor.GRAY)),
        )
}
