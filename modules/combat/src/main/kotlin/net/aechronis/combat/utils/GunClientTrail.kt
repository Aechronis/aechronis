package net.aechronis.combat.utils

import net.aechronis.combat.objects.Gun
import net.minestom.server.color.AlphaColor
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.MainHand
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle

/**
 * One stationary endpoint for the owner's render-frame trail. The particle shader
 * moves a bright head from the animated muzzle to this endpoint over 90 ms.
 * R = start phase low 8 bits; G = phase bit 8 | (hand << 1) | (motion level << 2)
 * | (motion target << 6) | 128; B = (profile << 4) | aim code. Alpha stays opaque.
 * Aim codes are hip/ADS (0/1), aim-in ages 0..6 (2..8), aim-out ages 0..6 (9..15).
 * The FIRE tint supplies the same movement sample and aim clock as the item mesh.
 */
internal fun gunClientTrail(
    gun: Gun,
    item: ItemStack,
    hand: MainHand,
    endpoint: Pos,
    worldAge: Long,
): ParticlePacket? {
    if (gun.bulletTrailParticle == null) return null
    val model = item.get(DataComponents.ITEM_MODEL)
    val aiming = model == gun.itemModelAiming
    val phase = gunAnimationPhase(worldAge)
    val tint =
        item
            .get(DataComponents.CUSTOM_MODEL_DATA)
            ?.colors()
            ?.firstOrNull()
    val motion = tint?.let(::decodeGunMotion) ?: GunMotion()
    val aimTicks = tint?.let(::decodeGunFireAimTicks)
    val aimCode =
        when {
            aimTicks == null -> if (aiming) 1 else 0
            aiming -> 2 + aimTicks
            else -> 9 + aimTicks
        }
    val green =
        (phase shr 8) or
            (if (hand == MainHand.LEFT) 2 else 0) or
            (motion.startLevel.coerceAtMost(8) shl 2) or
            (motion.targetCode.coerceAtMost(1) shl 6) or
            128
    val color = AlphaColor(255, phase and 255, green, (gun.animatedViewModelProfile shl 4) or aimCode)
    return ParticlePacket(Particle.FLASH.withColor(color), true, true, endpoint, Pos.ZERO, 0F, 1)
}
