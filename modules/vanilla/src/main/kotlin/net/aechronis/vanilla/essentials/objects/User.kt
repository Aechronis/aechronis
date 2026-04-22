package net.aechronis.vanilla.essentials.objects

import java.util.UUID

data class User(
    val uuid: UUID, // player's UUID
    val name: String, // username
    val mutedTime: Long, // timestamp of mute time
    val bannedTime: Long, // timestamp of ban time
    val mutes: HashMap<Long, String>, // timestamp of mute time to reason
    val bans: HashMap<Long, String>, // timestamp of ban time to reason
    val ignored: HashSet<UUID>, // ignored players
    val lastMessage: UUID?, // last player messaged
)
