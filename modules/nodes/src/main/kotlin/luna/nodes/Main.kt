/*
 * Implement bukkit plugin interface
 */

package luna.nodes

//import org.bukkit.command.TabCompleter
//import org.bukkit.plugin.java.JavaPlugin
//import luna.nodes.commands.AllyChatCommand
//import luna.nodes.commands.AllyCommand
//import luna.nodes.commands.GlobalChatCommand
//import luna.nodes.commands.NationChatCommand
import luna.nodes.commands.NationCommand
//import luna.nodes.commands.NodesAdminCommand
import luna.nodes.commands.NodesCommand
//import luna.nodes.commands.PlayerCommand
//import luna.nodes.commands.PortCommand
//import luna.nodes.commands.TerritoryCommand
//import luna.nodes.commands.TownChatCommand
import luna.nodes.commands.TownCommand
//import luna.nodes.commands.UnallyCommand
import luna.nodes.commands.WarCommand
//import luna.nodes.listeners.NodesChatListener
//import luna.nodes.listeners.NodesChestProtectionDestroyListener
//import luna.nodes.listeners.NodesChestProtectionListener
import luna.nodes.listeners.onInventoryClick
//import luna.nodes.listeners.NodesPlayerDamageListener
import io.github.togar2.pvp.MinestomPvP
import io.github.togar2.pvp.feature.CombatFeatures
import luna.nodes.listeners.onBlockBreak
import luna.nodes.listeners.onBlockBreakSuccess
import luna.nodes.listeners.onBlockPlace
import luna.nodes.listeners.onBlockPlaceSuccess
import luna.nodes.listeners.onInventoryClose
import me.lucko.spark.minestom.SparkMinestom
import luna.nodes.listeners.onPlayerConfiguration
import luna.nodes.listeners.onPlayerJoin
import luna.nodes.listeners.onPlayerQuit
import luna.nodes.listeners.onPlayerMove
import luna.nodes.listeners.onPlayerTeleport
import luna.nodes.utils.loadLongFromFile
import luna.nodes.utils.PlayerNameCache
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.anvil.AnvilLoader
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import org.everbuild.blocksandstuff.fluids.MinestomFluids


fun main() {
    // measure load time
    val timeStart = System.currentTimeMillis()

    // initialize server
    val minecraftServer = MinecraftServer.init(Auth.Online())

    // create instance
    val instanceManager = MinecraftServer.getInstanceManager()
    val instanceContainer = instanceManager.createInstanceContainer()

    instanceContainer.chunkLoader = AnvilLoader(Config.pathLevel)

    // initialize spark
    val spark = SparkMinestom.builder(Config.pathSpark)
        .commands(true) // enables registration of Spark commands
        .permissionHandler { sender, permission -> true } // allows all command senders
        .enable()

    // initialize blocks and stuff
    BlockPlacementRuleRegistrations.registerDefault()
    BlockBehaviorRuleRegistrations.registerDefault()
    PlacedHandlerRegistration.registerDefault()
    BlockPickup.enable()
    MinestomFluids.enableFluids()
//    MinestomFluids.enableVanillaFluids() // this causes massive lag when interacting with fluid on coastlines


    // initialize minestompvp
    MinestomPvP.init()
    MinecraftServer.getGlobalEventHandler().addChild(CombatFeatures.modernVanilla().createNode())

    // ===================================
    // load config
    // ===================================
    Nodes.reloadConfig()

    Nodes.war.initialize(Config.flagBlocks)

    // initalize username -> uuid cache
    Nodes.playerNameCache = PlayerNameCache()
    Nodes.playerNameCache.load()

    // try load world
    val pluginPath = Config.pathPlugin
    println("Loading world from: $pluginPath")
    try {
        if (Nodes.loadWorld() == true) { // successful load
            // print number of resource nodes and territories loaded
            println("- Resource Nodes: ${Nodes.getResourceNodeCount()}")
            println("- Territories: ${Nodes.getTerritoryCount()}")
            println("- Residents: ${Nodes.getResidentCount()}")
            println("- Towns: ${Nodes.getTownCount()}")
            println("- Nations: ${Nodes.getNationCount()}")
        } else {
            println("Error loading world: Invalid world file at $pluginPath/${Config.pathWorld}")
        }
    } catch (err: Exception) {
        err.printStackTrace()
        println("Error loading world: $err")
    }

    var eventHandler = MinecraftServer.getGlobalEventHandler()

    // register listeners
//    pluginManager.registerEvents(NodesChatListener(), this)
//    pluginManager.registerEvents(NodesChestProtectionListener(), this)
//    pluginManager.registerEvents(NodesChestProtectionDestroyListener(), this)
    eventHandler.addListener(InventoryPreClickEvent::class.java, { event -> onInventoryClick(event) })
    eventHandler.addListener(InventoryCloseEvent::class.java, { event -> onInventoryClose(event) })
    eventHandler.addListener(PlayerBlockBreakEvent::class.java, { event -> onBlockBreak(event) })
    eventHandler.addListener(PlayerBlockBreakEvent::class.java, { event -> onBlockBreakSuccess(event) })
    eventHandler.addListener(PlayerBlockPlaceEvent::class.java, { event -> onBlockPlace(event) })
    eventHandler.addListener(PlayerBlockPlaceEvent::class.java, { event -> onBlockPlaceSuccess(event) })
//    pluginManager.registerEvents(NodesPlayerJoinQuitListener(), this)
    eventHandler.addListener(AsyncPlayerConfigurationEvent::class.java, { event -> onPlayerConfiguration(event) })
    eventHandler.addListener(PlayerLoadedEvent::class.java, { event -> onPlayerJoin(event)})
    eventHandler.addListener(PlayerDisconnectEvent::class.java, { event -> onPlayerQuit(event)})
    eventHandler.addListener(PlayerMoveEvent::class.java, { event -> onPlayerMove(event) })
    eventHandler.addListener(EntityTeleportEvent::class.java, { event -> onPlayerTeleport(event) })
//    pluginManager.registerEvents(NodesPlayerDamageListener(), this)

    // shutdown task
    MinecraftServer.getSchedulerManager().buildShutdownTask({onDisable()})
//
//    // register commands
    MinecraftServer.getCommandManager().register(TownCommand())
    MinecraftServer.getCommandManager().register(NationCommand())
    MinecraftServer.getCommandManager().register(NodesCommand())
//    this.getCommand("nodesadmin")?.setExecutor(NodesAdminCommand())
//    this.getCommand("ally")?.setExecutor(AllyCommand())
//    this.getCommand("unally")?.setExecutor(UnallyCommand())
    MinecraftServer.getCommandManager().register(WarCommand())
//    this.getCommand("globalchat")?.setExecutor(GlobalChatCommand())
//    this.getCommand("townchat")?.setExecutor(TownChatCommand())
//    this.getCommand("nationchat")?.setExecutor(NationChatCommand())
//    this.getCommand("allychat")?.setExecutor(AllyChatCommand())
//    this.getCommand("player")?.setExecutor(PlayerCommand())
//    this.getCommand("territory")?.setExecutor(TerritoryCommand())
//    this.getCommand("port")?.setExecutor(PortCommand())

    minecraftServer.start("0.0.0.0",25565)

    // load current income tick
    val currTime = System.currentTimeMillis()
    Nodes.lastBackupTime = loadLongFromFile(Config.pathLastBackupTime) ?: currTime
    Nodes.lastIncomeTime = loadLongFromFile(Config.pathLastIncomeTime) ?: currTime

    // run background schedulers/tasks
    Nodes.reloadManagers()

    // initialize all players online
    Nodes.initializeOnlinePlayers()

//    // set final initialized flag
//    Nodes.initialized = true
//    Nodes.enabled = true

    // print load time
    val timeEnd = System.currentTimeMillis()
    val timeLoad = timeEnd - timeStart
    println("Enabled in ${timeLoad}ms")

    // print success message
    println("now this is epic")
}

fun onDisable() {
    println("wtf i hate xeth now")

    // world cleanup
    Nodes.cleanup()

    // final synchronous save of world
    Nodes.saveWorld(checkIfNeedsSave = false, async = false)

}
