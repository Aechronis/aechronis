package net.aechronis.combat

import net.aechronis.combat.commands.CombatAdminCommand
import net.aechronis.combat.commands.HatsCommand
import net.aechronis.combat.listeners.AimingListener
import net.aechronis.combat.listeners.ArmorProtectionListener
import net.aechronis.combat.listeners.CooldownResetListener
import net.aechronis.combat.listeners.DroneListener
import net.aechronis.combat.listeners.FireListener
import net.aechronis.combat.listeners.HatListener
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.listeners.MannequinDamageListener
import net.aechronis.combat.listeners.MeleeListener
import net.aechronis.combat.listeners.PlayerDeathListener
import net.aechronis.combat.listeners.PlayerDisconnectListener
import net.aechronis.combat.listeners.ReloadListener
import net.aechronis.combat.listeners.VehicleListener
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.tasks.ActionBarManager
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.tasks.PlayerPositionManager
import net.aechronis.combat.tasks.ProjectileTickManager
import net.aechronis.combat.tasks.VehicleTickManager
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task

object Combat {
    // event nodes for listeners
    val lowPriorityEventNode = EventNode.all("combat-low-priority").setPriority(999)
    val eventNode = EventNode.all("combat")
    val highPriorityEventNode = EventNode.all("combat-high-priority").setPriority(-999)

    val playerAiming = HashMap<Player, Boolean>()
    val aimingResetTasks = HashMap<Player, Task>()

    val reloadTasks = HashMap<Player, Task>()

    val playerPreviousPositions = HashMap<Player, ArrayDeque<Pos>>()
    val playerSpeeds = HashMap<Player, Float>()

    val playerKillers = HashMap<Player, Player?>()

    internal fun recordKiller(
        victim: Player,
        killer: Player,
    ) {
        synchronized(playerKillers) {
            playerKillers[victim] = killer
        }
    }

    internal fun takeKiller(victim: Player): Player? = synchronized(playerKillers) { playerKillers.remove(victim) }

    internal fun removeKillerReferences(player: Player) {
        synchronized(playerKillers) {
            playerKillers.entries.removeIf { (victim, killer) -> victim === player || killer === player }
        }
    }

    val playerLastActionTimes = HashMap<Player, Long>()

    val meleeLastAttackTimes = HashMap<Player, Long>()

    val placeTasks = HashMap<Player, Task>()

    val entityLastDamageTime = HashMap<LivingEntity, Long>()

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

    fun initialize() {
        // measure load time
        val timeStart = System.currentTimeMillis()

        // initialize storage
        HatCollection.initialize()

        MinecraftServer.getGlobalEventHandler().addChild(lowPriorityEventNode)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        MinecraftServer.getGlobalEventHandler().addChild(highPriorityEventNode)

        // register listeners
        AimingListener.init()
        ReloadListener.init()
        FireListener.init()
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

        // register commands
        MinecraftServer.getCommandManager().register(CombatAdminCommand())
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
