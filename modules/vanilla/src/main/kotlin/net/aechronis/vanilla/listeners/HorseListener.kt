package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Horses
import net.aechronis.vanilla.managers.Items
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.entity.EntityDeathEvent
import net.minestom.server.event.instance.InstanceChunkLoadEvent
import net.minestom.server.event.instance.InstanceChunkUnloadEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.client.play.ClientEntityActionPacket
import kotlin.math.ceil

object HorseListener {
    private val CONSUMED = setOf(Material.GOLDEN_CARROT, Material.GOLDEN_APPLE, Material.SADDLE)

    fun onUseEgg(event: PlayerUseItemOnBlockEvent) {
        val player = event.player
        val held = player.getItemInHand(event.hand)
        if (held.material() != Material.HORSE_SPAWN_EGG || player.gameMode == GameMode.SPECTATOR) return

        val instance = player.instance ?: return
        val target = event.position.add(event.blockFace.toDirection().vec())
        if (instance.getBlock(target).isSolid) return

        val yaw = player.position.yaw + 180f
        Horses.spawnFromEgg(instance, Pos(target.blockX() + 0.5, target.blockY().toDouble(), target.blockZ() + 0.5, yaw, 0f))
        if (player.gameMode != GameMode.CREATIVE) player.setItemInHand(event.hand, held.consume(1))
    }

    fun onInteract(event: PlayerEntityInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val horse = event.target as? EntityCreature ?: return
        if (!Horses.isHorse(horse)) return
        val player = event.player
        if (player.gameMode == GameMode.SPECTATOR) return

        val held = player.itemInMainHand
        val used =
            when (held.material()) {
                Material.GOLDEN_CARROT, Material.GOLDEN_APPLE -> Horses.feed(horse, held)
                Material.SADDLE -> Horses.saddle(horse)
                Material.SHEARS -> unsaddle(player, horse) || mount(player, horse)
                else -> mount(player, horse)
            }
        if (used && held.material() in CONSUMED && player.gameMode != GameMode.CREATIVE) {
            player.itemInMainHand = held.consume(1)
        }
    }

    private fun unsaddle(
        player: Player,
        horse: EntityCreature,
    ): Boolean {
        if (!Horses.unsaddle(horse)) return false
        val saddle = ItemStack.of(Material.SADDLE)
        if (!player.inventory.addItemStack(saddle)) horse.instance?.let { Items.spawn(it, horse.position, saddle) }
        return true
    }

    private fun mount(
        player: Player,
        horse: EntityCreature,
    ): Boolean {
        // Sneaking leaves room to interact with a horse without getting on it.
        if (player.isSneaking) return false
        when (Horses.mount(player, horse)) {
            Horses.MountResult.MOUNTED -> return true
            Horses.MountResult.OCCUPIED -> {}
            // Unsaddled horses show their stats instead.
            Horses.MountResult.NO_SADDLE -> Horses.openStats(player, horse)
            Horses.MountResult.COOLDOWN -> {
                val cooldownSeconds = ceil(Horses.mountCooldownMillis(player) / 1000.0).toInt()
                player.sendMessage(Component.text("You can mount a horse again in ${cooldownSeconds}s.", NamedTextColor.RED))
            }
        }
        return false
    }

    // Riders' clients ask the server for the horse's inventory instead of opening their own.
    fun onPacket(event: PlayerPacketEvent) {
        val packet = event.packet as? ClientEntityActionPacket ?: return
        if (packet.action() != ClientEntityActionPacket.Action.OPEN_HORSE_INVENTORY) return
        val horse = event.player.vehicle as? EntityCreature ?: return
        if (Horses.isHorse(horse)) Horses.openStats(event.player, horse)
    }

    fun onPreClick(event: InventoryPreClickEvent) {
        if (Horses.isStatsInventory(event.inventory)) event.isCancelled = true
    }

    fun onInput(event: PlayerInputEvent) {
        if (!event.hasPressedShiftKey()) return
        if (Horses.isHorse(event.player.vehicle)) Horses.dismount(event.player)
    }

    fun onDamage(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val player = event.entity as? Player ?: return
        Horses.blockMounting(player)
    }

    fun onHorseDeath(event: EntityDeathEvent) {
        val horse = event.entity as? EntityCreature ?: return
        Horses.onDeath(horse)
    }

    fun onPlayerDeath(event: PlayerDeathEvent) {
        Horses.dismount(event.player)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        Horses.dismount(event.player)
        Horses.forget(event.player)
    }

    fun onChunkLoad(event: InstanceChunkLoadEvent) {
        Horses.onChunkLoad(event.instance, event.chunkX, event.chunkZ)
    }

    fun onChunkUnload(event: InstanceChunkUnloadEvent) {
        Horses.onChunkUnload(event.instance, event.chunkX, event.chunkZ)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, HorseListener::onUseEgg)
        Vanilla.eventNode.addListener(PlayerEntityInteractEvent::class.java, HorseListener::onInteract)
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, HorseListener::onInput)
        Vanilla.eventNode.addListener(PlayerPacketEvent::class.java, HorseListener::onPacket)
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, HorseListener::onPreClick)
        Vanilla.eventNode.addListener(EntityDamageEvent::class.java, HorseListener::onDamage)
        Vanilla.eventNode.addListener(EntityDeathEvent::class.java, HorseListener::onHorseDeath)
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, HorseListener::onPlayerDeath)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, HorseListener::onDisconnect)
        Vanilla.eventNode.addListener(InstanceChunkLoadEvent::class.java, HorseListener::onChunkLoad)
        Vanilla.eventNode.addListener(InstanceChunkUnloadEvent::class.java, HorseListener::onChunkUnload)
    }
}
