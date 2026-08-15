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
import net.aechronis.server.commands.SetSpawnCommand
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Boats
import net.aechronis.server.constants.Cars
import net.aechronis.server.constants.Drones
import net.aechronis.server.constants.Grenades
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Hats
import net.aechronis.server.constants.Planes
import net.aechronis.server.constants.Tanks
import net.aechronis.server.listeners.PlayerJoinListener
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.resourcepack.ResourcePackServer
import net.aechronis.server.tasks.TabManager
import net.aechronis.server.tasks.WorldSaver
import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.aechronis.watchdog.Watchdog
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.color.Color
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.registry.RegistryKey
import net.minestom.server.tag.Tag
import net.minestom.server.world.DimensionType
import net.minestom.server.world.attribute.EnvironmentAttribute
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import org.everbuild.blocksandstuff.blocks.group.VanillaBlockBehaviour
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

object Server {
    private val spawnXTag = Tag.Double("aechronis:spawn_x")
    private val spawnYTag = Tag.Double("aechronis:spawn_y")
    private val spawnZTag = Tag.Double("aechronis:spawn_z")
    private val spawnYawTag = Tag.Float("aechronis:spawn_yaw")
    private val spawnPitchTag = Tag.Float("aechronis:spawn_pitch")

    lateinit var fullbrightKey: RegistryKey<DimensionType>
    lateinit var instance: InstanceContainer
    var spawnPoint: Pos = Pos(0.0, 64.0, 0.0)
        private set
    val eventNode = EventNode.all("aechronis")

    fun loadSpawnPoint() {
        val tags = instance.tagHandler()
        val x = tags.getTag(spawnXTag) ?: return
        val y = tags.getTag(spawnYTag) ?: return
        val z = tags.getTag(spawnZTag) ?: return
        spawnPoint =
            Pos(
                x,
                y,
                z,
                tags.getTag(spawnYawTag) ?: 0.0f,
                tags.getTag(spawnPitchTag) ?: 0.0f,
            )
    }

    fun setSpawnPoint(position: Pos) {
        spawnPoint = position
        val tags = instance.tagHandler()
        tags.setTag(spawnXTag, position.x)
        tags.setTag(spawnYTag, position.y)
        tags.setTag(spawnZTag, position.z)
        tags.setTag(spawnYawTag, position.yaw)
        tags.setTag(spawnPitchTag, position.pitch)
        instance.saveInstance()
    }
}

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 25565
    val velocitySecret = args.getOrNull(1)
    val resourcePackDirectory =
        System.getProperty("aechronis.resourcePack.directory")?.let(Path::of)
            ?: EmbeddedResourcePack.defaultDirectory().resolve("resource-pack")

    EmbeddedResourcePack.install(resourcePackDirectory)
    val resourcePackPort = System.getProperty("aechronis.resourcePack.port")?.toInt() ?: port + 1
    val resourcePackServer =
        ResourcePackServer.start(
            directory = resourcePackDirectory,
            address =
                InetSocketAddress(
                    System.getProperty("aechronis.resourcePack.bindAddress", "0.0.0.0"),
                    resourcePackPort,
                ),
            publicBaseUri = System.getProperty("aechronis.resourcePack.publicBaseUrl")?.let(::URI),
        )
    Runtime.getRuntime().addShutdownHook(Thread(resourcePackServer::close, "resource-pack-server-shutdown"))

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
    val worldPath = Path.of("world")
    Files.createDirectories(worldPath)
    Server.instance =
        MinecraftServer
            .getInstanceManager()
            .createInstanceContainer(
                Server.fullbrightKey,
                AnvilLoader(worldPath, DimensionType.OVERWORLD.key()),
            )
    Server.loadSpawnPoint()
    MinecraftServer.getCommandManager().register(SetSpawnCommand())

    // tasks
    TabManager.start()
    WorldSaver.start()

    Item.registerItems(
        // Ammo
        Ammo.ammo762x39mm,
        Ammo.tankShell,
        // Guns
        Guns.ak47,
        // Grenades
        Grenades.frag,
        // Armor
        Armor.jacket,
        Armor.trousers,
        Armor.boots,
        // Hats
        Hats.gasMask,
        // Vehicles - Planes
        Planes.fighter,
        Planes.bomber,
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

    Vanilla.init()

    val nodesConfig = NodesConfig(defaultRespawnPoint = Server.spawnPoint)
    Nodes.initialize(nodesConfig)

    val logger = LoggerConfig(limit = 999999999)
    Logger.init(logger)

    val worldEdit = MinestomWorldEdit()
    worldEdit.init()
    Guard.init()

    PlayerJoinListener.init(resourcePackServer)

    try {
        server.start("0.0.0.0", port)
    } catch (exception: Exception) {
        resourcePackServer.close()
        throw exception
    }
}
