package net.aechronis.combat.utils

import net.kyori.adventure.text.Component
import net.minestom.server.entity.damage.Damage
import net.minestom.server.tag.Tag

internal enum class CombatDamageKind {
    PROJECTILE,
    MELEE,
    EXPLOSION,
}

private val damageKindTag = Tag.String("combat:damage_kind")
private val damageWeaponTag = Tag.Component("combat:damage_weapon")

internal fun Damage.withCombatAttribution(
    kind: CombatDamageKind,
    weapon: Component? = null,
): Damage =
    apply {
        setTag(damageKindTag, kind.name)
        if (weapon != null) setTag(damageWeaponTag, weapon)
    }

internal fun Damage.combatDamageKind(): CombatDamageKind? =
    getTag(damageKindTag)?.let { runCatching { CombatDamageKind.valueOf(it) }.getOrNull() }

internal fun Damage.combatWeapon(): Component? = getTag(damageWeaponTag)

internal fun Damage.clearCombatAttribution() {
    removeTag(damageKindTag)
    removeTag(damageWeaponTag)
}
