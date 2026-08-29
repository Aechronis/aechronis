package net.aechronis.guard

import net.aechronis.combat.events.ExplosionBlockDamageEvent
import net.aechronis.combat.events.VehicleSpawnEvent
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
import net.aechronis.server.modules.ModuleEvents
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object Guard {
    lateinit var eventNode: EventNode<Event>
        private set

    private val initialized = AtomicBoolean()
    private val storage = ZoneStorage()
    private lateinit var config: GuardConfig
    private lateinit var policy: ZonePolicy
    private lateinit var registry: ZoneRegistry
    private val bypassOverrides = ConcurrentHashMap<UUID, Boolean>()
    private val saveLock = Any()
    private var command: GuardCommand? = null

    @Synchronized
    fun init(config: GuardConfig = GuardConfig()) {
        check(initialized.compareAndSet(false, true)) { "Guard is already initialized" }
        try {
            this.config = config
            eventNode = EventNode.all("guard").setPriority(-1000)
            registry = ZoneRegistry()
            policy =
                ZonePolicy(
                    config.defaultFlags
                        .mapNotNull { (name, value) ->
                            (value as? BooleanFlagValue)?.let { name to it }
                        }.toMap(),
                )

            runCatching {
                registry.replaceAll(storage.load(config.dataPath))
            }.onFailure { println("Guard could not load zones from ${config.dataPath}: $it") }

            eventNode.addListener(PlayerBlockPlaceEvent::class.java, BlockPlaceListener::handle)
            eventNode.addListener(PlayerBlockBreakEvent::class.java, BlockBreakListener::handle)
            eventNode.addListener(PlayerBlockInteractEvent::class.java, BlockInteractListener::handle)
            eventNode.addListener(PlayerMoveEvent::class.java, MoveListener::handle)
            eventNode.addListener(EntityTeleportEvent::class.java, TeleportListener::handle)
            eventNode.addListener(EntityDamageEvent::class.java, DamageListener::handle)
            eventNode.addListener(ExplosionBlockDamageEvent::class.java, ::handleExplosion)
            eventNode.addListener(VehicleSpawnEvent::class.java, ::handleVehicleSpawn)
            ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
            command = GuardCommand(config.adminPermission, config.bypassPermission)
            MinecraftServer.getCommandManager().register(command!!)
        } catch (error: Throwable) {
            shutdown()
            throw error
        }
    }

    fun zones(): ZoneRegistry {
        check(initialized.get()) { "Guard is not initialized" }
        return registry
    }

    fun check(
        player: Player,
        x: Int,
        y: Int,
        z: Int,
        flag: FlagName,
        deny: () -> Unit,
    ) {
        check(x, y, z, flag, player, deny)
    }

    fun check(
        x: Int,
        y: Int,
        z: Int,
        flag: FlagName,
        actor: Player? = null,
        deny: () -> Unit,
    ) {
        if (actor?.let(::isBypassing) == true) return
        val zone = registry.find(x, y, z)
        if (!policy.allows(zone, flag)) {
            deny()
            actor?.let { config.onDenied(it, flag) }
        }
    }

    private fun handleExplosion(event: ExplosionBlockDamageEvent) {
        if (event.isCancelled) return

        val positions =
            buildSet {
                add(Triple(event.position.blockX(), event.position.blockY(), event.position.blockZ()))
                event.changes.forEach { change ->
                    add(Triple(change.position.blockX(), change.position.blockY(), change.position.blockZ()))
                }
            }
        for ((x, y, z) in positions) {
            var denied = false
            check(x, y, z, FlagName.EXPLOSION, event.sourcePlayer) { denied = true }
            if (denied) {
                event.isCancelled = true
                return
            }
        }
    }

    private fun handleVehicleSpawn(event: VehicleSpawnEvent) {
        if (event.isCancelled) return
        check(
            event.position.blockX(),
            event.position.blockY(),
            event.position.blockZ(),
            FlagName.VEHICLE_SPAWN,
            event.player,
        ) {
            event.isCancelled = true
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
        synchronized(saveLock) {
            storage.save(config.dataPath, registry.all())
        }
    }

    @Synchronized
    fun shutdown() {
        if (!initialized.get()) return

        if (this::eventNode.isInitialized) {
            MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
        }
        command?.let { registered ->
            MinecraftServer.getCommandManager().unregister(registered)
            command = null
        }
        save()
        bypassOverrides.clear()
        TeleportListener.reset()
        if (this::registry.isInitialized) registry.replaceAll(emptyList())
        initialized.set(false)
    }
}
