package net.aechronis.crates

import kotlinx.serialization.json.Json
import net.aechronis.crates.objects.Crate
import net.aechronis.crates.objects.CrateBlock
import net.aechronis.crates.objects.CrateReward
import net.aechronis.utils.JsonRegistryFile
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Block crates opened with digital keys. Definitions and placed blocks live in crates/crates.json. */
object Crates {
    private val crates = linkedMapOf<String, Crate>()
    private val storage = JsonRegistryFile("crates")
    private val previews: MutableSet<Inventory> = ConcurrentHashMap.newKeySet()

    private val idPattern = Regex("[a-z0-9_]{1,32}")
    private val legacyCrateIdTag = Tag.String("aechronis:crate_id")
    private val openSound = Sound.sound(Key.key("minecraft:entity.player.levelup"), Sound.Source.PLAYER, 0.8f, 1.2f)
    private val failSound = Sound.sound(Key.key("minecraft:block.note_block.bass"), Sound.Source.PLAYER, 0.8f, 0.6f)
    private const val EDIT_DISABLED = "Crates failed to load; editing is disabled until a successful reload."

    fun init(directory: Path) {
        val path = directory.resolve("crates.json")
        val seed = Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
        storage.load(path, crates) { contents ->
            Json.decodeFromString<List<Crate>>(contents).filter { idPattern.matches(it.id) }.associateByTo(linkedMapOf()) { it.id }
        }
        if (seed) seedVoteCrate()
        CrateKeys.init(directory.resolve("keys.json"))
    }

    fun shutdown() {
        previews.toList().forEach { inventory -> inventory.viewers.toList().forEach(Player::closeInventory) }
        previews.clear()
        crates.clear()
    }

    fun exists(id: String): Boolean = id in crates

    fun ids(): Set<String> = crates.keys

    fun title(id: String): String = crates[id]?.title ?: id

    fun crateAt(
        instance: Instance,
        position: Point,
    ): Crate? {
        val block = blockOf(instance, position)
        return crates.values.firstOrNull { block in it.blocks }
    }

    // Admin editing. Each returns an error message, or null on success.

    fun create(
        id: String,
        title: String,
    ): String? {
        if (!storage.canSave) return EDIT_DISABLED
        if (!idPattern.matches(id)) return "Crate ids use lowercase letters, digits and underscores (max 32)."
        if (id in crates) return "Crate $id already exists."
        crates[id] = Crate(id, title)
        save()
        return null
    }

    fun delete(id: String): String? = edit(id) { crates.remove(id) }

    fun setTitle(
        id: String,
        title: String,
    ): String? = edit(id) { it.title = title }

    fun place(
        id: String,
        instance: Instance,
        position: Point,
    ): String? {
        if (instance.getBlock(position).name().endsWith("air")) return "Look at the block that should become the crate."
        if (crateAt(instance, position) != null) return "That block is already a crate."
        return edit(id) { it.blocks += blockOf(instance, position) }
    }

    fun unplace(
        instance: Instance,
        position: Point,
    ): String? {
        val crate = crateAt(instance, position) ?: return "That block is not a crate."
        return edit(crate.id) { it.blocks -= blockOf(instance, position) }
    }

    fun addReward(
        id: String,
        item: ItemStack,
        weight: Double,
    ): String? {
        if (item.isAir) return "Hold the reward item in your main hand."
        if (weight !in MIN_WEIGHT..MAX_WEIGHT) return "Weight must be between $MIN_WEIGHT and $MAX_WEIGHT."
        if ((crates[id]?.rewards?.size ?: 0) >= MAX_REWARDS) return "A crate holds at most $MAX_REWARDS rewards."
        return edit(id) { it.rewards += CrateReward.of(item, weight) }
    }

    fun removeReward(
        id: String,
        index: Int,
    ): String? {
        if (crates[id]?.rewards?.indices?.contains(index) == false) return "No reward at index $index."
        return edit(id) { it.rewards.removeAt(index) }
    }

    fun describeRewards(id: String): List<Component>? {
        val crate = crates[id] ?: return null
        return crate.rewards.mapIndexed { index, reward ->
            Component
                .text("$index: ${reward.stack.amount()}x ", NamedTextColor.GRAY)
                .append(itemName(reward.stack))
                .append(Component.text(" (weight ${reward.weight}, ${formatChance(crate.chance(reward))})", NamedTextColor.GRAY))
        }
    }

    private inline fun edit(
        id: String,
        change: (Crate) -> Unit,
    ): String? {
        if (!storage.canSave) return EDIT_DISABLED
        val crate = crates[id] ?: return "Unknown crate: $id"
        change(crate)
        save()
        return null
    }

    // Player actions.

    /** Right click: uses one key. */
    fun openOne(
        player: Player,
        crate: Crate,
    ) {
        if (!canOpen(player, crate)) return
        val reward = crate.roll()!!
        if (!player.inventory.addItemStack(reward)) {
            fail(player, "Need more inventory space!")
            return
        }
        CrateKeys.take(player.uuid, crate.id, 1)
        player.playSound(openSound)
        player.sendMessage(
            Component
                .text("You opened a ${crate.title} and received ", NamedTextColor.GREEN)
                .append(Component.text("${reward.amount()}x ", NamedTextColor.WHITE))
                .append(itemName(reward))
                .append(Component.text(". Keys left: ${CrateKeys.count(player.uuid, crate.id)}", NamedTextColor.GREEN)),
        )
    }

    /** Shift right click: uses every key until the inventory is full. */
    fun openAll(
        player: Player,
        crate: Crate,
    ) {
        if (!canOpen(player, crate)) return
        val keys = CrateKeys.count(player.uuid, crate.id)
        var opened = 0
        // A roll that does not fit is discarded without using a key.
        while (opened < keys && player.inventory.addItemStack(crate.roll()!!)) opened++
        if (opened > 0) {
            CrateKeys.take(player.uuid, crate.id, opened)
            player.playSound(openSound)
            player.sendMessage(
                Component.text("You opened $opened ${crate.title} key${if (opened == 1) "" else "s"}.", NamedTextColor.GREEN),
            )
        }
        if (opened < keys) fail(player, "Need more inventory space! ${keys - opened} ${crate.title} keys left.")
    }

    private fun canOpen(
        player: Player,
        crate: Crate,
    ): Boolean {
        val error =
            when {
                crate.rewards.isEmpty() -> "This crate has no rewards yet."
                CrateKeys.count(player.uuid, crate.id) <= 0 -> "You don't have any ${crate.title} keys."
                else -> return true
            }
        fail(player, error)
        return false
    }

    /** Left click: shows every reward with its chance in the item tooltip. */
    fun preview(
        player: Player,
        crate: Crate,
    ) {
        if (previews.any { it === player.openInventory }) return
        val rows = ((crate.rewards.size + 8) / 9).coerceIn(1, 6)
        val type = InventoryType.valueOf("CHEST_${rows}_ROW")
        val inventory = Inventory(type, Component.text("${crate.title} (${CrateKeys.count(player.uuid, crate.id)} keys)"))
        crate.rewards.forEachIndexed { slot, reward ->
            val lore = reward.stack.get(DataComponents.LORE).orEmpty()
            val chance =
                Component
                    .text(
                        "Chance: ${formatChance(crate.chance(reward))}",
                        NamedTextColor.YELLOW,
                    ).decoration(TextDecoration.ITALIC, false)
            inventory.setItemStack(slot, reward.stack.with(DataComponents.LORE, lore + chance))
        }
        previews += inventory
        if (!player.openInventory(inventory)) previews -= inventory
    }

    fun isPreview(inventory: Inventory): Boolean = inventory in previews

    fun closePreview(inventory: Inventory) {
        previews -= inventory
    }

    /** Converts a physical crate item from before keys existed into keys. Returns true when [item] was one. */
    fun redeemLegacyItem(
        player: Player,
        item: ItemStack,
    ): Boolean {
        val id = item.getTag(legacyCrateIdTag) ?: return false
        if (!CrateKeys.give(player.uuid, id, item.amount())) return false
        player.setItemInMainHand(ItemStack.AIR)
        player.sendMessage(Component.text("Converted ${item.amount()} ${title(id)} item(s) into keys.", NamedTextColor.GREEN))
        return true
    }

    private fun fail(
        player: Player,
        message: String,
    ) {
        player.playSound(failSound)
        player.sendMessage(Component.text(message, NamedTextColor.RED))
    }

    private fun blockOf(
        instance: Instance,
        position: Point,
    ) = CrateBlock(instance.uuid.toString(), position.blockX(), position.blockY(), position.blockZ())

    private fun itemName(item: ItemStack): Component =
        item.get(DataComponents.CUSTOM_NAME)
            ?: Component.text(
                item
                    .material()
                    .key()
                    .value()
                    .split('_')
                    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
                NamedTextColor.WHITE,
            )

    private fun formatChance(percent: Double): String = "%.2f%%".format(percent)

    private fun save() = storage.save { Json.encodeToString(crates.values.toList()) }

    /** First boot: carry over the old hardcoded vote loot table so voting keeps working. */
    private fun seedVoteCrate() {
        val crate = Crate("vote", "Vote Crate")
        listOf(
            Triple(Material.DIAMOND_BLOCK, 64, 2.0),
            Triple(Material.IRON_BLOCK, 64, 3.0),
            Triple(Material.GOLD_BLOCK, 64, 3.0),
            Triple(Material.DRAGON_BREATH, 32, 2.0),
            Triple(Material.DIAMOND, 64, 10.0),
            Triple(Material.IRON_INGOT, 64, 10.0),
            Triple(Material.GOLD_INGOT, 64, 10.0),
            Triple(Material.COOKED_BEEF, 64, 20.0),
            Triple(Material.GUNPOWDER, 64, 10.0),
            Triple(Material.IRON_INGOT, 32, 10.0),
            Triple(Material.GOLD_INGOT, 32, 10.0),
            Triple(Material.DIAMOND, 32, 10.0),
        ).forEach { (material, amount, weight) -> crate.rewards += CrateReward.of(ItemStack.of(material, amount), weight) }
        crates[crate.id] = crate
        save()
    }

    private const val MAX_REWARDS = 54
    private const val MIN_WEIGHT = 0.001
    private const val MAX_WEIGHT = 1_000_000.0
}
