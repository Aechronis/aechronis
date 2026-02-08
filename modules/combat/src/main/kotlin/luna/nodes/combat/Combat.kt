package luna.nodes.combat

import luna.nodes.combat.commands.CombatAdminCommand
import luna.nodes.combat.listeners.AimingListener
import luna.nodes.combat.listeners.ArmorProtectionListener
import luna.nodes.combat.listeners.CooldownResetListener
import luna.nodes.combat.listeners.FireListener
import luna.nodes.combat.listeners.MannequinDamageListener
import luna.nodes.combat.listeners.PlayerDeathListener
import luna.nodes.combat.listeners.PlayerDisconnectListener
import luna.nodes.combat.listeners.ReloadListener
import luna.nodes.combat.tasks.ActionBarManager
import luna.nodes.combat.tasks.CooldownManager
import luna.nodes.combat.tasks.ModelManager
import luna.nodes.combat.tasks.PlayerPositionManager
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
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

    val playerFiring = HashMap<Player, Boolean>()
    val firingResetTasks = HashMap<Player, Task>()

    val reloadTasks = HashMap<Player, Task>()

    val playerPreviousPositions = HashMap<Player, ArrayDeque<Pos>>()
    val playerSpeeds = HashMap<Player, Float>()

    val playerKillers = HashMap<Player, Player?>()

    val playerCooldowns = HashMap<Player, Long>()

    fun initialize() {
        // measure load time
        val timeStart = System.currentTimeMillis()

        MinecraftServer.getGlobalEventHandler().addChild(lowPriorityEventNode)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        MinecraftServer.getGlobalEventHandler().addChild(highPriorityEventNode)

        // register listeners
        AimingListener.init()
        ReloadListener.init()
        FireListener.init()
        PlayerDeathListener.init()
        PlayerDisconnectListener.init()
        CooldownResetListener.init()
        ArmorProtectionListener.init()
        MannequinDamageListener.init()

        // register commands
        MinecraftServer.getCommandManager().register(CombatAdminCommand())

        // run background schedulers/tasks
        ModelManager.start()
        PlayerPositionManager.start()
        ActionBarManager.start()
        CooldownManager.start()

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
