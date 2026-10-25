package net.aechronis.music

import net.aechronis.music.objects.MusicDisc

data class MusicConfig(
    val path: String = "music",
    val musicDiscs: List<MusicDisc> = emptyList(),
)
