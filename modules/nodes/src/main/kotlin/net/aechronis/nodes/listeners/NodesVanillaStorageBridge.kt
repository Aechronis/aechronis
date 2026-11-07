package net.aechronis.nodes.listeners

import net.aechronis.vanilla.managers.ItemFrames
import net.aechronis.vanilla.managers.Storage

object NodesVanillaStorageBridge {
    fun init() {
        Storage.setAccessChecker(NodesWorldListener::hasStorageAccess)
        ItemFrames.setAccessChecker(NodesWorldListener::hasItemFrameAccess)
    }
}
