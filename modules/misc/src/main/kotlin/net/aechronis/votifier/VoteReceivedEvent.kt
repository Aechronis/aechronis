package net.aechronis.votifier

import com.vexsoftware.votifier.model.Vote
import com.vexsoftware.votifier.net.VotifierSession
import net.minestom.server.event.Event

class VoteReceivedEvent(
    vote: Vote,
    val protocolVersion: VotifierSession.ProtocolVersion,
    val remoteAddress: String,
) : Event {
    val vote = Vote(vote)

    val serviceName: String get() = vote.serviceName
    val username: String get() = vote.username
    val address: String get() = vote.address
    val timestamp: String get() = vote.timeStamp
    val additionalData: ByteArray? get() = vote.additionalData
}
