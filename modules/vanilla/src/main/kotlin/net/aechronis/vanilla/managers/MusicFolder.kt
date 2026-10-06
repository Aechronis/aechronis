package net.aechronis.vanilla.managers

import net.aechronis.vanilla.objects.MusicDisc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * Turns every Ogg Vorbis file in the music folder into a disc. The file name is the disc title
 * ("Anthem of Aechronis.ogg"), and the audio is shipped to players in [Music]'s resource pack.
 */
object MusicFolder {
    class Song(
        val disc: MusicDisc,
        val audio: ByteArray,
    )

    fun load(directory: Path): List<Song> {
        Files.createDirectories(directory)
        val songs = mutableListOf<Song>()
        val seen = HashSet<String>()
        for (file in directory.listDirectoryEntries().sortedBy { it.fileName.toString() }) {
            if (!file.isRegularFile() || !file.extension.equals("ogg", ignoreCase = true)) continue
            val title = file.nameWithoutExtension.trim()
            val songName = songNameFor(title)
            if (songName.isEmpty() || !seen.add(songName)) {
                println("[Music] Skipping ${file.fileName}: its name is empty or clashes with another song")
                continue
            }
            val audio = Files.readAllBytes(file)
            val info = runCatching { vorbisInfo(audio) }.getOrNull()
            if (info == null) {
                println("[Music] Skipping ${file.fileName}: not an Ogg Vorbis file")
                continue
            }
            // Minecraft only plays sounds from a position (fading with distance) when they are mono
            if (info.channels != 1) println("[Music] ${file.fileName} is stereo, so it won't fade with distance; export it as mono")
            songs += Song(MusicDisc(title, info.seconds, "", songName), audio)
        }
        return songs
    }

    // Sound event paths only allow [a-z0-9_.-/]
    fun songNameFor(title: String): String =
        title
            .lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_')

    class VorbisInfo(
        val channels: Int,
        val seconds: Float,
    )

    /** Reads the channel count and sample rate from the identification header, and the length from the last page. */
    fun vorbisInfo(audio: ByteArray): VorbisInfo? {
        val buffer = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
        if (!audio.startsWithAt(0, oggCapture)) return null
        val segments = audio[26].toInt() and 0xFF
        val packet = 27 + segments
        if (!audio.startsWithAt(packet, vorbisId)) return null
        val channels = audio[packet + 11].toInt() and 0xFF
        val sampleRate = buffer.getInt(packet + 12)
        if (channels == 0 || sampleRate <= 0) return null

        var last = audio.size - oggCapture.size
        while (last > 0 && !audio.startsWithAt(last, oggCapture)) last--
        val samples = buffer.getLong(last + 6)
        if (samples <= 0) return null
        return VorbisInfo(channels, samples.toFloat() / sampleRate)
    }

    private fun ByteArray.startsWithAt(
        offset: Int,
        prefix: ByteArray,
    ): Boolean = offset >= 0 && offset + prefix.size <= size && prefix.indices.all { this[offset + it] == prefix[it] }

    private val oggCapture = "OggS".toByteArray()
    private val vorbisId = byteArrayOf(1) + "vorbis".toByteArray()
}
