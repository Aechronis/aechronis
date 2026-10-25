package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.jukebox.JukeboxSong
import net.minestom.server.item.ItemStack
import net.minestom.server.tag.Tag
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

/** Vanilla jukebox storage and playback, with removable resolvers for additional playable items. */
object Jukeboxes {
    val RECORD_ITEM_TAG: Tag<ItemStack> = Tag.ItemStack("RecordItem")
    val PLAYING_TAG: Tag<Boolean> = Tag.Boolean("aechronis_playing")

    var enabled: Boolean = false
        private set

    private class Resolver(
        val songFor: (ItemStack) -> JukeboxSong?,
    )

    class Song internal constructor(
        val song: JukeboxSong,
        internal val owner: Any?,
    )

    private data class Location(
        val instance: Instance,
        val position: BlockVec,
    )

    private class Playback(
        val resolved: Song,
        val item: ItemStack,
    ) {
        var task: Task? = null
    }

    private val resolvers = linkedSetOf<Resolver>()
    private val playing = mutableMapOf<Location, Playback>()

    fun init() {
        enabled = true
    }

    /** Close before unloading the provider so no callback retains its module classloader. */
    fun registerSongResolver(songFor: (ItemStack) -> JukeboxSong?): AutoCloseable {
        val resolver = Resolver(songFor)
        resolvers += resolver
        return AutoCloseable {
            resolvers -= resolver
            playing.entries
                .filter { it.value.resolved.owner === resolver }
                .map { it.key }
                .forEach(::stop)
            // Release cancelled playback callbacks when a song provider closes.
            ModuleScheduler.releaseCancelledTasks()
        }
    }

    fun songFor(item: ItemStack): Song? {
        for (resolver in resolvers) {
            resolver.songFor(item)?.let { return Song(it, resolver) }
        }
        val key = item.get(DataComponents.JUKEBOX_PLAYABLE) ?: return null
        return MinecraftServer.getJukeboxSongRegistry().get(key)?.let { Song(it, null) }
    }

    fun play(
        instance: Instance,
        position: Point,
        resolved: Song,
        item: ItemStack,
    ) {
        val location = Location(instance, BlockVec(position))
        stop(location)
        instance.setBlock(position, instance.getBlock(position).withTag(PLAYING_TAG, true))
        val playback = Playback(resolved, item)
        playing[location] = playback
        instance.playSound(Sound.sound(resolved.song.soundEvent(), Sound.Source.RECORD, 4f, 1f), position.add(0.5, 0.5, 0.5))
        playback.task =
            ModuleScheduler
                .buildTask {
                    if (playing[location] !== playback) return@buildTask
                    playing.remove(location)
                    clearPlayingFlag(location, playback.item)
                }.delay(TaskSchedule.millis((resolved.song.lengthInSeconds() * 1000).toLong()))
                .schedule()
    }

    fun stop(
        instance: Instance,
        position: Point,
        block: Block = instance.getBlock(position),
    ) {
        val location = Location(instance, BlockVec(position))
        if (playing.containsKey(location)) {
            stop(location)
        } else {
            val item = block.getTag(RECORD_ITEM_TAG) ?: return
            songFor(item)?.song?.let { stopSound(location, it) }
            clearPlayingFlag(location, item)
        }
    }

    private fun stop(location: Location) {
        val playback = playing.remove(location) ?: return
        playback.task?.cancel()
        stopSound(location, playback.resolved.song)
        clearPlayingFlag(location, playback.item)
    }

    private fun stopSound(
        location: Location,
        song: JukeboxSong,
    ) {
        val stop = SoundStop.named(song.soundEvent())
        location.instance.players
            .filter { it.position.distanceSquared(location.position) <= 64.0 * 64.0 }
            .forEach { it.stopSound(stop) }
    }

    private fun clearPlayingFlag(
        location: Location,
        item: ItemStack,
    ) {
        if (location.instance.getChunkAt(location.position) == null) return
        val block = location.instance.getBlock(location.position)
        if (block.getTag(RECORD_ITEM_TAG)?.isSimilar(item) == true && block.getTag(PLAYING_TAG) == true) {
            location.instance.setBlock(location.position, block.withTag(PLAYING_TAG, false))
        }
    }

    fun shutdown() {
        playing.keys.toList().forEach(::stop)
        resolvers.clear()
        enabled = false
        ModuleScheduler.releaseCancelledTasks()
    }
}
