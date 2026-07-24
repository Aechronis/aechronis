package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.potion.PotionEffect
import net.minestom.server.registry.RegistryKey
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EnvironmentalDamage {
    private val fireContactTicks = ConcurrentHashMap<UUID, Int>()
    private val IN_FIRE: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:in_fire")
    private val ON_FIRE: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:on_fire")
    private val DROWN: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:drown")

    fun init() {
        val timeStart = System.currentTimeMillis()
        MinecraftServer
            .getSchedulerManager()
            .buildTask(::tick)
            .repeat(TaskSchedule.tick(1))
            .schedule()
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, ::removePlayer)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::removePlayer)
        println("├─ Environmental damage enabled in ${System.currentTimeMillis() - timeStart}ms")
    }

    private fun tick() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            tickPlayer(player)
        }
    }

    internal fun tickPlayer(player: Player) {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) {
            reset(player)
            return
        }
        if (Vanilla.config.fireDamageEnabled) tickFire(player) else fireContactTicks.remove(player.uuid)
        if (Vanilla.config.drowningEnabled) tickDrowning(player)
    }

    private fun tickFire(player: Player) {
        if (isInWaterOrBubbleColumn(player)) {
            player.fireTicks = 0
            fireContactTicks.remove(player.uuid)
            return
        }

        if (isInFire(player)) {
            player.fireTicks = maxOf(player.fireTicks, Vanilla.config.fireTicks)
            if (player.hasEffect(PotionEffect.FIRE_RESISTANCE)) {
                fireContactTicks.remove(player.uuid)
                return
            }
            val ticks = (fireContactTicks[player.uuid] ?: 0) + 1
            fireContactTicks[player.uuid] = ticks
            if (ticks >= Vanilla.config.fireContactTicks) {
                player.damage(IN_FIRE, Vanilla.config.fireDmg)
                fireContactTicks[player.uuid] = 0
            } else {
                fireContactTicks[player.uuid] = ticks
            }
            return
        }

        fireContactTicks.remove(player.uuid)
        if (player.fireTicks > 0 && player.fireTicks % 20 == 0 && !player.hasEffect(PotionEffect.FIRE_RESISTANCE)) {
            player.damage(ON_FIRE, Vanilla.config.fireDmg)
        }
    }

    private fun tickDrowning(player: Player) {
        val metadata = player.entityMeta
        if (!isEyeInWater(player) || canBreatheUnderwater(player)) {
            metadata.airTicks = Vanilla.config.maxAirTicks
            return
        }

        val airTicks = metadata.airTicks - 1
        if (airTicks == -20) {
            metadata.airTicks = 0
            player.damage(DROWN, Vanilla.config.drowningDmg)
        } else {
            metadata.airTicks = airTicks
        }
    }

    private fun isInFire(player: Player): Boolean = blocksOccupiedBy(player).any { it === Block.FIRE || it === Block.SOUL_FIRE }

    private fun isInWaterOrBubbleColumn(player: Player): Boolean =
        blocksOccupiedBy(player).any { it === Block.WATER || it === Block.BUBBLE_COLUMN }

    private fun isEyeInWater(player: Player): Boolean {
        val instance = player.instance ?: return false
        val eye = player.position.add(0.0, player.eyeHeight, 0.0)
        return instance.getBlock(eye, Block.Getter.Condition.TYPE) === Block.WATER
    }

    private fun canBreatheUnderwater(player: Player): Boolean =
        player.hasEffect(PotionEffect.WATER_BREATHING) || player.hasEffect(PotionEffect.CONDUIT_POWER)

    private fun blocksOccupiedBy(player: Player): Sequence<Block> =
        sequence {
            val instance = player.instance ?: return@sequence
            val blocks = player.boundingBox.getBlocks(player.position)
            while (blocks.hasNext()) {
                val block = blocks.next()
                yield(instance.getBlock(block.blockX(), block.blockY(), block.blockZ(), Block.Getter.Condition.TYPE) ?: Block.AIR)
            }
        }

    private fun removePlayer(event: PlayerDeathEvent) = reset(event.player)

    private fun removePlayer(event: PlayerDisconnectEvent) = reset(event.player)

    private fun reset(player: Player) {
        fireContactTicks.remove(player.uuid)
        player.fireTicks = 0
        player.entityMeta.airTicks = Vanilla.config.maxAirTicks
    }
}
