package io.github.openminigameserver.worldedit.platform

import com.sk89q.worldedit.world.registry.BlockRegistry
import com.sk89q.worldedit.world.registry.BundledRegistries


object MinestomRegistries : BundledRegistries() {
    override fun getBlockRegistry(): BlockRegistry {
        return MinestomBlockRegistry
    }
}