package net.aechronis.combat

import net.aechronis.combat.commands.AdsCommand
import net.aechronis.combat.commands.CombatAdminCommand
import net.aechronis.combat.commands.HatsCommand
import net.aechronis.combat.listeners.AimingListener
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
import net.aechronis.combat.listeners.VehicleListener
import net.aechronis.combat.objects.Grenade
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.tasks.ActionBarManager
import net.aechronis.combat.tasks.LeafRestoreManager
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.tasks.PlayerPositionManager
import net.aechronis.combat.tasks.ProjectileTickManager
import net.aechronis.combat.tasks.VehicleTickManager
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.bypassesCombatDamageImmunity
import net.aechronis.combat.utils.combatDamageKind
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task
import java.util.UUID

object Combat {
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

    private const val DAMAGE_IMMUNITY_MS = 500L

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
        if (damaged) return true

        if (useDamageImmunity) {
            if (previousDamageTime == null) {
                entityLastDamageTime.remove(entity)
            } else {
                entityLastDamageTime[entity] = previousDamageTime
            }
        }
        return false
    }

    internal fun activeDamage(entity: LivingEntity): Damage? = activeDamage[entity]

    fun initialize(config: CombatConfig = CombatConfig()) {
        this.config = config

        // measure load time
        val timeStart = System.currentTimeMillis()

        // initialize storage
        HatCollection.initialize()
        LeafRestoreManager.initialize()

        MinecraftServer.getGlobalEventHandler().addChild(lowPriorityEventNode)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        MinecraftServer.getGlobalEventHandler().addChild(highPriorityEventNode)

        // register listeners
        AimingListener.init()
        ReloadListener.init()
        FireListener.init()
        GrenadeListener.init()
        MeleeListener.init()
        PlayerDeathListener.init()
        PlayerDisconnectListener.init()
        CooldownResetListener.init()
        ArmorProtectionListener.init()
        MannequinDamageListener.init()
        VehicleListener.init()
        DroneListener.init()
        KeyPressListener.init()
        HatListener.init()
        LagCompensationListener.init()

        // register commands
        MinecraftServer.getCommandManager().register(CombatAdminCommand())
        MinecraftServer.getCommandManager().register(AdsCommand())
        MinecraftServer.getCommandManager().register(HatsCommand())

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
    }
}
