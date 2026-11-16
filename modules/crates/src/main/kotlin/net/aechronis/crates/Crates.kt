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
import net.minestom.server.item.component.TooltipDisplay
import net.minestom.server.tag.Tag
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

    fun init(
        directory: Path,
        defaults: List<CrateDefaults>,
    ) {
        storage.load(directory.resolve("crates.json"), crates) { contents ->
            Json.decodeFromString<List<Crate>>(contents).filter { idPattern.matches(it.id) }.associateByTo(linkedMapOf()) { it.id }
        }
        seed(defaults)
        CrateHolograms.refresh(crates.values)
        CrateKeys.init(directory.resolve("keys.json"))
    }

    fun shutdown() {
        CrateHolograms.clear()
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

    /** Left click: shows every reward with its chance, framed by glass with a key summary at the bottom. */
    fun preview(
        player: Player,
        crate: Crate,
    ) {
        if (previews.any { it === player.openInventory }) return
        val rewards = crate.rewards
        // Up to 4 rows of 7 fit inside a glass frame; bigger crates fall back to a plain 6-row grid.
        val framed = rewards.size <= 28
        val rows = if (framed) ((rewards.size + 6) / 7).coerceAtLeast(1) + 2 else 6
        val inventory = Inventory(InventoryType.valueOf("CHEST_${rows}_ROW"), Component.text(crate.title))
        val slots = if (framed) framedSlots(rewards.size) else rewards.indices.toList()
        if (framed) {
            for (slot in 0 until rows * 9) inventory.setItemStack(slot, filler)
            inventory.setItemStack(rows * 9 - 5, keyInfo(CrateKeys.count(player.uuid, crate.id)))
        }
        rewards.forEachIndexed { index, reward ->
            val lore = reward.stack.get(DataComponents.LORE).orEmpty()
            val chance =
                Component
                    .text("Chance: ", NamedTextColor.GRAY)
                    .append(Component.text(formatChance(crate.chance(reward)), NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false)
            val extra = if (lore.isEmpty()) listOf(chance) else listOf(Component.empty(), chance)
            inventory.setItemStack(slots[index], reward.stack.with(DataComponents.LORE, lore + extra))
        }
        previews += inventory
        if (!player.openInventory(inventory)) previews -= inventory
    }

    /** Inner slots of a glass-framed chest, filled row by row with the last partial row centred. */
    private fun framedSlots(count: Int): List<Int> =
        (0 until count).map { index ->
            val row = index / 7
            val inRow = minOf(7, count - row * 7)
            (row + 1) * 9 + 1 + (7 - inRow) / 2 + index % 7
        }

    private val filler =
        ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).with(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(true, setOf()))

    private fun keyInfo(keys: Int): ItemStack {
        fun line(
            text: String,
            color: NamedTextColor,
        ) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)
        return ItemStack
            .builder(Material.TRIPWIRE_HOOK)
            .set(
                DataComponents.CUSTOM_NAME,
                line("Your keys: $keys", if (keys > 0) NamedTextColor.GREEN else NamedTextColor.RED).decorate(TextDecoration.BOLD),
            ).set(
                DataComponents.LORE,
                listOf(
                    line("Right click the crate to open one.", NamedTextColor.GRAY),
                    line("Shift + right click to open them all.", NamedTextColor.GRAY),
                ),
            ).build()
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

    private fun save() {
        storage.save { Json.encodeToString(crates.values.toList()) }
        CrateHolograms.refresh(crates.values)
    }

    /** Creates configured crates that do not exist yet; existing crates keep their in-game edits. */
    private fun seed(defaults: List<CrateDefaults>) {
        if (!storage.canSave) return
        val missing = defaults.filter { it.id !in crates && idPattern.matches(it.id) }
        if (missing.isEmpty()) return
        missing.forEach { default ->
            crates[default.id] =
                Crate(default.id, default.title, default.rewards.mapTo(mutableListOf()) { (item, weight) -> CrateReward.of(item, weight) })
        }
        save()
    }

    private const val MAX_REWARDS = 54
    private const val MIN_WEIGHT = 0.001
    private const val MAX_WEIGHT = 1_000_000.0
}
