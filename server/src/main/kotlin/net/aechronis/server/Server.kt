package net.aechronis.server

import io.github._4drian3d.signedvelocity.minestom.SignedVelocity
import me.lucko.luckperms.common.config.generic.adapter.EnvironmentVariableConfigAdapter
import me.lucko.luckperms.minestom.CommandRegistry
import me.lucko.luckperms.minestom.LuckPermsMinestom
import me.lucko.spark.minestom.SparkMinestom
import net.aechronis.server.commands.SetSpawnCommand
import net.aechronis.server.events.SpawnPointChangedEvent
import net.aechronis.server.listeners.ResourcePackListener
import net.aechronis.server.modules.MODULE_MANAGEMENT_PERMISSION
import net.aechronis.server.modules.ModuleCommand
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleManager
import net.aechronis.server.modules.PlayerAdmissionGate
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.resourcepack.ResourcePackServer
import net.aechronis.server.tasks.WorldSaver
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.color.Color
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
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
import java.util.UUID

object Server {
    // This ID scopes persisted data such as Guard zones to the durable world/ instance.
    val primaryWorldInstanceId: UUID = UUID.fromString("d5ae5978-ac5d-395a-9872-e2ba4ae1b7c3")

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
        eventNode.call(SpawnPointChangedEvent(position))
    }
}

fun main(args: Array<String>) {
    System.setProperty("minestom.shutdown-on-signal", "false")

    val port = args.getOrNull(0)?.toInt() ?: 25565
    val velocitySecret = args.getOrNull(1)
    val resourcePackDirectory =
        System.getProperty("aechronis.resourcePack.directory")?.let(Path::of)
            ?: EmbeddedResourcePack.defaultDirectory()

    val resourcePackPort = System.getProperty("aechronis.resourcePack.port")?.toInt() ?: port + 1
    val resourcePackServer =
        ResourcePackServer.start(
            address =
                InetSocketAddress(
                    System.getProperty("aechronis.resourcePack.bindAddress", "0.0.0.0"),
                    resourcePackPort,
                ),
            publicBaseUri = System.getProperty("aechronis.resourcePack.publicBaseUrl")?.let(::URI),
        )
    ServerShutdown.install(resourcePackServer)

    try {
        startMinecraftServer(port, velocitySecret, resourcePackDirectory, resourcePackServer)
    } catch (exception: Throwable) {
        ServerShutdown.shutdown()
        throw exception
    }
}

private fun startMinecraftServer(
    port: Int,
    velocitySecret: String?,
    resourcePackDirectory: Path,
    resourcePackServer: ResourcePackServer,
) {
    val server =
        if (velocitySecret == null) {
            MinecraftServer.init(Auth.Online())
        } else {
            MinecraftServer.init(Auth.Velocity(velocitySecret))
        }

    // Discovery and core registration happen after Minestom initialization. Install a minimal
    // cleanup plan immediately so failures in either phase cannot leave Minestom or the already
    // bound resource-pack server running.
    ServerShutdown.configure(
        beginShutdown = {},
        stopWorldSaver = {},
        closeCraftingStore = {},
        closeVotifier = {},
        prepareModules = {},
        saveModuleState = {},
        stopServer = MinecraftServer::stopCleanly,
        saveCoreWorld = {},
        closeModules = {},
    )

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
    Server.eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
        // keep the server joinable when an iteration is intentionally disabled
        event.spawningInstance = Server.instance
    }

    // create instance
    val worldPath = Path.of("world")
    Files.createDirectories(worldPath)
    Server.instance =
        InstanceContainer(
            Server.primaryWorldInstanceId,
            Server.fullbrightKey,
            AnvilLoader(worldPath, DimensionType.OVERWORLD.key()),
        )
    MinecraftServer.getInstanceManager().registerInstance(Server.instance)
    Server.loadSpawnPoint()

    val moduleDirectory = Path.of(System.getProperty("aechronis.modules.directory", "modules"))
    val moduleManager = ModuleManager.discover(moduleDirectory)
    val moduleContext =
        ModuleContext(
            saveCoreWorld = WorldSaver::saveWorldAndWait,
            resourcePackDirectory = resourcePackDirectory,
            resourcePackServer = resourcePackServer,
        )

    ServerShutdown.configure(
        beginShutdown = moduleManager::beginShutdown,
        stopWorldSaver = WorldSaver::shutdown,
        closeCraftingStore = CraftingStoreIntegration::shutdown,
        closeVotifier = VotifierIntegration::shutdown,
        prepareModules = { moduleManager.prepareForShutdown(moduleContext) },
        saveModuleState = { moduleManager.saveState(moduleContext) },
        stopServer = MinecraftServer::stopCleanly,
        saveCoreWorld = WorldSaver::saveWorldAndWait,
        closeModules = moduleManager::close,
    )

    // This core-owned gate spans the gaps where one complete module generation is being replaced
    // by another. It must be installed before the module baseline is captured during initialize.
    PlayerAdmissionGate(moduleManager::isAcceptingPlayers).install(Server.eventNode)

    // Core registrations must exist before the module manager captures its baseline.
    LuckPermsMinestom
        .builder(Path.of("luckperms"))
        .permissionSuggestions(LuckPermsPermissions.all + MODULE_MANAGEMENT_PERMISSION)
        .commandRegistry(CommandRegistry.minestom())
        .configurationAdapter { plugin ->
            EnvironmentVariableConfigAdapter(plugin)
        }.enable()

    MinecraftServer.getCommandManager().register(ModuleCommand(moduleManager))
    MinecraftServer.getCommandManager().register(SetSpawnCommand())
    ResourcePackListener.initialize(resourcePackServer)
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

    BlockPlacementRuleRegistrations.registerDefault()
    BlockBehaviorRuleRegistrations.register(*VanillaBlockBehaviour.ALL.toTypedArray())
    PlacedHandlerRegistration.registerDefault()
    BlockPickup.enable()

    moduleManager.initialize(moduleContext)
    WorldSaver.start { moduleManager.saveCheckpoint(moduleContext) }

    server.start("0.0.0.0", port)
}
