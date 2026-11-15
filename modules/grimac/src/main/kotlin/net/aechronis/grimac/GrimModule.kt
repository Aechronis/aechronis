package net.aechronis.grimac

import ac.grim.grimac.GrimAPI
import ac.grim.grimac.platform.minestom.GrimMinestom
import com.github.retrooper.packetevents.PacketEvents
import net.aechronis.server.Permissions
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.minestom.server.entity.Player
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class GrimModule : AechronisModule {
    override val id = "grimac"
    override val dependencies = setOf("viaversion")

    private var runtime: GrimMinestom? = null

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
    }

    override fun prepareForShutdown(context: ModuleContext) {
        runtime?.quiesce()
    }

    override fun shutdown(context: ModuleContext) {
        runtime?.close()
        runtime = null
    }

    companion object {
        private const val DISABLED_PERMISSION = "grim.disabled"
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
