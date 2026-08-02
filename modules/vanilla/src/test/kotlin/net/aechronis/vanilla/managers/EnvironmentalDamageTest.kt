package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.instance.block.Block
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentalDamageTest : ManagerTest() {
    @Test
    fun `fire damages immediately then every ten ticks`() {
        val player = VanillaTest.createPlayer(Pos(0.5, 40.0, 0.5))
        VanillaTest.instance.setBlock(0, 40, 0, Block.FIRE)

        EnvironmentalDamage.tickPlayer(player)
        assertEquals(19f, player.health)
        repeat(9) { EnvironmentalDamage.tickPlayer(player) }
        assertEquals(19f, player.health)
        EnvironmentalDamage.tickPlayer(player)

        assertEquals(18f, player.health)
        assertEquals(160, player.fireTicks)
        VanillaTest.instance.setBlock(0, 40, 0, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `drowning damages after vanilla air supply expires`() {
        val player = VanillaTest.createPlayer(Pos(2.5, 40.0, 2.5))
        VanillaTest.instance.setBlock(2, 41, 2, Block.WATER)

        repeat(320) { EnvironmentalDamage.tickPlayer(player) }

        assertEquals(18f, player.health)
        assertEquals(0, player.entityMeta.airTicks)
        VanillaTest.instance.setBlock(2, 41, 2, Block.AIR)
        VanillaTest.remove(player)
    }

    @Test
    fun `fire resistance and creative mode prevent environmental damage`() {
        val fireResistant = VanillaTest.createPlayer(Pos(4.5, 40.0, 4.5))
        VanillaTest.instance.setBlock(4, 40, 4, Block.FIRE)
        fireResistant.addEffect(Potion(PotionEffect.FIRE_RESISTANCE, 0, 100))

        EnvironmentalDamage.tickPlayer(fireResistant)

        assertEquals(20f, fireResistant.health)
        assertEquals(160, fireResistant.fireTicks)

        val creative = VanillaTest.createPlayer(Pos(6.5, 40.0, 6.5))
        VanillaTest.instance.setBlock(6, 40, 6, Block.FIRE)
        creative.entityMeta.airTicks = 10
        creative.setGameMode(GameMode.CREATIVE)

        EnvironmentalDamage.tickPlayer(creative)

        assertEquals(20f, creative.health)
        assertEquals(0, creative.fireTicks)
        assertEquals(300, creative.entityMeta.airTicks)
        VanillaTest.instance.setBlock(4, 40, 4, Block.AIR)
        VanillaTest.instance.setBlock(6, 40, 6, Block.AIR)
        VanillaTest.remove(fireResistant)
        VanillaTest.remove(creative)
    }
}
