package net.aechronis.tablist

import net.aechronis.nodes.chat.Chat
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.staff.managers.Vanish
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TabListModule : AechronisModule {
    override val id = "tablist"
    override val dependencies = setOf("nodes", "staff", "luckperms")

    private val senders = ConcurrentHashMap<UUID, TabSender>()
    private var joinRefreshQueued = false

    override fun initialize(context: ModuleContext) {
        // Dev only: AECHRONIS_TAB_SPOOF=300 fakes that many extra online players.
        val spoof = System.getenv("AECHRONIS_TAB_SPOOF")?.toIntOrNull()
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.isListed = false }
        context.addListener(PlayerSpawnEvent::class.java) { event ->
            // Real players stay in the world but leave the vanilla list; we draw our own.
            if (event.isFirstSpawn) {
                event.player.isListed = false
                if (!joinRefreshQueued) {
                    joinRefreshQueued = true
                    ModuleScheduler.scheduleNextTick {
                        joinRefreshQueued = false
                        refresh(spoof)
                    }
                }
            }
        }
        Chat.rankPrefix = TabWorlds::chatPrefix
        context.addListener(PlayerDisconnectEvent::class.java) { senders.remove(it.player.uuid) }
        if (spoof != null) spoofChat(spoof)
        ModuleScheduler
            .buildTask { refresh(spoof) }
            .repeat(TaskSchedule.tick(REFRESH_TICKS))
            .schedule()
    }

    /** Dev only: broadcast fake global chat lines from the spoofed players, formatted like Nodes global chat. */
    private fun spoofChat(spoof: Int) {
        val world = TabWorlds.spoof(spoof)
        val speakers = world.nations.flatMap { n -> n.towns.flatMap { t -> t.online.map { Triple(n, t, it) } } }
        val lines =
            listOf("anyone selling iron?", "gg", "> be me, lose the war", "who is online for the siege", "lol", "need a medic at spawn")
        ModuleScheduler
            .buildTask {
                val (n, t, m) = speakers.randomOrNull() ?: return@buildTask
                val tags = "[${Chat.colorNation}${n.name}${Chat.colorDefault}|${Chat.colorTown}${t.name}${Chat.colorDefault}] "
                val msg = lines.random().let { if (it[0] == '>') "${Chat.colorGreen}$it" else it }
                val text = "$tags${TabWorlds.prefix(m.rank)}${Chat.colorDefault}${m.name}${Chat.colorDefault}: $msg"
                MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(Component.text(text)) }
            }.repeat(TaskSchedule.tick(40))
            .schedule()
    }

    private fun refresh(spoof: Int?) {
        val players = MinecraftServer.getConnectionManager().onlinePlayers
        if (players.isEmpty()) return
        val world = if (spoof != null) TabWorlds.spoof(spoof) else TabWorlds.real()
        val layout = TabLayout(world)
        for (p in players) {
            val seesVanished = Vanish.level(p)
            val cells = layout.build(p.uuid, seesVanished) ?: layout.buildStaffOnly(seesVanished)
            senders.getOrPut(p.uuid, ::TabSender).update(p, cells)
        }
    }

    override fun shutdown(context: ModuleContext) {
        val online = MinecraftServer.getConnectionManager().onlinePlayers.associateBy(Player::getUuid)
        senders.forEach { (id, s) -> s.clear(online[id]) }
        senders.clear()
        Chat.rankPrefix = { "" }
        online.values.forEach { it.isListed = true }
    }

    private companion object {
        const val REFRESH_TICKS = 20
    }
}
