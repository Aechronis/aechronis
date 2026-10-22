package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.client.play.ClientNameItemPacket
import net.minestom.server.network.packet.server.play.WindowPropertyPacket

/**
 * The converter's search bar: an anvil whose text field filters blocks as you type.
 * The middle slot completes the word being typed, the right slot opens the results in the converter.
 */
object ConverterSearch {
    private const val TEXT_SLOT = 0
    private const val AUTOFILL_SLOT = 1
    private const val RESULT_SLOT = 2
    private const val ANVIL_COST_PROPERTY: Short = 0
    private const val LISTED_MATCHES = 6
    private const val MAX_QUERY_LENGTH = 40

    private val searches: MutableMap<Inventory, List<Material>> = mutableMapOf()
    private val autofills: MutableMap<Inventory, String> = mutableMapOf()

    private fun textItem(text: String): ItemStack = ItemStack.of(Material.PAPER).withCustomName(Component.text(text))

    fun open(player: Player) {
        val inv = Inventory(InventoryType.ANVIL, Component.text("Search blocks"))
        searches[inv] = emptyList()
        // A blank name keeps the text field empty until the player types.
        inv.setItemStack(TEXT_SLOT, textItem(" "))
        player.openInventory(inv)
        sendCost(player, inv)
    }

    // The client only lets you take an anvil result when the repair cost is above zero.
    private fun sendCost(
        player: Player,
        inv: Inventory,
    ) = player.sendPacket(WindowPropertyPacket(inv.getWindowId().toInt(), ANVIL_COST_PROPERTY, 0))

    private fun update(
        player: Player,
        inv: Inventory,
        query: String,
    ) {
        val matches = ConverterBrowse.matches(query)
        searches[inv] = matches
        val best = matches.firstOrNull()

        val completion = ConverterBrowse.autofill(query)
        if (completion == null) autofills.remove(inv) else autofills[inv] = completion
        inv.setItemStack(
            AUTOFILL_SLOT,
            if (completion == null) {
                ItemStack.AIR
            } else {
                val icon = ConverterBrowse.matches(completion).firstOrNull() ?: best ?: Material.PAPER
                ItemStack.of(icon).withCustomName(ConverterBrowse.text("Autofill: $completion", NamedTextColor.GREEN))
            },
        )
        inv.setItemStack(
            RESULT_SLOT,
            if (best == null) {
                ItemStack.AIR
            } else {
                ItemStack
                    .of(
                        Material.COMPASS,
                    ).withCustomName(ConverterBrowse.text("Show ${matches.size} results", NamedTextColor.AQUA))
                    .withLore(
                        matches.take(LISTED_MATCHES).map { ConverterBrowse.text("  ${Recipes.materialName(it)}", NamedTextColor.GRAY) } +
                            if (matches.size > LISTED_MATCHES) {
                                listOf(ConverterBrowse.text("  +${matches.size - LISTED_MATCHES} more", NamedTextColor.DARK_GRAY))
                            } else {
                                emptyList()
                            },
                    )
            },
        )
        sendCost(player, inv)
    }

    private fun onPacket(event: PlayerPacketEvent) {
        val packet = event.packet as? ClientNameItemPacket ?: return
        val inv = event.player.openInventory as? Inventory ?: return
        // Cap the query so a client spamming long names can't make every keystroke expensive.
        if (inv in searches) update(event.player, inv, packet.itemName().take(MAX_QUERY_LENGTH))
    }

    private fun onClick(event: InventoryPreClickEvent) {
        val inv = event.player.openInventory as? Inventory ?: return
        val matches = searches[inv] ?: return
        event.isCancelled = true
        if (event.inventory !== inv) return

        when (event.slot) {
            AUTOFILL_SLOT -> {
                val completion = autofills[inv] ?: return
                inv.setItemStack(TEXT_SLOT, textItem(completion))
                update(event.player, inv, completion)
            }
            RESULT_SLOT -> {
                if (matches.isEmpty()) return
                val results = matches.map(ConverterBrowse::result)
                Blocks.openConverter(event.player, results)
            }
        }
    }

    private fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        searches.remove(inv)
        autofills.remove(inv)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerPacketEvent::class.java, ConverterSearch::onPacket)
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, ConverterSearch::onClick)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, ConverterSearch::onClose)
    }
}
