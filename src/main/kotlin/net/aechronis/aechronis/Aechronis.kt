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
import net.aechronis.aechronis.constants.Drones
import net.aechronis.aechronis.constants.Guns
import net.aechronis.aechronis.constants.Hats
import net.aechronis.aechronis.constants.Planes
import net.aechronis.aechronis.constants.Tanks
import net.aechronis.aechronis.listeners.PlayerJoinListener
import net.aechronis.aechronis.tasks.TabManager
import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.NodesConfig
import net.aechronis.vanilla.Vanilla
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.Node
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.world.DimensionType
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import java.nio.file.Path

object Aechronis {
    internal const val VIEW_DISTANCE = 32

    lateinit var instance: InstanceContainer
    val eventNode = EventNode.all("aechronis")
}

private fun Player.hasPermission(permission: String): Boolean =
    LuckPermsProvider
        .get()
        .userManager
        .getUser(uuid)
        ?.cachedData
        ?.permissionData
        ?.checkPermission(permission)
        ?.asBoolean()
        ?: false

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 25565
    val velocitySecret = args.getOrNull(1)

    val allPermsForTesting = System.getProperty("aechronis.allperms")?.toBoolean() == true

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

    val fullbrightKey = MinecraftServer.getDimensionTypeRegistry().register("aechronis:fullbright", fullbright)

    AxiomMinestom.initialize()

    MinecraftServer.getGlobalEventHandler().addChild(Aechronis.eventNode)

    // create instance
    Aechronis.instance = MinecraftServer.getInstanceManager().createInstanceContainer(fullbrightKey)
    Aechronis.instance.chunkLoader = AnvilLoader("world")
    Aechronis.instance.viewDistance(Aechronis.VIEW_DISTANCE)

    // tasks
    TabManager.start()

    // register listeners
    PlayerJoinListener.init()

    Item.registerItems(
        Ammo.ammo762x39mm,
        Guns.ak47,
        Armor.jacket,
        Armor.trousers,
        Armor.boots,
        Hats.gasMask,
        Planes.fighter,
        Cars.truck,
        Tanks.m1a1Abrams,
        Drones.scoutDrone,
        Drones.kamikazeDrone,
    )

    // initialize luckperms
    LuckPermsMinestom
        .builder(Path.of("luckperms"))
        .commandRegistry(CommandRegistry.minestom())
        .configurationAdapter { plugin ->
            EnvironmentVariableConfigAdapter(plugin)
        }.enable()

    if (allPermsForTesting) {
        LuckPermsProvider.get().groupManager.loadGroup("default").thenAccept { group ->
            group.ifPresent {
                it.transientData().add(Node.builder("*").value(true).build())
            }
        }
    }

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

    // Set axiom permission logic
    AxiomPermissions.setPermissionPredicate { player: Player, permission: AxiomPermission ->
        player.hasPermission(permission.permissionNode)
    }

    // blocks and stuff
    BlockPlacementRuleRegistrations.registerDefault()
    BlockBehaviorRuleRegistrations.registerDefault()
    PlacedHandlerRegistration.registerDefault()
    BlockPickup.enable()

    Combat.initialize()

    val nodesConfig = NodesConfig()

    Nodes.initialize(nodesConfig)

    Vanilla.init()

    server.start("0.0.0.0", port)
}
