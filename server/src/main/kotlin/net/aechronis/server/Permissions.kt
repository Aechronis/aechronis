package net.aechronis.server

import net.minestom.server.entity.Player
import java.util.UUID

fun interface PermissionProvider {
    fun hasPermission(
        uuid: UUID,
        permission: String,
    ): Boolean
}

/** Shared permission boundary; the active module owns the provider implementation. */
object Permissions {
    private var registration: Registration? = null

    /** Close before disabling the provider. Closing also waits for any in-flight checks. */
    @Synchronized
    fun registerProvider(provider: PermissionProvider): AutoCloseable {
        check(registration == null) { "A permission provider is already registered" }
        val installed = Registration(provider)
        registration = installed
        return AutoCloseable {
            synchronized(this) {
                if (registration === installed) registration = null
            }
        }
    }

    @Synchronized
    fun hasPermission(
        uuid: UUID,
        permission: String?,
    ): Boolean {
        if (permission == null) return true
        if (System.getProperty("aechronis.dangerously-enable-all-permissions").toBoolean()) return true
        return try {
            registration?.provider?.hasPermission(uuid, permission) == true
        } catch (_: Exception) {
            false
        }
    }

    private class Registration(
        val provider: PermissionProvider,
    )
}

/** Missing providers/users deny access; a null permission does not restrict access. */
fun Player.hasPermission(permission: String?): Boolean = Permissions.hasPermission(uuid, permission)

/** Checks permissions without requiring an online player. */
fun UUID.hasPermission(permission: String?): Boolean = Permissions.hasPermission(this, permission)
