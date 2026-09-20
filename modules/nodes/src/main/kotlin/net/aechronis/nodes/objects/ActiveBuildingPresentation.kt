package net.aechronis.nodes.objects

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockEntityType
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket
import net.minestom.server.utils.block.BlockUtils

/** Owned by the production task, including its shutdown cleanup. */
internal object ActiveBuildingPresentation {
    private const val RANGE_SQUARED = 32.0 * 32.0
    private val markers = mutableMapOf<ActiveBuilding, Marker>()

    private class Marker(building: ActiveBuilding, val instance: Instance) {
        val bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)
        val viewers = mutableSetOf<Player>()
        private var beamPosition = groundMarkerPosition(building, instance)

        private fun showBeam(player: Player) {
            val position = beamPosition ?: return
            player.sendPacket(BlockChangePacket(position, Block.TEST_INSTANCE_BLOCK))
            player.sendPacket(BlockEntityDataPacket(position, BlockEntityType.TEST_INSTANCE_BLOCK, BEAM_DATA))
        }

        private fun restoreBeam(player: Player) {
            val position = beamPosition ?: return
            if (!player.isOnline || player.instance !== instance) return
            if (instance.getChunk(Math.floorDiv(position.blockX(), 16), Math.floorDiv(position.blockZ(), 16))?.isLoaded != true) return
            val original = instance.getBlock(position)
            player.sendPacket(BlockChangePacket(position, original))
            original.registry()?.blockEntityType()?.let { type ->
                player.sendPacket(BlockEntityDataPacket(position, type, BlockUtils.extractClientNbt(original)))
            }
        }

        fun update(building: ActiveBuilding, production: ActiveBuilding.Production, nearby: Set<Player>, now: Long) {
            val percent = production.progress(now)
            bar.name(building.displayName.append(Component.text(" • $percent%")))
            bar.progress(((now - production.startedAt).toDouble() / ACTIVE_BUILDING_DURATION_MS).coerceIn(0.0, 1.0).toFloat())
            for (player in viewers - nearby) {
                player.hideBossBar(bar)
                restoreBeam(player)
            }
            for (player in nearby - viewers) player.showBossBar(bar)
            val ground = groundMarkerPosition(building, instance)
            if (ground != beamPosition) {
                (viewers intersect nearby).forEach(::restoreBeam)
                beamPosition = ground
            }
            // Refresh after chunk resends or terrain edits that replace the client-only block.
            nearby.forEach(::showBeam)
            viewers.clear()
            viewers.addAll(nearby)
        }

        fun close() {
            viewers.forEach {
                it.hideBossBar(bar)
                restoreBeam(it)
            }
            viewers.clear()
        }
    }

    // A finished, error-free client test instance gives a green native beacon beam.
    // Zero size prevents a structure outline; no test identifier is supplied.
    private val BEAM_DATA = CompoundBinaryTag.builder().put(
        "data",
        CompoundBinaryTag.builder()
            .putIntArray("size", intArrayOf(0, 0, 0))
            .putString("rotation", "none")
            .putBoolean("ignore_entities", true)
            .putString("status", "finished")
            .build(),
    ).build()

    private fun groundMarkerPosition(building: ActiveBuilding, instance: Instance): BlockVec? {
        val minY = instance.cachedDimensionType.minY()
        val startY = building.position.blockY().coerceAtMost(instance.cachedDimensionType.maxY() - 1)
        val x = building.position.blockX()
        val z = building.position.blockZ()
        for (y in startY downTo minY + 1) {
            if (instance.getBlock(x, y, z).isSolid) {
                // Bury the client-only block below the surface so the beam emerges from the ground.
                return BlockVec(x, y - 1, z)
            }
        }
        return null
    }

    fun refresh(buildings: List<ActiveBuilding>, now: Long) {
        val visible = mutableSetOf<ActiveBuilding>()
        for (building in buildings) {
            val production = building.production ?: continue
            val instance = MinecraftServer.getInstanceManager().instances.firstOrNull { it.getDimensionName() == building.world } ?: continue
            val nearby = instance.players.filterTo(mutableSetOf()) { it.position.distanceSquared(building.outputPosition) <= RANGE_SQUARED }
            if (nearby.isEmpty() || instance.getChunk(building.chunkX, building.chunkZ)?.isLoaded != true) continue
            visible.add(building)
            if (markers[building]?.instance?.let { it !== instance } == true) markers.remove(building)?.close()
            markers.getOrPut(building) { Marker(building, instance) }.update(building, production, nearby, now)
        }
        for (building in markers.keys.toList()) {
            if (building !in visible) markers.remove(building)?.close()
        }
    }

    fun clear() {
        markers.values.forEach { it.close() }
        markers.clear()
    }
}
