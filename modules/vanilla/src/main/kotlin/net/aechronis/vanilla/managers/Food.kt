package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.FoodEatListener
import net.aechronis.vanilla.listeners.FoodMovementListener
import net.aechronis.vanilla.objects.FoodItem
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.item.Material
import net.minestom.server.registry.RegistryKey
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Food {
    val foodItems = mutableMapOf<Material, FoodItem>()
    private val exhaustion = ConcurrentHashMap<UUID, Float>()
    private val STARVE: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:starve")

    fun init() {
        val timeStart = System.currentTimeMillis()
        val config = Vanilla.config!!
        for (item in config.foodItems) {
            foodItems[item.material] = item
        }
        FoodEatListener.init()
        FoodMovementListener.init()
        MinecraftServer
            .getSchedulerManager()
            .buildTask(::tick)
            .repeat(TaskSchedule.seconds(config.foodTickSeconds))
            .schedule()
        val timeEnd = System.currentTimeMillis()
        println("Food enabled in ${timeEnd - timeStart}ms")
    }

    fun onEat(
        player: Player,
        item: FoodItem,
    ) {
        player.food = (player.food + item.hunger).coerceAtMost(20)
        player.foodSaturation = (player.foodSaturation + item.saturation).coerceAtMost(player.food.toFloat())
    }

    fun addExhaustion(
        player: Player,
        amount: Float,
    ) {
        var current = (exhaustion[player.uuid] ?: 0f) + amount
        while (current >= 4.0f) {
            current -= 4.0f
            when {
                player.foodSaturation > 0f -> player.foodSaturation = (player.foodSaturation - 1f).coerceAtLeast(0f)
                player.food > 0 -> player.food -= 1
            }
        }
        exhaustion[player.uuid] = current
    }

    private fun tick() {
        val config = Vanilla.config!!
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) continue
            applyHealing(player, config.foodHealAmount, config.foodHealSaturationCost)
            applyStarvation(player, config.foodStarvationDamage)
        }
    }

    private fun applyHealing(
        player: Player,
        healAmount: Float,
        saturationCost: Float,
    ) {
        if (player.food < 18 || player.health >= player.getAttributeValue(Attribute.MAX_HEALTH)) return
        player.health = (player.health + healAmount).coerceAtMost(player.getAttributeValue(Attribute.MAX_HEALTH).toFloat())
        if (player.foodSaturation >= saturationCost) {
            player.foodSaturation -= saturationCost
        } else {
            player.foodSaturation = 0f
            if (player.food > 0) player.food -= 1
        }
    }

    private fun applyStarvation(
        player: Player,
        damage: Float,
    ) {
        if (player.food > 0) return
        if (player.health <= 1f) return
        player.damage(STARVE, damage)
    }
}
