package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.MusicListener
import net.aechronis.vanilla.objects.MusicDisc
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.jukebox.JukeboxSong
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.registry.RegistryKey
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.timer.TaskSchedule

object Music {
    const val SOUND_NAMESPACE = "aechronis"
    val RECORD_ITEM_TAG: Tag<ItemStack> = Tag.ItemStack("RecordItem")
    val PLAYING_TAG: Tag<Boolean> = Tag.Boolean("aechronis_playing")

    private val keysByDisc = HashMap<MusicDisc, RegistryKey<JukeboxSong>>()
    private val discsByKey = HashMap<Key, MusicDisc>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        registerSongs()
        MusicListener.init()
        val timeEnd = System.currentTimeMillis()
        println("├─ Music enabled in ${timeEnd - timeStart}ms")
    }

    private fun registerSongs() {
        val registry = MinecraftServer.getJukeboxSongRegistry()
        for (disc in Vanilla.config.musicDiscs) {
            require(disc.length > 0f) { "Music disc '${disc.name}' must have a positive length" }
            require(disc.songName.isNotBlank()) { "Music disc '${disc.name}' must have a song name" }

            val key = Key.key(SOUND_NAMESPACE, disc.songName)
            require(!discsByKey.containsKey(key)) { "Duplicate music disc song name: ${disc.songName}" }

            val song =
                JukeboxSong.create(
                    SoundEvent.of(key, null),
                    Component.text(disc.name),
                    disc.length,
                    0,
                )
            val registryKey = registry.register(key, song)
            keysByDisc[disc] = registryKey
            discsByKey[key] = disc
        }
    }

    fun itemFor(disc: MusicDisc): ItemStack {
        val key = keysByDisc[disc] ?: error("Music disc is not registered: ${disc.name}")
        val minutes = (disc.length / 60f).toInt()
        val seconds = (disc.length % 60f).toInt()
        val length = "%d:%02d".format(minutes, seconds)
        return ItemStack
            .of(Material.MUSIC_DISC_5)
            .with(DataComponents.JUKEBOX_PLAYABLE, key)
            .withCustomName(Component.text(disc.name, NamedTextColor.GOLD))
            .withLore(
                listOf(
                    Component.text("Author: ${disc.author}", NamedTextColor.GRAY),
                    Component.text("Length: $length", NamedTextColor.GRAY),
                    Component.text("Audio: ${disc.songName}", NamedTextColor.DARK_GRAY),
                ),
            ).withMaxStackSize(1)
    }

    fun discFor(item: ItemStack): MusicDisc? {
        val key = item.get(DataComponents.JUKEBOX_PLAYABLE) ?: return null
        return discsByKey[key.key()]
    }

    fun discIn(block: Block): MusicDisc? = discFor(block.getTag(RECORD_ITEM_TAG) ?: return null)

    fun play(
        instance: Instance,
        position: Point,
        disc: MusicDisc,
        item: ItemStack,
    ) {
        val sound = soundEventFor(disc)
        val soundInstance = Sound.sound(sound, Sound.Source.RECORD, 4f, 1f)
        instance.playSound(soundInstance, position.add(0.5, 0.5, 0.5))

        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                val current = instance.getBlock(position)
                if (current.getTag(PLAYING_TAG) != true) return@buildTask
                if (current.getTag(RECORD_ITEM_TAG)?.isSimilar(item) != true) return@buildTask
                instance.setBlock(position, current.withTag(PLAYING_TAG, false))
            }.delay(TaskSchedule.millis((disc.length * 1000).toLong()))
            .schedule()
    }

    fun stop(
        instance: Instance,
        position: Point,
        disc: MusicDisc,
    ) {
        val stop = SoundStop.named(soundEventFor(disc))
        for (player in instance.players) {
            if (player.position.distanceSquared(position) <= 64.0 * 64.0) player.stopSound(stop)
        }
    }

    private fun soundEventFor(disc: MusicDisc): SoundEvent = SoundEvent.of(Key.key(SOUND_NAMESPACE, disc.songName), null)
}
