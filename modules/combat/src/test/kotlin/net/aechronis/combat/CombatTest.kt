package net.aechronis.combat

import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneWeapon
import net.aechronis.combat.objects.Tank
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.damageAtDistance
import net.aechronis.combat.objects.distanceToBoundingBox
import net.aechronis.combat.objects.firstProjectileImpact
import net.aechronis.combat.objects.selectProjectileImpact
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.calculateVehicleCameraDistance
import net.aechronis.combat.utils.withCombatDamageImmunityBypass
import net.aechronis.utils.createTestServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
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
        Combat.initialize()
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

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
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
