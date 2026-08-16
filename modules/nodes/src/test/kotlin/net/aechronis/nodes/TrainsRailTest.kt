package net.aechronis.nodes

import net.aechronis.nodes.objects.Trains
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals

class TrainsRailTest {
    private val origin = BlockVec(10, 64, 10)

    @Test
    fun `corner states expose only their two physical exits`() {
        val cases = listOf(
            "south_east" to setOf(BlockVec(10, 64, 11), BlockVec(11, 64, 10)),
            "south_west" to setOf(BlockVec(10, 64, 11), BlockVec(9, 64, 10)),
            "north_east" to setOf(BlockVec(10, 64, 9), BlockVec(11, 64, 10)),
            "north_west" to setOf(BlockVec(10, 64, 9), BlockVec(9, 64, 10)),
        )

        cases.forEach { (shape, expected) ->
            val block = Block.RAIL.withProperty("shape", shape)
            assertEquals(expected, Trains.railExits(origin, block).toSet(), shape)
        }
    }

    @Test
    fun `ascending states preserve the vertical endpoint`() {
        val cases = listOf(
            "ascending_east" to setOf(BlockVec(9, 64, 10), BlockVec(11, 65, 10)),
            "ascending_west" to setOf(BlockVec(11, 64, 10), BlockVec(9, 65, 10)),
            "ascending_north" to setOf(BlockVec(10, 64, 11), BlockVec(10, 65, 9)),
            "ascending_south" to setOf(BlockVec(10, 64, 9), BlockVec(10, 65, 11)),
        )

        cases.forEach { (shape, expected) ->
            val block = Block.RAIL.withProperty("shape", shape)
            assertEquals(expected, Trains.railExits(origin, block).toSet(), shape)
        }
    }

    @Test
    fun `ascending rails link to the matching upper track`() {
        val cases = listOf(
            "ascending_east" to (BlockVec(11, 65, 10) to "east_west"),
            "ascending_west" to (BlockVec(9, 65, 10) to "east_west"),
            "ascending_north" to (BlockVec(10, 65, 9) to "north_south"),
            "ascending_south" to (BlockVec(10, 65, 11) to "north_south"),
        )

        cases.forEach { (slopeShape, upper) ->
            val (upperPosition, upperShape) = upper
            assertEquals(
                true,
                Trains.railsAreLinked(
                    origin,
                    Block.RAIL.withProperty("shape", slopeShape),
                    upperPosition,
                    Block.RAIL.withProperty("shape", upperShape),
                ),
                slopeShape,
            )
        }
    }

    @Test
    fun `ascending rail does not link to an incorrectly aligned upper track`() {
        assertEquals(
            false,
            Trains.railsAreLinked(
                origin,
                Block.RAIL.withProperty("shape", "ascending_east"),
                BlockVec(11, 65, 10),
                Block.RAIL.withProperty("shape", "north_south"),
            ),
        )
    }

    @Test
    fun `all vanilla rail types use their shape topology`() {
        val rails = listOf(Block.RAIL, Block.POWERED_RAIL, Block.DETECTOR_RAIL, Block.ACTIVATOR_RAIL)
        val expected = setOf(BlockVec(10, 64, 9), BlockVec(10, 64, 11))

        rails.forEach { rail ->
            val state = rail.withProperty("shape", "north_south")
            assertEquals(expected, Trains.railExits(origin, state).toSet(), rail.key().asString())
        }
    }
}
