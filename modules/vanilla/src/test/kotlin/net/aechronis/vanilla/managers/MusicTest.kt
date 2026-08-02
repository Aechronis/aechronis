package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.MusicDisc
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MusicTest : ManagerTest() {
    @Test
    fun `unregistered items and jukeboxes do not resolve to a disc`() {
        assertNull(Music.discFor(ItemStack.of(Material.MUSIC_DISC_5)))
        assertNull(Music.discFor(ItemStack.of(Material.DIAMOND)))
        assertNull(Music.discIn(Block.JUKEBOX))
        assertNull(Music.discIn(Block.JUKEBOX.withTag(Music.RECORD_ITEM_TAG, ItemStack.of(Material.MUSIC_DISC_5))))
    }

    @Test
    fun `play clears a matching jukebox after the disc length`() {
        val position = Pos(90.0, 40.0, 4.0)
        val item = ItemStack.of(Material.MUSIC_DISC_5)
        val disc = MusicDisc("Short test", length = 0.01f, author = "Test", songName = "short-test")
        VanillaTest.instance.setBlock(
            position,
            Block.JUKEBOX
                .withTag(Music.RECORD_ITEM_TAG, item)
                .withTag(Music.PLAYING_TAG, true),
        )

        Music.play(VanillaTest.instance, position, disc, item)
        Thread.sleep(100)

        assertFalse(VanillaTest.instance.getBlock(position).getTag(Music.PLAYING_TAG) == true)
        VanillaTest.instance.setBlock(position, Block.AIR)
    }
}
