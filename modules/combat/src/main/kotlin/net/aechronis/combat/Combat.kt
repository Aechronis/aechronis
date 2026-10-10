package net.aechronis.combat

import net.aechronis.combat.commands.CombatAdminCommand
import net.aechronis.combat.commands.HatsCommand
import net.aechronis.combat.listeners.AimingListener
import net.aechronis.combat.listeners.AmmoInventoryListener
import net.aechronis.combat.listeners.ArmorProtectionListener
import net.aechronis.combat.listeners.CooldownResetListener
import net.aechronis.combat.listeners.FireListener
import net.aechronis.combat.listeners.GrenadeListener
import net.aechronis.combat.listeners.HatListener
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.listeners.LagCompensationListener
import net.aechronis.combat.listeners.MannequinDamageListener
import net.aechronis.combat.listeners.MeleeListener
import net.aechronis.combat.listeners.PlayerDeathListener
import net.aechronis.combat.listeners.PlayerDisconnectListener
import net.aechronis.combat.listeners.ReloadListener
import net.aechronis.combat.listeners.RespawnProtectionListener
import net.aechronis.combat.listeners.VehicleListener
import net.aechronis.combat.listeners.WeaponLoreListener
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Projectile
import net.aechronis.combat.objects.TurretScope
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.combat.tasks.ActionBarManager
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.tasks.PlayerPositionManager
import net.aechronis.combat.tasks.ProjectileTickManager
import net.aechronis.combat.tasks.VehicleTickManager
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.GunAnimation
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.bypassesCombatDamageImmunity
import net.aechronis.combat.utils.combatDamageKind
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.event.EventNode
import java.nio.file.Path

object Combat {
    private var initialized = false
    private var catalogueVehiclesNeedRestore = false

    var config: CombatConfig = CombatConfig()
        private set

    // event nodes for listeners
    val lowPriorityEventNode = EventNode.all("combat-low-priority").setPriority(999)
    val eventNode = EventNode.all("combat")
    val highPriorityEventNode = EventNode.all("combat-high-priority").setPriority(-999)

    internal val playerStates = CombatPlayerStates()

    val entityLastDamageTime = HashMap<LivingEntity, Long>()
    private val activeDamage = HashMap<LivingEntity, Damage>()

    private const val DAMAGE_IMMUNITY_MS = 500L
    internal const val RESPAWN_PROTECTION_MS = 5_000L

    internal fun grantRespawnProtection(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ) {
        playerStates.getOrCreate(player).respawnProtectionExpiresAt = now + RESPAWN_PROTECTION_MS
    }

    internal fun isRespawnProtected(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val state = playerStates[player] ?: return false
        val expiresAt = state.respawnProtectionExpiresAt ?: return false
        if (now < expiresAt) return true

        state.respawnProtectionExpiresAt = null
        return false
    }

    internal fun revokeRespawnProtection(player: Player) {
        playerStates[player]?.respawnProtectionExpiresAt = null
    }

    fun canDamage(
        entity: LivingEntity,
        now: Long = System.currentTimeMillis(),
    ): Boolean = now - (entityLastDamageTime[entity] ?: 0L) >= DAMAGE_IMMUNITY_MS

    fun recordDamage(
        entity: LivingEntity,
        now: Long = System.currentTimeMillis(),
    ) {
        entityLastDamageTime[entity] = now
    }

    /** Returns Combat's classification for damage created by this module, if any. */
    fun damageKind(damage: Damage): CombatDamageKind? = damage.combatDamageKind()

    fun applyDamage(
        entity: LivingEntity,
        damage: Damage,
        now: Long = System.currentTimeMillis(),
    ): Boolean = applyDamage(entity, damage, now, useDamageImmunity = true)

    fun applyDamageWithoutImmunity(
        entity: LivingEntity,
        damage: Damage,
        now: Long = System.currentTimeMillis(),
    ): Boolean = applyDamage(entity, damage, now, useDamageImmunity = false)

    private fun applyDamage(
        entity: LivingEntity,
        damage: Damage,
        now: Long,
        useDamageImmunity: Boolean,
    ): Boolean {
        if (useDamageImmunity && !damage.bypassesCombatDamageImmunity() && !canDamage(entity, now)) return false

        val previousDamageTime = if (useDamageImmunity) entityLastDamageTime.put(entity, now) else null
        val previousActiveDamage = activeDamage.put(entity, damage)
        val damaged =
            try {
                entity.damage(damage)
            } finally {
                if (previousActiveDamage == null) {
                    activeDamage.remove(entity)
                } else {
                    activeDamage[entity] = previousActiveDamage
                }
            }
        if (damaged) {
            revokeRespawnProtectionAfterSuccessfulPlayerDamage(entity, damage)
            return true
        }

        if (useDamageImmunity) {
            if (previousDamageTime == null) {
                entityLastDamageTime.remove(entity)
            } else {
                entityLastDamageTime[entity] = previousDamageTime
            }
        }
        return false
    }

    internal fun revokeRespawnProtectionAfterSuccessfulPlayerDamage(
        victim: LivingEntity,
        damage: Damage,
    ) {
        if (damage.amount <= 0f) return
        val attacker = damage.attacker as? Player ?: return
        if (victim !is Player || victim === attacker) return
        revokeRespawnProtection(attacker)
    }

    internal fun activeDamage(entity: LivingEntity): Damage? = activeDamage[entity]

    @Synchronized
    fun initialize(config: CombatConfig = CombatConfig()) {
        if (initialized) return
        initialized = true
        this.config = config

        try {
            // initialize storage
            measure("Storage") {
                HatCollection.initialize()
                BlockRestoreManager.initialize()
            }

            // register listeners
            measure("Listeners") {
                GunAnimation.init()
                AimingListener.init()
                AmmoInventoryListener.init()
                ReloadListener.init()
                FireListener.init()
                GrenadeListener.init()
                MeleeListener.init()
                PlayerDeathListener.init()
                PlayerDisconnectListener.init()
                CooldownResetListener.init()
                ArmorProtectionListener.init()
                RespawnProtectionListener.init()
                MannequinDamageListener.init()
                VehicleListener.init()
                KeyPressListener.init()
                HatListener.init()
                LagCompensationListener.init()
                WeaponLoreListener.init()
                ModelManager.initListeners()

                val globalEventHandler = MinecraftServer.getGlobalEventHandler()
                ModuleEvents.addChild(globalEventHandler, lowPriorityEventNode)
                ModuleEvents.addChild(globalEventHandler, eventNode)
                ModuleEvents.addChild(globalEventHandler, highPriorityEventNode)
            }

            // register commands
            measure("Commands") {
                ModuleCommands
                    .register(CombatAdminCommand())
                ModuleCommands
                    .register(HatsCommand())
            }

            // run background schedulers/tasks
            measure("Schedulers") {
                ModelManager.start()
                PlayerPositionManager.start()
                ActionBarManager.start()
                VehicleTickManager.start()
                ProjectileTickManager.start()
            }
        } catch (exception: Throwable) {
            try {
                shutdown()
            } catch (shutdownException: Throwable) {
                exception.addSuppressed(shutdownException)
            }
            throw exception
        }
    }

    internal fun initializeVehiclePersistence(context: ModuleContext) {
        val vehiclePath = Path.of("combat", "vehicles.json")
        VehiclePersistence.initialize(vehiclePath, context.instance)
        catalogueVehiclesNeedRestore = false
    }

    /** Release old definitions while keeping Combat's listeners, storage and dependency graph alive. */
    fun prepareItemCatalogueReload() {
        if (!initialized || catalogueVehiclesNeedRestore) return
        VehiclePersistence.prepareForShutdown()
        VehiclePersistence.save()
        playerStates.cancelActions()
        Projectile.shutdown()
        VehiclePersistence.shutdown()
        TurretScope.shutdown()
        ModelManager.shutdown()
        GunAnimation.shutdown()
        VehicleTickManager.shutdown()
        playerStates.clearAiming()
        ModuleScheduler.releaseCancelledTasks()
        catalogueVehiclesNeedRestore = true
    }

    /** Recreate saved vehicles against the replacement catalogue after its items are registered. */
    fun restoreItemCatalogue(context: ModuleContext) {
        if (!initialized || !catalogueVehiclesNeedRestore) return
        try {
            initializeVehiclePersistence(context)
        } catch (error: Throwable) {
            // A partial spawn must not survive into rollback or overwrite the authoritative save.
            runCatching(VehiclePersistence::shutdown).onFailure(error::addSuppressed)
            catalogueVehiclesNeedRestore = true
            throw error
        }
    }

    /** Releases every piece of combat-owned runtime state without causing gameplay effects. */
    @Synchronized
    fun shutdown() {
        initialized = false
        catalogueVehiclesNeedRestore = false
        val failures = ArrayList<Throwable>()

        cleanup(failures, "task cancellation") { playerStates.cancelActions() }
        cleanup(failures, "projectile removal") { Projectile.shutdown() }
        cleanup(failures, "vehicle removal") { VehiclePersistence.shutdown() }
        cleanup(failures, "turret scope restoration") { TurretScope.shutdown() }
        cleanup(failures, "player model restoration") { ModelManager.shutdown() }
        cleanup(failures, "temporary block restoration") { BlockRestoreManager.shutdown() }
        cleanup(failures, "hat cosmetics") { HatListener.shutdown() }
        cleanup(failures, "hat storage") { HatCollection.shutdown() }
        cleanup(failures, "vehicle tick state") { VehicleTickManager.shutdown() }
        cleanup(failures, "lag compensation") { LagCompensation.clear() }

        cleanup(failures, "player state") { playerStates.clear() }
        entityLastDamageTime.clear()
        activeDamage.clear()
        KeyPressListener.playerInputEvent.clear()
        MannequinDamageListener.shutdown()
        Hitbox.viewingHitboxes.clear()
        Item.registeredItems.clear()
        Hat.registeredHats.clear()
        config = CombatConfig()

        if (failures.isNotEmpty()) {
            throw IllegalStateException("Combat shutdown completed with ${failures.size} cleanup failure(s)").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }

    private inline fun cleanup(
        failures: MutableList<Throwable>,
        name: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (exception: Throwable) {
            failures.add(IllegalStateException("Failed to clean up $name", exception))
        }
    }
}
