package net.aechronis.grimac

import ac.grim.grimac.platform.minestom.GrimMinestom
import net.aechronis.server.Permissions
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.minestom.server.entity.Player
import java.nio.file.Path

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
                    sender !is Player || Permissions.hasPermission(sender.uuid, permission)
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
}
