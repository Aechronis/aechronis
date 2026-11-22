package net.aechronis.server

import ac.grim.grimac.minestom.GrimPlayer
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection

class AechronisPlayer(
    connection: PlayerConnection,
    profile: GameProfile,
) : GrimPlayer(connection, profile) {
    // Grim turns off Minestom's viewable packets, so movement from a player with predictable viewers
    // goes to its chunk's viewers, which are rebuilt per packet by scanning every chunk in the chunk
    // view distance (1089 at 16) and include players too far away to have this player spawned.
    // The player's own viewers are already tracked and are exactly the players who can see it.
    override fun hasPredictableViewers(): Boolean = false
}
