package net.aechronis.vanilla.listeners

import net.aechronis.utils.EntityTags
import net.aechronis.vanilla.ManagerTest
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.damage.Damage
import net.minestom.server.event.entity.EntityDamageEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MannequinDamageListenerTest : ManagerTest() {
    @Test
    fun `vanilla only protects non-combat mannequins from damage`() {
        val corpse = EntityCreature(EntityType.MANNEQUIN)
        val corpseDamage = Damage.fromEntity(corpse, 1F)
        val corpseEvent = EntityDamageEvent(corpse, corpseDamage, corpseDamage.getSound(corpse))
        MannequinListener.onEntityDamage(corpseEvent)
        assertTrue(corpseEvent.isCancelled)

        val defender = EntityCreature(EntityType.MANNEQUIN)
        defender.setTag(EntityTags.DAMAGEABLE_MANNEQUIN, true)
        val defenderDamage = Damage.fromEntity(defender, 1F)
        val defenderEvent = EntityDamageEvent(defender, defenderDamage, defenderDamage.getSound(defender))
        MannequinListener.onEntityDamage(defenderEvent)
        assertFalse(defenderEvent.isCancelled)
    }
}
