package net.aechronis.nodes.serdes

import net.aechronis.nodes.objects.Nation.NationSaveState
import net.aechronis.nodes.objects.Resident.ResidentSaveState
import net.aechronis.nodes.objects.Town.TownSaveState

/** Everything the towns.json writer needs, captured before handing work to the save queue. */
class WorldSaveState(
    residents: List<ResidentSaveState>,
    towns: List<TownSaveState>,
    nations: List<NationSaveState>,
) : SaveState() {
    val residents: List<ResidentSaveState> = residents.snapshotList()
    val towns: List<TownSaveState> = towns.snapshotList()
    val nations: List<NationSaveState> = nations.snapshotList()

    override fun encode(): String = Serializer.worldToJson(this)
}
