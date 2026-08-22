package net.aechronis.craftingstore

import net.craftingstore.core.models.donation.Donation
import net.minestom.server.event.trait.CancellableEvent
import java.util.UUID

/** Fired on the Minestom tick thread immediately before a donation command runs. */
class DonationReceivedEvent(
    val donation: Donation,
) : CancellableEvent {
    private var cancelled = false
    val command: String get() = donation.command
    val username: String get() = donation.player.username
    val uuid: UUID? get() = donation.player.uuid
    val packageName: String get() = donation.`package`.name
    val priceInCents: Int get() = donation.`package`.priceInCents
    val discount: Int get() = donation.discount

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancelled: Boolean) {
        this.cancelled = cancelled
    }
}
