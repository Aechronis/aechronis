package net.aechronis.server

import io.github._4drian3d.signedvelocity.minestom.SignedVelocity
import io.github.openminigameserver.worldedit.MinestomWorldEdit
import me.lucko.luckperms.common.config.generic.adapter.EnvironmentVariableConfigAdapter
import me.lucko.luckperms.minestom.CommandRegistry
import me.lucko.luckperms.minestom.LuckPermsMinestom
import me.lucko.spark.minestom.SparkMinestom
import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.guard.Guard
import net.aechronis.logger.Logger
import net.aechronis.logger.LoggerConfig
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.NodesConfig
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Boats
import net.aechronis.server.constants.Cars
import net.aechronis.server.constants.Drones
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Hats
import net.aechronis.server.constants.Melees
import net.aechronis.server.constants.Planes
import net.aechronis.server.constants.Tanks
import net.aechronis.server.listeners.PlayerJoinListener
import net.aechronis.server.resourcepack.ResourcePackServer
import net.aechronis.server.tasks.TabManager
import net.aechronis.server.tasks.WorldSaver
import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.config.ShopConfig
import net.aechronis.vanilla.objects.ShopItem
import net.aechronis.watchdog.Watchdog
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.color.Color
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.registry.RegistryKey
import net.minestom.server.world.DimensionType
import net.minestom.server.world.attribute.EnvironmentAttribute
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import org.everbuild.blocksandstuff.blocks.group.VanillaBlockBehaviour
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path

object Server {
    lateinit var fullbrightKey: RegistryKey<DimensionType>
    lateinit var instance: InstanceContainer
    val eventNode = EventNode.all("aechronis")
}

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 25565
    val velocitySecret = args.getOrNull(1)

    val allPermsPlayers =
        System
            .getProperty("aechronis.allperms")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    val server =
        if (velocitySecret == null) {
            MinecraftServer.init(Auth.Online())
        } else {
            MinecraftServer.init(Auth.Velocity(velocitySecret))
        }

    // register fullbright dimension
    val fullbright =
        DimensionType
            .builder()
            .skylight(false)
            .ambientLight(1.0f)
            .modifyAttribute(
                EnvironmentAttribute.AMBIENT_LIGHT_COLOR,
                EnvironmentAttribute.Modifier.Override(Color.CODEC),
                Color.WHITE,
            ).build()

    Server.fullbrightKey = MinecraftServer.getDimensionTypeRegistry().register("aechronis:fullbright", fullbright)

    MinecraftServer.getGlobalEventHandler().addChild(Server.eventNode)

    // create instance
    Server.instance = MinecraftServer.getInstanceManager().createInstanceContainer(Server.fullbrightKey)
    Server.instance.chunkLoader = AnvilLoader(Path.of("world"), DimensionType.OVERWORLD.key())

    // tasks
    TabManager.start()
    WorldSaver.start()

    Item.registerItems(
        // Ammo
        Ammo.ammo762x39mm,
        Ammo.ammo9mm,
        // Guns
        Guns.ak12,
        Guns.ak74,
        Guns.awp,
        Guns.g3,
        Guns.glock17,
        Guns.m4a1,
        Guns.m9,
        Guns.mg3,
        Guns.mp5,
        Guns.qbz95,
        Guns.vz61,
        // Melee weapons
        Melees.baton,
        // Armor
        Armor.usMarineJacket,
        Armor.usMarineTrousers,
        Armor.usMarineBoots,
        Armor.idfJacket,
        Armor.idfTrousers,
        Armor.idfBoots,
        // Hats
        Hats.gasMask,
        // Vehicles - Planes
        Planes.f16,
        // Vehicles - Cars
        Cars.truck,
        // Vehicles - Tanks
        Tanks.m1a1Abrams,
        // Vehicles - Drones
        Drones.scoutDrone,
        Drones.kamikazeDrone,
        // Vehicles - Boats
        Boats.ussButler,
    )

    // initialize luckperms
    LuckPermsMinestom
        .builder(Path.of("luckperms"))
        .commandRegistry(CommandRegistry.minestom())
        .configurationAdapter { plugin ->
            EnvironmentVariableConfigAdapter(plugin)
        }.enable()

    // initialize spark
    SparkMinestom
        .builder(Path.of("spark"))
        .commands(true)
        .permissionHandler({ player, permission ->
            if (player is Player) {
                player.hasPermission(permission)
            } else {
                true // console
            }
        })
        .enable()

    SignedVelocity.initialize()

    // blocks and stuff
    BlockPlacementRuleRegistrations.registerDefault()
    BlockBehaviorRuleRegistrations.register(*VanillaBlockBehaviour.ALL.toTypedArray())
    PlacedHandlerRegistration.registerDefault()
    BlockPickup.enable()

    Watchdog.initialize()

    Combat.initialize()

    val shopConfig =
        ShopConfig(
            shopItems =
                listOf(
                    ShopItem(ItemStack.of(Material.BEEF, 64), cooldownTicks = 300L, cost = 0),
                    ShopItem(ItemStack.of(Material.OAK_FENCE, 64), cooldownTicks = 300L, cost = 0),
                    ShopItem(ItemStack.of(Material.DIAMOND_PICKAXE), cooldownTicks = 300L, cost = 0),
                    // Ammo
                    ShopItem(Ammo.ammo762x39mm.toItemStack().withAmount(16), cooldownTicks = 0L, cost = 0),
                    ShopItem(Ammo.ammo9mm.toItemStack().withAmount(16), cooldownTicks = 0L, cost = 0),
                    // Guns
                    ShopItem(Guns.ak12.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.ak74.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.awp.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.g3.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.glock17.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.m4a1.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.m9.toItemStack(), cooldownTicks = 0L, cost = 0),
                    ShopItem(Guns.mg3.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.mp5.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.qbz95.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Guns.vz61.toItemStack(), cooldownTicks = 300L, cost = 0),
                    // Melee weapons
                    ShopItem(Melees.baton.toItemStack(), cooldownTicks = 300L, cost = 0),
                    // Armor
                    ShopItem(Armor.usMarineJacket.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Armor.usMarineTrousers.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Armor.usMarineBoots.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Armor.idfJacket.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Armor.idfTrousers.toItemStack(), cooldownTicks = 300L, cost = 0),
                    ShopItem(Armor.idfBoots.toItemStack(), cooldownTicks = 300L, cost = 0),
                    // Hats
                    ShopItem(Hats.gasMask.toItemStack(), cooldownTicks = 300L, cost = 0),
                    // Vehicles
                    ShopItem(Planes.f16.toItemStack(), cooldownTicks = 0L, cost = 10),
                    ShopItem(Cars.truck.toItemStack(), cooldownTicks = 0L, cost = 2),
                    ShopItem(Tanks.m1a1Abrams.toItemStack(), cooldownTicks = 0L, cost = 20),
                    ShopItem(Drones.scoutDrone.toItemStack(), cooldownTicks = 0L, cost = 5),
                    ShopItem(Drones.kamikazeDrone.toItemStack(), cooldownTicks = 0L, cost = 7),
//                    ShopItem(Boats.ussButler.toItemStack(), cooldownTicks = 0L, cost = 20),
                ),
        )

    Vanilla.init(VanillaConfig(shopConfig = shopConfig))

    val nodesConfig = NodesConfig()
    Nodes.initialize(nodesConfig)

    val logger = LoggerConfig(limit = 999999999)
    Logger.init(logger)

    val worldEdit = MinestomWorldEdit()
    worldEdit.init()
    Guard.init()

    val resourcePackPort = System.getProperty("aechronis.resourcePack.port")?.toInt() ?: port + 1

    val resourcePackServer =
        ResourcePackServer.start(
            directory = Path.of(System.getProperty("aechronis.resourcePack.directory", "resource-pack")),
            address =
                InetSocketAddress(
                    System.getProperty("aechronis.resourcePack.bindAddress", "0.0.0.0"),
                    resourcePackPort,
                ),
            publicBaseUri = System.getProperty("aechronis.resourcePack.publicBaseUrl")?.let(::URI),
        )
    Runtime.getRuntime().addShutdownHook(Thread(resourcePackServer::close, "resource-pack-server-shutdown"))
    PlayerJoinListener.init(resourcePackServer)

    try {
        server.start("0.0.0.0", port)
    } catch (exception: Exception) {
        resourcePackServer.close()
        throw exception
    }
}
