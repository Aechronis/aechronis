package net.aechronis.aechronis

import fr.ghostrider584.axiom.AxiomMinestom
import fr.ghostrider584.axiom.restrictions.AxiomPermission
import fr.ghostrider584.axiom.restrictions.AxiomPermissions
import io.github._4drian3d.signedvelocity.minestom.SignedVelocity
import io.github.openminigameserver.worldedit.MinestomWorldEdit
import me.lucko.luckperms.common.config.generic.adapter.EnvironmentVariableConfigAdapter
import me.lucko.luckperms.minestom.CommandRegistry
import me.lucko.luckperms.minestom.LuckPermsMinestom
import me.lucko.spark.minestom.SparkMinestom
import net.aechronis.aechronis.constants.Ammo
import net.aechronis.aechronis.constants.Armor
import net.aechronis.aechronis.constants.Cars
import net.aechronis.aechronis.constants.Drones
import net.aechronis.aechronis.constants.Guns
import net.aechronis.aechronis.constants.Planes
import net.aechronis.aechronis.constants.Tanks
import net.aechronis.aechronis.listeners.PlayerJoinListener
import net.aechronis.aechronis.tasks.TabManager
import net.aechronis.aechronis.tasks.WorldSaver
import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.logger.Logger
import net.aechronis.logger.LoggerConfig
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.NodesConfig
import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.objects.ShopItem
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.Node
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.registry.RegistryKey
import net.minestom.server.world.DimensionType
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import java.nio.file.Path

object Aechronis {
    internal const val VIEW_DISTANCE = 32
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
            .build()

    Aechronis.fullbrightKey = MinecraftServer.getDimensionTypeRegistry().register("aechronis:fullbright", fullbright)

    AxiomMinestom.initialize()

    MinecraftServer.getGlobalEventHandler().addChild(Aechronis.eventNode)

    // create instance
    Aechronis.instance = MinecraftServer.getInstanceManager().createInstanceContainer(Aechronis.fullbrightKey)
    Aechronis.instance.chunkLoader = AnvilLoader("world")
    Aechronis.instance.viewDistance(Aechronis.VIEW_DISTANCE)

    // tasks
    TabManager.start()
    WorldSaver.start()

    // register listeners
    PlayerJoinListener.init()

    // ammo
    Item.registerItems(Ammo.ammo762x39mm, Ammo.ammo9mm)

    // guns
    Item.registerItems(Guns.ak74, Guns.m4a1, Guns.m9)

    // armor
    Item.registerItems(Armor.usMarineJacket, Armor.usMarineTrousers, Armor.usMarineBoots)
    Item.registerItems(Armor.russianDesertJacket, Armor.russianDesertTrousers, Armor.russianDesertBoots)

    // planes
    Item.registerItems(Planes.f16)

    // cars
    Item.registerItems(Cars.truck)

    // tanks
    Item.registerItems(Tanks.m1a1Abrams)

    // drones
    Item.registerItems(Drones.scoutDrone, Drones.kamikazeDrone)

    // initialize luckperms
    LuckPermsMinestom
        .builder(Path.of("luckperms"))
        .commandRegistry(CommandRegistry.minestom())
        .configurationAdapter { plugin ->
            EnvironmentVariableConfigAdapter(plugin)
        }.enable()

    if (allPermsPlayers.isNotEmpty()) {
        Aechronis.eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            val player = event.player
            if (allPermsPlayers.any { it.equals(player.username, ignoreCase = true) }) {
                LuckPermsProvider.get().userManager.getUser(player.uuid)?.let { user ->
                    user.transientData().add(Node.builder("*").value(true).build())
                }
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

    val nodesConfig =
        NodesConfig(
            chunkAttackTime = 60000,
        )

    Nodes.initialize(nodesConfig)

    val logger = LoggerConfig(limit = 999999999)
    Logger.init(logger)

    val worldEdit = MinestomWorldEdit()
    worldEdit.init()

    server.start("0.0.0.0", port)
    val vanillaConfig =
        VanillaConfig(
            shopItems =
                listOf(
                    ShopItem(ItemStack.of(Material.COBBLESTONE, 64), cooldownTicks = 1200L, cost = 0),
                    ShopItem(ItemStack.of(Material.OAK_FENCE, 64), cooldownTicks = 1200L, cost = 0),
                    ShopItem(ItemStack.of(Material.COOKED_BEEF, 64), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Armor.russianDesertJacket.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Armor.russianDesertTrousers.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Armor.russianDesertBoots.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Armor.usMarineJacket.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Armor.usMarineTrousers.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Armor.usMarineBoots.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Guns.ak74.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Guns.m4a1.toItemStack(), cooldownTicks = 1200L, cost = 0),
                    ShopItem(Guns.m9.toItemStack(), cooldownTicks = 0L, cost = 0),
                    ShopItem(Ammo.ammo762x39mm.toItemStack().withAmount(16), cooldownTicks = 0L, cost = 0),
                    ShopItem(Ammo.ammo9mm.toItemStack().withAmount(16), cooldownTicks = 0L, cost = 0),
                    ShopItem(Cars.truck.toItemStack(), cooldownTicks = 0L, cost = 2),
                    ShopItem(Drones.scoutDrone.toItemStack(), cooldownTicks = 0L, cost = 5),
                    ShopItem(Drones.kamikazeDrone.toItemStack(), cooldownTicks = 0L, cost = 7),
                    ShopItem(Planes.f16.toItemStack(), cooldownTicks = 0L, cost = 10),
                    ShopItem(Tanks.m1a1Abrams.toItemStack(), cooldownTicks = 0L, cost = 20),
                ),
        )

    Vanilla.init(vanillaConfig)
}
