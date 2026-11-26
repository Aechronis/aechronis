package net.aechronis.tablist

import net.aechronis.nodes.chat.Chat
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.staff.managers.Vanish
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSkinInitEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.timer.TaskSchedule
import java.util.Base64
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
        MinecraftServer.getConnectionManager().onlinePlayers.forEach {
            it.isListed = false
            if (it.skin == null && TabWorlds.hasTransparentDefaultSkin(it.uuid)) it.skin = FALLBACK_SKIN
        }
        // The pack makes one default skin transparent for text rows; a skinless player who hashes to it would be invisible.
        context.addListener(PlayerSkinInitEvent::class.java) { event ->
            if (event.skin == null && TabWorlds.hasTransparentDefaultSkin(event.player.uuid)) event.skin = FALLBACK_SKIN
        }
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
                val text =
                    Component
                        .text(
                            tags,
                        ).append(TabWorlds.prefix(m.rank))
                        .append(Component.text("${Chat.colorDefault}${m.name}${Chat.colorDefault}: $msg"))
                MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(text) }
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
        Chat.rankPrefix = { Component.empty() }
        online.values.forEach { it.isListed = true }
    }

    private companion object {
        const val REFRESH_TICKS = 20

        /** Steve, from Mojang's texture server (the client only loads skins from Mojang domains). */
        val FALLBACK_SKIN =
            PlayerSkin(
                Base64.getEncoder().encodeToString(
                    """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb"}}}"""
                        .toByteArray(),
                ),
                "",
            )
    }
}
