package net.aechronis.server

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.nodes.NodesModule
import net.aechronis.server.config.VictorianNodesConfiguration
import net.aechronis.server.config.VictorianVanillaConfiguration
import net.aechronis.server.constants.Airships
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Balloons
import net.aechronis.server.constants.Boats
import net.aechronis.server.constants.FieldPieces
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Melees
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.tasks.TabManager
import net.aechronis.vanilla.VanillaModule
import net.kyori.adventure.resource.ResourcePackInfo
import java.net.URI

class VictorianEraModule : AechronisModule {
    override val id = "victorian-era"
    override val externalResourcePacks =
        listOf(
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
                .hash("d312836c38143301b7ba6a1247372b3f467116db")
                .build(),
        )
    override val dependencies = setOf("combat", "vanilla", "recipes", "nodes")
    override val reloadTogetherInputs =
        mapOf(
            "vanilla" to
                setOf(
                    "net/aechronis/server/VictorianEraModule.class",
                    "net/aechronis/server/config/VictorianVanillaConfiguration.class",
                    "net/aechronis/server/craft/",
                ),
            "nodes" to
                setOf(
                    "net/aechronis/server/VictorianEraModule.class",
                    "net/aechronis/server/config/VictorianNodesConfiguration.class",
                ),
        )

    override fun configure(context: ModuleContext) {
        measure("Item catalogue") { registerItems() }
        val hands by lazy {
            checkNotNull(EmbeddedResourcePack.readAsset(javaClass, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" }
        }
        GunHandSkins.registerTemplate { hands }
        if (context.isModuleStarting("vanilla")) {
            measure("Vanilla configuration") { VanillaModule.configure(VictorianVanillaConfiguration.create()) }
        }
        if (context.isModuleStarting("nodes")) {
            measure("Nodes configuration") { NodesModule.configure(VictorianNodesConfiguration.create(context.spawnPoint)) }
        }
    }

    override fun initialize(context: ModuleContext) {
        measure("Restore item catalogue") { Combat.restoreItemCatalogue(context) }
        measure("Tab list") { TabManager.start() }
    }

    override fun resourcePackChanged(context: ModuleContext) {
        GunHandSkins.replaceTemplate(
            checkNotNull(context.readResourcePackAsset(id, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" },
        )
    }

    override fun prepareForShutdown(context: ModuleContext) {
        Combat.prepareItemCatalogueReload()
    }

    private fun registerItems() {
        Item.registerItems(
            *Ammo.all.toTypedArray(),
            *Guns.all.toTypedArray(),
            *Melees.all.toTypedArray(),
            *Armor.all.toTypedArray(),
            *Boats.all.toTypedArray(),
            FieldPieces.kruppC64,
            FieldPieces.gatlingGun,
            FieldPieces.maximGun,
            Balloons.hotAirBalloon,
            Airships.zeppelin,
            Airships.airship,
        )
    }

    override fun shutdown(context: ModuleContext) {
        TabManager.shutdown()
    }
}
