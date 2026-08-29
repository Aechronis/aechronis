package net.aechronis.combat

import net.aechronis.combat.listeners.AmmoInventoryListener
import net.aechronis.combat.listeners.PlayerDeathListener
import net.aechronis.combat.objects.AIMING_REDUCTION_MULTIPLIER
import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Explosion
import net.aechronis.combat.objects.Grenade
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneWeapon
import net.aechronis.combat.objects.Projectile
import net.aechronis.combat.objects.Tank
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.aimingMultiplier
import net.aechronis.combat.objects.damageAtDistance
import net.aechronis.combat.objects.distanceToBoundingBox
import net.aechronis.combat.objects.firstProjectileImpact
import net.aechronis.combat.objects.isBombRelease
import net.aechronis.combat.objects.selectProjectileImpact
import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.combat.tasks.BLOCK_RESTORE_DELAY_MILLIS
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.utils.HistoricalHitbox
import net.aechronis.combat.utils.HitboxSnapshot
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.calculateVehicleCameraDistance
import net.aechronis.combat.utils.interpolateHitbox
import net.aechronis.combat.utils.particleLinePointCount
import net.aechronis.combat.utils.withCombatDamageImmunityBypass
import net.aechronis.server.modules.ModuleContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.inventory.CreativeInventoryActionEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSwapItemEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.particle.Particle
import net.minestom.server.utils.inventory.PlayerInventoryUtils
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CombatTest {
    private lateinit var instance: InstanceContainer

    val shipGen =
        Generator { unit ->
            unit.modifier().fillHeight(0, 60, Block.WATER)
            if (unit.absoluteStart().blockX() == 320 && unit.absoluteStart().blockZ() == 320) {
                unit.modifier().setBlock(320, 60, 320, Block.STONE)
            }
        }

    @BeforeAll
    fun setup() {
        instance =
            CombatTestServer.createInstance(
                generator = shipGen,
                gameMode = GameMode.CREATIVE,
            )

        val testAmmo =
            Ammo(
                name = "test-ammo",
                ammoType = AmmoTypes.NORMAL,
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
                bulletTrailParticle = Particle.SMALL_GUST,
            )

        val testHat =
            Hat(
                name = "test-hat",
                itemName = Component.text("Test hat", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                itemModel = "combat:test-armor",
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

        val testPlaneHitbox =
            Hitbox(
                listOf(
                    HitboxPart(
                        offset = Vec(0.0, 0.0, -2.0),
                        size = Vec(1.0, 1.0, 8.0),
                    ),
                    HitboxPart(
                        offset = Vec.ZERO,
                        size = Vec(8.0, 1.0, 2.0),
                    ),
                ),
            )

        val testPlaneWeapon =
            PlaneWeapon(
                testGun,
                listOf(Vec(4.0, 0.0, 6.0), Vec(-4.0, 0.0, 6.0)),
            )

        val testPlane =
            Plane(
                name = "test-plane",
                itemName = Component.text("Test Plane", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                model = "aechronis:biplane",
                hitbox = testPlaneHitbox,
                health = testHealth(1000F),
                ammo = testAmmo,
                maxAmmo = 120,
                weapons = listOf(testPlaneWeapon),
                scale = 7.0,
                speed = 1.25,
                turnSpeed = 0.1f,
                seatOffset = listOf(Vec(0.0, 3.0, 0.0)),
            )

        val testCarHitbox =
            Hitbox(
                listOf(
                    HitboxPart(
                        offset = Vec(0.4, 0.0, -1.0),
                        size = Vec(1.4, 1.0, 3.0),
                    ),
                ),
            )

        val testCar =
            Car(
                name = "test-car",
                itemName = Component.text("Test Car", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                model = "aechronis:truck",
                hitbox = testCarHitbox,
                health = testHealth(100F),
                scale = 3.0,
                seatOffsets = listOf(Vec.ZERO, Vec(1.0, 0.0, 0.0)),
            )

        val testBoat =
            Boat(
                name = "test-ship",
                itemName = Component.text("Test Ship", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                model = "aechronis:boat",
                hitbox =
                    Hitbox(
                        listOf(
                            HitboxPart(
                                offset = Vec(0.0, 1.0, 0.0),
                                size = Vec(1.0, 1.0, 1.0),
                            ),
                        ),
                    ),
                scale = 3.0,
                health = testHealth(100F),
                seatOffsets = listOf(Vec.ZERO, Vec(1.0, 0.0, 0.0)),
            )

        val testTankHitbox =
            Hitbox(
                listOf(
                    HitboxPart(
                        offset = Vec(0.0, 0.0, 0.0),
                        size = Vec(1.7, 0.8, 2.7),
                    ),
                    HitboxPart(
                        offset = Vec(0.0, 0.9, 0.0),
                        size = Vec(1.2, 0.45, 1.4),
                    ),
                ),
            )

        val testTank =
            Tank(
                name = "m1a1-abrams",
                itemName = Component.text("Test Tank", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                model = "aechronis:m1a1-abrams",
                hitbox = testTankHitbox,
                scale = 3.0,
                health = testHealth(500F),
                ammo = testAmmo,
                maxAmmo = 10,
                placeTime = 1500,
                maxSpeed = 0.18f,
                acceleration = 0.008f,
                braking = 0.02f,
                friction = 0.96f,
                turnSpeed = 1.5f,
                maxClimbHeight = 1.0f,
                turretTraverseSpeed = 3.0f,
                projectileModel = "aechronis:m1a1-abrams-shell",
                projectileSpeed = 4.0,
                projectileExplosionRadius = 4,
                projectileExplosionFire = 0.1,
                barrelTipOffset = Vec(0.0, 0.0, 5.0),
                fireCooldown = 1000,
                seatOffsets = listOf(Vec(0.0, 0.95, -0.9), Vec(0.0, 1.35, 0.0)),
            )

        val testDroneHitbox =
            Hitbox(
                listOf(
                    HitboxPart(
                        offset = Vec(0.0, -0.5, 0.0),
                        size = Vec(1.0, 0.5, 1.0),
                    ),
                ),
            )

        val testDrone =
            Drone(
                name = "drone",
                itemName = Component.text("drone"),
                scale = 1.5,
                hitbox = testDroneHitbox,
                projectileModel = "aechronis:rpg-rocket",
                projectileScale = 0.5,
                projectileMountOffset = Vec(0.0, -0.5, 0.0),
            )

        Item.registerItems(
            testAmmo,
            testGun,
            testHat,
            testChestplate,
            testLeggings,
            testBoots,
            testSword,
            testPlane,
            testCar,
            testBoat,
            testTank,
            testDrone,
        )

        // initialize combat with test config
        BlockRestoreManager.restoreDelayMillis = 1_000L
        Combat.initialize()
    }

    @Test
    fun `vehicle persistence saves health and ammo but excludes drones`(
        @TempDir temporaryDirectory: Path,
    ) {
        val tank = Item.getFromName("m1a1-abrams") as Tank
        val drone = Item.getFromName("drone") as Drone
        val tankEntity = tank.spawn(instance, Pos(80.0, 61.0, 80.0))
        val droneEntity = drone.spawn(instance, Pos(90.0, 61.0, 90.0))
        Vehicle.entityHealth[tankEntity]!!.takeHp(AmmoTypes.NORMAL)
        Vehicle.entityAmmo[tankEntity] = 3

        var restoredPlane: Entity? = null
        try {
            val savePath = temporaryDirectory.resolve("vehicles.json")
            Files.writeString(
                savePath,
                """
                {
                  "version": 1,
                  "vehicles": [
                    {"type": "test-plane", "x": 320.0, "y": 100.0, "z": 320.0}
                  ]
                }
                """.trimIndent(),
            )
            assertFalse(instance.isChunkLoaded(20, 20))

            VehiclePersistence.initialize(savePath, instance)
            restoredPlane =
                assertNotNull(
                    Vehicle.entityVehicle.entries
                        .singleOrNull { (_, vehicle) ->
                            vehicle.name == "test-plane"
                        },
                ).key
            assertTrue(instance.isChunkLoaded(20, 20))

            VehiclePersistence.save()
            val saved = Files.readString(savePath)
            assertTrue(saved.contains("\"type\": \"m1a1-abrams\""))
            assertTrue(saved.contains("\"type\": \"test-plane\""))
            assertTrue(saved.contains("\"health\": 490.0"))
            assertTrue(saved.contains("\"ammo\": 3"))
            assertFalse(saved.contains("\"type\": \"drone\""))

            Files.delete(savePath)
            Files.createDirectory(savePath)
            Files.writeString(savePath.resolve("prevent-replacement"), "test")
            val writeFailure = assertFailsWith<IllegalStateException> { VehiclePersistence.save() }
            assertTrue(writeFailure.message.orEmpty().contains("Failed to save vehicles"))
        } finally {
            restoredPlane?.let { entity -> Vehicle.entityVehicle[entity]?.destroy(entity) }
            tank.destroy(tankEntity)
            drone.destroy(droneEntity)
        }
    }

    @Test
    fun `grenades have a stack size of one`() {
        val grenade = Grenade(name = "test-grenade", itemName = Component.empty())

        assertEquals(1, grenade.toItemStack().maxStackSize())
    }

    @Test
    fun `ammo cannot be placed in equipment slots`() {
        val ammo = (Item.getFromName("test-ammo") as Ammo).toItemStack()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "ammo-inventory"))
        val restrictedSlots =
            (PlayerInventoryUtils.HELMET_SLOT..PlayerInventoryUtils.BOOTS_SLOT) + PlayerInventoryUtils.OFFHAND_SLOT

        for (slot in restrictedSlots) {
            player.inventory.cursorItem = ammo
            val event = InventoryPreClickEvent(player.inventory, player, Click.Left(slot))
            AmmoInventoryListener.onInventoryClick(event)
            assertTrue(event.isCancelled, "ammo should not be placeable in slot $slot")
        }

        player.inventory.cursorItem = ammo
        val dragEvent =
            InventoryPreClickEvent(
                player.inventory,
                player,
                Click.LeftDrag(listOf(PlayerInventoryUtils.HELMET_SLOT)),
            )
        AmmoInventoryListener.onInventoryClick(dragEvent)
        assertTrue(dragEvent.isCancelled)

        player.inventory.setItemStack(0, ammo)
        val hotbarSwapEvent =
            InventoryPreClickEvent(
                player.inventory,
                player,
                Click.HotbarSwap(0, PlayerInventoryUtils.CHESTPLATE_SLOT),
            )
        AmmoInventoryListener.onInventoryClick(hotbarSwapEvent)
        assertTrue(hotbarSwapEvent.isCancelled)

        val offhandSwapEvent = InventoryPreClickEvent(player.inventory, player, Click.OffhandSwap(0))
        AmmoInventoryListener.onInventoryClick(offhandSwapEvent)
        assertTrue(offhandSwapEvent.isCancelled)

        player.inventory.setItemStack(PlayerInventoryUtils.OFFHAND_SLOT, ammo)
        val armorOffhandSwapEvent =
            InventoryPreClickEvent(
                player.inventory,
                player,
                Click.OffhandSwap(PlayerInventoryUtils.CHESTPLATE_SLOT),
            )
        AmmoInventoryListener.onInventoryClick(armorOffhandSwapEvent)
        assertTrue(armorOffhandSwapEvent.isCancelled)

        val creativeEvent = CreativeInventoryActionEvent(player, PlayerInventoryUtils.OFFHAND_SLOT, ammo)
        AmmoInventoryListener.onCreativeInventoryAction(creativeEvent)
        assertTrue(creativeEvent.isCancelled)

        val handSwapEvent = PlayerSwapItemEvent(player, ammo, ItemStack.AIR)
        AmmoInventoryListener.onPlayerSwapItem(handSwapEvent)
        assertTrue(handSwapEvent.isCancelled)
    }

    @Test
    fun `reload ammo ignores crafting equipment and offhand slots`() {
        val ammo = Item.getFromName("test-ammo") as Ammo
        val ammoStack = ammo.toItemStack()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "reload-ammo"))

        for (slot in PlayerInventoryUtils.CRAFT_RESULT..PlayerInventoryUtils.OFFHAND_SLOT) {
            player.inventory.setItemStack(slot, ammoStack)
        }
        assertEquals(0, ammo[player])

        player.inventory.setItemStack(0, ammoStack.withAmount(3))
        assertEquals(3, ammo[player])

        ammo[player] = 2

        assertEquals(2, player.inventory.getItemStack(0).amount())
        for (slot in PlayerInventoryUtils.CRAFT_RESULT..PlayerInventoryUtils.OFFHAND_SLOT) {
            assertEquals(1, player.inventory.getItemStack(slot).amount(), "ammo in slot $slot should not be consumed")
        }
    }

    @Test
    fun `armor remains placeable in armor slots`() {
        val chestplate = (Item.getFromName("test-chestplate") as ArmorPiece).toItemStack()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "armor-inventory"))
        player.inventory.cursorItem = chestplate

        val event = InventoryPreClickEvent(player.inventory, player, Click.Left(PlayerInventoryUtils.CHESTPLATE_SLOT))
        AmmoInventoryListener.onInventoryClick(event)

        assertFalse(event.isCancelled)
    }

    @Test
    fun `hats provide helmet armor protection`() {
        val hat = Hat(name = "protection-test-hat", itemName = Component.empty(), protection = 0.15F)
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "hat-protection"))
        Item.registerItems(hat)
        player.helmet = hat.toItemStack()

        assertEquals(0.85F, ArmorPiece.getTotalProtection(player), 0.0001F)
        assertEquals(1, player.helmet.maxStackSize())
    }

    @Test
    fun `plane bomb only releases on the initial fire-key press`() {
        assertTrue(isBombRelease(isHoldingFireKey = true, wasHoldingFireKey = false))
        assertFalse(isBombRelease(isHoldingFireKey = true, wasHoldingFireKey = true))
        assertFalse(isBombRelease(isHoldingFireKey = false, wasHoldingFireKey = false))
    }

    @Test
    fun `unattended plane dive accelerates up to its configured maximum`() {
        val ammo = Ammo("dive-test-ammo", AmmoTypes.NORMAL, Component.empty())
        val plane =
            Plane(
                name = "dive-test-plane",
                itemName = Component.empty(),
                scale = 1.0,
                hitbox = Hitbox(emptyList()),
                health = testHealth(10f),
                ammo = ammo,
                maxAmmo = 1,
                diveAcceleration = 0.25,
                maxDiveSpeed = 0.7,
            )

        assertEquals(0.55, plane.nextDiveSpeed(0.3), 0.0001)
        assertEquals(0.7, plane.nextDiveSpeed(0.55), 0.0001)
    }

    @Test
    fun `health applies configured ammo damage and ignores missing ammo`() {
        val health = Health(100F, mapOf(AmmoTypes.NORMAL to 15F))

        assertFalse(health.takeHp(AmmoTypes.EXPLOSIVE))
        assertEquals(100F, health.health)
        assertFalse(health.takeHp(AmmoTypes.NORMAL))
        assertEquals(85F, health.health)
    }

    @Test
    fun `health reports depletion after applying damage`() {
        val health = Health(10F, mapOf(AmmoTypes.MISSILE to 15F))

        assertTrue(health.takeHp(AmmoTypes.MISSILE))
        assertEquals(0F, health.health)
    }

    @Test
    fun `fresh health instances do not share current health`() {
        val template = Health(20F, mapOf(AmmoTypes.NORMAL to 5F))
        val first = template.fresh()
        val second = template.fresh()

        first.takeHp(AmmoTypes.NORMAL)

        assertEquals(15F, first.health)
        assertEquals(20F, second.health)
    }

    @Test
    fun `hitbox collision resolves an entity outside the vehicle`() {
        val hitbox = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(1.0, 1.0, 1.0))))

        val collision =
            hitbox.resolveCollision(
                Pos.ZERO,
                0f,
                0f,
                0f,
                Pos.ZERO,
                Vec(-0.25, 0.0, -0.25),
                Vec(0.25, 1.8, 0.25),
            )

        assertNotNull(collision)
        assertTrue(collision.position.x > 1.0)
        assertTrue(collision.normal.x > 0.0)
    }

    @Test
    fun `hitbox collision ignores an entity outside the hitbox`() {
        val hitbox = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(1.0, 1.0, 1.0))))

        assertNull(
            hitbox.resolveCollision(
                Pos.ZERO,
                0f,
                0f,
                0f,
                Pos(2.0, 0.0, 0.0),
                Vec(-0.25, 0.0, -0.25),
                Vec(0.25, 1.8, 0.25),
            ),
        )
    }

    @Test
    fun `vehicle hitboxes detect rotated overlap and separation`() {
        val hitbox = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(1.0, 1.0, 2.0))))

        assertTrue(hitbox.intersects(hitbox, Pos.ZERO, 45f, 0f, 0f, Pos(1.0, 0.0, 0.0), -45f, 0f, 0f))
        assertFalse(hitbox.intersects(hitbox, Pos.ZERO, 0f, 0f, 0f, Pos(10.0, 0.0, 0.0), 0f, 0f, 0f))
    }

    @Test
    fun `hitbox distance includes offsets and half extents`() {
        val hitbox =
            Hitbox(
                listOf(
                    HitboxPart(
                        offset = Vec(2.0, -1.0, 3.0),
                        size = Vec(1.0, 2.0, 0.5),
                    ),
                ),
            )

        assertEquals(kotlin.math.sqrt(26.25), hitbox.getMaxDistanceFrom(Vec(1.0, 1.0, 1.0)), 0.0001)
    }

    @Test
    fun `hitbox distance uses the farthest part`() {
        val hitbox =
            Hitbox(
                listOf(
                    HitboxPart(Vec.ZERO, Vec(1.0, 1.0, 1.0)),
                    HitboxPart(Vec(5.0, 0.0, 0.0), Vec(1.0, 1.0, 1.0)),
                ),
            )

        assertEquals(kotlin.math.sqrt(38.0), hitbox.getMaxDistanceFrom(Vec.ZERO), 0.0001)
        assertEquals(0.0, Hitbox(emptyList()).getMaxDistanceFrom(Vec.ZERO))
    }

    @Test
    fun `vehicle camera distance frames the hitbox from the seat and accounts for scale`() {
        val hitbox = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(8.0, 0.0, 0.0))))

        assertEquals(7.25, calculateVehicleCameraDistance(hitbox, Vec.ZERO, 2.0), 0.0001)
        assertEquals(14.5, calculateVehicleCameraDistance(hitbox, Vec.ZERO, 1.0), 0.0001)
    }

    @Test
    fun `vehicle camera distance keeps defaults and clamps oversized hitboxes`() {
        assertEquals(4.0, calculateVehicleCameraDistance(Hitbox(emptyList()), Vec.ZERO, 1.0))

        val oversized = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(100.0, 100.0, 100.0))))
        assertEquals(32.0, calculateVehicleCameraDistance(oversized, Vec.ZERO, 1.0))
    }

    @Test
    fun `vehicle uses configured ammo damage and destroys itself at zero health`() {
        val vehicle = TestBoat()
        val entity = Entity(EntityType.ITEM_DISPLAY)
        Vehicle.entityHealth[entity] = Health(20F, mapOf(AmmoTypes.NORMAL to 7F))

        try {
            assertFalse(vehicle.takeDamage(entity, null, 1000F, null))
            assertEquals(20F, Vehicle.entityHealth[entity]?.health)

            assertFalse(vehicle.takeDamage(entity, AmmoTypes.NORMAL, 1000F, null))
            assertEquals(13F, Vehicle.entityHealth[entity]?.health)

            assertFalse(vehicle.takeDamage(entity, AmmoTypes.NORMAL, 1000F, null))
            assertTrue(vehicle.takeDamage(entity, AmmoTypes.NORMAL, 1000F, null))
            assertFalse(Vehicle.entityHealth.containsKey(entity))
        } finally {
            Vehicle.entityHealth.remove(entity)
        }
    }

    @Test
    fun `explosion damages a vehicle when only its hitbox is in range`() {
        val vehicle =
            Car(
                name = "explosion-hitbox-target",
                itemName = Component.empty(),
                scale = 1.0,
                hitbox = Hitbox(listOf(HitboxPart(Vec(5.0, 0.0, 0.0), Vec(1.0, 1.0, 1.0)))),
                health = Health(10F, mapOf(AmmoTypes.BOMB to 4F)),
            )
        val entity = vehicle.spawn(instance, Pos(120.0, 61.0, 120.0))
        val impact = entity.position.add(5.0, 0.0, 0.0)

        try {
            assertEquals(0.0, vehicle.hitbox.distanceToPoint(impact, entity.position, 0f, 0f, 0f), 0.0001)
            assertEquals(0f, damageAtDistance(20f, 1, entity.position.distance(impact)))

            Explosion(
                instance = instance,
                pos = impact,
                radius = 1,
                fire = 0.0,
                damage = 20f,
                ammoType = AmmoTypes.BOMB,
            )

            assertEquals(6F, Vehicle.entityHealth[entity]?.health)
        } finally {
            if (Vehicle.entityVehicle.containsKey(entity)) vehicle.destroy(entity)
        }
    }

    @Test
    fun `drone retains raw damage health`() {
        val drone =
            Drone(
                name = "health-test-drone",
                itemName = Component.text("Health Test Drone"),
                scale = 1.0,
                hitbox = Hitbox(emptyList()),
            )
        val entity = Entity(EntityType.ITEM_DISPLAY)
        Drone.entityHealth[entity] = drone.rawHealth

        try {
            assertFalse(drone.takeDamage(entity, null, 0.25F, null, null))
            assertEquals(0.75F, Drone.entityHealth[entity])
            assertTrue(drone.takeDamage(entity, null, 0.75F, null, null))
            assertFalse(Drone.entityHealth.containsKey(entity))
        } finally {
            Drone.entityHealth.remove(entity)
        }
    }

    @Test
    fun `ship float height controls how much of the hitbox is above water`() {
        val surfaceY = 10.0

        assertEquals(7.0, TestBoat(0.0).vehicleY(surfaceY))
        assertEquals(9.0, TestBoat(0.5).vehicleY(surfaceY))
        assertEquals(11.0, TestBoat(1.0).vehicleY(surfaceY))
    }

    @Test
    fun `ship float height defaults to current center position`() {
        val ship = TestBoat()
        val surfaceY = 10.0
        val vehicleY = ship.vehicleY(surfaceY)

        assertEquals(surfaceY - ship.hitbox.getCenterOffset().y, vehicleY)
        assertEquals(surfaceY, ship.currentSurfaceY(Pos(0.0, vehicleY, 0.0)))
    }

    @Test
    fun `ship float height must be between zero and one`() {
        assertFailsWith<IllegalArgumentException> { TestBoat(-0.01) }
        assertFailsWith<IllegalArgumentException> { TestBoat(1.01) }
        assertFailsWith<IllegalArgumentException> { TestBoat(Double.NaN) }
    }

    @Test
    fun `fully out ship can move while touching water`() {
        instance.loadChunk(0, 0).join()
        val ship = TestBoat(1.0)
        val waterSurfaceY = 60.0
        val position = Pos(8.0, ship.vehicleY(waterSurfaceY), 8.0)

        assertTrue(ship.canMove(instance, position))
    }

    @Test
    fun `tank damage bypasses recent damage immunity and starts a new immunity window`() {
        val target = LivingEntity(EntityType.ZOMBIE)
        target.health = 20f
        Combat.entityLastDamageTime[target] = 1_000L

        try {
            val blockedDamage = Damage(DamageType.EXPLOSION, null, null, null, 5f)
            assertFalse(Combat.applyDamage(target, blockedDamage, now = 1_200L))
            assertEquals(20f, target.health)

            val tankDamage =
                Damage(DamageType.EXPLOSION, null, null, null, 5f)
                    .withCombatDamageImmunityBypass()
            assertTrue(Combat.applyDamage(target, tankDamage, now = 1_200L))
            assertEquals(15f, target.health)
            assertFalse(Combat.canDamage(target, now = 1_699L))
            assertTrue(Combat.canDamage(target, now = 1_700L))
        } finally {
            Combat.entityLastDamageTime.remove(target)
        }
    }

    @Test
    fun `explosion directly below an entity deals damage`() {
        val target = LivingEntity(EntityType.ZOMBIE)
        val impactBelowFeet = target.position.sub(0.0, 0.1, 0.0)
        val distance = distanceToBoundingBox(target, impactBelowFeet)

        assertEquals(0.1, distance, 0.0001)
        assertTrue(damageAtDistance(20f, 4, distance) > 0f)
    }

    @Test
    fun `explosion distance uses the closest point on the entity hitbox`() {
        val target = LivingEntity(EntityType.ZOMBIE)
        val impactNearHead = target.position.add(0.0, target.boundingBox.height() - 0.1, 0.0)
        val hitboxDistance = distanceToBoundingBox(target, impactNearHead)

        assertEquals(0.0, hitboxDistance)
        assertEquals(0f, damageAtDistance(20f, 1, target.position.distance(impactNearHead)))
        assertTrue(damageAtDistance(20f, 1, hitboxDistance) > 0f)
    }

    @Test
    fun `explosion damages a player standing on an affected block despite damage immunity`() {
        val block = BlockVec(160, 60, 160)
        instance.loadChunk(block.blockX(), block.blockZ()).join()
        instance.setBlock(block, Block.STONE)

        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "explosion-target"))
        player.setInstance(instance, Pos(160.5, 61.0, 160.5)).join()
        player.gameMode = GameMode.SURVIVAL
        player.health = 20f
        Combat.entityLastDamageTime[player] = System.currentTimeMillis()

        try {
            // The player is two blocks above the impact and outside the radius, but
            // the block under their feet is part of the blast.
            Explosion(
                instance = instance,
                pos = Pos(160.5, 59.0, 160.5),
                radius = 1,
                fire = 0.0,
                damage = 20f,
            )

            assertEquals(19f, player.health)
        } finally {
            Combat.entityLastDamageTime.remove(player)
            player.remove()
        }
    }

    @Test
    fun `explosion damage remains based on configured weapon damage`() {
        assertEquals(10f, damageAtDistance(10f, 4, 0.0))
        assertEquals(30f, damageAtDistance(30f, 4, 0.0))
        assertEquals(5f, damageAtDistance(10f, 4, 2.0))
        assertEquals(15f, damageAtDistance(30f, 4, 2.0))
    }

    @Test
    fun `tracer line sampling preserves endpoints within its particle budget`() {
        assertEquals(1, particleLinePointCount(0.0))
        assertEquals(3, particleLinePointCount(1.0, spacing = 0.5))
        assertEquals(96, particleLinePointCount(128.0))
        assertEquals(10, particleLinePointCount(200.0, spacing = 1.0, maxParticles = 10))
    }

    @Test
    fun `projectile expires when it reaches its configured range`() {
        instance.loadChunk(0, 0).join()
        val projectile =
            Projectile(
                instance = instance,
                pos = Pos(4.0, 70.0, 4.0),
                model = "aechronis:m1a1-abrams-shell",
                direction = Vec(1.0, 0.0, 0.0),
                speed = 4.0,
                gravity = 0.0,
                maxRange = 2.0,
            )

        projectile.onTick()

        assertFalse(projectile.isActive)
    }

    @Test
    fun `projectile uses the nearest entity or block impact`() {
        val target = LivingEntity(EntityType.ZOMBIE)
        val entityHit = Ray.Hit(1.0, Pos(1.0, 0.0, 0.0), target)
        val blockHit = Ray.Hit(2.0, Pos(2.0, 0.0, 0.0), Block.STONE)

        assertEquals(entityHit.point, selectProjectileImpact(blockHit, entityHit)?.point)
        assertEquals(blockHit.point, selectProjectileImpact(blockHit.copy(t = 0.5), entityHit)?.point)
    }

    @Test
    fun `projectile detects living entities and ignores tank occupants`() {
        instance.loadChunk(0, 0).join()
        val target = LivingEntity(EntityType.ZOMBIE)
        target.setInstance(instance, Pos(8.0, 61.0, 8.0)).join()
        target.spawn()

        try {
            val ray = Ray(Pos(4.0, 62.0, 8.0), Vec(8.0, 0.0, 0.0))
            assertNotNull(firstProjectileImpact(ray, instance))
            assertNull(firstProjectileImpact(ray, instance, setOf(target)))
        } finally {
            target.remove()
        }
    }

    @Test
    fun `lag compensation interpolates between historical hitboxes`() {
        val snapshots =
            listOf(
                HitboxSnapshot(100L, HistoricalHitbox(Vec(0.0, 0.0, 0.0), Vec(1.0, 1.0, 1.0))),
                HitboxSnapshot(200L, HistoricalHitbox(Vec(10.0, 10.0, 10.0), Vec(11.0, 11.0, 11.0))),
            )

        val interpolated = assertNotNull(interpolateHitbox(snapshots, 150L))

        assertEquals(5.0, interpolated.minimum.x(), 0.0001)
        assertEquals(5.0, interpolated.minimum.y(), 0.0001)
        assertEquals(6.0, interpolated.maximum.x(), 0.0001)
        assertNull(interpolateHitbox(snapshots, 99L))
        assertEquals(10.0, assertNotNull(interpolateHitbox(snapshots, 250L)).minimum.x(), 0.0001)
    }

    @Test
    fun `lag compensation reads histories while they are recorded concurrently`() {
        val shooter = Player(TestConnection(), GameProfile(UUID.randomUUID(), "lag-shooter"))
        val target = Player(TestConnection(), GameProfile(UUID.randomUUID(), "lag-target"))
        shooter.setInstance(instance, Pos(220.0, 61.0, 220.0)).join()
        target.setInstance(instance, Pos(225.0, 61.0, 220.0)).join()
        val timestamp = System.nanoTime()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)

        try {
            LagCompensation.recordPlayer(target, timestamp - 1L)
            val writer =
                executor.submit {
                    start.await()
                    repeat(20_000) { offset ->
                        LagCompensation.recordPlayer(target, timestamp + offset)
                    }
                }
            val reader =
                executor.submit {
                    start.await()
                    val ray = Ray(Pos(220.0, 62.0, 220.0), Vec(20.0, 0.0, 0.0))
                    repeat(20_000) {
                        LagCompensation.firstEntityHit(ray, shooter, instance, Long.MAX_VALUE)
                    }
                }

            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            LagCompensation.removePlayer(shooter)
            LagCompensation.removePlayer(target)
            shooter.remove()
            target.remove()
        }
    }

    @Test
    fun `ADS animation suppression defaults false and toggles by UUID`() {
        val playerUuid = UUID.randomUUID()

        assertFalse(Combat.isAdsAnimationDisabled(playerUuid))
        assertTrue(Combat.toggleAdsAnimation(playerUuid))
        assertTrue(Combat.isAdsAnimationDisabled(playerUuid))

        // The preference is keyed by UUID, so a reconnect using this UUID retains it.
        assertTrue(Combat.isAdsAnimationDisabled(playerUuid))
        assertFalse(Combat.toggleAdsAnimation(playerUuid))
        assertFalse(Combat.isAdsAnimationDisabled(playerUuid))
    }

    @Test
    fun `ADS reduces recoil and spread to 67 percent`() {
        assertEquals(AIMING_REDUCTION_MULTIPLIER, aimingMultiplier(true))
        assertEquals(1F, aimingMultiplier(false))
        assertEquals(6.7F, 10F * aimingMultiplier(true), 0.0001F)
    }

    @Test
    fun `temporary combat block changes last ten minutes by default`() {
        assertEquals(600_000L, BLOCK_RESTORE_DELAY_MILLIS)
    }

    @Test
    fun `explosion damage and fire are temporary`() {
        instance.loadChunk(0, 0).join()
        val position = BlockVec(10, 61, 10)
        val original = Block.OAK_LOG.withProperty("axis", "x")
        instance.setBlock(position, original)
        instance.setBlock(position.sub(0, 1, 0), Block.STONE)

        Explosion(
            instance = instance,
            pos = Pos(position.x(), position.y(), position.z()),
            radius = 0,
            fire = 1.0,
        )

        waitFor { instance.getBlock(position).compare(Block.FIRE) }
        waitFor { instance.getBlock(position).state() == original.state() }
    }

    @Test
    fun `leaf blocks are temporarily broken and restored`() {
        instance.loadChunk(0, 0).join()
        val position = BlockVec(8, 61, 8)
        val original = Block.OAK_LEAVES.withProperty("persistent", "true")
        val broken = CompletableFuture<Boolean>()
        instance.scheduleNextTick {
            instance.setBlock(position.blockX, position.blockY, position.blockZ, original)
            assertTrue(BlockRestoreManager.temporarilyBreakLeaf(instance, position, original))
            broken.complete(instance.getBlock(position).isAir)
        }

        assertTrue(broken.get(3, TimeUnit.SECONDS))

        waitFor { instance.getBlock(position).compare(original) }
    }

    @Test
    fun `module preparation restores temporary blocks before persistence`() {
        instance.loadChunk(0, 0).join()
        val position = BlockVec(10, 61, 8)
        val original = Block.OAK_LEAVES.withProperty("persistent", "true")
        val broken = CompletableFuture<Boolean>()
        val previousDelay = BlockRestoreManager.restoreDelayMillis
        BlockRestoreManager.restoreDelayMillis = 60_000L

        try {
            instance.scheduleNextTick {
                instance.setBlock(position.blockX, position.blockY, position.blockZ, original)
                assertTrue(BlockRestoreManager.temporarilyBreakLeaf(instance, position, original))
                broken.complete(instance.getBlock(position).isAir)
            }
            assertTrue(broken.get(3, TimeUnit.SECONDS))
            CombatModule().prepareForShutdown(ModuleContext())
            assertTrue(instance.getBlock(position).compare(original))
        } finally {
            BlockRestoreManager.restoreDelayMillis = previousDelay
            BlockRestoreManager.initialize()
        }
    }

    @Test
    fun `leaf restoration does not overwrite a changed block`() {
        instance.loadChunk(0, 0).join()
        val position = BlockVec(9, 61, 8)
        val original = Block.OAK_LEAVES
        val changed = CompletableFuture<Boolean>()
        instance.scheduleNextTick {
            instance.setBlock(position.blockX, position.blockY, position.blockZ, original)
            assertTrue(BlockRestoreManager.temporarilyBreakLeaf(instance, position, original))
            instance.setBlock(position.blockX, position.blockY, position.blockZ, Block.STONE)
            changed.complete(instance.getBlock(position).compare(Block.STONE))
        }

        assertTrue(changed.get(3, TimeUnit.SECONDS))
        waitFor { instance.getBlock(position).compare(Block.STONE) }
    }

    @Test
    fun `dismounted protected occupant immediately loses vehicle protection and is reconciled`() {
        val vehicle = TestBoat()
        val entity = vehicle.spawn(instance, Pos(200.0, 61.0, 200.0))
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "stale-occupant"))
        player.setInstance(instance, Pos(202.0, 61.0, 200.0)).join()

        try {
            vehicle.onEnter(player, entity)
            assertTrue(Vehicle.isProtectedOccupant(player))
            val seat = requireNotNull(player.vehicle)

            // This is the same state left behind by an external dismount.
            seat.removePassenger(player)

            assertFalse(Vehicle.isProtectedOccupant(player))
            Vehicle.reconcileOccupant(player)
            assertNull(Vehicle.playerVehicle[player])
            assertNull(Vehicle.playerVehicleEntity[player])
            assertNull(Vehicle.playerSeatEntity[player])
            assertNull(player.vehicle)
        } finally {
            if (Vehicle.entityVehicle.containsKey(entity)) vehicle.destroy(entity)
            player.remove()
        }
    }

    @Test
    fun `vehicle body invalidation force exits its protected occupant`() {
        val vehicle = TestBoat()
        val entity = vehicle.spawn(instance, Pos(205.0, 61.0, 205.0))
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "body-invalid"))
        player.setInstance(instance, Pos(207.0, 61.0, 205.0)).join()

        try {
            vehicle.onEnter(player, entity)
            assertTrue(Vehicle.isProtectedOccupant(player))

            Vehicle.invalidateEntity(entity)

            assertFalse(Vehicle.isProtectedOccupant(player))
            assertNull(Vehicle.playerVehicle[player])
            assertNull(Vehicle.playerVehicleEntity[player])
            assertNull(Vehicle.playerSeatEntity[player])
            assertNull(player.vehicle)
        } finally {
            if (Vehicle.entityVehicle.containsKey(entity)) vehicle.destroy(entity)
            player.remove()
        }
    }

    @Test
    fun `invalid direct vehicle entry does not create occupant state`() {
        val vehicle = TestBoat()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "invalid-vehicle"))
        player.setInstance(instance, Pos(210.0, 61.0, 210.0)).join()
        val unregisteredEntity = Entity(EntityType.ITEM_DISPLAY)

        try {
            vehicle.onEnter(player, unregisteredEntity)

            assertNull(Vehicle.playerVehicle[player])
            assertNull(Vehicle.playerVehicleEntity[player])
            assertNull(Vehicle.playerSeatEntity[player])
            assertNull(player.vehicle)
        } finally {
            player.remove()
        }
    }

    @Test
    fun `death grants exactly five seconds of respawn protection`() {
        val player = testPlayer("respawn-death")
        val before = System.currentTimeMillis()

        try {
            PlayerDeathListener.onPlayerDeath(PlayerDeathEvent(player, null, null))

            val expiresAt = assertNotNull(Combat.respawnProtectionExpiresAt[player])
            assertTrue(expiresAt in before + Combat.RESPAWN_PROTECTION_MS..System.currentTimeMillis() + Combat.RESPAWN_PROTECTION_MS)
        } finally {
            removeTestPlayers(player)
        }
    }

    @Test
    fun `respawn protection lasts until but not through its expiry`() {
        val player = testPlayer("respawn-expiry")

        try {
            Combat.grantRespawnProtection(player, now = 1_000L)

            assertTrue(Combat.isRespawnProtected(player, now = 1_000L))
            assertTrue(Combat.isRespawnProtected(player, now = 5_999L))
            assertFalse(Combat.isRespawnProtected(player, now = 6_000L))
            assertNull(Combat.respawnProtectionExpiresAt[player])
        } finally {
            removeTestPlayers(player)
        }
    }

    @Test
    fun `a later death replaces rather than extends respawn protection`() {
        val player = testPlayer("respawn-redeath")

        try {
            Combat.grantRespawnProtection(player, now = 1_000L)
            Combat.grantRespawnProtection(player, now = 4_000L)

            assertTrue(Combat.isRespawnProtected(player, now = 8_999L))
            assertFalse(Combat.isRespawnProtected(player, now = 9_000L))
        } finally {
            removeTestPlayers(player)
        }
    }

    @Test
    fun `disconnect clears respawn protection`() {
        val player = testPlayer("respawn-disconnect")
        Combat.grantRespawnProtection(player)

        try {
            Combat.eventNode.call(PlayerDisconnectEvent(player))
            assertNull(Combat.respawnProtectionExpiresAt[player])
        } finally {
            removeTestPlayers(player)
        }
    }

    @Test
    fun `respawn protection blocks every incoming combat damage source`() {
        val protected = testPlayer("protected-victim")
        val attacker = testPlayer("protected-attacker")
        Combat.grantRespawnProtection(protected)

        try {
            val damageSources =
                listOf(
                    Damage(DamageType.FALL, null, null, null, 1f),
                    Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1f),
                    Damage.fromProjectile(attacker, null, 1f),
                    Damage(DamageType.PLAYER_EXPLOSION, attacker, attacker, null, 1f),
                    Damage(DamageType.CRAMMING, attacker, attacker, null, 1f),
                    Damage(DamageType.EXPLOSION, null, null, null, 1f).withCombatDamageImmunityBypass(),
                )

            damageSources.forEach { damage ->
                assertFalse(Combat.applyDamageWithoutImmunity(protected, damage))
                assertEquals(20f, protected.health)
            }
        } finally {
            removeTestPlayers(protected, attacker)
        }
    }

    @Test
    fun `expired respawn protection permits damage normally`() {
        val player = testPlayer("expired-protection")
        Combat.grantRespawnProtection(player, now = 0L)

        try {
            assertTrue(
                Combat.applyDamageWithoutImmunity(
                    player,
                    Damage(DamageType.FALL, null, null, null, 1f),
                ),
            )
            assertEquals(19f, player.health)
            assertNull(Combat.respawnProtectionExpiresAt[player])
        } finally {
            removeTestPlayers(player)
        }
    }

    @Test
    fun `melee damage removes the attackers respawn protection`() {
        assertSuccessfulPlayerDamageRevokesProtection { attacker ->
            Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1f)
        }
    }

    @Test
    fun `projectile damage removes the attackers respawn protection`() {
        assertSuccessfulPlayerDamageRevokesProtection { attacker -> Damage.fromProjectile(attacker, null, 1f) }
    }

    @Test
    fun `explosion damage removes the attackers respawn protection`() {
        assertSuccessfulPlayerDamageRevokesProtection { attacker ->
            Damage(DamageType.PLAYER_EXPLOSION, attacker, attacker, null, 1f)
        }
    }

    @Test
    fun `vehicle impact damage removes the drivers respawn protection`() {
        assertSuccessfulPlayerDamageRevokesProtection { attacker ->
            Damage(DamageType.CRAMMING, attacker, attacker, null, 1f)
        }
    }

    @Test
    fun `direct external player damage removes respawn protection`() {
        val attacker = testPlayer("external-attacker")
        val victim = testPlayer("external-victim")
        Combat.grantRespawnProtection(attacker)

        try {
            assertTrue(victim.damage(Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1f)))
            assertFalse(Combat.isRespawnProtected(attacker))
        } finally {
            removeTestPlayers(attacker, victim)
        }
    }

    @Test
    fun `blocked damage does not remove the attackers respawn protection`() {
        val attacker = testPlayer("blocked-attacker")
        val victim = testPlayer("blocked-victim")
        Combat.grantRespawnProtection(attacker)
        Combat.grantRespawnProtection(victim)

        try {
            assertFalse(
                Combat.applyDamageWithoutImmunity(
                    victim,
                    Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1f),
                ),
            )
            assertTrue(Combat.isRespawnProtected(attacker))
        } finally {
            removeTestPlayers(attacker, victim)
        }
    }

    @Test
    fun `zero damage does not remove respawn protection`() {
        val attacker = testPlayer("zero-damage-attacker")
        val victim = testPlayer("zero-damage-victim")
        Combat.grantRespawnProtection(attacker)

        try {
            Combat.applyDamageWithoutImmunity(
                victim,
                Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 0f),
            )
            assertTrue(Combat.isRespawnProtected(attacker))
        } finally {
            removeTestPlayers(attacker, victim)
        }
    }

    @Test
    fun `self damage does not remove respawn protection`() {
        val player = testPlayer("self-damage")
        Combat.grantRespawnProtection(player)

        try {
            assertFalse(
                Combat.applyDamageWithoutImmunity(
                    player,
                    Damage(DamageType.PLAYER_EXPLOSION, player, player, null, 1f),
                ),
            )
            assertTrue(Combat.isRespawnProtected(player))
        } finally {
            removeTestPlayers(player)
        }
    }

    @Test
    fun `damage to a non-player does not remove respawn protection`() {
        val attacker = testPlayer("mob-attacker")
        val target = LivingEntity(EntityType.ZOMBIE)
        target.health = 20f
        Combat.grantRespawnProtection(attacker)

        try {
            assertTrue(
                Combat.applyDamageWithoutImmunity(
                    target,
                    Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1f),
                ),
            )
            assertTrue(Combat.isRespawnProtected(attacker))
        } finally {
            Combat.revokeRespawnProtection(attacker)
            attacker.remove()
            target.remove()
        }
    }

    private fun assertSuccessfulPlayerDamageRevokesProtection(damage: (Player) -> Damage) {
        val attacker = testPlayer("revoking-attacker")
        val victim = testPlayer("revoking-victim")
        Combat.grantRespawnProtection(attacker)

        try {
            assertTrue(Combat.applyDamageWithoutImmunity(victim, damage(attacker)))
            assertFalse(Combat.isRespawnProtected(attacker))
        } finally {
            removeTestPlayers(attacker, victim)
        }
    }

    private fun testPlayer(name: String): Player =
        Player(TestConnection(), GameProfile(UUID.randomUUID(), name.take(16))).also { player ->
            player.setInstance(instance, Pos(8.0, 61.0, 8.0)).join()
            player.gameMode = GameMode.SURVIVAL
            player.health = 20f
        }

    private fun removeTestPlayers(vararg players: Player) {
        players.forEach { player ->
            Combat.revokeRespawnProtection(player)
            Combat.entityLastDamageTime.remove(player)
            player.remove()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25L)
        assertTrue(condition())
    }

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private class TestBoat(
        floatHeight: Double = 0.5,
    ) : Boat(
            name = "float-height-test-ship",
            itemName = Component.text("Float Height Test Ship"),
            scale = 1.0,
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, 1.0, 0.0),
                            size = Vec(1.0, 2.0, 1.0),
                        ),
                    ),
                ),
            health = testHealth(100F),
            floatHeight = floatHeight,
        ) {
        fun vehicleY(surfaceY: Double): Double = getVehicleY(surfaceY)

        fun currentSurfaceY(position: Pos): Double = getCurrentSurfaceY(position)

        fun canMove(
            instance: Instance,
            position: Pos,
        ): Boolean = canStartMoving(instance, position)
    }
}

private fun testHealth(health: Float): Health =
    Health(
        health,
        mapOf(
            AmmoTypes.NORMAL to 10F,
            AmmoTypes.EXPLOSIVE to 25F,
            AmmoTypes.MISSILE to 50F,
            AmmoTypes.BOMB to 75F,
        ),
    )
