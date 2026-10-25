package net.aechronis.music.managers

import net.aechronis.music.listeners.JukeboxListener
import net.aechronis.music.objects.MusicDisc
import net.aechronis.server.modules.ModuleContext
import net.aechronis.vanilla.managers.Jukeboxes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.instance.block.jukebox.JukeboxSong
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.registry.RegistryKey
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import java.nio.file.Path

object Music {
    const val SOUND_NAMESPACE = "aechronis"

    // Minestom 2026.09.12 still writes jukebox_playable in the pre-26.2 format, which disconnects clients, so custom discs carry their song here
    private val SONG_TAG: Tag<String> = Tag.String("aechronis_song")

    private val keysByDisc = HashMap<MusicDisc, RegistryKey<JukeboxSong>>()
    private val registeredSongNames = HashSet<Key>()
    private var folderSongs: List<MusicFolder.Song> = emptyList()
    private var packRegistration: AutoCloseable? = null
    private var songResolver: AutoCloseable? = null

    /** Configured discs first, then one per file in the music folder. */
    var discs: List<MusicDisc> = emptyList()
        private set

    fun init(
        directory: Path,
        configuredDiscs: List<MusicDisc>,
    ) {
        folderSongs = MusicFolder.load(directory)
        discs = configuredDiscs + folderSongs.map { it.disc }
        registerSongs()
        songResolver = Jukeboxes.registerSongResolver(::songFor)
        DiscMenu.init()
        JukeboxListener.init()
        Jukeboxes.init()
        println("[Music] Loaded ${folderSongs.size} song(s) from $directory")
    }

    /** Ships the music folder's audio; configured discs bring their own sounds in an external pack. */
    fun registerResourcePack(context: ModuleContext) {
        if (folderSongs.isEmpty()) return
        val assets = mutableMapOf("pack.mcmeta" to packMetadata)
        val sounds =
            folderSongs.joinToString(",", "{", "}") { song ->
                val name = song.disc.songName
                assets["assets/$SOUND_NAMESPACE/sounds/music/$name.ogg"] = song.audio
                """"$name":{"sounds":[{"name":"$SOUND_NAMESPACE:music/$name","stream":true}]}"""
            }
        assets["assets/$SOUND_NAMESPACE/sounds.json"] = sounds.toByteArray()
        packRegistration = context.registerPlayerResourcePack("music") { _ -> assets }
    }

    fun shutdown() {
        Jukeboxes.shutdown()
        DiscMenu.closeAll()
        packRegistration?.close()
        packRegistration = null
        songResolver?.close()
        songResolver = null
        keysByDisc.clear()
        registeredSongNames.clear()
        folderSongs = emptyList()
        discs = emptyList()
    }

    private val packMetadata =
        """{"pack":{"description":"§6§lAechronis\n§7Music","min_format":[75,0],"max_format":[88,0]}}"""
            .toByteArray()

    private fun registerSongs() {
        val registry = MinecraftServer.getJukeboxSongRegistry()
        for (disc in discs) {
            require(disc.length > 0f) { "Music disc '${disc.name}' must have a positive length" }
            require(disc.songName.isNotBlank()) { "Music disc '${disc.name}' must have a song name" }

            val key = Key.key(SOUND_NAMESPACE, disc.songName)
            require(registeredSongNames.add(key)) { "Duplicate music disc song name: ${disc.songName}" }

            val song =
                JukeboxSong.create(
                    SoundEvent.of(key, null),
                    Component.text(disc.name),
                    disc.length,
                    0,
                )
            // Keep stable registry IDs across reloads; entries contain only core-owned values.
            val registryKey = registry.getKey(key) ?: registry.register(key, song)
            keysByDisc[disc] = registryKey
        }
    }

    fun itemFor(disc: MusicDisc): ItemStack {
        val key = keysByDisc[disc] ?: error("Music disc is not registered: ${disc.name}")
        val minutes = (disc.length / 60f).toInt()
        val seconds = (disc.length % 60f).toInt()
        val length = "%d:%02d".format(minutes, seconds)
        return ItemStack
            .of(Material.MUSIC_DISC_5)
            .without(DataComponents.JUKEBOX_PLAYABLE)
            .withTag(SONG_TAG, key.key().asString())
            .withCustomName(Component.text(disc.name, NamedTextColor.GOLD))
            .withLore(
                listOfNotNull(
                    disc.author.takeIf { it.isNotBlank() }?.let { Component.text("Author: $it", NamedTextColor.GRAY) },
                    Component.text("Length: $length", NamedTextColor.GRAY),
                    Component.text("Audio: ${disc.songName}", NamedTextColor.DARK_GRAY),
                ),
            ).withMaxStackSize(1)
    }

    private fun songFor(item: ItemStack): JukeboxSong? {
        val song = item.getTag(SONG_TAG) ?: return null
        val key = runCatching { Key.key(song) }.getOrNull() ?: return null
        if (key !in registeredSongNames) return null
        return MinecraftServer.getJukeboxSongRegistry().get(key)
    }
}
