package net.aechronis.watchdog.probe

import net.minestom.server.coordinate.BlockVec

internal class Probe(
    val position: BlockVec,
    val originalStateId: Int,
    var keyIndex: Int = 0,
    var awaitingKey: String? = null,
    var sentAtTick: Long = 0,
    val resolvedKeys: MutableList<String> = mutableListOf(),
    var complete: Boolean = false,
)
