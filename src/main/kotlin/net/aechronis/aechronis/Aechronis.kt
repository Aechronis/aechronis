package net.aechronis.aechronis

import fr.ghostrider584.axiom.AxiomMinestom
import fr.ghostrider584.axiom.restrictions.AxiomPermission
import fr.ghostrider584.axiom.restrictions.AxiomPermissions
import io.github._4drian3d.signedvelocity.minestom.SignedVelocity
import me.lucko.luckperms.common.config.generic.adapter.EnvironmentVariableConfigAdapter
import me.lucko.luckperms.minestom.CommandRegistry
import me.lucko.luckperms.minestom.LuckPermsMinestom
import me.lucko.spark.minestom.SparkMinestom
import net.aechronis.aechronis.constants.Ammo
import net.aechronis.aechronis.constants.Armor
import net.aechronis.aechronis.constants.Cars
import net.aechronis.aechronis.constants.Guns
import net.aechronis.aechronis.constants.Hats
import net.aechronis.aechronis.constants.Planes
import net.aechronis.aechronis.listeners.PlayerJoinListener
import net.aechronis.aechronis.tasks.TabManager
import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.NodesConfig
import net.aechronis.vanilla.Vanilla
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.registry.RegistryKey
import net.minestom.server.world.DimensionType
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import org.everbuild.blocksandstuff.fluids.MinestomFluids
import java.nio.file.Path

object Aechronis {
    lateinit var instance: InstanceContainer
    lateinit var fullbrightKey: RegistryKey<DimensionType>
    val eventNode = EventNode.all("aechronis")
}

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 25565
    val velocitySecret = args.getOrNull(1)

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
            .ambientLight(1.0f)
            .build()

    Aechronis.fullbrightKey = MinecraftServer.getDimensionTypeRegistry().register("aechronis:fullbright", fullbright)

    AxiomMinestom.initialize()

    server.start("0.0.0.0", port)

    MinecraftServer.getGlobalEventHandler().addChild(Aechronis.eventNode)

    // create instance
    Aechronis.instance = MinecraftServer.getInstanceManager().createInstanceContainer(Aechronis.fullbrightKey)
    Aechronis.instance.chunkLoader = AnvilLoader("world")
    Aechronis.instance.viewDistance(32)

    // tasks
    TabManager.start()

    // register listeners
    PlayerJoinListener.init()

    // ammo
    Item.registerItems(Ammo.ammo762x39mm)

    // guns
    Item.registerItems(Guns.ak47)

    // armor
    Item.registerItems(Armor.jacket, Armor.trousers, Armor.boots)

    // hats
    Item.registerItems(Hats.gasMask)

    // planes
    Item.registerItems(Planes.fighter)

    // cars
    Item.registerItems(Cars.truck)

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
                LuckPermsProvider
                    .get()
                    .userManager
                    .getUser(player.uuid)
                    ?.cachedData
                    ?.permissionData
                    ?.checkPermission(permission)
                    ?.asBoolean()
                    ?: false
            } else {
                true // console
            }
        })
        .enable()

    SignedVelocity.initialize()

    // Set axiom permission logic
    AxiomPermissions.setPermissionPredicate { player: Player, permission: AxiomPermission ->
        LuckPermsProvider
            .get()
            .userManager
            .getUser(player.uuid)
            ?.cachedData
            ?.permissionData
            ?.checkPermission(permission.permissionNode)
            ?.asBoolean()
            ?: false
    }

    // blocks and stuff
    BlockPlacementRuleRegistrations.registerDefault()
    BlockBehaviorRuleRegistrations.registerDefault()
    PlacedHandlerRegistration.registerDefault()
    BlockPickup.enable()
    MinestomFluids.enableFluids()
    MinestomFluids.enableVanillaFluids()
    MinestomFluids.enableAutoIngestion()

    Combat.initialize()

    val nodesConfig = NodesConfig()

    Nodes.initialize(nodesConfig)

    Vanilla.init()
}
