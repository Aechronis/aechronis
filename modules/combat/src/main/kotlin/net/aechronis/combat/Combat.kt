package net.aechronis.combat

import net.aechronis.combat.commands.AdsCommand
import net.aechronis.combat.commands.CombatAdminCommand
import net.aechronis.combat.commands.HatsCommand
import net.aechronis.combat.listeners.AimingListener
import net.aechronis.combat.listeners.AmmoInventoryListener
import net.aechronis.combat.listeners.ArmorProtectionListener
import net.aechronis.combat.listeners.CooldownResetListener
import net.aechronis.combat.listeners.DroneListener
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
import net.aechronis.combat.objects.Grenade
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Projectile
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.combat.tasks.ActionBarManager
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.tasks.PlayerPositionManager
import net.aechronis.combat.tasks.ProjectileTickManager
import net.aechronis.combat.tasks.VehicleTickManager
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.bypassesCombatDamageImmunity
import net.aechronis.combat.utils.combatDamageKind
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task
import java.util.UUID

object Combat {
    private var initialized = false

    var config: CombatConfig = CombatConfig()
        private set

    // event nodes for listeners
    val lowPriorityEventNode = EventNode.all("combat-low-priority").setPriority(999)
    val eventNode = EventNode.all("combat")
    val highPriorityEventNode = EventNode.all("combat-high-priority").setPriority(-999)

    val playerAiming = HashMap<Player, Boolean>()
    val aimingResetTasks = HashMap<Player, Task>()

    // This preference is intentionally process-local and retained across reconnects.
    private val adsAnimationDisabledPlayers = HashSet<UUID>()

    fun isAdsAnimationDisabled(playerUuid: UUID): Boolean = playerUuid in adsAnimationDisabledPlayers

    fun toggleAdsAnimation(playerUuid: UUID): Boolean =
        if (adsAnimationDisabledPlayers.remove(playerUuid)) {
            false
        } else {
            adsAnimationDisabledPlayers.add(playerUuid)
            true
        }

    val reloadTasks = HashMap<Player, Task>()

    val playerPreviousPositions = HashMap<Player, ArrayDeque<Pos>>()
    val playerSpeeds = HashMap<Player, Float>()

    val playerLastActionTimes = HashMap<Player, Long>()

    val meleeLastAttackTimes = HashMap<Player, Long>()

    val placeTasks = HashMap<Player, Task>()

    val armedGrenades = HashMap<Player, Grenade>()
    val grenadeFuseTasks = HashMap<Player, Task>()
    val grenadeFuseDeadlines = HashMap<Player, Long>()

    val entityLastDamageTime = HashMap<LivingEntity, Long>()
    private val activeDamage = HashMap<LivingEntity, Damage>()

    internal val respawnProtectionExpiresAt = HashMap<Player, Long>()

    private const val DAMAGE_IMMUNITY_MS = 500L
    internal const val RESPAWN_PROTECTION_MS = 5_000L

    internal fun grantRespawnProtection(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ) {
        respawnProtectionExpiresAt[player] = now + RESPAWN_PROTECTION_MS
    }

    internal fun isRespawnProtected(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val expiresAt = respawnProtectionExpiresAt[player] ?: return false
        if (now < expiresAt) return true

        respawnProtectionExpiresAt.remove(player)
        return false
    }

    internal fun revokeRespawnProtection(player: Player) {
        respawnProtectionExpiresAt.remove(player)
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
            // measure load time
            val timeStart = System.currentTimeMillis()

            // initialize storage
            HatCollection.initialize()
            BlockRestoreManager.initialize()

            // register listeners
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
            DroneListener.init()
            KeyPressListener.init()
            HatListener.init()
            LagCompensationListener.init()
            WeaponLoreListener.init()

            val globalEventHandler = MinecraftServer.getGlobalEventHandler()
            ModuleEvents.addChild(globalEventHandler, lowPriorityEventNode)
            ModuleEvents.addChild(globalEventHandler, eventNode)
            ModuleEvents.addChild(globalEventHandler, highPriorityEventNode)

            // register commands
            ModuleCommands
                .register(CombatAdminCommand())
            ModuleCommands
                .register(AdsCommand())
            ModuleCommands
                .register(HatsCommand())

            // run background schedulers/tasks
            ModelManager.start()
            PlayerPositionManager.start()
            ActionBarManager.start()
            VehicleTickManager.start()
            ProjectileTickManager.start()

            // print load time
            val timeEnd = System.currentTimeMillis()
            val timeLoad = timeEnd - timeStart
            println("Enabled in ${timeLoad}ms")
        } catch (exception: Throwable) {
            try {
                shutdown()
            } catch (shutdownException: Throwable) {
                exception.addSuppressed(shutdownException)
            }
            throw exception
        }
    }

    /** Releases every piece of combat-owned runtime state without causing gameplay effects. */
    @Synchronized
    fun shutdown() {
        initialized = false
        val failures = ArrayList<Throwable>()

        cleanup(failures, "task cancellation") {
            cancelAndClear(aimingResetTasks)
            cancelAndClear(reloadTasks)
            cancelAndClear(placeTasks)
            cancelAndClear(grenadeFuseTasks)
            armedGrenades.clear()
            grenadeFuseDeadlines.clear()
        }
        cleanup(failures, "projectile removal") { Projectile.shutdown() }
        cleanup(failures, "vehicle removal") { VehiclePersistence.shutdown() }
        cleanup(failures, "player model restoration") { ModelManager.shutdown() }
        cleanup(failures, "temporary block restoration") { BlockRestoreManager.shutdown() }
        cleanup(failures, "hat storage") { HatCollection.shutdown() }
        cleanup(failures, "vehicle tick state") { VehicleTickManager.shutdown() }
        cleanup(failures, "lag compensation") { LagCompensation.clear() }

        playerAiming.clear()
        adsAnimationDisabledPlayers.clear()
        playerPreviousPositions.clear()
        playerSpeeds.clear()
        playerLastActionTimes.clear()
        meleeLastAttackTimes.clear()
        entityLastDamageTime.clear()
        activeDamage.clear()
        respawnProtectionExpiresAt.clear()
        KeyPressListener.playerInputEvent.clear()
        MannequinDamageListener.shutdown()
        Hitbox.viewingHitboxes.clear()
        Item.registeredItems.clear()
        config = CombatConfig()

        if (failures.isNotEmpty()) {
            throw IllegalStateException("Combat shutdown completed with ${failures.size} cleanup failure(s)").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }

    private fun cancelAndClear(tasks: MutableMap<*, Task>) {
        val activeTasks = tasks.values.toSet()
        tasks.clear()
        activeTasks.forEach(Task::cancel)
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
