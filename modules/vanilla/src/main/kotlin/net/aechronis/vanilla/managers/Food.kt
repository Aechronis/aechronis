package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.FoodListener
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
    private val tickTimer = ConcurrentHashMap<UUID, Int>()
    private val STARVE: RegistryKey<DamageType> = DamageType.STARVE

    fun init() {
        val config = Vanilla.config
        for (item in config.foodConfig.foodItems) {
            foodItems[item.material] = item
        }
        FoodListener.init()
        ModuleScheduler
            .buildTask(::tick)
            .repeat(TaskSchedule.tick(1))
            .schedule()
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

    internal fun removePlayer(player: Player) {
        exhaustion.remove(player.uuid)
        tickTimer.remove(player.uuid)
    }

    // Vanilla FoodData.tick, run every game tick.
    private fun tick() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) continue
            if (player.isDead) {
                removePlayer(player)
                continue
            }
            val food = player.food
            val starving = food <= 0
            // idle players (not hurt, not starving) clear their timer; skips the attribute read below 18 food
            val maxHealth = if (food >= 18) player.getAttributeValue(Attribute.MAX_HEALTH).toFloat() else 0f
            val hurt = food >= 18 && player.health < maxHealth
            if (!hurt && !starving) {
                tickTimer.remove(player.uuid)
                continue
            }
            var timer = (tickTimer[player.uuid] ?: 0) + 1
            when {
                hurt && food >= 20 && player.foodSaturation > 0f -> {
                    if (timer >= 10) {
                        val spent = minOf(player.foodSaturation, 6f)
                        player.health = (player.health + spent / 6f).coerceAtMost(maxHealth)
                        addExhaustion(player, spent)
                        timer = 0
                    }
                }
                hurt -> {
                    if (timer >= 80) {
                        player.health = (player.health + 1f).coerceAtMost(maxHealth)
                        addExhaustion(player, 6f)
                        timer = 0
                    }
                }
                else -> {
                    if (timer >= 80) {
                        if (player.health > 1f) player.damage(STARVE, 1f)
                        timer = 0
                    }
                }
            }
            tickTimer[player.uuid] = timer
        }
    }
}
