package net.aechronis.combat

import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.objects.Vehicle
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CombatTest {
    @BeforeAll
    fun setup() {
        // start server
        MinecraftServer.init(Auth.Online()).start("0.0.0.0", 25565)

        // create instance
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.setGenerator(TestGenerator())

        val eventNode = EventNode.all("test-node").setPriority(0)

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)

        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val player = event.player
            event.spawningInstance = instance
            player.respawnPoint = Pos(27000.0, 60.0, 5700.0)
            player.gameMode = GameMode.SURVIVAL
        }

        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            event.player.showBossBar(bossBar)
        }

        eventNode.addListener(ServerTickMonitorEvent::class.java) { e ->
            val tickTime = floor(e.tickMonitor.tickTime * 100.0) / 100.0
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024

            bossBar.name(
                Component
                    .text()
                    .append(Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB")),
            )
            bossBar.progress(min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat())

            if (tickTime > MinecraftServer.TICK_MS) {
                bossBar.color(BossBar.Color.RED)
            } else {
                bossBar.color(BossBar.Color.GREEN)
            }
        }

        val testAmmo =
            Ammo(
                name = "test-ammo",
                itemName = Component.text("Test Ammo", NamedTextColor.GOLD),
            )

        val testGun =
            Gun(
                name = "test-gun",
                itemName = Component.text("Test Gun", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                ammo = testAmmo,
                maxAmmo = 30,
                damage = 25F,
                sniper = true,
                automatic = true,
                cooldown = 100,
                reloadTime = 3000,
                recoilMin = 3F,
                recoilMax = 7F,
                spreadMin = 0.0F,
                spreadMax = 3F,
            )

        val testHat =
            Hat(
                name = "test-hat",
                itemName = Component.text("Test hat", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            )

        val testChestplate =
            ArmorPiece(
                name = "test-chestplate",
                itemName = Component.text("Test chestplate", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                slot = EquipmentSlot.CHESTPLATE,
                protection = 0.25F,
                assetId = "combat:test-armor",
            )

        val testLeggings =
            ArmorPiece(
                name = "test-leggings",
                itemName = Component.text("Test leggings", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                slot = EquipmentSlot.LEGGINGS,
                protection = 0.2F,
                assetId = "combat:test-armor",
            )

        val testBoots =
            ArmorPiece(
                name = "test-boots",
                itemName = Component.text("Test boots", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                slot = EquipmentSlot.BOOTS,
                protection = 0.1F,
                assetId = "combat:test-armor",
            )

        val testSword =
            Melee(
                name = "test-sword",
                itemName = Component.text("Test Sword", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                itemModel = "minecraft:diamond_sword",
                damage = 1.0,
                attackSpeed = 1.6,
                sweepable = true,
            )

        Item.registerItems(testAmmo, testGun, testHat, testChestplate, testLeggings, testBoots, testSword)

        // initialize combat with test config
        Combat.initialize()
    }

    @Test
    fun `placeholder test`() {
        assertTrue(true)
    }

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }
}
