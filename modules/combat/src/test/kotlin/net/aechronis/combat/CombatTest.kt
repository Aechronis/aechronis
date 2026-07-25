package net.aechronis.combat

import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneWeapon
import net.aechronis.combat.objects.Ship
import net.aechronis.combat.objects.Tank
import net.aechronis.utils.createTestServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import net.minestom.server.particle.Particle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CombatTest {
    private lateinit var instance: InstanceContainer

    val shipGen =
        Generator { unit ->
            unit.modifier().fillHeight(0, 60, Block.WATER)
        }

    @BeforeAll
    fun setup() {
        instance =
            createTestServer(
                generator = shipGen,
                gameMode = GameMode.CREATIVE,
            )

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
                scale = 3.0,
                seatOffsets = listOf(Vec.ZERO, Vec(1.0, 0.0, 0.0)),
            )

        val testShip =
            Ship(
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
                health = 500F,
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
            testShip,
            testTank,
            testDrone,
        )

        // initialize combat with test config
        Combat.initialize()
    }

    @Test
    fun `ship float height controls how much of the hitbox is above water`() {
        val surfaceY = 10.0

        assertEquals(7.0, TestShip(0.0).vehicleY(surfaceY))
        assertEquals(9.0, TestShip(0.5).vehicleY(surfaceY))
        assertEquals(11.0, TestShip(1.0).vehicleY(surfaceY))
    }

    @Test
    fun `ship float height defaults to current center position`() {
        val ship = TestShip()
        val surfaceY = 10.0
        val vehicleY = ship.vehicleY(surfaceY)

        assertEquals(surfaceY - ship.hitbox.getCenterOffset().y, vehicleY)
        assertEquals(surfaceY, ship.currentSurfaceY(Pos(0.0, vehicleY, 0.0)))
    }

    @Test
    fun `ship float height must be between zero and one`() {
        assertFailsWith<IllegalArgumentException> { TestShip(-0.01) }
        assertFailsWith<IllegalArgumentException> { TestShip(1.01) }
        assertFailsWith<IllegalArgumentException> { TestShip(Double.NaN) }
    }

    @Test
    fun `fully out ship can move while touching water`() {
        instance.loadChunk(0, 0).join()
        val ship = TestShip(1.0)
        val waterSurfaceY = 60.0
        val position = Pos(8.0, ship.vehicleY(waterSurfaceY), 8.0)

        assertTrue(ship.canMove(instance, position))
    }

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }

    private class TestShip(
        floatHeight: Double = 0.5,
    ) : Ship(
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
