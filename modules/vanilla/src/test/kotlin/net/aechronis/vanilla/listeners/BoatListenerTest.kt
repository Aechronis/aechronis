package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoatListenerTest : ManagerTest() {
    @Test
    fun `all vanilla boat items select their matching entity type`() {
        val expected =
            mapOf(
                Material.OAK_BOAT to EntityType.OAK_BOAT,
                Material.OAK_CHEST_BOAT to EntityType.OAK_CHEST_BOAT,
                Material.SPRUCE_BOAT to EntityType.SPRUCE_BOAT,
                Material.SPRUCE_CHEST_BOAT to EntityType.SPRUCE_CHEST_BOAT,
                Material.BIRCH_BOAT to EntityType.BIRCH_BOAT,
                Material.BIRCH_CHEST_BOAT to EntityType.BIRCH_CHEST_BOAT,
                Material.JUNGLE_BOAT to EntityType.JUNGLE_BOAT,
                Material.JUNGLE_CHEST_BOAT to EntityType.JUNGLE_CHEST_BOAT,
                Material.ACACIA_BOAT to EntityType.ACACIA_BOAT,
                Material.ACACIA_CHEST_BOAT to EntityType.ACACIA_CHEST_BOAT,
                Material.CHERRY_BOAT to EntityType.CHERRY_BOAT,
                Material.CHERRY_CHEST_BOAT to EntityType.CHERRY_CHEST_BOAT,
                Material.DARK_OAK_BOAT to EntityType.DARK_OAK_BOAT,
                Material.DARK_OAK_CHEST_BOAT to EntityType.DARK_OAK_CHEST_BOAT,
                Material.PALE_OAK_BOAT to EntityType.PALE_OAK_BOAT,
                Material.PALE_OAK_CHEST_BOAT to EntityType.PALE_OAK_CHEST_BOAT,
                Material.MANGROVE_BOAT to EntityType.MANGROVE_BOAT,
                Material.MANGROVE_CHEST_BOAT to EntityType.MANGROVE_CHEST_BOAT,
                Material.BAMBOO_RAFT to EntityType.BAMBOO_RAFT,
                Material.BAMBOO_CHEST_RAFT to EntityType.BAMBOO_CHEST_RAFT,
            )

        assertEquals(expected, expected.keys.associateWith(BoatListener::entityType))
        assertNull(BoatListener.entityType(Material.MINECART))
    }

    @Test
    fun `water ray stops at solid blocks`() {
        val instance = VanillaTest.instance
        val eye = Pos(180.5, 68.5, 4.5)
        instance.setBlock(180, 66, 4, Block.WATER)

        val target = BoatListener.findWaterPlacementPosition(instance, eye, Vec(0.0, -1.0, 0.0))

        assertNotNull(target)
        assertEquals(Pos(180.5, 67.0, 4.5), target)

        instance.setBlock(180, 67, 4, Block.STONE)
        assertNull(BoatListener.findWaterPlacementPosition(instance, eye, Vec(0.0, -1.0, 0.0)))

        instance.setBlock(180, 67, 4, Block.AIR)
        instance.setBlock(180, 66, 4, Block.AIR)
    }

    @Test
    fun `using a boat on water spawns it and consumes the used hand`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(184.5, 69.0, 4.5, 35f, 90f))
        player.itemInMainHand = ItemStack.of(Material.CHERRY_BOAT, 2)
        instance.setBlock(184, 66, 4, Block.WATER)
        val event = PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L)

        BoatListener.onUseItem(event)

        assertTrue(event.isCancelled)
        assertEquals(ItemStack.of(Material.CHERRY_BOAT), player.itemInMainHand)
        val boat =
            instance.entities.singleOrNull { entity ->
                entity.entityType == EntityType.CHERRY_BOAT && entity.position.distanceSquared(Pos(184.5, 67.0, 4.5)) < 0.01
            }
        assertNotNull(boat)
        assertTrue(boat.hasNoGravity())

        boat.remove()
        instance.setBlock(184, 66, 4, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `creative placement keeps the offhand boat`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(188.5, 69.0, 4.5, 0f, 90f))
        player.gameMode = GameMode.CREATIVE
        player.itemInOffHand = ItemStack.of(Material.BAMBOO_RAFT)
        instance.setBlock(188, 66, 4, Block.WATER)
        val event = PlayerUseItemEvent(player, PlayerHand.OFF, player.itemInOffHand, 0L)

        BoatListener.onUseItem(event)

        assertTrue(event.isCancelled)
        assertEquals(Material.BAMBOO_RAFT, player.itemInOffHand.material())
        val boat = instance.entities.singleOrNull { it.entityType == EntityType.BAMBOO_RAFT }
        assertNotNull(boat)

        boat.remove()
        instance.setBlock(188, 66, 4, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `boats are not placed where their hull intersects a block`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(196.5, 69.0, 4.5, 0f, 90f))
        player.itemInMainHand = ItemStack.of(Material.SPRUCE_BOAT)
        instance.setBlock(196, 66, 4, Block.WATER)
        instance.setBlock(197, 67, 4, Block.STONE)
        val event = PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L)

        BoatListener.onUseItem(event)

        assertFalse(event.isCancelled)
        assertEquals(Material.SPRUCE_BOAT, player.itemInMainHand.material())
        assertTrue(instance.entities.none { it.entityType == EntityType.SPRUCE_BOAT })

        instance.setBlock(196, 66, 4, Block.AIR)
        instance.setBlock(197, 67, 4, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `spectators and dry uses do not place boats`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(192.5, 69.0, 4.5, 0f, 90f))
        player.itemInMainHand = ItemStack.of(Material.OAK_BOAT)
        val dryEvent = PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L)

        BoatListener.onUseItem(dryEvent)

        assertFalse(dryEvent.isCancelled)
        assertEquals(Material.OAK_BOAT, player.itemInMainHand.material())

        instance.setBlock(192, 66, 4, Block.WATER)
        player.gameMode = GameMode.SPECTATOR
        val spectatorEvent = PlayerUseItemEvent(player, PlayerHand.MAIN, player.itemInMainHand, 0L)
        BoatListener.onUseItem(spectatorEvent)

        assertFalse(spectatorEvent.isCancelled)
        assertTrue(instance.entities.none { it.entityType == EntityType.OAK_BOAT })

        instance.setBlock(192, 66, 4, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `interacting with a boat enters it`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(200.5, 67.0, 4.5))
        val boat = Entity(EntityType.OAK_BOAT)
        boat.setInstance(instance, Pos(202.5, 67.0, 4.5)).join()

        BoatListener.onInteract(PlayerEntityInteractEvent(player, boat, PlayerHand.MAIN, Vec.ZERO))

        assertSame(boat, player.vehicle)
        assertEquals(listOf(player), boat.passengers)

        boat.remove()
        VanillaTest.remove(player)
    }

    @Test
    fun `boats enforce vanilla passenger capacity`() {
        val instance = VanillaTest.instance
        val first = VanillaTest.createPlayer(Pos(204.5, 67.0, 4.5))
        val second = VanillaTest.createPlayer(Pos(206.5, 67.0, 4.5))
        val regularBoat = Entity(EntityType.BAMBOO_RAFT)
        val chestBoat = Entity(EntityType.BAMBOO_CHEST_RAFT)
        regularBoat.setInstance(instance, Pos(208.5, 67.0, 4.5)).join()
        chestBoat.setInstance(instance, Pos(210.5, 67.0, 4.5)).join()

        BoatListener.onInteract(PlayerEntityInteractEvent(first, chestBoat, PlayerHand.MAIN, Vec.ZERO))
        BoatListener.onInteract(PlayerEntityInteractEvent(second, chestBoat, PlayerHand.MAIN, Vec.ZERO))

        assertEquals(listOf(first), chestBoat.passengers)
        assertNull(second.vehicle)

        chestBoat.removePassenger(first)
        BoatListener.onInteract(PlayerEntityInteractEvent(first, regularBoat, PlayerHand.MAIN, Vec.ZERO))
        BoatListener.onInteract(PlayerEntityInteractEvent(second, regularBoat, PlayerHand.MAIN, Vec.ZERO))

        assertEquals(listOf(first, second), regularBoat.passengers)

        regularBoat.remove()
        chestBoat.remove()
        VanillaTest.remove(first)
        VanillaTest.remove(second)
    }

    @Test
    fun `pressing shift exits a boat`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(212.5, 67.0, 4.5))
        val boat = Entity(EntityType.OAK_BOAT)
        boat.setInstance(instance, Pos(214.5, 67.0, 4.5)).join()
        boat.addPassenger(player)
        player.inputs().refresh(false, false, false, false, false, true, false)

        BoatListener.onInput(PlayerInputEvent(player, false, false, false, false, false, false, false))

        assertNull(player.vehicle)
        assertTrue(boat.passengers.isEmpty())

        boat.remove()
        VanillaTest.remove(player)
    }

    @Test
    fun `one attack breaks a boat and drops its matching item`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(212.5, 67.0, 4.5))
        val passenger = VanillaTest.createPlayer(Pos(214.5, 67.0, 4.5))
        val boat = Entity(EntityType.DARK_OAK_CHEST_BOAT)
        boat.setInstance(instance, Pos(216.5, 67.0, 4.5)).join()
        boat.addPassenger(passenger)
        val existingEntities = instance.entities.toSet()

        BoatListener.onAttack(EntityAttackEvent(player, boat))

        assertNull(boat.instance)
        assertNull(passenger.vehicle)
        val drop = (instance.entities - existingEntities).single() as ItemEntity
        assertEquals(ItemStack.of(Material.DARK_OAK_CHEST_BOAT), drop.itemStack)

        drop.remove()
        VanillaTest.remove(player)
        VanillaTest.remove(passenger)
    }

    @Test
    fun `creative attacks break boats without dropping items`() {
        val instance = VanillaTest.instance
        val player = VanillaTest.createPlayer(Pos(218.5, 67.0, 4.5))
        player.gameMode = GameMode.CREATIVE
        val boat = Entity(EntityType.OAK_BOAT)
        boat.setInstance(instance, Pos(220.5, 67.0, 4.5)).join()
        val existingEntities = instance.entities.toSet()

        BoatListener.onAttack(EntityAttackEvent(player, boat))

        assertNull(boat.instance)
        assertEquals(existingEntities - boat, instance.entities.toSet())

        VanillaTest.remove(player)
    }
}
