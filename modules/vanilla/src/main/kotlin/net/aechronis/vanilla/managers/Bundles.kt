package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.BundleListener
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.CustomData
import net.minestom.server.tag.Tag

object Bundles {
    private const val DATA_KEY = "VanillaBundleKit"
    private const val ITEMS_KEY = "Items"
    private const val SLOT_KEY = "Slot"

    private val previewSlots =
        (0..8).toList() +
            listOf(41, 42, 43, 44, 45) +
            (9..35).toList()

    private val bundleMaterials by lazy {
        Material
            .values()
            .filter { material ->
                val path = material.key().value()
                path == "bundle" || path.endsWith("_bundle")
            }.toSet()
    }

    private data class StoredItem(
        val slot: Int,
        val item: ItemStack,
    )

    fun isBundle(item: ItemStack): Boolean = item.material() in bundleMaterials

    fun makeBundle(items: Map<Int, ItemStack>): ItemStack =
        makeBundle(ItemStack.of(Material.BUNDLE), items)

    fun use(
        player: Player,
        hand: PlayerHand,
    ) {
        val held = player.getItemInHand(hand)
        if (!isBundle(held)) return

        val kitData = kitData(held)
        if (kitData != null || hasKitData(held)) {
            if (kitData != null) restore(player, hand, held, kitData)
            return
        }

        // Avoid replacing contents from a regular/native bundle that has no slot data.
        if (held.get(DataComponents.BUNDLE_CONTENTS)?.isNotEmpty() == true) return
        fill(player, hand, held)
    }

    private fun fill(
        player: Player,
        hand: PlayerHand,
        held: ItemStack,
    ) {
        val heldSlot = heldSlot(player, hand)
        val stored =
            previewSlots
                .asSequence()
                .filter { it != heldSlot }
                .map { slot -> StoredItem(slot, player.inventory.getItemStack(slot)) }
                .filterNot { it.item.isAir }
                .toList()

        if (stored.isEmpty() || stored.size > Vanilla.config.bundleMaxItemStacks) return

        val filled = makeBundle(held, stored.associate { it.slot to it.item })

        stored.forEach { player.inventory.setItemStack(it.slot, ItemStack.AIR) }
        player.setItemInHand(hand, filled)
    }

    private fun makeBundle(
        bundle: ItemStack,
        items: Map<Int, ItemStack>,
    ): ItemStack {
        val stored =
            items.entries
                .sortedWith(compareBy({ previewSlots.indexOf(it.key).let { index -> if (index == -1) previewSlots.size else index } }, { it.key }))
                .map { StoredItem(it.key, it.value) }
        val entries = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
        stored.forEach { entry ->
            val itemBuilder =
                CompoundBinaryTag
                    .builder()
                    .putByte(SLOT_KEY, entry.slot.toByte())
            val itemNbt = entry.item.toItemNBT()
            for (key in itemNbt.keySet()) {
                itemBuilder.put(key, itemNbt.get(key)!!)
            }
            entries.add(itemBuilder.build())
        }

        val data =
            CompoundBinaryTag
                .builder()
                .put(ITEMS_KEY, entries.build())
                .build()
        val customData =
            (bundle.get(DataComponents.CUSTOM_DATA) ?: CustomData.EMPTY)
                .withTag(Tag.NBT(DATA_KEY), data)
        return bundle
            .with(DataComponents.CUSTOM_DATA, customData)
            .with(DataComponents.BUNDLE_CONTENTS, stored.map { it.item })
    }

    private fun restore(
        player: Player,
        hand: PlayerHand,
        held: ItemStack,
        stored: List<StoredItem>,
    ) {
        val heldSlot = heldSlot(player, hand)
        if (stored.any { it.slot !in 0..45 || it.slot == heldSlot } || stored.map { it.slot }.toSet().size != stored.size) {
            return
        }
        if (stored.any { !player.inventory.getItemStack(it.slot).isAir }) return

        stored.forEach { player.inventory.setItemStack(it.slot, it.item) }
        player.setItemInHand(
            hand,
            if (held.amount() > 1) held.withAmount(held.amount() - 1) else ItemStack.AIR,
        )
    }

    private fun heldSlot(
        player: Player,
        hand: PlayerHand,
    ): Int = if (hand == PlayerHand.MAIN) player.heldSlot.toInt() else 45

    private fun hasKitData(item: ItemStack): Boolean =
        item
            .get(DataComponents.CUSTOM_DATA)
            ?.nbt()
            ?.get(DATA_KEY) != null

    private fun kitData(item: ItemStack): List<StoredItem>? {
        val payload =
            item
                .get(DataComponents.CUSTOM_DATA)
                ?.nbt()
                ?.get(DATA_KEY) as? CompoundBinaryTag
                ?: return null
        val entries = payload.getList(ITEMS_KEY)
        if (entries.isEmpty()) return null

        val stored = mutableListOf<StoredItem>()
        for (entry in entries) {
            if (entry !is CompoundBinaryTag) return null
            val slot = entry.getByte(SLOT_KEY, -1).toInt()
            if (slot < 0) return null

            val itemBuilder = CompoundBinaryTag.builder()
            for (key in entry.keySet()) {
                if (key != SLOT_KEY) itemBuilder.put(key, entry.get(key)!!)
            }
            val item = runCatching { ItemStack.fromItemNBT(itemBuilder.build()) }.getOrNull() ?: return null
            stored += StoredItem(slot, item)
        }
        return stored
    }

    fun init() {
        require(Vanilla.config.bundleMaxItemStacks > 0) {
            "bundleMaxItemStacks must be greater than zero"
        }
        BundleListener.init()
    }
}
