package net.aechronis.watchdog.probe

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.alert.StaffAlert
import net.aechronis.watchdog.objects.TranslationProbeCodec
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockEntityType
import net.minestom.server.network.packet.client.play.ClientUpdateSignPacket
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket
import net.minestom.server.network.packet.server.play.OpenSignEditorPacket
import java.util.concurrent.ConcurrentHashMap

internal class TranslationProbe(
    private val config: WatchdogConfig,
    private val currentTick: () -> Long,
    private val alertService: StaffAlert,
) {
    private val sessions = ConcurrentHashMap<Player, Probe>()

    fun start(player: Player) {
        if (config.translationProbeKeys.isEmpty()) return
        val instance = player.instance ?: return
        val position = BlockVec(player.position.blockX(), instance.cachedDimensionType.minY(), player.position.blockZ())
        val session = Probe(position, instance.getBlock(position).stateId())
        sessions[player] = session
        sendKey(player, session)
    }

    fun advance() {
        val tick = currentTick()
        for ((player, session) in sessions.toMap()) {
            if (!player.isOnline || session.complete) continue
            if (session.awaitingKey != null && tick - session.sentAtTick < config.translationProbeTimeoutTicks) continue
            if (session.awaitingKey != null) {
                session.awaitingKey = null
                session.keyIndex++
            }
            if (session.keyIndex >= config.translationProbeKeys.size) {
                complete(player, session)
            } else {
                sendKey(player, session)
            }
        }
    }

    fun handleResponse(
        event: PlayerPacketEvent,
        packet: ClientUpdateSignPacket,
    ) {
        val session = sessions[event.player] ?: return
        if (session.complete || !packet.blockPosition().sameBlock(session.position) || session.awaitingKey == null) return
        event.isCancelled = true
        if (packet.lines().firstOrNull()?.let(TranslationProbeCodec::isResolved) == true) {
            session.resolvedKeys += session.awaitingKey!!
        }
        session.awaitingKey = null
        session.keyIndex++
        if (session.keyIndex >= config.translationProbeKeys.size) {
            complete(event.player, session)
        } else {
            sendKey(event.player, session)
        }
    }

    fun end(
        player: Player,
        notify: Boolean,
    ) {
        val session = sessions.remove(player) ?: return
        if (!session.complete) restore(player, session)
        if (notify) {
            val result = TranslationProbeCodec.result(session.resolvedKeys, config.forbiddenTranslationKeys)
            alertService.notify(player, result)
        }
    }

    private fun sendKey(
        player: Player,
        session: Probe,
    ) {
        val key = config.translationProbeKeys[session.keyIndex]
        player.sendPacket(BlockChangePacket(session.position, Block.CHERRY_HANGING_SIGN))
        player.sendPacket(
            BlockEntityDataPacket(
                session.position,
                BlockEntityType.HANGING_SIGN,
                TranslationProbeCodec.signData(key),
            ),
        )
        if (config.openTranslationProbeEditor) {
            player.sendPacket(OpenSignEditorPacket(session.position, true))
        }
        session.awaitingKey = key
        session.sentAtTick = currentTick()
    }

    private fun complete(
        player: Player,
        session: Probe,
    ) {
        if (session.complete) return
        session.complete = true
        restore(player, session)
    }

    private fun restore(
        player: Player,
        session: Probe,
    ) {
        if (player.isOnline) player.sendPacket(BlockChangePacket(session.position, session.originalStateId))
    }
}
