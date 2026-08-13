package net.aechronis.guard

import net.aechronis.guard.commands.GuardCommand
import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.listeners.BlockBreakListener
import net.aechronis.guard.listeners.BlockInteractListener
import net.aechronis.guard.listeners.BlockPlaceListener
import net.aechronis.guard.listeners.DamageListener
import net.aechronis.guard.listeners.MoveListener
import net.aechronis.guard.listeners.TeleportListener
import net.aechronis.guard.objects.ZonePolicy
import net.aechronis.guard.storage.ZoneRegistry
import net.aechronis.guard.storage.ZoneStorage
import net.aechronis.utils.hasPermission
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.Instance
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object Guard {
    val eventNode: EventNode<Event> = EventNode.all("guard").setPriority(-1000)

    private val initialized = AtomicBoolean()
    private val storage = ZoneStorage()
    private lateinit var config: GuardConfig
    private lateinit var policy: ZonePolicy
    private lateinit var registry: ZoneRegistry
    private val bypassOverrides = ConcurrentHashMap<UUID, Boolean>()

    fun init(config: GuardConfig = GuardConfig()) {
        check(initialized.compareAndSet(false, true)) { "Guard is already initialized" }
        this.config = config
        registry = ZoneRegistry()
        policy =
            ZonePolicy(
                config.defaultFlags
                    .mapNotNull { (name, value) ->
                        (value as? BooleanFlagValue)?.let { name to it }
                    }.toMap(),
            )

        runCatching { registry.replaceAll(storage.load(config.dataPath)) }
            .onFailure { println("Guard could not load zones from ${config.dataPath}: $it") }

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        eventNode.addListener(PlayerBlockPlaceEvent::class.java, BlockPlaceListener::handle)
        eventNode.addListener(PlayerBlockBreakEvent::class.java, BlockBreakListener::handle)
        eventNode.addListener(PlayerBlockInteractEvent::class.java, BlockInteractListener::handle)
        eventNode.addListener(PlayerMoveEvent::class.java, MoveListener::handle)
        eventNode.addListener(EntityTeleportEvent::class.java, TeleportListener::handle)
        eventNode.addListener(EntityDamageEvent::class.java, DamageListener::handle)
        MinecraftServer
            .getCommandManager()
            .register(GuardCommand(config.adminPermission, config.bypassPermission))

        Runtime.getRuntime().addShutdownHook(Thread(::save, "guard-zone-save"))
    }

    fun zones(): ZoneRegistry {
        check(initialized.get()) { "Guard is not initialized" }
        return registry
    }

    fun check(
        player: Player,
        instance: Instance?,
        x: Int,
        y: Int,
        z: Int,
        flag: FlagName,
        deny: () -> Unit,
    ) {
        if (instance == null || isBypassing(player)) return
        val zone = registry.find(instance.uuid, x, y, z)
        if (!policy.allows(zone, flag)) {
            deny()
            config.onDenied(player, flag)
        }
    }

    fun isBypassing(player: Player): Boolean =
        config.bypass(player) ||
            (player.hasPermission(config.bypassPermission) && bypassOverrides[player.uuid] != false)

    fun toggleBypass(player: Player): Boolean {
        val enabled = !isBypassing(player)
        bypassOverrides[player.uuid] = enabled
        return enabled
    }

    fun save() {
        if (!initialized.get()) return
        runCatching { storage.save(config.dataPath, registry.all()) }
            .onFailure { println("Guard could not save zones to ${config.dataPath}: $it") }
    }
}
