/**
 * Tinker mode: with an empty hand, right-clicking a block edits it (see [Tinker]). It only works
 * in the main world, on town land the player may interact with, outside combat and while
 * nodes war is off. Sneaking gives the normal interaction.
 */

package net.aechronis.nodes.listeners

import net.aechronis.combat.managers.CombatTags
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Outposts
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.permissions.LandAccess
import net.aechronis.nodes.permissions.LandAccessAction
import net.aechronis.nodes.permissions.LandAccessDecision
import net.aechronis.nodes.utils.Tinker
import net.aechronis.nodes.utils.TinkerHit
import net.aechronis.nodes.war.FlagWar
import net.aechronis.server.Server
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NodesTinkerListener {
    private const val MAX_REACH = 7.0

    private val active = ConcurrentHashMap.newKeySet<UUID>()

    /** Why tinker mode cannot be used right now, or null if it can. */
    private fun blockedReason(player: Player): String? = when {
        FlagWar.enabled -> "nodes war is enabled"
        CombatTags.isInCombat(player) -> "you are in combat"
        else -> null
    }

    fun isOn(player: Player): Boolean = active.contains(player.uuid)

    /** Turns tinker mode on or off. Returns false (and tells the player why) if it cannot be turned on. */
    fun set(player: Player, on: Boolean): Boolean {
        if (!on) {
            active.remove(player.uuid)
            return true
        }
        blockedReason(player)?.let {
            Message.error(player, "Tinker mode unavailable: $it")
            return false
        }
        active.add(player.uuid)
        return true
    }

    fun init() {
        Tinker.warmUp()
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onInteract)
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java) { active.remove(it.player.uuid) }
    }

    /** Why the player cannot tinker at this position, or null if they can. Only town land counts, never wilderness or outposts. */
    private fun denial(player: Player, at: BlockVec): String? {
        val territory = Territory.fromBlock(at.blockX(), at.blockZ())
        if (territory?.town == null || Outposts.contains(territory)) return "You can only tinker inside a town"
        // no block is passed so the town permission is always checked, even for blocks like stairs
        val decision = LandAccess.check(player, at, LandAccessAction.INTERACT)
        return if (decision == LandAccessDecision.ALLOW) null else decision.message
    }

    private fun onInteract(event: PlayerBlockInteractEvent) {
        // quick checks first, since most interact events are from players who are not tinkering
        if (active.isEmpty() || event.hand != PlayerHand.MAIN) return
        val player = event.player
        if (!active.contains(player.uuid) || event.isCancelled) return
        if (player.isSneaking || !player.itemInMainHand.isAir) return

        blockedReason(player)?.let {
            active.remove(player.uuid)
            Message.error(player, "Tinker mode disabled: $it")
            return
        }
        // duel arenas use the same coordinates as the main world, so the town check would be wrong there
        if (event.instance !== Server.instance) return
        event.isCancelled = true

        val instance = event.instance
        val pos = BlockVec(event.blockPosition)
        val cursor = event.cursorPosition
        val eye = player.position.add(0.0, player.eyeHeight, 0.0)
        val hit = TinkerHit(
            pos = pos,
            block = event.block,
            face = event.blockFace.toDirection(),
            rx = cursor.x(),
            ry = cursor.y(),
            rz = cursor.z(),
            eyeDistance = eye.distance(pos.x() + cursor.x(), pos.y() + cursor.y(), pos.z() + cursor.z()),
            blockAt = { instance.getBlock(it) },
        )
        // ignore clicks from too far away (modified clients can send any position)
        if (hit.eyeDistance > MAX_REACH) return
        val changes = Tinker.apply(hit)
        if (changes.isEmpty()) return

        for (at in changes.keys) {
            denial(player, at)?.let {
                Message.error(player, it)
                return
            }
        }
        changes.forEach { (at, block) -> instance.setBlock(at, block) }
    }
}
