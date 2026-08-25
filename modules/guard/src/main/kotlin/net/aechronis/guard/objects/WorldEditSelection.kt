package net.aechronis.guard.objects

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.adapters.MinestomWorld
import net.minestom.server.entity.Player

object WorldEditSelection {
    fun read(player: Player): SelectedRegion {
        val actor = MinestomAdapter.asActor(player)
        val session =
            WorldEdit.getInstance().sessionManager.getIfPresent(actor)
                ?: error("You do not have a WorldEdit selection")
        val world = session.selectionWorld ?: error("Select both corners in this world first")
        val selector =
            session.getRegionSelector(world) as? CuboidRegionSelector
                ?: error("Your WorldEdit selection is not cuboid")
        require(selector.isDefined) { "Select both corners with the WorldEdit wand first" }

        val min = selector.region.minimumPoint
        val max = selector.region.maximumPoint
        require(world is MinestomWorld) { "The WorldEdit selection is not from a Minestom instance" }

        return SelectedRegion(ZoneBounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()))
    }
}
