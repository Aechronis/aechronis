/*
 * Implement bukkit plugin interface
 */

package phonon.nodes

//import org.bukkit.command.TabCompleter
//import org.bukkit.plugin.java.JavaPlugin
//import phonon.nodes.commands.AllyChatCommand
//import phonon.nodes.commands.AllyCommand
//import phonon.nodes.commands.GlobalChatCommand
//import phonon.nodes.commands.NationChatCommand
//import phonon.nodes.commands.NationCommand
//import phonon.nodes.commands.NodesAdminCommand
//import phonon.nodes.commands.NodesCommand
//import phonon.nodes.commands.PeaceCommand
//import phonon.nodes.commands.PlayerCommand
//import phonon.nodes.commands.PortCommand
//import phonon.nodes.commands.TerritoryCommand
//import phonon.nodes.commands.TownChatCommand
import phonon.nodes.commands.TownCommand
//import phonon.nodes.commands.TruceCommand
//import phonon.nodes.commands.UnallyCommand
//import phonon.nodes.commands.WarCommand
//import phonon.nodes.listeners.DisabledWorldListener
//import phonon.nodes.listeners.NodesBlockGrowListener
//import phonon.nodes.listeners.NodesChatListener
//import phonon.nodes.listeners.NodesChestProtectionDestroyListener
//import phonon.nodes.listeners.NodesChestProtectionListener
//import phonon.nodes.listeners.NodesDiplomacyAllianceListener
//import phonon.nodes.listeners.NodesDiplomacyTruceExpiredListener
//import phonon.nodes.listeners.NodesEntityBreedListener
//import phonon.nodes.listeners.NodesGuiListener
//import phonon.nodes.listeners.NodesIncomeInventoryListener
//import phonon.nodes.listeners.NodesPlayerAFKKickListener
//import phonon.nodes.listeners.NodesPlayerDamageListener
import io.github.togar2.pvp.MinestomPvP
import io.github.togar2.pvp.feature.CombatFeatures
import me.lucko.spark.minestom.SparkMinestom
import phonon.nodes.listeners.onPlayerConfiguration
import phonon.nodes.listeners.onPlayerJoin
import phonon.nodes.listeners.onPlayerQuit
import phonon.nodes.listeners.onPlayerMove
import phonon.nodes.listeners.onPlayerTeleport
//import phonon.nodes.listeners.NodesSheepShearListener
//import phonon.nodes.listeners.NodesWarFlagArmorStandListener
//import phonon.nodes.listeners.NodesWorldListener
import phonon.nodes.utils.loadLongFromFile
import phonon.nodes.utils.PlayerNameCache
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.entity.EntityTeleportEvent
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

//    instanceContainer.chunkLoader = AnvilLoader("C:\\Users\\Luna\\AppData\\Roaming\\PrismLauncher\\instances\\1.21.4\\minecraft\\saves\\WorldMap120")

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
//
//    Nodes.war.initialize(Config.flagMaterials)
//
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
//    pluginManager.registerEvents(NodesBlockGrowListener(), this)
//    pluginManager.registerEvents(NodesChatListener(), this)
//    pluginManager.registerEvents(NodesChestProtectionListener(), this)
//    pluginManager.registerEvents(NodesChestProtectionDestroyListener(), this)
//    pluginManager.registerEvents(NodesDiplomacyAllianceListener(), this)
//    pluginManager.registerEvents(NodesDiplomacyTruceExpiredListener(), this)
//    pluginManager.registerEvents(NodesEntityBreedListener(), this)
//    pluginManager.registerEvents(NodesGuiListener(), this)
//    pluginManager.registerEvents(NodesIncomeInventoryListener(), this)
//    pluginManager.registerEvents(NodesWorldListener(), this)
//    pluginManager.registerEvents(NodesPlayerAFKKickListener(), this)
//    pluginManager.registerEvents(NodesPlayerJoinQuitListener(), this)
    eventHandler.addListener(AsyncPlayerConfigurationEvent::class.java, { event -> onPlayerConfiguration(event) })
    eventHandler.addListener(PlayerLoadedEvent::class.java, { event -> onPlayerJoin(event)})
    eventHandler.addListener(PlayerDisconnectEvent::class.java, { event -> onPlayerQuit(event)})
    eventHandler.addListener(PlayerMoveEvent::class.java, { event -> onPlayerMove(event) })
    eventHandler.addListener(EntityTeleportEvent::class.java, { event -> onPlayerTeleport(event) })
//    pluginManager.registerEvents(NodesSheepShearListener(), this)
//    pluginManager.registerEvents(NodesWarFlagArmorStandListener(), this)
//    pluginManager.registerEvents(NodesPlayerDamageListener(), this)

    // shutdown task
    MinecraftServer.getSchedulerManager().buildShutdownTask({onDisable()})
//
//    // register commands
    MinecraftServer.getCommandManager().register(TownCommand())
//    this.getCommand("nation")?.setExecutor(NationCommand())
//    this.getCommand("nodes")?.setExecutor(NodesCommand())
//    this.getCommand("nodesadmin")?.setExecutor(NodesAdminCommand())
//    this.getCommand("ally")?.setExecutor(AllyCommand())
//    this.getCommand("unally")?.setExecutor(UnallyCommand())
//    this.getCommand("war")?.setExecutor(WarCommand())
//    this.getCommand("peace")?.setExecutor(PeaceCommand())
//    this.getCommand("truce")?.setExecutor(TruceCommand())
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
//
//    // run background schedulers/tasks
//    Nodes.reloadManagers()

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

//    Nodes.enabled = false
//
//    // world cleanup
//    Nodes.cleanup()
//
//    // final synchronous save of world
//    // -> only save when world was properly initialized,
//    //    to avoid saving junk empty data when plugin fails load
//    if (Nodes.initialized) {
//        Nodes.saveWorld(checkIfNeedsSave = false, async = false)
//        Nodes.saveTruce(async = false)
//    }
}
