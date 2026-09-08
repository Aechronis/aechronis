package net.aechronis.nodes.serdes

import net.aechronis.nodes.objects.Nation.NationSaveState
import net.aechronis.nodes.objects.Resident.ResidentSaveState
import net.aechronis.nodes.objects.Town.TownSaveState

/** Everything the towns.json writer needs, captured before handing work to the save queue. */
class WorldSaveState(
    residents: List<ResidentSaveState>,
    towns: List<TownSaveState>,
    nations: List<NationSaveState>,
    val miningBoost: MiningBoostSaveState,
) : SaveState() {
    val residents: List<ResidentSaveState> = residents.snapshotList()
    val towns: List<TownSaveState> = towns.snapshotList()
    val nations: List<NationSaveState> = nations.snapshotList()

    override fun encode(): String = Serializer.worldToJson(this)
}

class MiningBoostSaveState(
    val haste: BoostSaveState?,
    val boost: BoostSaveState?,
) : SaveState() {
    data class BoostSaveState(val multiplier: Int, val startedAt: Long, val expiresAt: Long)

    override fun encode(): String = MiningBoostJsonCodec.encode(this)
}
