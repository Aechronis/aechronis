package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.key.Key
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.timer.TaskSchedule

object AimingListener {
    private fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player
        // crossbow material is only for its pose; don't start vanilla charging
        if (Item.getFromItemStack(event.itemStack) is Gun) event.itemUseTime = 0
        if (event.hand != PlayerHand.MAIN || Item.getFromItemStack(player.itemInMainHand) !is Gun) return
        refreshRightClickAim(player)
    }

    private fun onPlayerUseItemOnBlock(event: PlayerUseItemOnBlockEvent) {
        if (event.hand != PlayerHand.MAIN || Item.getFromItemStack(event.player.itemInMainHand) !is Gun) return
        refreshRightClickAim(event.player)
    }

    private fun refreshRightClickAim(player: Player) {
        val state = Combat.playerStates.getOrCreate(player)
        state.aiming = true
        state.cancelAimingReset()

        // right click repeats every four ticks, including during automatic fire
        state.aimingResetTask =
            ModuleScheduler
                .buildTask {
                    Combat.playerStates[player]?.aimingResetTask = null
                    Combat.playerStates.getOrCreate(player).aiming = player.isSneaking
                }.delay(TaskSchedule.tick(6))
                .schedule()
    }

    private fun onPlayerInput(event: PlayerInputEvent) {
        val state = Combat.playerStates.getOrCreate(event.player)
        state.aiming = event.isHoldingShiftKey || state.aimingResetTask != null
    }

    private fun onPlayerChangeHeldSlot(event: PlayerChangeHeldSlotEvent) {
        if (event.isCancelled || event.oldSlot == event.newSlot) return
        val state = Combat.playerStates.getOrCreate(event.player)
        state.cancelAimingReset()
        state.aiming = event.player.isSneaking
    }

    // Right-click aiming moves at sneaking speed whether or not the player sprints. Sneaking already
    // applies the client's own sneak slowdown, so no modifier is needed then. While sprinting, the
    // amount also cancels Minestom's +30% sprint modifier. Spread scales with measured speed, so this
    // keeps ADS spread from being taken at walking or sprint pace.
    private const val SPRINT_SPEED_MULTIPLIER = 1.3
    private val aimSlowdownKey = Key.key("aechronis", "aim_slowdown")

    // Polled each tick because aimingResetTask is also cleared by catalogue resets and combat tags;
    // the attribute is only touched when the wanted amount changes.
    private fun onPlayerTick(event: PlayerTickEvent) {
        val player = event.player
        val state = Combat.playerStates[player] ?: return
        val amount =
            if (state.aimingResetTask == null || player.isSneaking) {
                0.0
            } else {
                val sprint = if (player.isSprinting) SPRINT_SPEED_MULTIPLIER else 1.0
                player.getAttributeValue(Attribute.SNEAKING_SPEED) / sprint - 1.0
            }
        if (amount == state.aimSlowdown) return
        if (amount == 0.0) {
            clearAimSlowdown(player)
        } else {
            state.aimSlowdown = amount
            val modifier = AttributeModifier(aimSlowdownKey, amount, AttributeOperation.ADD_MULTIPLIED_TOTAL)
            player.getAttribute(Attribute.MOVEMENT_SPEED).addModifier(modifier)
        }
    }

    /** Removes the aiming slowdown; safe to call when it is not applied. */
    internal fun clearAimSlowdown(player: Player) {
        player.getAttribute(Attribute.MOVEMENT_SPEED).removeModifier(aimSlowdownKey)
        Combat.playerStates[player]?.aimSlowdown = 0.0
    }

    fun init() {
        Combat.eventNode.addListener(PlayerTickEvent::class.java, AimingListener::onPlayerTick)
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, AimingListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, AimingListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerInputEvent::class.java, AimingListener::onPlayerInput)
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, AimingListener::onPlayerChangeHeldSlot)
    }
}
