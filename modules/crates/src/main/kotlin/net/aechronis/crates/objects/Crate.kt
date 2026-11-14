package net.aechronis.crates.objects

import kotlinx.serialization.Serializable
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.MinecraftServer
import net.minestom.server.item.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

@Serializable
data class CrateBlock(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

/** A loot table entry. [item] is the item's NBT, Base64 encoded, so custom names and enchantments survive. */
@Serializable
class CrateReward(
    val item: String,
    val weight: Double,
) {
    val stack: ItemStack by lazy {
        ItemStack.fromItemNBT(
            BinaryTagIO.reader().readNameless(ByteArrayInputStream(Base64.getDecoder().decode(item))),
            MinecraftServer.process(),
        )
    }

    companion object {
        fun of(
            stack: ItemStack,
            weight: Double,
        ): CrateReward {
            val bytes = ByteArrayOutputStream()
            BinaryTagIO.writer().writeNameless(stack.toItemNBT(MinecraftServer.process()), bytes)
            return CrateReward(Base64.getEncoder().encodeToString(bytes.toByteArray()), weight)
        }
    }
}

@Serializable
data class Crate(
    val id: String,
    var title: String,
    val rewards: MutableList<CrateReward> = mutableListOf(),
    val blocks: MutableList<CrateBlock> = mutableListOf(),
) {
    val totalWeight: Double get() = rewards.sumOf { it.weight }

    fun chance(reward: CrateReward): Double = reward.weight / totalWeight * 100.0

    fun roll(): ItemStack? {
        if (rewards.isEmpty()) return null
        var target = Math.random() * totalWeight
        for (reward in rewards) {
            target -= reward.weight
            if (target < 0.0) return reward.stack
        }
        return rewards.last().stack
    }
}
