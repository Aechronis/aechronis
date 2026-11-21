package net.aechronis.grimac

import ac.grim.grimac.GrimAPI
import ac.grim.grimac.platform.minestom.GrimMinestom
import com.github.retrooper.packetevents.PacketEvents
import net.aechronis.server.Permissions
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class GrimModule : AechronisModule {
    override val id = "grimac"
    override val dependencies = setOf("viaversion")

    private var runtime: GrimMinestom? = null
    private var passengerTask: Task? = null
    private var passengerDisabler: AutoCloseable? = null

    // Players Grim is currently off for, and until when (epoch ms) a non-driving boat rider stays exempt.
    private val passengerExempt: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val passengerUntil = ConcurrentHashMap<UUID, Long>()

    override fun initialize(context: ModuleContext) {
        check(runtime == null) { "Grim is already initialized" }
        val grim =
            GrimMinestom
                .builder(Path.of("grimac"))
                .permissions { sender, permission, _ ->
                    sender !is Player ||
                        Permissions.hasPermission(sender.uuid, permission) ||
                        (permission == DISABLED_PERMISSION && disablers.any { it(sender.uuid) })
                }.commands(ModuleCommands::register, ModuleCommands::unregister)
                .permissionRegistration { permission, _ -> ModulePermissions.register(permission) }
                .build()
        runtime = grim
        grim.start()
        passengerDisabler = registerDisabler(passengerExempt::contains)
        passengerTask =
            ModuleScheduler
                .buildTask { updatePassengerExemptions() }
                .repeat(TaskSchedule.tick(1))
                .schedule()
    }

    override fun prepareForShutdown(context: ModuleContext) {
        runtime?.quiesce()
    }

    override fun shutdown(context: ModuleContext) {
        passengerTask?.cancel()
        passengerTask = null
        passengerDisabler?.close()
        passengerDisabler = null
        passengerExempt.clear()
        passengerUntil.clear()
        runtime?.close()
        runtime = null
    }

    /**
     * Grim only tracks the driver's vehicle moves, so a non-driving passenger is set back to where they boarded when
     * they dismount. Keep Grim off for them while riding and briefly after.
     */
    private fun updatePassengerExemptions() {
        val now = System.currentTimeMillis()
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            val vehicle = player.vehicle
            if (vehicle != null && vehicle.passengers.firstOrNull() !== player) {
                passengerUntil[player.uuid] = now + PASSENGER_GRACE_MS
            }
            val until = passengerUntil[player.uuid] ?: return@forEach
            if (now < until) {
                if (passengerExempt.add(player.uuid)) refresh(player)
            } else {
                passengerUntil.remove(player.uuid)
                if (passengerExempt.remove(player.uuid)) refresh(player)
            }
        }
        // Drop players who left mid-ride.
        val connections = MinecraftServer.getConnectionManager()
        passengerUntil.keys.removeIf { connections.getOnlinePlayerByUuid(it) == null }
        passengerExempt.removeIf { connections.getOnlinePlayerByUuid(it) == null }
    }

    companion object {
        private const val DISABLED_PERMISSION = "grim.disabled"
        private const val PASSENGER_GRACE_MS = 2000L
        private val disablers = CopyOnWriteArrayList<(UUID) -> Boolean>()

        /** Turns Grim off for players matching [disabled]. Dependents close the handle when they shut down. */
        fun registerDisabler(disabled: (UUID) -> Boolean): AutoCloseable {
            disablers += disabled
            return AutoCloseable { disablers -= disabled }
        }

        /** Applies a disabler change now instead of at Grim's next periodic permission refresh. */
        fun refresh(player: Player) {
            // Grim's UUID lookup relies on PacketEvents seeing login success, which happens before the
            // Minestom transport attaches, so it never finds anyone. Resolve through the connection instead.
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
            GrimAPI.INSTANCE.playerDataManager
                .getPlayer(user)
                ?.updatePermissions()
        }
    }
}
