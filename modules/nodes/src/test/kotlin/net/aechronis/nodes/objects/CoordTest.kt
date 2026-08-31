package net.aechronis.nodes.objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CoordTest {
    @Test
    fun `equal coordinates hash equally`() {
        assertEquals(Coord(5, -12).hashCode(), Coord(5, -12).hashCode())
        assertEquals(Coord(5, -12), Coord(5, -12))
    }

    @Test
    fun `distinct coordinates that collide under the default data class hash no longer collide`() {
        // 31*x + z collides for these pairs (31*1+31 == 31*2+0 == 62); the packed-long hash used
        // by Coord.hashCode should tell them apart instead of funnelling both into one bucket.
        assertNotEquals(Coord(1, 31).hashCode(), Coord(2, 0).hashCode())
        assertNotEquals(Coord(0, 0).hashCode(), Coord(1, -31).hashCode())
    }
}
