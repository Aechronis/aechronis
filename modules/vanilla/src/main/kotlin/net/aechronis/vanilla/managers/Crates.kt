package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.CratesListener
import net.aechronis.vanilla.objects.Crate
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object Crates {
    const val ANIMATION_STEPS = 32
    private const val MIN_ANIMATION_DELAY_MS = 45L
    private const val MAX_ANIMATION_DELAY_MS = 220L
    private const val RESULT_DISPLAY_MS = 1_200L

    val CRATE_ID_TAG: Tag<String> = Tag.String("aechronis:crate_id")

    private val definitions = linkedMapOf<String, Crate>()
    private val activeRolls = ConcurrentHashMap<UUID, Roll>()
    private val inventoryOwners = ConcurrentHashMap<Inventory, UUID>()

    private data class Roll(
        val player: Player,
        val inventory: Inventory,
        val winner: ItemStack,
        val rewardItems: List<ItemStack>,
        var step: Int = 0,
    )

    fun init() {
        val timeStart = System.currentTimeMillis()
        val configured = Vanilla.config.cratesConfig.crates
        configured.forEach(Crate::validate)
        require(configured.map { it.id }.toSet().size == configured.size) {
            "Crate ids must be unique"
        }

        definitions.clear()
        definitions.putAll(configured.associateBy { it.id })
        CratesListener.init()

        println("├─ Crates enabled in ${System.currentTimeMillis() - timeStart}ms")
    }

    fun itemFor(id: String): ItemStack {
        val crate = definitions[id] ?: error("Unknown crate: $id")
        return ItemStack
            .of(crate.material)
            .withCustomName(Component.text(crate.title, NamedTextColor.GOLD))
            .withTag(CRATE_ID_TAG, crate.id)
            .withMaxStackSize(1)
    }

    fun crateFor(item: ItemStack): Crate? {
        val id = item.getTag(CRATE_ID_TAG) ?: return null
        val crate = definitions[id] ?: return null
        return crate.takeIf { item.material() == it.material }
    }

    fun isCrateInventory(inventory: Inventory): Boolean = inventoryOwners.containsKey(inventory)

    fun openCrate(
        player: Player,
        item: ItemStack,
    ): Boolean {
        if (activeRolls.containsKey(player.uuid)) return false
        val crate = crateFor(item) ?: return false

        val winner = chooseReward(crate)
        val rewards = crate.rewards.keys.toList()
        val inventory = Inventory(InventoryType.CHEST_3_ROW, Component.text(crate.title))
        val roll = Roll(player, inventory, winner, rewards)

        for (slot in 0..8) {
            inventory.setItemStack(slot, ItemStack.of(Material.BLACK_STAINED_GLASS_PANE))
            inventory.setItemStack(18 + slot, ItemStack.of(Material.BLACK_STAINED_GLASS_PANE))
        }
        updateRewardRow(roll)

        val held = player.itemInMainHand
        if (crateFor(held)?.id != crate.id) return false
        player.setItemInMainHand(if (held.amount() == 1) ItemStack.AIR else held.withAmount(held.amount() - 1))

        activeRolls[player.uuid] = roll
        inventoryOwners[inventory] = player.uuid
        if (!player.openInventory(inventory)) {
            activeRolls.remove(player.uuid, roll)
            inventoryOwners.remove(inventory)
            player.setItemInMainHand(held)
            return false
        }

        MinecraftServer.getSchedulerManager().submitTask {
            val current = activeRolls[player.uuid]
            if (current !== roll) return@submitTask TaskSchedule.stop()

            if (roll.step >= ANIMATION_STEPS) {
                finishRoll(roll, showResult = true)
                return@submitTask TaskSchedule.stop()
            }

            updateRewardRow(roll)
            player.playSound(rollSound())
            roll.step++
            TaskSchedule.millis(animationDelay(roll.step))
        }
        return true
    }

    fun closeInventory(
        player: Player,
        inventory: Inventory,
    ) {
        val roll = activeRolls[player.uuid]
        if (roll == null) {
            inventoryOwners.remove(inventory)
            return
        }
        if (roll.inventory !== inventory) return
        finishRoll(roll, showResult = false)
    }

    fun disconnect(player: Player) {
        activeRolls[player.uuid]?.let { finishRoll(it, showResult = false) }
        inventoryOwners.entries.removeIf { it.value == player.uuid }
    }

    private fun updateRewardRow(roll: Roll) {
        val offset = roll.step % roll.rewardItems.size
        for (slot in 0..8) {
            roll.inventory.setItemStack(9 + slot, roll.rewardItems[(offset + slot) % roll.rewardItems.size])
        }
    }

    private fun finishRoll(
        roll: Roll,
        showResult: Boolean,
    ) {
        if (!activeRolls.remove(roll.player.uuid, roll)) return

        if (showResult) {
            roll.inventory.setItemStack(13, roll.winner)
            roll.player.playSound(winSound())
            MinecraftServer
                .getSchedulerManager()
                .buildTask {
                    inventoryOwners.remove(roll.inventory)
                    if (roll.player.openInventory === roll.inventory) roll.player.closeInventory()
                }.delay(TaskSchedule.millis(RESULT_DISPLAY_MS))
                .schedule()
        } else {
            inventoryOwners.remove(roll.inventory)
        }

        if (!roll.player.inventory.addItemStack(roll.winner)) {
            roll.player.dropItem(roll.winner)
        }
    }

    private fun chooseReward(crate: Crate): ItemStack {
        val total = crate.rewards.values.sum()
        var target = Random.nextDouble() * total
        for ((item, chance) in crate.rewards) {
            target -= chance
            if (target < 0.0) return item
        }
        return crate.rewards.keys.last()
    }

    private fun animationDelay(step: Int): Long {
        val progress = (step.toDouble() / ANIMATION_STEPS).coerceIn(0.0, 1.0)
        return (MIN_ANIMATION_DELAY_MS + (MAX_ANIMATION_DELAY_MS - MIN_ANIMATION_DELAY_MS) * progress).toLong()
    }

    private fun rollSound(): Sound =
        Sound.sound(
            Key.key("minecraft:block.note_block.bell"),
            Sound.Source.PLAYER,
            0.7f,
            1.5f,
        )

    private fun winSound(): Sound =
        Sound.sound(
            Key.key("minecraft:entity.player.levelup"),
            Sound.Source.PLAYER,
            0.8f,
            1.0f,
        )
}
