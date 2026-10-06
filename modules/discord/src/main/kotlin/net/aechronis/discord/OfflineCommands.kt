package net.aechronis.discord

/** Reviewed paths only. New commands default to requiring a connected player. */
internal object OfflineCommands {
    fun allows(
        path: String,
        arguments: List<String>,
    ): Boolean =
        when (path) {
            "territory" -> arguments.isNotEmpty()
            "trains" -> arguments.isEmpty()
            "nodesadmin.trains" -> arguments.isEmpty() || arguments.first() in setOf("ban", "unban", "list", "remove")
            "nodesadmin.town.plot" -> arguments.isEmpty() || arguments.getOrNull(1) in setOf("permissions", "list", "delete")
            else -> path in independent
        }

    private val independent =
        """
        town town.help town.promote town.demote town.accept town.deny town.kick
        town.list town.info town.online town.income.info town.permissions town.trust town.untrust
        town.minimap town.minimap.toggle town.minimap.position town.minimap.shift town.minimap.northlock
        town.plot town.plot.permissions town.plot.list town.plot.delete
        nation nation.help nation.list nation.online nation.info
        ally unally player port port.list port.info warzone
        nodesadmin nodesadmin.help
        nodesadmin.war nodesadmin.war.enable nodesadmin.war.disable nodesadmin.war.skirmish nodesadmin.war.deathwar
        nodesadmin.town nodesadmin.town.create nodesadmin.town.delete nodesadmin.town.merge nodesadmin.town.move
        nodesadmin.town.lives nodesadmin.town.rename nodesadmin.town.addplayer nodesadmin.town.removeplayer
        nodesadmin.town.addterritory nodesadmin.town.removeterritory nodesadmin.town.captureterritory nodesadmin.town.releaseterritory
        nodesadmin.town.addofficer nodesadmin.town.removeofficer nodesadmin.town.leader nodesadmin.town.removeleader
        nodesadmin.town.color nodesadmin.town.sethome nodesadmin.town.defaulttownspawns
        nodesadmin.town.ai nodesadmin.town.ai.show nodesadmin.town.ai.set nodesadmin.town.ai.clear nodesadmin.town.coatofarms
        nodesadmin.nation nodesadmin.nation.create nodesadmin.nation.delete nodesadmin.nation.rename
        nodesadmin.nation.addtown nodesadmin.nation.removetown nodesadmin.nation.capital
        nodesadmin.nation.addally nodesadmin.nation.removeally nodesadmin.nation.addenemy nodesadmin.nation.removeenemy
        nodesadmin.nation.rallycap nodesadmin.nation.color nodesadmin.nation.longname nodesadmin.nation.flag
        nodesadmin.resident nodesadmin.resident.removecooldown nodesadmin.resident.leavepenalty
        nodesadmin.building nodesadmin.reasorce nodesadmin.save nodesadmin.load nodesadmin.runincome nodesadmin.miningboost
        nodesadmin.warzone nodesadmin.warzone.create nodesadmin.warzone.list nodesadmin.warzone.stop nodesadmin.warzone.cancel
    """.trim().split(Regex("\\s+")).toSet()
}
