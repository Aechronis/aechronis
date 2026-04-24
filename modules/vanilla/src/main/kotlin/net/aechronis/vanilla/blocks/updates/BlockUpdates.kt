package net.aechronis.vanilla.blocks.updates

import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance

interface BlockUpdates {
    fun update(
        instance: Instance,
        pos: Pos,
        info: BlockUpdateInfo,
    )
}
