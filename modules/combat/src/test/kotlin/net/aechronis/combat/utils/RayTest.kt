package net.aechronis.combat.utils

import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RayTest {
    @Test
    fun `block walk terminates for a yaw 180 look from a block boundary`() {
        // Minestom's BlockIterator never ends here: x is about -1.2e-16, so it leaves the end cell at once.
        val eye = Pos(3770.0, 67.62, -345.0, 180f, 0f)
        val cells = Ray(eye, eye.direction().mul(5.0)).blockCells()

        assertTrue(cells.size <= 3 * 5 + 12, "walk not bounded: ${cells.size} cells")
        assertEquals(3770 - 1, cells[1].blockX())
    }

    @Test
    fun `block walk is empty for a non-finite ray`() {
        assertTrue(Ray(Vec(0.0, 64.0, 0.0), Vec(Double.NaN, 0.0, 1.0)).blockCells().isEmpty())
        assertTrue(Ray(Vec(0.0, 64.0, 0.0), Vec(Double.POSITIVE_INFINITY, 0.0, 0.0)).blockCells().isEmpty())
        assertTrue(Ray(Vec(Double.NaN, 64.0, 0.0), Vec(1.0, 0.0, 0.0)).blockCells().isEmpty())
    }

    @Test
    fun `block walk visits every cell of an ordinary ray`() {
        val cells = Ray(Vec(0.5, 64.5, 0.5), Vec(0.0, 0.0, 3.0)).blockCells()
        assertEquals(listOf(0, 1, 2, 3), cells.map { it.blockZ() })
    }

    private fun Ray.blockCells(): List<Point> =
        buildList {
            firstAlongCells<Unit> {
                add(it)
                null
            }
        }
}
