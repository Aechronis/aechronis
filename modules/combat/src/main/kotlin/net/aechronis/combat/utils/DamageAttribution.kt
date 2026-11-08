package net.aechronis.combat.utils

import net.kyori.adventure.text.Component
import net.minestom.server.entity.damage.Damage
import net.minestom.server.tag.Tag

enum class CombatDamageKind {
    PROJECTILE,
    MELEE,
    EXPLOSION,
    VEHICLE,
}

internal enum class MeleeHit {
    NONE,
    PARTIAL,
    FULL,
}

private val damageKindTag = Tag.String("combat:damage_kind")
private val damageWeaponTag = Tag.Component("combat:damage_weapon")
private val damageImmunityBypassTag = Tag.Boolean("combat:damage_immunity_bypass")
private val partialHitTag = Tag.Boolean("combat:partial_hit")

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

internal fun Damage.withCombatDamageImmunityBypass(): Damage = apply { setTag(damageImmunityBypassTag, true) }

internal fun Damage.bypassesCombatDamageImmunity(): Boolean = getTag(damageImmunityBypassTag) == true

internal fun Damage.asPartialHit(): Damage = apply { setTag(partialHitTag, true) }

internal fun Damage.isPartialHit(): Boolean = getTag(partialHitTag) == true

internal fun Damage.clearCombatAttribution() {
    removeTag(damageKindTag)
    removeTag(damageWeaponTag)
    removeTag(damageImmunityBypassTag)
    removeTag(partialHitTag)
}
