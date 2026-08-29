package net.aechronis.combat.listeners

import net.aechronis.combat.CombatTestServer
import net.aechronis.utils.EntityTags
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.damage.Damage
import net.minestom.server.event.entity.EntityDamageEvent
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MannequinDamageListenerTest {
    @BeforeAll
    fun setup() {
        CombatTestServer.initialize()
    }

    @Test
    fun `combat only protects non-combat mannequins from damage`() {
        val protected = EntityCreature(EntityType.MANNEQUIN)
        val protectedDamage = Damage.fromEntity(protected, 1F)
        val protectedEvent = EntityDamageEvent(protected, protectedDamage, protectedDamage.getSound(protected))
        MannequinDamageListener.onEntityDamage(protectedEvent)
        assertTrue(protectedEvent.isCancelled)

        val defender = EntityCreature(EntityType.MANNEQUIN)
        defender.setTag(EntityTags.DAMAGEABLE_MANNEQUIN, true)
        val defenderDamage = Damage.fromEntity(defender, 1F)
        val defenderEvent = EntityDamageEvent(defender, defenderDamage, defenderDamage.getSound(defender))
        MannequinDamageListener.onEntityDamage(defenderEvent)
        assertFalse(defenderEvent.isCancelled)
    }
}
